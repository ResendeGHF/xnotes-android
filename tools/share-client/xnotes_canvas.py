"""In-app drawing canvas for the xnotes share companion.

Stdlib + tkinter only (Windows/macOS/Linux): the page background comes from
the device as PNG (`/api/page`), strokes are vectors both ways
(`/api/strokes`). Drawn strokes upload on pen-up, so the device sees them
within a second; device commits arrive through the 1 s version poll. The
protocol is cursor-based (`since` versions), so a push channel can replace
polling later without changing the wire format.
"""

from __future__ import annotations

import hashlib
import os
import sys
import tkinter as tk
import urllib.parse

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from xnotes_share import ShareClient, apply_theme, cache_path  # noqa: E402

ZOOM_DPIS = [55, 80, 110, 150, 220, 300]

PEN_COLORS = ["#111111", "#1a46c0", "#c02020", "#1a7a3a", "#7a3aa0"]
MARK_COLORS = ["#ffd400", "#7dff6a", "#ff9bd0", "#6ad4ff"]


def _wire_tool(tool: str) -> str:
    return "highlighter" if tool == "mark" else "pen"


class CanvasWindow(tk.Toplevel):
    """One note page: background PNG + vector ink, drawn and synced live."""

    def __init__(self, master, client: ShareClient, uri: str, name: str, holder: str):
        super().__init__(master)
        self.client = client
        self.uri = uri
        self.holder = holder
        self.title("Draw — %s" % name)
        self.geometry("900x700")

        self.page = 0
        self.zoom_idx = 2
        self.dpi = ZOOM_DPIS[self.zoom_idx]
        self.doc_dpi = 160
        self.pages: list[dict] = []
        self.version = ""
        self.title_text = name
        self.committed: list[dict] = []
        self.pending: list[dict] = []
        self.drawing: dict | None = None
        self.lease: str | None = None
        self.readonly = False
        self.tool = "pen"
        self.color = PEN_COLORS[0]
        self.width_pt = 2.5
        self.bg_photo = None
        self.poll_job = None
        self.beat_job = None

        self._build_ui()
        self._load_meta()
        self._acquire_lease()
        self.refresh_all()
        self._schedule_poll()
        self.protocol("WM_DELETE_WINDOW", self.close)

    # ------------------------------------------------------------- UI ---

    def _build_ui(self):
        bar = tk.Frame(self)
        bar.pack(fill="x", padx=6, pady=4)
        self.prev_btn = tk.Button(bar, text="◀", width=3, command=lambda: self._turn(-1))
        self.prev_btn.pack(side="left")
        self.page_var = tk.StringVar(value="?")
        tk.Label(bar, textvariable=self.page_var, width=12).pack(side="left")
        self.next_btn = tk.Button(bar, text="▶", width=3, command=lambda: self._turn(1))
        self.next_btn.pack(side="left")
        tk.Button(bar, text="−", width=3, command=lambda: self._zoom(-1)).pack(side="left", padx=(8, 0))
        self.zoom_var = tk.StringVar(value="")
        tk.Label(bar, textvariable=self.zoom_var, width=10).pack(side="left")
        tk.Button(bar, text="+", width=3, command=lambda: self._zoom(1)).pack(side="left")

        tools = tk.Frame(bar)
        tools.pack(side="left", padx=(12, 0))
        self.tool_var = tk.StringVar(value="pen")
        for label, tool in (("Pen", "pen"), ("Mark", "mark"), ("Erase", "erase")):
            b = tk.Button(tools, text=label, width=6,
                          command=lambda t=tool: self._set_tool(t))
            b.pack(side="left", padx=1)
            setattr(self, "tool_btn_" + tool, b)
        self._paint_tool_buttons()

        self.swatch_row = tk.Frame(bar)
        self.swatch_row.pack(side="left", padx=(8, 0))
        self._paint_swatches()

        tk.Label(bar, text="W:").pack(side="left", padx=(8, 0))
        self.width_var = tk.DoubleVar(value=self.width_pt)
        tk.Scale(bar, variable=self.width_var, from_=1.0, to=12.0, resolution=0.5,
                 orient="horizontal", length=90, showvalue=False,
                 command=lambda _v: setattr(self, "width_pt", float(self.width_var.get()))).pack(side="left")

        tk.Button(bar, text="Undo", command=self._undo_pending).pack(side="left", padx=(8, 0))
        tk.Button(bar, text="Sync", command=self._sync_pending).pack(side="left", padx=2)

        self.msg_var = tk.StringVar(value="")
        tk.Label(self, textvariable=self.msg_var, anchor="w").pack(fill="x", padx=8)

        view = tk.Frame(self)
        view.pack(fill="both", expand=True, padx=6, pady=(0, 6))
        self.hbar = tk.Scrollbar(view, orient="horizontal")
        self.hbar.pack(side="bottom", fill="x")
        self.vbar = tk.Scrollbar(view, orient="vertical")
        self.vbar.pack(side="right", fill="y")
        self.cv = tk.Canvas(view, background="#ffffff", highlightthickness=0,
                            xscrollcommand=self.hbar.set, yscrollcommand=self.vbar.set)
        self.cv.pack(side="left", fill="both", expand=True)
        self.hbar.config(command=self.cv.xview)
        self.vbar.config(command=self.cv.yview)
        self.cv.bind("<ButtonPress-1>", self._press)
        self.cv.bind("<B1-Motion>", self._motion)
        self.cv.bind("<ButtonRelease-1>", self._release)
        self.ver_var = tk.StringVar(value="")
        tk.Label(self, textvariable=self.ver_var, anchor="w").pack(fill="x", padx=8)

    # ---------------------------------------------------------- state ---

    @property
    def scale(self) -> float:
        return self.dpi / 72.0

    def _set_tool(self, tool: str):
        self.tool = tool
        self._paint_tool_buttons()
        self._paint_swatches()

    def _paint_tool_buttons(self):
        for tool in ("pen", "mark", "erase"):
            btn = getattr(self, "tool_btn_" + tool, None)
            if btn is not None:
                btn.config(relief="sunken" if self.tool == tool else "raised")

    def _paint_swatches(self):
        for child in self.swatch_row.winfo_children():
            child.destroy()
        colors = MARK_COLORS if self.tool == "mark" else PEN_COLORS
        if self.color not in colors:
            self.color = colors[0]
        for col in colors:
            b = tk.Button(self.swatch_row, background=col, activebackground=col,
                          width=2, relief="sunken" if col == self.color else "raised",
                          command=lambda c=col: (setattr(self, "color", c), self._paint_swatches()))
            b.pack(side="left", padx=1)

    # ----------------------------------------------------------- net ---

    def _load_meta(self):
        st, payload = self.client.get_json("/api/canvas?uri=" + urllib.parse.quote(self.uri, safe=""))
        if st != 200:
            self.msg_var.set("canvas unavailable: HTTP %d" % st)
            return
        self.pages = payload.get("pages", [])
        self.doc_dpi = payload.get("dpi", 160) or 160
        self.version = payload.get("version", "")
        self.title_text = payload.get("title", "")

    def _acquire_lease(self):
        q = "uri=%s&holder=%s" % (urllib.parse.quote(self.uri, safe=""),
                                  urllib.parse.quote(self.holder, safe=""))
        try:
            st, _, data = self.client.request("POST", "/api/lock?" + q, body=b"")
        except Exception as exc:
            self.readonly = True
            self.msg_var.set("offline — read only (%s)" % exc)
            return
        if st != 200:
            self.readonly = True
            self.msg_var.set("locked by someone else — read only")
            return
        import json as _json
        self.lease = _json.loads(data.decode("utf-8"))["lease"]
        self._schedule_beat()

    def _schedule_beat(self):
        if self.beat_job is not None:
            self.after_cancel(self.beat_job)
        if self.lease is None:
            return
        try:
            self.client.request("POST", "/api/heartbeat",
                                body=("lease=%s&holder=%s" % (
                                    urllib.parse.quote(self.lease, safe=""),
                                    urllib.parse.quote(self.holder, safe=""))).encode(),
                                headers={"Content-Type": "application/x-www-form-urlencoded"})
        except Exception:
            pass
        self.beat_job = self.after(20000, self._schedule_beat)

    def _schedule_poll(self):
        if self.poll_job is not None:
            self.after_cancel(self.poll_job)
        self.poll_job = self.after(1000, self._poll)

    def _poll(self):
        self.poll_job = None
        if self.drawing is None:
            try:
                st, payload = self.client.get_json(
                    "/api/version?uri=" + urllib.parse.quote(self.uri, safe=""))
                if st == 200 and payload.get("version", self.version) != self.version:
                    self.refresh_all()
            except Exception:
                pass
        self._schedule_poll()

    def refresh_all(self):
        try:
            st, payload = self.client.get_json("/api/canvas?uri=" + urllib.parse.quote(self.uri, safe=""))
        except Exception as exc:
            self.msg_var.set("refresh failed: %s" % exc)
            return
        if st != 200:
            self.msg_var.set("refresh failed: HTTP %d" % st)
            return
        self.version = payload.get("version", self.version)
        self.pages = payload.get("pages", self.pages)
        self._load_background()
        self._load_strokes()
        self._dedupe_pending()
        self._redraw()
        self.ver_var.set("v %s · %d committed · %d pending" % (
            self.version, len(self.committed), len(self.pending)))

    def _load_background(self):
        if not self.pages:
            return
        q = "uri=%s&page=%d&dpi=%d" % (urllib.parse.quote(self.uri, safe=""), self.page, self.dpi)
        try:
            st, _, data = self.client.request("GET", "/api/page?" + q)
        except Exception as exc:
            self.msg_var.set("page failed: %s" % exc)
            return
        if st != 200:
            self.msg_var.set("page failed: HTTP %d" % st)
            return
        path = cache_path("xnotes-bg-%s-p%d-%d.png" % (
            hashlib.sha1(self.uri.encode()).hexdigest()[:8], self.page, self.dpi))
        with open(path, "wb") as fh:
            fh.write(data)
        self.bg_photo = tk.PhotoImage(file=path)
        self._redraw()
        self.page_var.set("p %d/%d" % (self.page + 1, len(self.pages)))
        self.zoom_var.set("%d dpi" % self.dpi)

    def _load_strokes(self):
        q = "uri=%s&page=%d&since=" % (urllib.parse.quote(self.uri, safe=""), self.page)
        try:
            st, payload = self.client.get_json("/api/strokes?" + q)
        except Exception as exc:
            self.msg_var.set("strokes failed: %s" % exc)
            return
        if st != 200:
            self.msg_var.set("strokes failed: HTTP %d" % st)
            return
        self.version = payload.get("version", self.version)
        self.committed = payload.get("strokes", [])

    def _dedupe_pending(self):
        seen = set()
        for s in self.committed:
            seen.add((s.get("tool"), s.get("color"), s.get("width"),
                      tuple(s.get("pts", []))))
        self.pending = [s for s in self.pending
                        if s.get("page", self.page) != self.page or
                        (s.get("tool"), s.get("color"), s.get("width"),
                         tuple(s.get("pts", []))) not in seen]

    # --------------------------------------------------------- drawing ---

    def _to_page(self, x: float, y: float) -> tuple[float, float]:
        return (self.cv.canvasx(x) / self.scale, self.cv.canvasy(y) / self.scale)

    def _press(self, event):
        if self.readonly:
            self.msg_var.set("read only (no edit lease)")
            return
        x, y = self._to_page(event.x, event.y)
        if self.tool == "erase":
            self._erase_at(x, y)
            return
        self.drawing = {"tool": _wire_tool(self.tool), "color": self.color,
                        "width": self.width_pt, "pts": [x, y], "items": []}

    def _motion(self, event):
        d = self.drawing
        if d is None:
            return
        x, y = self._to_page(event.x, event.y)
        pts = d["pts"]
        if pts and abs(pts[-2] - x) + abs(pts[-1] - y) < 0.75 / self.scale:
            return  # sub-pixel jitter: don't spam points
        pts.extend((x, y))
        s = self.scale
        d["items"].append(self.cv.create_line(
            pts[-4] * s, pts[-3] * s, x * s, y * s,
            width=max(1.0, d["width"] * s), fill=d["color"],
            capstyle="round", joinstyle="round", smooth=True,
            stipple="gray50" if d["tool"] == "highlighter" else "",
            tags=("live",)))

    def _release(self, _event):
        d = self.drawing
        self.drawing = None
        if d is None:
            return
        for item in d.pop("items"):
            self.cv.delete(item)
        if len(d["pts"]) < 4:
            x, y = d["pts"][0], d["pts"][1]
            d["pts"] = [x, y, x + 0.5, y + 0.5]  # a tap still leaves a dot
        d["page"] = self.page
        self.pending.append(d)
        self._redraw()
        self._upload([d])

    def _erase_at(self, x: float, y: float):
        tol = max(6.0 / self.scale, 2.0)
        for i in range(len(self.pending) - 1, -1, -1):
            if self.pending[i].get("page", self.page) != self.page:
                continue
            pts = self.pending[i]["pts"]
            for k in range(0, len(pts) - 2, 2):
                if abs(pts[k] - x) + abs(pts[k + 1] - y) < tol:
                    del self.pending[i]
                    self._redraw()
                    self.ver_var.set("v %s · %d committed · %d pending" % (
                        self.version, len(self.committed), len(self.pending)))
                    return

    def _undo_pending(self):
        for i in range(len(self.pending) - 1, -1, -1):
            if self.pending[i].get("page", self.page) == self.page:
                del self.pending[i]
                break
        self._redraw()
        self.ver_var.set("v %s · %d committed · %d pending" % (
            self.version, len(self.committed), len(self.pending)))

    def _sync_pending(self):
        mine = [s for s in self.pending if s.get("page", self.page) == self.page]
        if mine:
            self._upload(mine)

    def _upload(self, strokes: list[dict]):
        if self.readonly or self.lease is None or not strokes:
            if not self.readonly:
                self.msg_var.set("no edit lease — reopen the canvas")
            return
        import json as _json
        wire = [{k: s[k] for k in ("tool", "color", "width", "pts")} for s in strokes]
        body = _json.dumps({"base": self.version, "strokes": wire}).encode()
        try:
            st, payload = self._post_strokes(body)
        except Exception as exc:
            self.msg_var.set("upload failed: %s" % exc)
            return
        if st == 200:
            self.version = payload.get("version", self.version)
            self._dedupe_pending()
            self._redraw()
            self.ver_var.set("v %s · %d committed · %d pending" % (
                self.version, len(self.committed), len(self.pending)))
        elif st == 409:
            self.msg_var.set("device changed first — refetched, press Sync to retry")
            self.refresh_all()
        elif st == 423:
            self.readonly = True
            self.msg_var.set("lease lost — read only, reopen the canvas")
        else:
            self.msg_var.set("upload failed: HTTP %d %s" % (st, payload))

    def _post_strokes(self, body: bytes):
        import json as _json
        st, _, data = self.client.request(
            "POST", "/api/strokes?uri=%s&page=%d" % (
                urllib.parse.quote(self.uri, safe=""), self.page),
            body=body, headers={"X-Lease": self.lease or "",
                                "Content-Type": "application/json"}, timeout=60)
        try:
            return st, _json.loads(data.decode("utf-8")) if data else {}
        except ValueError:
            return st, {"_raw": data.decode("utf-8", "replace")}

    # ----------------------------------------------------------- view ---

    def _turn(self, delta: int):
        if not self.pages:
            return
        self.page = (self.page + delta) % len(self.pages)
        self._load_background()
        self._load_strokes()
        self._redraw()

    def _zoom(self, delta: int):
        self.zoom_idx = min(max(0, self.zoom_idx + delta), len(ZOOM_DPIS) - 1)
        self.dpi = ZOOM_DPIS[self.zoom_idx]
        self._load_background()

    def _redraw(self):
        self.cv.delete("ink")
        self.cv.delete("bg")
        if self.bg_photo is not None:
            self.cv.create_image(0, 0, anchor="nw", image=self.bg_photo, tags=("bg",))
            self.cv.config(scrollregion=(0, 0, self.bg_photo.width(), self.bg_photo.height()))
        s = self.scale
        visible = [st for st in list(self.committed) + list(self.pending)
                   if st.get("page", self.page) == self.page]
        for stroke in visible:
            pts = stroke.get("pts", [])
            if len(pts) < 4:
                continue
            xy = [c * s for c in pts]
            self.cv.create_line(
                *xy, width=max(1.0, stroke.get("width", 2.0) * s),
                fill=stroke.get("color", "#111111"), capstyle="round",
                joinstyle="round", smooth=True,
                stipple="gray50" if stroke.get("tool") == "highlighter" else "",
                tags=("ink",))

    def close(self):
        try:
            if self.poll_job is not None:
                self.after_cancel(self.poll_job)
            if self.beat_job is not None:
                self.after_cancel(self.beat_job)
        except Exception:
            pass
        if self.lease is not None:
            try:
                import urllib.parse as _u
                self.client.request("DELETE", "/api/lock?lease=%s&holder=%s" % (
                    _u.quote(self.lease, safe=""), _u.quote(self.holder, safe="")))
            except Exception:
                pass
            self.lease = None
        self.destroy()


def run_standalone(client: ShareClient, uri: str, name: str):
    """`canvas <uri>` entry point: its own Tk root, no file browser."""
    root = tk.Tk()
    root.title("xnotes share")
    try:
        from xnotes_share import apply_theme as _theme
    except ImportError:
        _theme = None
    if _theme is not None:
        _theme(root)
    root.withdraw()
    holder = "linux-canvas-%d" % os.getpid()
    win = CanvasWindow(root, client, uri, name, holder)
    win.protocol("WM_DELETE_WINDOW", lambda: (win.close(), root.destroy()))
    root.mainloop()
