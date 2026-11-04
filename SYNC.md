# Companion sync — wire spec v1 (LAN first, USB same path)

Goal: the Android app and the Linux companion edit shared notes simultaneously,
with live stroke feedback in both directions, over a network neither trusts.

## Roles and topology

- **Host** = the Android app. It owns the files, the policy, and commit authority.
  It applies every committed op and fans it out.
- **Companion** = the Linux app. It keeps a cache of shared files and submits ops.
- Transport: the companion **listens** (TLS server, default port `42317`), the host
  **dials** (TLS client). A desktop has the stable IP and is always on; a phone
  connects to it. USB is the same path: `adb reverse tcp:42317 tcp:42317`, then
  the host dials `127.0.0.1:42317`. No mDNS in v1 (manual IP entry); `_xnoteshare._tcp`
  stays reserved for later.
- One TCP connection per pairing (multiplexed by `file` fields). Reconnect with
  resume (per-file versions, `resync` on mismatch).

## Security (hostile LAN)

- TLS 1.2+ only, no plaintext listener anywhere, either side.
- Each side generates its own long-term identity on first run: RSA-2048 +
  self-signed X.509 (`CN=xnotes-share`, 10 y). Android mints via BouncyCastle
  into `filesDir/share/` (PEM trio, tmp+rename); Linux mints via
  `openssl req -x509 -newkey rsa:2048 -nodes` into
  `~/.config/xnotes/companion.{key,crt}`.
- Pairing is TOFU + short authentication string:
  1. Companion shows a random 6-digit code (single use, 5 min, 3 attempts).
  2. Host user types it into Connect. It travels **inside** TLS in `pair_request`.
  3. Companion shows `Approve <device-name>?` with the host cert fingerprint;
     the user approves on the desktop.
  4. Both sides **persistently pin** the other's cert fingerprint (SHA-256 of DER).
- After pairing, mutual authentication on every connect: each side aborts unless
  the peer's live fingerprint equals the pin. Unpair deletes both pins.
- No bearer tokens in v1: the pinned mutual-TLS identity *is* the auth. (The old
  HTTP API's token scheme is retired with it.)
- Rate-limit pairing attempts; pairing codes are single-use.

## Framing

- Every frame: 4-byte big-endian length `N` (1..16 MiB) + `N` bytes UTF-8 JSON.
- Binary (file bytes, stroke samples) rides as base64 inside JSON in v1
  (debuggable; a binary frame type may follow without changing op semantics).
- Every message has `t` (type) and `seq` (per-sender monotonic, for logs).
  Replies carry `in_reply_to`. Unknown `t` values are ignored (forward compat);
  malformed frames close the connection.

## Handshake

The dialer (host) speaks first on every connection:

```
HOST -> COMP hello {t, seq, app, version:1, device_name, host_cert_fp}
COMP -> HOST hello_ack {t, seq, in_reply_to, companion_cert_fp, paired:bool, code_required:bool}
# unpaired (code_required):
HOST -> COMP pair_request {t, seq, device_name, host_cert_fp, code}
COMP -> HOST pair_result {t, seq, ok, reason?}   # after desktop approval; both sides pin now
# paired (hello_ack paired, or after pair_result ok):
COMP -> HOST index_subscribe {}
HOST -> COMP index {files:[{id, path, kind:file|dir, version, size}], full:true}
```

`host_cert_fp` / `companion_cert_fp`: lowercase hex SHA-256 of cert DER.
`device_name`: e.g. `Pixel 8`; the companion shows `Guest-<device-name>` only
for not-yet-paired devices (paired ones show their pinned name).

## Files

- `id`: host-assigned **stable file identity**, never a path. It survives
  renames and moves; deleting the file retires its id, and a new file created
  at the same path gets a fresh id and is unshared by default (no policy
  inheritance by name). On Android the id is the document URI (stable across
  rename/move inside its provider); the index also carries the current
  `path` (relative, `/`-separated, for display and navigation) and `name`,
  which change on move/rename while `id` does not.
- A shared **folder** shares its whole subtree by id-prefix: the index lists
  the dir id, and new files under it appear in later `index` deltas
  (`full:false`) with fresh ids, already shared.
- `version`: host's per-file commit counter (integer, starts at 1, +1 per
  committed batch). Companion caches `(id -> version, bytes)`.
