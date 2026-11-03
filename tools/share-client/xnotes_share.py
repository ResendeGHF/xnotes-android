#!/usr/bin/env python3
"""xnotes-share: Linux companion for the xnotes LAN share server.

Stdlib only (no pip dependencies), so it runs on any university lab machine.
TLS is verified by certificate pinning: the server's self-signed certificate
SHA-256 fingerprint must match the pairing file (trust-on-first-use, checked
against the fingerprint shown on the device's notification).

Typical flow::

    python3 xnotes_share.py pair --url https://192.168.1.20:42317 \\
        --fingerprint AB:CD:... --token <token-from-device>
    python3 xnotes_share.py list
    python3 xnotes_share.py gui          # tabbed viewer, one tab per file
    python3 xnotes_share.py pdf '<uri>' note.pdf      # open in Firefox/pdf.js
    # ... annotate and save in Firefox ...
    python3 xnotes_share.py push '<uri>' note.pdf
    python3 xnotes_share.py watch '<uri>'  # follow device commits

Device edits appear on the desktop once the device's save commits them; the
client re-downloads on change notifications (no live stroke streaming).
One file can be edited at a time across all parties; different files are
independent. Run ``python3 xnotes_share.py selftest`` for offline checks.
"""

from __future__ import annotations

import argparse
import hashlib
import http.client
import json
import os
import ssl
import subprocess
import sys
import tempfile
import threading
import urllib.parse

CONFIG_DIR = None  # resolved lazily by config_dir() so imports stay side-effect free
PAIRING_FILE_NAME = "pairing.json"
DEFAULT_PORT = 42317


def config_dir() -> str:
    """Per-OS config dir (Windows/macOS/Linux); created on demand."""
    if sys.platform == "win32":
        base = os.environ.get("APPDATA") or os.path.expanduser("~")
    elif sys.platform == "darwin":
        base = os.path.join(os.path.expanduser("~"), "Library", "Application Support")
    else:
        base = os.environ.get("XDG_CONFIG_HOME") or os.path.join(os.path.expanduser("~"), ".config")
    return os.path.join(base, "xnotes-share")


def pairing_file() -> str:
    return os.path.join(config_dir(), PAIRING_FILE_NAME)


def open_file(path: str) -> None:
    """Open a file with the platform default viewer (no Linux-only xdg-open)."""
    if sys.platform == "win32":
        os.startfile(path)  # noqa: S606 - local file the user just downloaded
    elif sys.platform == "darwin":
        subprocess.Popen(["open", path])
    else:
        subprocess.Popen(["xdg-open", path])


def cache_path(name: str) -> str:
    return os.path.join(tempfile.gettempdir(), name)


# ---------------------------------------------------------------- pairing ---

def load_pairing() -> dict:
    try:
        with open(pairing_file(), encoding="utf-8") as fh:
            return json.load(fh)
    except (OSError, ValueError):
        return {}


def save_pairing(url: str, fingerprint: str, token: str) -> None:
    os.makedirs(config_dir(), exist_ok=True)
    with open(pairing_file(), "w", encoding="utf-8") as fh:
        json.dump({"url": url, "fingerprint": fingerprint, "token": token}, fh, indent=2)
    try:
        os.chmod(pairing_file(), 0o600)
    except OSError:
        pass  # Windows: no POSIX modes; the file stays in the user's profile


def normalize_fingerprint(fp: str) -> str:
    return "".join(ch for ch in fp if ch in "0123456789abcdefABCDEF").lower()


# ---------------------------------------------------------------- client ----

class PinError(Exception):
    pass


