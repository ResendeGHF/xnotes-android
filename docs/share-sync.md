# Share & sync (LAN, encrypted)

View, browse and draw on notes from a desktop on the same network — or over
a USB cable — without ever trusting the network. The Android device is the
source of truth and serves **TLS only** (no plaintext listener exists); the
Linux companion (`tools/share-client/xnotes_share.py`, plus the built-in
drawing canvas in `xnotes_canvas.py`) shows one tab per file, syncs vector
strokes live, and pushes edits back as real device strokes.

## What is shared: the policy screen

Nothing leaves the device until you tick it. The sidebar's **Policy** button
(between Browse and Graph) lists every file under the granted folder — name
and metadata only, no thumbnails — with a search box. Ticked files become
visible to the paired desktop; unticked URIs answer 404 as if they did not
exist (no existence leak). Policy changes apply to the running server
immediately; pruning dead selections happens automatically.

## Drawing canvas (no external viewer needed)

The companion ships its own canvas, so Firefox/pdf.js is optional:

- `python3 xnotes_share.py canvas '<uri>'` opens a drawing window for one
  note; the GUI's **Draw** button does the same per tab.
- Background pages render on the device (`/api/page`) at the zoom's dpi;
  pen/highlighter strokes draw live and upload on pen-up (`/api/strokes`),
  landing as editable native strokes. Eraser and undo cover your own
  not-yet-synced strokes; a 1 s version poll pulls device commits in.
- Editing one file per party still holds: opening the canvas takes the
  file's edit lease (heartbeat-renewed, released on close). If the device
  holds the same note open with unsaved changes, its next autosave forks a
  copy rather than overwriting — close the note on the device before a long
  drawing session to avoid the fork.

The stroke wire format is plain JSON polylines in PDF points
(`ShareStrokes`), deliberately dumber than the native model; pressure is
baked out at the boundary. The `since` version cursor makes the channel
live-ready: a push transport can replace polling without changing the API.

## How it works

## Companion portability (Windows/macOS/Linux)

The client is stdlib-only Python on purpose, so the same tree runs on
Windows and macOS:

- config lives in `%APPDATA%/xnotes-share` (Windows),
  `~/Library/Application Support/xnotes-share` (macOS),
  `$XDG_CONFIG_HOME`/`~/.config/xnotes-share` (Linux);
- files open with the platform viewer (`os.startfile`/`open`/`xdg-open`);
- temp files use the platform temp dir; the Tk GUI is `clam`-based with no
  third-party theme dependency (`tkinter` ships with python.org and
  Microsoft Store Pythons). Pairing material never leaves the user's profile.

```
desktop (Firefox/pdf.js)            Android device (source of truth)
  xnotes_share.py  <-- TLS 1.2+ -->  ShareService :42317
  GET /api/pdf?uri=…                ShareBackendImpl: committed .pnote bytes
  PUT /api/pdf?uri=…&base=…            → DocumentCodec → PdfExporter snapshot
```

- **Commit-only updates.** Versions (`ETag: "<size>-<mtime>"`) derive from
  the *stored* file, which the editor rewrites via temp-encode-then-copy
  (`writeNoteSafely`). Mid-stroke states live in RAM and are never served;
  `GET /api/watch` long-polls (≤ 25 s) and wakes only on committed writes.
  The desktop therefore sees device edits once the device's save commits —
  seconds later, never keystrokes.
- **One editor per file.** `/api/lock` grants a per-file lease (30 s TTL,
  renewed by heartbeat); different files are independent, so note A can be
  edited on the desktop while note B is edited on the device. A `PUT` needs
  the lease plus the base version it was fetched at — a stale base gets
  `409`, a missing/foreign lease `423`.
- **No silent overwrites.** Desktop `PUT` of a note swaps the bundle's
  embedded source PDF and rewrites the `.pnote` through the same
  temp-validate-copy path as local saves. If the device's editor holds the
  same note with unsaved changes, its existing `changedUnderneath` check
  forks instead of overwriting — both copies survive.
- **Safe on hostile LANs.** Device-generated RSA-2048 + self-signed
  certificate (`files/share/`, 10 y), SHA-256 fingerprint shown on the
  notification for trust-on-first-use, client pins the cert, every request
  carries a 256-bit bearer token, and repeated 401s are delayed (2 s after
  5 failures). Pairing material also lands in `files/share/pairing.json`
  (private storage; `adb pull` until the settings UI lands).

## Setup

1. Start sharing: Preferences → **Sharing** → enable **Share files on the
   local network**. The section shows the `https://<ip>:<port>` URL, the
   certificate fingerprint and the token the desktop needs. Sharing resumes
   on launch while the toggle stays on.
2. Connect the desktop:
   - **Wi-Fi:** same network is enough.
   - **USB cable:** enable USB tethering on the phone, or
     `adb reverse tcp:42317 tcp:42317` and use `https://127.0.0.1:42317`.
3. Pair (verifies TLS pinning + token, stores `~/.config/xnotes-share/`):
   `python3 xnotes_share.py pair --url <url> --fingerprint <fp> --token <tok>`
4. `python3 xnotes_share.py list`, then `gui` (tabs), or `pdf`/`push`/`watch`
   per file. Open downloaded PDFs in Firefox; annotate, save, `push` back.

Discovery: the service also announces `_xnoteshare._tcp` via mDNS
(`avahi-browse -t _xnoteshare._tcp`); the TXT `fp` prefix identifies it.

## API (all under `/api/`, all need `Authorization: Bearer`)

| Method | Path | Notes |
|---|---|---|
| GET | `/api/status` | `{name, api, locked[]}` |
| GET | `/api/files?uri=` | children of a folder (`uri` omitted = granted root); dirs first (`"dir": true`), every file listed (`note`/`pdf`/`canvas`/`file`); only URIs inside the granted tree resolve |
| GET | `/api/version?uri=` | `{uri, version}` |
| GET | `/api/pdf?uri=` | snapshot; `ETag`, `Range`, `If-None-Match` |
| GET | `/api/raw?uri=` | stored bytes as-is |
| POST | `/api/lock?uri=&holder=` | `{lease, expires}` or `423 {lockedBy}` |
| POST | `/api/heartbeat` | form/query `lease`+`holder` |
| DELETE | `/api/lock?lease=&holder=` | `{released}` |
| PUT | `/api/pdf?uri=&base=` | `X-Lease` header; `{uri, version}` / `409` / `423` |
| GET | `/api/watch?uri=&since=` | long-poll → `{uri, version, changed}` |

## Current limits (follow-ups)

- The server only sees the folder granted in the app's Home/explorer (an
  Android SAF rule, not a bug): to share a wider tree, grant a higher folder
  (e.g. the whole `Shared` directory) and browse into subfolders with
  `list <uri>` or the GUI's file list (double-click to enter, Up to go back).
- `PUT` of a multi-MB PDF over a dozing radio can still be slow: the service
  now holds a high-performance Wi-Fi lock while sharing, the client waits up
  to 3 minutes on uploads, and every request is logged to logcat
  (`adb logcat -s xnotes-share`) so a stall shows exactly where it stops.
- Canvas (`.pcanva`) notes are listed but `GET /api/pdf` answers 404 —
  `CanvasPdfExporter` wiring is a separate change.
- `PUT` replaces whole PDFs (what pdf.js saves); per-annotation merge
  (desktop Ink → editable strokes) is future work — nothing is lost, the
  desktop's ink stays baked in the updated source PDF.
- `PUT` to a pure note (no embedded PDF) is rejected (`415`).
- No QR pairing yet — copy the URL, fingerprint and token from the
  Sharing section by hand.
