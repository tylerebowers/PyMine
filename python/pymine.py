"""
pymine — Python client for the Pymine Fabric mod control API.

Zero required dependencies (uses urllib). Optional: Pillow / numpy for
convenient frame decoding.

    from pymine import Minecraft
    mc = Minecraft()

    frame = mc.getframe()          # PIL.Image (or bytes / numpy via as_=)
    mc.look(pitch=10, yaw=45)      # relative camera turn
    mc.w(hold=True)                # walk forward until released
    mc.w(hold=False)
    mc.ml()                        # single left click / attack
    mc.e()                         # open inventory
    mc.cursor(220, 140)            # move GUI cursor (menus only)
    mc.i3()                        # select hotbar slot 3

All button controls accept:
    hold=None  (default) press once (in-world: held for `ticks` client
               ticks, default 4; in menus: an immediate press+release)
    hold=True  press and keep held until hold=False
    hold=False release
This makes each control drivable from a logit: e.g. hold = logit > 0.
"""

from __future__ import annotations

import io
import json
import urllib.request
from typing import Any, Optional


class PymineError(RuntimeError):
    pass


class Minecraft:
    def __init__(self, host: str = "127.0.0.1", port: int = 8765, timeout: float = 10.0):
        self.base = f"http://{host}:{port}"
        self.timeout = timeout

    # ------------------------------------------------------------------ http

    def _request(self, path: str, payload: Optional[dict] = None) -> bytes:
        data = json.dumps(payload).encode() if payload is not None else None
        req = urllib.request.Request(
            self.base + path,
            data=data,
            method="POST" if data is not None else "GET",
            headers={"Content-Type": "application/json"},
        )
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                return resp.read()
        except urllib.error.HTTPError as e:
            try:
                msg = json.loads(e.read()).get("error", str(e))
            except Exception:
                msg = str(e)
            raise PymineError(msg) from None

    def _post(self, path: str, payload: dict) -> dict:
        return json.loads(self._request(path, payload))

    # ---------------------------------------------------------------- camera

    def look(self, pitch: float = 0.0, yaw: float = 0.0, relative: bool = True) -> None:
        """Turn the camera. relative=True adds deltas; relative=False sets
        absolute angles. Pitch is clamped to Minecraft's [-90, 90]
        (up/down); yaw wraps freely."""
        self._post("/look", {"pitch": pitch, "yaw": yaw, "relative": relative})

    def cursor(self, x: float, y: float, relative: bool = False) -> None:
        """Move the mouse cursor while a menu/screen is open. Coordinates
        are GUI-scaled pixels — the same space the red dot is drawn in.
        Use state()['gui_width'/'gui_height'] for bounds."""
        self._post("/cursor", {"x": x, "y": y, "relative": relative})

    # ----------------------------------------------------------------- state

    def getframe(self, as_: str = "pil"):
        """Screenshot of the game framebuffer (includes the red cursor dot
        when a menu is open). as_ = 'pil' | 'numpy' | 'bytes'."""
        png = self._request("/frame")
        if as_ == "bytes":
            return png
        from PIL import Image  # pip install pillow
        img = Image.open(io.BytesIO(png)).convert("RGB")
        if as_ == "pil":
            return img
        if as_ == "numpy":
            import numpy as np  # pip install numpy
            return np.asarray(img)
        raise ValueError(f"unknown as_: {as_}")

    def state(self) -> dict[str, Any]:
        """Plumbing/debug info: in_menu, cursor position, GUI size, yaw/pitch."""
        return json.loads(self._request("/state"))

    def release_all(self) -> None:
        """Release every held control (call between RL episodes)."""
        self._post("/release_all", {})

    # ---------------------------------------------------------------- inputs

    def key(self, key: str, hold: Optional[bool] = None, ticks: Optional[int] = None) -> None:
        payload: dict[str, Any] = {"key": key}
        if hold is not None:
            payload["hold"] = hold
        if ticks is not None:
            payload["ticks"] = ticks
        self._post("/key", payload)

    # Mouse buttons
    def ml(self, hold: Optional[bool] = None, ticks: Optional[int] = None) -> None:
        """Left mouse / attack. In menus, clicks at the cursor position."""
        self.key("ml", hold, ticks)

    def mr(self, hold: Optional[bool] = None, ticks: Optional[int] = None) -> None:
        """Right mouse / use item / place block."""
        self.key("mr", hold, ticks)

    # Movement
    def w(self, hold: Optional[bool] = None, ticks: Optional[int] = None) -> None:
        self.key("w", hold, ticks)

    def a(self, hold: Optional[bool] = None, ticks: Optional[int] = None) -> None:
        self.key("a", hold, ticks)

    def s(self, hold: Optional[bool] = None, ticks: Optional[int] = None) -> None:
        self.key("s", hold, ticks)

    def d(self, hold: Optional[bool] = None, ticks: Optional[int] = None) -> None:
        self.key("d", hold, ticks)

    def space(self, hold: Optional[bool] = None, ticks: Optional[int] = None) -> None:
        """Jump (hold to bunny-hop / swim up)."""
        self.key("space", hold, ticks)

    def shift(self, hold: Optional[bool] = None, ticks: Optional[int] = None) -> None:
        """Sneak."""
        self.key("shift", hold, ticks)

    def sprint(self, hold: Optional[bool] = None, ticks: Optional[int] = None) -> None:
        self.key("sprint", hold, ticks)

    # Actions
    def e(self, hold: Optional[bool] = None, ticks: Optional[int] = None) -> None:
        """Open/close inventory."""
        self.key("e", hold, ticks)

    def q(self, hold: Optional[bool] = None, ticks: Optional[int] = None) -> None:
        """Drop held item."""
        self.key("q", hold, ticks)

    def f(self, hold: Optional[bool] = None, ticks: Optional[int] = None) -> None:
        """Swap item to offhand."""
        self.key("f", hold, ticks)

    def esc(self) -> None:
        """Escape: closes menus (or opens the pause menu in-world)."""
        self.key("esc")


# Hotbar slots: mc.i1() ... mc.i9()
def _make_hotbar(n: int):
    def _fn(self: Minecraft, hold: Optional[bool] = None, ticks: Optional[int] = None) -> None:
        self.key(str(n), hold, ticks)

    _fn.__name__ = f"i{n}"
    _fn.__doc__ = f"Select hotbar slot {n}."
    return _fn


for _n in range(1, 10):
    setattr(Minecraft, f"i{_n}", _make_hotbar(_n))