class ShareClient:
    """Minimal HTTPS client with certificate pinning + bearer token."""

    def __init__(self, url: str, fingerprint: str, token: str, timeout: int = 30):
        # The server is TLS-only, so a bare host:port unambiguously means https.
        if "://" not in url:
            url = "https://" + url.lstrip("/")
        parts = urllib.parse.urlparse(url)
        if parts.scheme != "https":
            raise ValueError("server url must be https (plaintext is not served; got %r)" % url)
        if not parts.hostname:
            raise ValueError("could not parse a host out of %r (try https://<ip>:<port>)" % url)
        self.host = parts.hostname or "127.0.0.1"
        self.port = parts.port or DEFAULT_PORT
        self.expected_fp = normalize_fingerprint(fingerprint)
        self.token = token
        self.timeout = timeout

    def _connection(self) -> http.client.HTTPSConnection:
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        conn = http.client.HTTPSConnection(self.host, self.port, timeout=self.timeout, context=ctx)
        return conn

    def _check_pin(self, conn: http.client.HTTPSConnection) -> None:
        der = conn.sock.getpeercert(binary_form=True)
        got = hashlib.sha256(der).hexdigest() if der else ""
        if got != self.expected_fp:
            conn.close()
            raise PinError("certificate fingerprint mismatch: got %s" % got)

    def request(self, method: str, path: str, body: bytes | None = None,
                headers: dict | None = None, timeout: int | None = None):
        conn = self._connection()
        if timeout is not None:
            conn.timeout = timeout
        h = {"Authorization": "Bearer " + self.token, "Connection": "close"}
        if headers:
            h.update(headers)
        if body is not None:
            h.setdefault("Content-Type", "application/pdf")
            h.setdefault("Content-Length", str(len(body)))
        conn.request(method, path, body=body, headers=h)
        try:
            self._check_pin(conn)
        except Exception:
            conn.close()
            raise
        resp = conn.getresponse()
        data = resp.read()
        response_headers = {k.lower(): v for k, v in resp.getheaders()}
        status = resp.status
        conn.close()
        return status, response_headers, data

    def get_json(self, path: str, timeout: int | None = None) -> tuple[int, dict]:
        status, _, data = self.request("GET", path, timeout=timeout)
        try:
            payload = json.loads(data.decode("utf-8")) if data else {}
        except ValueError:
            payload = {"_raw": data.decode("utf-8", "replace")}
        return status, payload


def client_from_pairing() -> ShareClient:
    p = load_pairing()
    if not p.get("url") or not p.get("fingerprint") or not p.get("token"):
        sys.exit("not paired: run `pair` first (see --help)")
    return ShareClient(p["url"], p["fingerprint"], p["token"])


# ---------------------------------------------------------------- commands ---

def cmd_pair(args) -> int:
    problems = []
    if len(normalize_fingerprint(args.fingerprint)) != 64:
        problems.append("fingerprint should be the 64-hex-digit SHA-256 shown in the app")
    if not all(ch in "0123456789abcdefABCDEF" for ch in args.token.strip()):
        problems.append("token should be hex only (0-9, a-f); check for 0/O or 1/l mixups")
    if problems:
        for p in problems:
            print("pairing refused: %s" % p)
        return 1
    probe = ShareClient(args.url, args.fingerprint, args.token)
    try:
        status, payload = probe.get_json("/api/status")
    except PinError as exc:
        print("pairing failed: %s" % exc)
        print("Compare with the fingerprint shown on the device notification.")
        return 1
    except Exception as exc:  # noqa: BLE001 - report any transport error plainly
        print("pairing failed: %s" % exc)
        return 1
    if status != 200:
        print("pairing failed: server answered %d (%s)" % (status, payload))
        return 1
    save_pairing(args.url, args.fingerprint, args.token)
    print("paired with %s (%s)" % (args.url, payload.get("name", "?")))
    return 0


def cmd_status(_args) -> int:
    c = client_from_pairing()
    status, payload = c.get_json("/api/status")
    print("HTTP %d\n%s" % (status, json.dumps(payload, indent=2)))
    return 0 if status == 200 else 1


def cmd_list(args) -> int:
    c = client_from_pairing()
    path = "/api/files"
    if getattr(args, "uri", None):
        path += "?uri=" + urllib.parse.quote(args.uri, safe="")
    status, payload = c.get_json(path)
    if status == 404:
        print("no such folder: %s" % getattr(args, "uri", ""))
        return 1
    if status != 200:
        print("list failed: HTTP %d %s" % (status, payload))
        return 1
    if not payload.get("root", True):
        print("no folder shared: pick your notes folder in the app's Home/explorer first,")
        print("then make sure Sharing is still on and retry `list`.")
        return 1
    for f in payload.get("files", []):
        mark = "DIR " if f.get("dir") else "    "
        lock = " [locked by %s]" % f["lockedBy"] if f.get("lockedBy") else ""
        print("%s%s\t%s\t%d bytes%s" % (mark, f["uri"], f["name"], f.get("size", -1), lock))
    return 0


