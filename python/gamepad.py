"""
gamepad.py — interactive "gamepad" for manually testing the Pymine API.

A small tkinter control panel that mirrors the game view and forwards your
keyboard/mouse to Minecraft through the mod's HTTP API. The Minecraft window
never needs focus — keep THIS window focused and play through it.

Run:
    python gamepad.py            (stdlib only; `pip install pillow` optional,
                                  it just makes the video smoother/scalable)

Controls (while the gamepad window is focused):
    W A S D          move (hold works)
    SPACE            jump            LSHIFT          sneak
    LCTRL            sprint          E               inventory
    Q                drop            F               swap offhand
    1-9              hotbar          ESC             close menu / pause
    Arrow keys       look (LEFT/RIGHT yaw, UP/DOWN pitch)
    Mouse on view:
        in-game      left-drag = look around
                     left-click = attack once, right-click = use/place once
        in a menu    move = move red cursor, left/right-click = click there
                     (hold left and drag to drag-move items)
    Buttons at the bottom: hold-toggles for LMB/RMB, release-all, save frame.
"""

from __future__ import annotations

import base64
import queue
import threading
import time
import tkinter as tk

from pymine import Minecraft, PymineError

try:
    from PIL import Image, ImageTk  # optional, for smooth scaling
    import io
    HAS_PIL = True
except ImportError:
    HAS_PIL = False

VIEW_MAX_W = 960          # displayed video width (px)
FRAME_INTERVAL = 0.10     # seconds between frame fetches (~10 fps)
CURSOR_INTERVAL = 0.10    # min seconds between cursor-position sends
LOOK_STEP_YAW = 10.0      # degrees per arrow-key press
LOOK_STEP_PITCH = 5.0
DRAG_SENS = 0.25          # degrees of look per dragged display pixel

KEYSYM_MAP = {
    "w": "w", "a": "a", "s": "s", "d": "d",
    "e": "e", "q": "q", "f": "f",
    "space": "space",
    "shift_l": "shift", "shift_r": "shift",
    "control_l": "sprint", "control_r": "sprint",
    "escape": "esc",
    **{str(n): str(n) for n in range(1, 10)},
}


