"""
Demo of the full Pymine API.

Setup:
  1. Launch Minecraft with the Pymine mod (e.g. `./gradlew runClient`).
  2. Load into a world (survival singleplayer is fine).
  3. Run: python demo.py
     (pip install pillow for frame saving; everything else is stdlib)

The window does NOT need to be focused — you can alt-tab away and watch.
"""

import time

from pymine import Minecraft

mc = Minecraft()


def snap(name: str) -> None:
    frame = mc.getframe()  # PIL image
    frame.save(name)
    print(f"  saved {name}  ({frame.size[0]}x{frame.size[1]})")


def main() -> None:
    print("== state ==")
    st = mc.state()
    print(f"  {st}")
    if not st["has_player"]:
        print("!! Load into a world first, then re-run this demo.")
        return

    print("== frame capture ==")
    snap("frame_world.png")

    print("== camera: relative look (a full spin in 8 steps) ==")
    for _ in range(8):
        mc.look(pitch=0, yaw=45, relative=True)
        time.sleep(0.15)

    print("== camera: absolute look (face north, level) ==")
    mc.look(pitch=0, yaw=180, relative=False)
    time.sleep(0.3)

    print("== movement: hold W + sprint for 2s, then jump ==")
    mc.w(hold=True)
    mc.sprint(hold=True)
    time.sleep(2.0)
    mc.space()  # single jump while still running
    time.sleep(0.8)
    mc.sprint(hold=False)
    mc.w(hold=False)

    print("== movement: one-shot presses (default ~4 ticks each) ==")
    mc.a()
    time.sleep(0.4)
    mc.d()
    time.sleep(0.4)
    mc.s(ticks=10)  # longer press: 10 client ticks (~0.5s)
    time.sleep(0.8)

    print("== sneak toggle ==")
    mc.shift(hold=True)
    time.sleep(1.0)
    mc.shift(hold=False)

    print("== hotbar: cycle slots 1-9 ==")
    for i in range(1, 10):
        mc.key(str(i))
        time.sleep(0.12)
    mc.i1()

    print("== attack: hold left mouse for 1.5s (mines if facing a block) ==")
    mc.look(pitch=30, yaw=0, relative=True)  # look down a bit
    mc.ml(hold=True)
    time.sleep(1.5)
    mc.ml(hold=False)
    mc.look(pitch=-30, yaw=0, relative=True)

    print("== right click: single use/place ==")
    mc.mr()
    time.sleep(0.4)

    print("== inventory: open, move cursor, click, close ==")
    mc.e()  # open inventory
    time.sleep(0.5)
    st = mc.state()
    print(f"  in_menu={st['in_menu']} screen={st['screen']}")
    gw, gh = st["gui_width"], st["gui_height"]

    # Sweep the cursor so the red dot is easy to spot, then park mid-screen.
    mc.cursor(10, 10)
    time.sleep(0.3)
    mc.cursor(gw - 10, gh - 10)
    time.sleep(0.3)
    mc.cursor(gw / 2, gh / 2)
    time.sleep(0.3)
    snap("frame_inventory_cursor.png")  # red dot visible in this frame

    # Pick up / put back whatever is under the cursor (slot-accurate clicks).
    mc.ml()
    time.sleep(0.3)
    mc.ml()
    time.sleep(0.3)

    # Relative cursor nudge, then shift-click example (quick-move).
    mc.cursor(20, 0, relative=True)
    mc.shift(hold=True)
    mc.ml()
    mc.shift(hold=False)
    time.sleep(0.3)

    mc.esc()  # close inventory (mc.e() also works)
    time.sleep(0.3)

    print("== drop an item ==")
    mc.q()
    time.sleep(0.4)

    print("== cleanup ==")
    mc.release_all()
    snap("frame_final.png")
    print("done.")


if __name__ == "__main__":
    main()