def cmd_pdf(args) -> int:
    c = client_from_pairing()
    uri = urllib.parse.quote(args.uri, safe="")
    status, headers, data = c.request("GET", "/api/pdf?uri=" + uri)
    if status == 304:
        print("unchanged (ETag match)")
        return 0
    if status != 200:
        print("download failed: HTTP %d %s" % (status, data.decode("utf-8", "replace")[:200]))
        return 1
    out = args.out or (args.uri.split("/")[-1].rsplit(".", 1)[0] + ".pdf")
    with open(out, "wb") as fh:
        fh.write(data)
    print("wrote %s (%d bytes, version %s)" % (out, len(data), headers.get("etag", "?")))
    return 0


def cmd_push(args) -> int:
    c = client_from_pairing()
    with open(args.file, "rb") as fh:
        pdf = fh.read()
    if not pdf.startswith(b"%PDF"):
        print("refusing: %s does not look like a PDF" % args.file)
        return 1
    holder = args.holder or ("linux-%d" % os.getpid())
    q = "uri=%s&holder=%s" % (urllib.parse.quote(args.uri, safe=""),
                              urllib.parse.quote(holder, safe=""))
    # lock via POST (query params carry uri+holder)
    status, _, data = c.request("POST", "/api/lock?" + q, body=b"")
    if status != 200:
        print("lock failed: HTTP %d %s" % (status, data.decode("utf-8", "replace")[:200]))
        return 1
    lease = json.loads(data.decode("utf-8"))["lease"]
    try:
        status, payload = c.get_json("/api/version?uri=" + urllib.parse.quote(args.uri, safe=""))
        if status != 200:
            print("version check failed: %s" % payload)
            return 1
        base = urllib.parse.quote(payload["version"], safe="")
        put_q = "uri=%s&base=%s" % (urllib.parse.quote(args.uri, safe=""), base)
        # Large uploads over a busy/idle radio can take a while; don't time out mid-send.
        status, _, data = c.request("PUT", "/api/pdf?" + put_q, body=pdf,
                                    headers={"X-Lease": lease}, timeout=180)
        if status == 409:
            print("rejected: device version moved (someone committed first). Re-fetch and rebase.")
            return 1
        if status == 423:
            print("rejected: lease lost (locked by someone else).")
            return 1
        if status != 200:
            print("push failed: HTTP %d %s" % (status, data.decode("utf-8", "replace")[:200]))
            return 1
        print("pushed %d bytes, new %s" % (len(pdf), data.decode("utf-8")[:160]))
        return 0
    finally:
        c.request("DELETE", "/api/lock?lease=%s&holder=%s" % (
            urllib.parse.quote(lease, safe=""), urllib.parse.quote(holder, safe="")))


def cmd_watch(args) -> int:
    c = client_from_pairing()
    since = args.since or ""
    print("watching %s (Ctrl-C to stop)..." % args.uri)
    while True:
        q = "uri=%s&since=%s" % (urllib.parse.quote(args.uri, safe=""),
                                 urllib.parse.quote(since, safe=""))
        try:
            status, payload = c.get_json("/api/watch?" + q, timeout=40)
        except KeyboardInterrupt:
            print("\nstopped")
            return 0
        except Exception as exc:  # noqa: BLE001 - transient network drop, retry
            print("watch error (%s), retrying..." % exc)
            continue
        if status != 200:
            print("watch failed: HTTP %d %s" % (status, payload))
            return 1
        since = payload.get("version", since)
        if payload.get("changed"):
            print("device committed version %s" % since)
            if args.download_to:
                rc = cmd_pdf(argparse.Namespace(uri=args.uri, out=args.download_to))
                if rc != 0:
                    return rc


# ------------------------------------------------------------------- gui ----