- Fetch: `C -> S fetch {id, offset?, length?}` / `S -> C file {id, version,
  total, offset, data(base64), last?}`. Chunked (256 KiB) so big PDFs stream.
- Images ride the same chunking as their own message: `image_data {file,
  image_id, total, offset, data(base64), last?}` (256 KiB), sent before the
  `add_image` op that keys off `image_id`. Receivers hold the op until the
  last chunk lands (bounded), then materialize the file and place the item.
- Changes ops cannot carry (a joined PDF, a cover page) go as a whole-file
  `file` push from the side that made them: current bytes (live document
  when open), head bumped once, index delta alongside. The far side replaces
  its bytes and reloads the file if open. Rare and explicit, never silent:
  only a PDF join or a cover change sends one.
- The companion opens a fetched file as a normal note; its bytes round-trip
  through the same codec, so a file the companion never edits stays
  byte-identical.

## Companion-requested create/delete (host approves in UI)

- The companion cannot change the file set on its own. It asks:
  `C -> S delete_request {id}` / `create_request {dir_id?, name, kind}`.
- The host shows a popup (Approve / Deny) with the file name and the
  companion's device name. Deny (or timeout) replies
  `S -> C delete_result / create_result {ok:false}` and nothing changes.
- Approve applies the change on the host, then `S -> C {ok:true, ...}` plus
  an `index` delta; a created file arrives with its fresh id (shared from
  birth when made under a shared folder, else unshared — the host decides).

## Committed ops (both directions, host-authoritative)

```
C -> S  op {file, base_version, ops:[...], dedupe}
S -> C  op {file, base_version, version, ops:[...], dedupe}  # host's own commits + fan-out; version = new head
S -> C  op_ack {file, version}                          # accepted; version is the new head
S -> C  resync {file, version}                          # base_version stale; refetch, then retry once
```

- `dedupe`: client-generated UUID per batch; a retried batch never double-applies.
  One id per batch end to end: the host fans a batch back with the *same*
  `dedupe`, so the sender recognizes its own echo (head moves, live run drops,
  model untouched) and a retry converges. A `:fanned` suffix from older hosts
  is tolerated the same way.
- Host applies a companion batch iff `base_version == head`; else `resync`.
- Companion applies host batches unconditionally (host is authority), including
  full-file `resync` (drop open doc state for that file, reload bytes).