class Gamepad:
    def __init__(self) -> None:
        self.mc = Minecraft()
        self.root = tk.Tk()
        self.root.title("Pymine gamepad")
        self.root.configure(bg="#1e1e1e")

        # ---- video view ----
        self.view = tk.Label(self.root, bg="black",
                             text="connecting...", fg="white",
                             width=96, height=27)
        self.view.pack(padx=6, pady=6)

        # ---- status + buttons ----
        self.status = tk.Label(self.root, anchor="w", bg="#1e1e1e", fg="#9cdcfe",
                               font=("Consolas", 10))
        self.status.pack(fill="x", padx=6)

        bar = tk.Frame(self.root, bg="#1e1e1e")
        bar.pack(fill="x", padx=6, pady=(2, 6))
        self.lmb_var = tk.BooleanVar()
        self.rmb_var = tk.BooleanVar()

        # IMPORTANT: takefocus=0 + refocusing the root after every widget
        # command. Clicking a Tk checkbutton/button gives it keyboard focus,
        # and Space is Tk's "invoke the focused widget" key — so without
        # this, pressing SPACE to jump would silently re-toggle whichever
        # control you clicked last (e.g. un-holding LMB, or worse, pressing
        # "release all" again).
        def cmd(fn):
            def wrapped():
                fn()
                self.root.focus_set()
            return wrapped

        tk.Checkbutton(bar, text="hold LMB", variable=self.lmb_var, takefocus=0,
                       command=cmd(lambda: self.send("ml", hold=self.lmb_var.get())),
                       bg="#1e1e1e", fg="white", selectcolor="#333").pack(side="left")
        tk.Checkbutton(bar, text="hold RMB", variable=self.rmb_var, takefocus=0,
                       command=cmd(lambda: self.send("mr", hold=self.rmb_var.get())),
                       bg="#1e1e1e", fg="white", selectcolor="#333").pack(side="left")
        tk.Button(bar, text="LMB once", takefocus=0,
                  command=cmd(lambda: self.send("ml"))).pack(side="left", padx=2)
        tk.Button(bar, text="RMB once", takefocus=0,
                  command=cmd(lambda: self.send("mr"))).pack(side="left", padx=2)
        tk.Button(bar, text="release all", takefocus=0,
                  command=cmd(self.release_all)).pack(side="left", padx=8)
        tk.Button(bar, text="save frame", takefocus=0,
                  command=cmd(self.save_frame)).pack(side="left", padx=2)

        # Defense in depth: even if one of these ever gets keyboard focus,
        # never let Space/Return invoke it — those are gameplay keys.
        for w in bar.winfo_children():
            w.bind("<space>", lambda e: "break")
            w.bind("<Return>", lambda e: "break")
        tk.Label(bar, text="keep THIS window focused; keys pass through",
                 bg="#1e1e1e", fg="#777").pack(side="right")

        # ---- input plumbing ----
        self.cmd_queue: queue.Queue = queue.Queue()
        self.held: set[str] = set()
        self.pending_release: dict[str, str] = {}   # keysym -> after() id
        self.state: dict = {}
        self.photo = None
        self.display_size = (VIEW_MAX_W, 540)
        self.frame_size = (VIEW_MAX_W, 540)
        self.drag_last: tuple[int, int] | None = None
        self.last_err = ""
        # Mouse motion coalescing: motion events only record the latest
        # target; a dedicated worker sends it at most every CURSOR_INTERVAL.
        # This keeps the command queue empty so clicks/holds stay snappy.
        self.cursor_target: tuple[float, float] | None = None

        self.root.bind("<KeyPress>", self.on_key_press)
        self.root.bind("<KeyRelease>", self.on_key_release)
        self.view.bind("<Motion>", self.on_motion)
        self.view.bind("<ButtonPress-1>", self.on_lmb_press)
        self.view.bind("<ButtonRelease-1>", self.on_lmb_release)
        self.view.bind("<ButtonPress-3>", lambda e: self.on_click(e, "mr"))
        self.view.bind("<B1-Motion>", self.on_drag)
        self.root.protocol("WM_DELETE_WINDOW", self.close)

        threading.Thread(target=self.command_worker, daemon=True).start()
        threading.Thread(target=self.cursor_worker, daemon=True).start()
        threading.Thread(target=self.frame_worker, daemon=True).start()
        self.root.after(100, self.tick_ui)

    # ------------------------------------------------------------- API calls

    def send(self, key: str, hold: bool | None = None) -> None:
        self.cmd_queue.put(("key", key, hold))

    def look(self, pitch: float, yaw: float) -> None:
        self.cmd_queue.put(("look", pitch, yaw))

    def cursor(self, x: float, y: float) -> None:
        self.cmd_queue.put(("cursor", x, y))

    def release_all(self) -> None:
        self.held.clear()
        self.lmb_var.set(False)
        self.rmb_var.set(False)
        self.cmd_queue.put(("release_all",))

    def cursor_worker(self) -> None:
        """Send at most one cursor position per CURSOR_INTERVAL, always the
        latest one. Runs outside cmd_queue so a fast-moving mouse can never
        back up clicks, holds, or key presses behind stale positions."""
        last_sent: tuple[float, float] | None = None
        while True:
            time.sleep(CURSOR_INTERVAL)
            target = self.cursor_target
            if target is None or target == last_sent:
                continue
            if not self.state.get("in_menu"):
                continue
            try:
                self.mc.cursor(*target, relative=False)
                last_sent = target
                self.last_err = ""
            except Exception as e:
                self.last_err = str(e)

    def command_worker(self) -> None:
        """All control HTTP calls happen off the UI thread, in order."""
        while True:
            cmd = self.cmd_queue.get()
            try:
                match cmd[0]:
                    case "key":
                        self.mc.key(cmd[1], hold=cmd[2])
                    case "look":
                        self.mc.look(pitch=cmd[1], yaw=cmd[2], relative=True)
                    case "cursor":
                        self.mc.cursor(cmd[1], cmd[2], relative=False)
                    case "release_all":
                        self.mc.release_all()
                self.last_err = ""
            except Exception as e:  # keep the pad alive on errors
                self.last_err = str(e)

    # ---------------------------------------------------------- keyboard I/O
    # OS key auto-repeat fires fake release+press pairs while a key is held.
    # Debounce: delay each release ~40ms and cancel it if the same key is
    # pressed again immediately.

    def on_key_press(self, event: tk.Event) -> None:
        ks = event.keysym.lower()
        if ks in self.pending_release:                 # auto-repeat: ignore
            self.root.after_cancel(self.pending_release.pop(ks))
            return
        if ks in ("left", "right", "up", "down"):      # look keys may repeat
            yaw = {"left": -LOOK_STEP_YAW, "right": LOOK_STEP_YAW}.get(ks, 0)
            pitch = {"up": -LOOK_STEP_PITCH, "down": LOOK_STEP_PITCH}.get(ks, 0)
            self.look(pitch, yaw)
            return
        key = KEYSYM_MAP.get(ks)
        if key is None or ks in self.held:
            return
        if key == "esc":                               # one-shot keys
            self.send("esc")
            return
        self.held.add(ks)
        self.send(key, hold=True)

    def on_key_release(self, event: tk.Event) -> None:
        ks = event.keysym.lower()
        if ks not in self.held:
            return
        aid = self.root.after(40, lambda: self.finish_release(ks))
        self.pending_release[ks] = aid

    def finish_release(self, ks: str) -> None:
        self.pending_release.pop(ks, None)
        if ks in self.held:
            self.held.discard(ks)
            self.send(KEYSYM_MAP[ks], hold=False)

    # ------------------------------------------------------------- mouse I/O

    def view_to_gui(self, ex: int, ey: int) -> tuple[float, float] | None:
        """Displayed-pixel -> GUI-scaled game coordinates."""
        if not self.state:
            return None
        dw, dh = self.display_size
        gw, gh = self.state.get("gui_width"), self.state.get("gui_height")
        if not gw or not dw:
            return None
        if not (0 <= ex < dw and 0 <= ey < dh):   # outside the video area
            return None
        return ex * gw / dw, ey * gh / dh

    def on_motion(self, event: tk.Event) -> None:
        if self.state.get("in_menu"):
            pos = self.view_to_gui(event.x, event.y)
            if pos:
                self.cursor_target = pos   # coalesced; sent by cursor_worker

    def on_lmb_press(self, event: tk.Event) -> None:
        self.drag_last = (event.x, event.y)
        if self.state.get("in_menu"):
            pos = self.view_to_gui(event.x, event.y)
            if pos:
                self.cursor(*pos)             # ordered: lands before the click
                self.cursor_target = pos
        # Hold in both modes: in-game this mines while held (drag to look at
        # the same time, like real MC); in menus it enables drag-moving items.
        # A quick click still registers as a single attack/click.
        self.send("ml", hold=True)

    def on_lmb_release(self, event: tk.Event) -> None:
        self.send("ml", hold=False)
        self.lmb_var.set(False)               # keep the hold-LMB checkbox truthful
        self.drag_last = None

    def on_click(self, event: tk.Event, button: str) -> None:
        if self.state.get("in_menu"):
            pos = self.view_to_gui(event.x, event.y)
            if pos:
                self.cursor(*pos)             # ordered: lands before the click
                self.cursor_target = pos
        self.send(button)

    def on_drag(self, event: tk.Event) -> None:
        if self.state.get("in_menu"):
            self.on_motion(event)             # dragging an item: keep cursor moving
            return
        if self.drag_last:                    # in-game: drag = look
            dx = event.x - self.drag_last[0]
            dy = event.y - self.drag_last[1]
            if dx or dy:
                self.look(dy * DRAG_SENS, dx * DRAG_SENS)
        self.drag_last = (event.x, event.y)

    # ------------------------------------------------------------- video/UI

    def frame_worker(self) -> None:
        while True:
            t0 = time.time()
            try:
                png = self.mc.getframe(as_="bytes")
                self.state = self.mc.state()
                self.latest_png = png
            except Exception as e:
                self.last_err = str(e)
            time.sleep(max(0.0, FRAME_INTERVAL - (time.time() - t0)))

    def tick_ui(self) -> None:
        png = getattr(self, "latest_png", None)
        if png:
            try:
                if HAS_PIL:
                    img = Image.open(io.BytesIO(png))
                    self.frame_size = img.size
                    scale = VIEW_MAX_W / img.width
                    img = img.resize((VIEW_MAX_W, int(img.height * scale)))
                    self.display_size = img.size
                    self.photo = ImageTk.PhotoImage(img)
                else:
                    photo = tk.PhotoImage(data=base64.b64encode(png))
                    self.frame_size = (photo.width(), photo.height())
                    factor = max(1, round(photo.width() / VIEW_MAX_W))
                    photo = photo.subsample(factor, factor)
                    self.display_size = (photo.width(), photo.height())
                    self.photo = photo
                self.view.configure(image=self.photo, text="", width=self.display_size[0],
                                    height=self.display_size[1])
            except Exception as e:
                self.last_err = f"frame decode: {e}"

        st = self.state
        menu = f"MENU:{st.get('screen')}" if st.get("in_menu") else "in-game"
        held = "+".join(sorted(self.held)) or "-"
        err = f"   ERR: {self.last_err}" if self.last_err else ""
        self.status.configure(
            text=f"{menu}   yaw={st.get('yaw', 0):.0f} pitch={st.get('pitch', 0):.0f}"
                 f"   cursor=({st.get('cursor_x', 0):.0f},{st.get('cursor_y', 0):.0f})"
                 f"   held: {held}{err}",
            fg="#f48771" if self.last_err else "#9cdcfe")
        self.root.after(50, self.tick_ui)

    def save_frame(self) -> None:
        png = getattr(self, "latest_png", None)
        if png:
            name = f"gamepad_{int(time.time())}.png"
            with open(name, "wb") as fh:
                fh.write(png)
            self.last_err = ""
            self.status.configure(text=f"saved {name}")

    def close(self) -> None:
        try:
            self.mc.release_all()
        except PymineError:
            pass
        self.root.destroy()

    def run(self) -> None:
        self.root.mainloop()


if __name__ == "__main__":
    Gamepad().run()