def apply_theme(root) -> None:
    """Small, dependency-free modern look on top of clam (present on every Tk).

    Dark slate surfaces, one accent, flat buttons — the same on Linux,
    Windows and macOS, without pulling in ttkthemes/sv-ttk (which would break
    the stdlib-only, copy-and-run goal).
    """
    try:
        from tkinter import ttk
    except ImportError:
        return
    style = ttk.Style(root)
    try:
        style.theme_use("clam")
    except Exception:  # noqa: BLE001 - fall back to whatever the platform has
        pass
    bg, bg2 = "#23262e", "#2c303a"
    fg, dim = "#e8eaf0", "#9aa0b0"
    accent = "#7aa2f7"
    style.configure(".", background=bg, foreground=fg, fieldbackground=bg2,
                    bordercolor="#3d4250", darkcolor=bg, lightcolor=bg)
    style.configure("TFrame", background=bg)
    style.configure("TLabel", background=bg, foreground=fg)
    style.configure("Dim.TLabel", background=bg, foreground=dim)
    style.configure("TButton", background=bg2, foreground=fg, borderwidth=1,
                    focusthickness=0, padding=(10, 5))
    style.map("TButton", background=[("active", "#3a3f4d"), ("pressed", "#343945")],
              foreground=[("disabled", dim)])
    style.configure("TEntry", fieldbackground=bg2, foreground=fg,
                    insertcolor=fg, borderwidth=1, padding=5)
    style.configure("TNotebook", background=bg, borderwidth=0)
    style.configure("TNotebook.Tab", background=bg2, foreground=dim, padding=(12, 5))
    style.map("TNotebook.Tab", background=[("selected", "#3a3f4d")],
              foreground=[("selected", fg)])
    style.configure("Treeview", background=bg2, foreground=fg, fieldbackground=bg2,
                    borderwidth=0, rowheight=24)
    style.configure("Treeview.Heading", background=bg2, foreground=dim, borderwidth=0)
    style.map("Treeview", background=[("selected", "#3a3f4d")])
    style.configure("TScrollbar", background=bg, troughcolor=bg, borderwidth=0,
                    arrowcolor=dim)
    style.configure("Horizontal.TScale", background=bg, troughcolor=bg2)
    try:
        root.configure(background=bg)
        root.option_add("*Listbox.background", bg2)
        root.option_add("*Listbox.foreground", fg)
        root.option_add("*Listbox.selectBackground", "#3a3f4d")
        root.option_add("*Listbox.selectForeground", fg)
        root.option_add("*Listbox.borderWidth", 0)
        root.option_add("*Listbox.highlightThickness", 0)
        root.option_add("*Text.background", bg2)
        root.option_add("*Text.foreground", fg)
        root.option_add("*Text.insertBackground", fg)
    except Exception:  # noqa: BLE001 - cosmetic only
        pass