- Mirror-first: each side applies accepted ops to its open document immediately,
  then persists (host) / marks dirty (companion, host persists on op receipt —
  companion never writes the host's files directly). Remote stroke adds bake
  into the warm ink cache in place (`appendToCache`), remote erases patch just
  the erased area (`repairRegion`); neither drops the page cache, so a remote
  commit never blanks the page for a frame.
- Receivers paint live runs in content space (inside the origin + zoom
  transform, like all page content); the committed `add_stroke` op follows
  through the normal path.

Op set v1 (strokes/erases/pages; text/markup follow the same envelope):

- `add_stroke {page, stroke:{tool, width, color, pressure, samples:[[x,y,p]...]}}`
  in page px. `tool`: `pen|highlighter|...`; `color`: `#rrggbb`; `width`: pt.
  Identity = content fingerprint: SHA-256 hex (first 16) of
  `tool|width(%.3f)|color|samples`, samples as `x.2f,y.2f,p.3f` joined by `;`
  (`,` within). Test vector: `pen|3.000|#00e676|10.00,20.00,0.500;11.50,21.00,0.600`
  → `11812f53989ff4dc`. Retries and double-delivery converge.
- `erase_strokes {page, ids:[fingerprint...]}`: exact fingerprint match applies;
  no match = no-op (never an error).
- `add_page {index, width, height}`: insert a blank page at `index` (page px
  sizes from the sender's page); out-of-range clamps. `remove_page {index}`:
  remove the page at `index`; no-op when it would empty the note or is out of
  range. Both bypass undo and relayout; page content beyond the blank shell
  (pasted items, PDF joins, reorder) is not carried in v1.
- `open_room {page, sideways, at, size}`: open room at `at` on `page` (across
  the page when `sideways`); `close_room {page, sideways, a, b}`: close the
  strip `a` to `b`. Both sides re-execute the same deterministic cut
  (same base version ⇒ same result); no-op when the page or room is gone.
- `add_image {page, image_id, width, height, rect:[x,y,w,h], orientation,
  angle, flip_x?, flip_y?, part?}`: place an image on `page`. The bytes travel
  first as `image_data` chunks (below); the op applies once they are in, and
  waits for them when they are still in flight. Eraser-protected images are
  never erased; select-delete of any non-stroke item is not synced in v1.
  Unknown op names are skipped with a log.

Undo of a remote op is local-only (undo removes what *you* committed; a
companion undo sends a compensating op, e.g. `erase_strokes` for your stroke).

## Live strokes and room drags (true streaming, both directions)

```
C <-> S  live {file, page, stroke_id, tool, width, color, samples:[[x,y,p]...], done}
C <-> S  room_live {file, page, sideways, split, resume, shift, live_id, done}
```

- Streaming is **per sample, no batching timer**: every pointer sample (and
  every room-drag frame) goes out on the socket as it happens (TCP_NODELAY
  both ends), and receivers paint it immediately — a partial damage rect
  covering just the runs (or the room band), never a full frame. `tool`/
  `width`/`color` ride along so the far side renders the run exactly as drawn.
- Receivers show the run as an overlay keyed by `(sender, stroke_id)`,
  *appending* each frame's samples, and a room drag as a live `RoomView`
  (same settle machinery, model untouched). `done:true` ends the run; the
  matching committed `add_stroke` (same id as dedupe) also drops it, and a
  room commit clears its preview — so a lost `done` frame can never leave a
  stale duplicate beside the committed result.

- Only for the file(s) both sides have open. Frames for a closed file,
  a `stroke_id` never started, or a room drag while the local Insert space
  gesture is active, are dropped.

## Presence and keepalive

- `presence {file, page?}` on open/page-change/close (`page:-1` = closed).
  v1 shows it in logs; UI badges follow.
- `heartbeat {}` every 20 s either way; 60 s silence closes the connection.
  Either side redials/resumes (host redials; companion re-listens).

## Policy (host side)

- Default-deny set of **stable file/folder ids** (never paths), persisted as
  `{"shared": [...]}` — the existing `SharePolicy` model, keyed by id.
  The index only ever lists shared ids.
- Rename/move keeps the id: the file stays shared and the companion sees it
  at its new path. Delete retires the id (policy entry pruned); a later file
  at the same path is a new id and stays unshared.
- Unshare mid-session: host sends `index` delta; companion drops the file
  (dirty companion-only state is kept locally, never pushed).
- View-only grants and kick/revoke: v2 (lease + grant in `hello_ack`).

## What is deliberately v2

View-only grants, kick/revoke mid-session, multi-companion fan-out beyond one,
binary frames, mDNS discovery, online relay, text/markup ops, conflict-free
undo across sides.

v1 syncs strokes, erases, blank page add/remove, room open/close and image
placement: local undo of a synced op does not propagate (re-sync by
refetching), area-erase splits and sticky-note ink stay local, undo/redo of
page commands is local, select-delete of non-stroke items is not mirrored,
and pasted page content, cover flags and page reorder are not carried as ops
(cover/PDF joins push whole-file bytes instead).

## Conformance

- Android (`app/.../share/companion/`) and Linux (`shared/.../sync/` + Qt)
  implement this file independently (different model classes); the spec text is
  the contract.
- Both sides carry framing + handshake + op tests: app `:app:testDebugUnitTest`,
  Linux `:shared:jvmTest` + `:shared:linuxX64Test` and Qt smoke tests.