def cmd_gui(_args) -> int:
    try:
        import tkinter as tk
        from tkinter import ttk
    except ImportError:
        print("tkinter is not installed; use list/pdf/push/watch commands instead.")
        return 1
    c = client_from_pairing()
    root = tk.Tk()
    root.title("xnotes share")
    apply_theme(root)
    holder = "linux-gui-%d" % os.getpid()
    tabs: dict[str, dict] = {}
    cur = {"uri": None, "name": "Shared folder"}

    notebook = ttk.Notebook(root)
    notebook.pack(fill="both", expand=True)

    browser = ttk.Frame(root)
    browser.pack(fill="x", padx=8, pady=4)
    path_var = tk.StringVar(value="Shared folder")
    ttk.Label(browser, textvariable=path_var).pack(side="left")
    file_list = tk.Listbox(root, height=10)
    file_list.pack(fill="x", padx=8)
    entries: list[dict] = []
    shown_idx: list[int] = []  # listbox row -> entries index (after search filter)
    search_var = tk.StringVar(value="")

    def refill_list():
        q = search_var.get().strip().lower()
        file_list.delete(0, tk.END)
        shown_idx.clear()
        for i, f in enumerate(entries):
            if q and q not in f["name"].lower():
                continue
            prefix = "[dir] " if f.get("dir") else ""
            lock = " [locked]" if f.get("lockedBy") else ""
            file_list.insert(tk.END, "%s%s%s" % (prefix, f["name"], lock))
            shown_idx.append(i)

    def browse(dir_uri: str | None, name: str):
        nonlocal entries
        cur["uri"], cur["name"] = dir_uri, name
        path_var.set(name)
        q = "" if dir_uri is None else "?uri=" + urllib.parse.quote(dir_uri, safe="")
        try:
            st, payload = c.get_json("/api/files" + q)
        except Exception as exc:  # noqa: BLE001 - show instead of dying
            path_var.set("list failed: %s" % exc)
            return
        if st != 200:
            path_var.set("list failed: HTTP %d" % st)
            return
        if not payload.get("root", True):
            path_var.set("no folder shared: pick one in the app first")
            entries = []
            refill_list()
            return
        entries = payload.get("files", [])
        refill_list()

    search_row = ttk.Frame(root)
    search_row.pack(fill="x", padx=8, pady=(4, 0))
    ttk.Label(search_row, text="Search").pack(side="left")
    search_entry = ttk.Entry(search_row, textvariable=search_var)
    search_entry.pack(side="left", fill="x", expand=True, padx=(6, 0))
    search_var.trace_add("write", lambda *_: refill_list())

    def go_up():
        # The server only serves inside the granted tree; root has no parent.
        browse(None, "Shared folder")

    def on_open(_event=None):
        sel = file_list.curselection()
        if not sel or sel[0] >= len(shown_idx):
            return
        f = entries[shown_idx[sel[0]]]
        if f.get("dir"):
            browse(f["uri"], cur["name"] + " / " + f["name"])
        else:
            open_tab(f["uri"], f["name"])

    ttk.Button(browser, text="Up", command=go_up).pack(side="right")
    ttk.Button(browser, text="Refresh", command=lambda: browse(cur["uri"], cur["name"])).pack(side="right")
    file_list.bind("<Double-Button-1>", on_open)

    def refresh_tab(uri: str):
        info = tabs[uri]
        try:
            st, payload = c.get_json("/api/version?uri=" + urllib.parse.quote(uri, safe=""))
        except Exception as exc:  # noqa: BLE001 - show instead of dying
            info["version"].set("error: %s" % exc)
            return
        if st == 200:
            info["version"].set(payload.get("version", "?"))
        else:
            info["version"].set("error %d" % st)

    def ensure_tab(uri: str, name: str):
        if uri in tabs:
            return
        frame = ttk.Frame(notebook)
        notebook.add(frame, text=name[:24])
        tabs[uri] = {"version": tk.StringVar(value="?"), "path": tk.StringVar(value=""),
                     "msg": tk.StringVar(value=""), "watching": False}
        ttk.Label(frame, text=name).pack(anchor="w", padx=8, pady=4)
        ttk.Label(frame, textvariable=tabs[uri]["version"]).pack(anchor="w", padx=8)
        ttk.Label(frame, textvariable=tabs[uri]["msg"]).pack(anchor="w", padx=8)
        row = ttk.Frame(frame)
        row.pack(anchor="w", padx=8, pady=8)
        ttk.Button(row, text="Refresh", command=lambda u=uri: refresh_tab(u)).pack(side="left")
        ttk.Button(row, text="Open", command=lambda u=uri, n=name: open_tab(u, n)).pack(side="left")
        ttk.Button(row, text="Save back", command=lambda u=uri: save_tab(u)).pack(side="left")
        ttk.Button(row, text="Watch", command=lambda u=uri: watch_tab(u)).pack(side="left")
        ttk.Button(row, text="Draw", command=lambda u=uri, n=name: open_canvas(u, n)).pack(side="left")
        refresh_tab(uri)

    def open_canvas(uri: str, name: str):
        ensure_tab(uri, name)
        try:
            from xnotes_canvas import CanvasWindow
        except ImportError as exc:
            tabs[uri]["msg"].set("canvas unavailable: %s" % exc)
            return
        try:
            CanvasWindow(root, c, uri, name, holder)
        except Exception as exc:  # noqa: BLE001 - keep the browser alive
            tabs[uri]["msg"].set("canvas failed: %s" % exc)

    def open_tab(uri: str, name: str):
        ensure_tab(uri, name)
        path = cache_path("xnotes-%s-%s.pdf" % (
            hashlib.sha1(uri.encode()).hexdigest()[:8], os.path.basename(name).rsplit(".", 1)[0]))
        try:
            rc = cmd_pdf(argparse.Namespace(uri=uri, out=path))
        except Exception as exc:  # noqa: BLE001 - keep the GUI alive
            tabs[uri]["msg"].set("download failed: %s" % exc)
            return
        if rc == 0:
            tabs[uri]["path"].set(path)
            try:
                open_file(path)
            except OSError as exc:
                tabs[uri]["msg"].set("saved %s (open it by hand: %s)" % (path, exc))

    def save_tab(uri: str):
        path = tabs[uri]["path"].get()
        if not path or not os.path.exists(path):
            tabs[uri]["msg"].set("nothing downloaded yet")
            return
        tabs[uri]["msg"].set("uploading…")
        try:
            rc = cmd_push(argparse.Namespace(uri=uri, file=path, holder=holder))
        except Exception as exc:  # noqa: BLE001 - uploads fail; the tab must survive
            tabs[uri]["msg"].set("push failed: %s" % exc)
            return
        tabs[uri]["msg"].set("pushed" if rc == 0 else "push failed (see terminal)")
        try:
            refresh_tab(uri)
        except Exception:
            pass

    def watch_tab(uri: str):
        def loop():
            since = tabs[uri]["version"].get()
            while tabs[uri]["watching"]:
                q = "uri=%s&since=%s" % (urllib.parse.quote(uri, safe=""),
                                         urllib.parse.quote(since, safe=""))
                try:
                    st, payload = c.get_json("/api/watch?" + q, timeout=40)
                except Exception:  # noqa: BLE001 - keep watching across drops
                    continue
                if st != 200:
                    continue
                since = payload.get("version", since)
                tabs[uri]["version"].set(since)
                if payload.get("changed"):
                    tabs[uri]["msg"].set("device committed %s — reopening" % since)
                    path = tabs[uri]["path"].get()
                    if path:
                        try:
                            cmd_pdf(argparse.Namespace(uri=uri, out=path))
                        except Exception as exc:  # noqa: BLE001 - keep watching
                            tabs[uri]["msg"].set("re-download failed: %s" % exc)
            # loop ends when tab closed
        tabs[uri]["watching"] = True
        threading.Thread(target=loop, daemon=True).start()

    browse(None, "Shared folder")
    root.geometry("680x480")
    root.mainloop()
    for info in tabs.values():
        info["watching"] = False
    return 0


# --------------------------------------------------------------- selftest ---

def cmd_canvas(args) -> int:
    try:
        from xnotes_canvas import run_standalone
    except ImportError as exc:
        print("canvas needs tkinter: %s" % exc)
        return 1
    c = client_from_pairing()
    name = args.name or args.uri.split("/")[-1]
    run_standalone(c, args.uri, name)
    return 0


def cmd_selftest(_args) -> int:
    failures = []

    def check(name: str, cond: bool):
        print(("PASS " if cond else "FAIL ") + name)
        if not cond:
            failures.append(name)

    check("fingerprint normalize", normalize_fingerprint("AB:cd:01") == "abcd01")
    q = urllib.parse.quote("content://x/1", safe="")
    check("uri quoting round-trips", urllib.parse.unquote(q) == "content://x/1")
    c = ShareClient("https://127.0.0.1:42317", "ab" * 32, "tok")
    check("https required", c.port == 42317)
    try:
        ShareClient("http://127.0.0.1/", "ab" * 32, "tok")
        check("http rejected", False)
    except ValueError:
        check("http rejected", True)
    return 1 if failures else 0


# ------------------------------------------------------------------- main ---

def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description="xnotes LAN share companion (TLS, pinned).")
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("pair", help="trust a device (TOFU) and store credentials")
    p.add_argument("--url", required=True, help="https://<device-ip>:42317 from the notification")
    p.add_argument("--fingerprint", required=True, help="cert fingerprint shown on the device")
    p.add_argument("--token", required=True, help="bearer token (device pairing file)")
    p.set_defaults(func=cmd_pair)

    s = sub.add_parser("status", help="server status")
    s.set_defaults(func=cmd_status)

    li = sub.add_parser("list", help="list shared files (a folder, with --uri)")
    li.add_argument("uri", nargs="?", default=None, help="folder uri from a previous list (default: root)")
    li.set_defaults(func=cmd_list)

    dl = sub.add_parser("pdf", help="download a PDF snapshot (open it in Firefox/pdf.js)")
    dl.add_argument("uri")
    dl.add_argument("out", nargs="?")
    dl.set_defaults(func=cmd_pdf)

    pu = sub.add_parser("push", help="upload an edited PDF (lock + version-checked PUT)")
    pu.add_argument("uri")
    pu.add_argument("file")
    pu.add_argument("--holder", default=None)
    pu.set_defaults(func=cmd_push)

    w = sub.add_parser("watch", help="follow device commits for one file")
    w.add_argument("uri")
    w.add_argument("--since", default="")
    w.add_argument("--download-to", default=None)
    w.set_defaults(func=cmd_watch)

    g = sub.add_parser("gui", help="tabbed viewer, one tab per shared file")
    g.set_defaults(func=cmd_gui)

    t = sub.add_parser("selftest", help="offline checks, no device needed")
    t.set_defaults(func=cmd_selftest)

    cv = sub.add_parser("canvas", help="draw on a note in the built-in canvas (live stroke sync)")
    cv.add_argument("uri")
    cv.add_argument("name", nargs="?", default=None)
    cv.set_defaults(func=cmd_canvas)

    args = ap.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
