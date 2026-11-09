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
- **Android-to-Android (symmetric):** every phone both listens (hosts its own
  files for peers) and dials (reads peers' files as guest). The listener tries
  `42317` then bumps until free (`42317..42347`) and shows the bound port; the
  dialer scans that range when no port is typed. One phone can host several
  peers at once (per-peer grant gates, shared version store) and guest several
  peers at once (one session each). The `hello` message carries `listen_port`
  so a peer can dial back without a scan.

## Pairing modes

- Linux companion: code-entry (`pair_request` + desktop approval), as above.
- Android-to-Android: **mutual-confirm SAS**. `hello_ack` carries
  `pair_mode:"sas"` plus `sas` (6 digits, `SHA-256(min(fpA,fpB)|max(fpA,fpB))`,
  order-independent so both ends agree). Both phones display the SAS; the
  dialer sends `pair_confirm {device_name, host_cert_fp, sas}` after its user
  taps Matches, and the host pins + answers `pair_result {ok}` after its user
  taps Allow. A relay MITM cannot produce the same SAS on both ends.

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
for not-yet-paired devices (paired ones show their pinned name). Both sides
may rename themselves (a user-set nickname travels as `device_name`) and
keep a local alias per peer; identity is always the certificate fingerprint,
never the name.

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
  total, offset, data(base64), last?}`. Chunked (1 MiB) so big PDFs stream.
- Stale cache: when the guest holds an older version it opens those bytes at
  once and asks only for the edits — `C -> S fetch_ops {id, since_version}`.
  The host replays its op journal (each op with `base_version` chaining from
  the last) and the guest applies them in order; anything the journal no
  longer covers answers `resync` and the guest refetches whole. Every applied
  op is gated on the open document's version, so a gap refetches instead of
  mis-applying.
- Big files open progressively: before the chunks, the host sends
  `S -> C file_head {id, version, total, manifest, flow?, markups?,
  templates?, packages?}` (manifest always; the rest best-effort and size
  capped). The guest opens a skeleton document from it immediately — ink,
  shapes and text are already complete — and the chunks fill images, audio
  and the source PDF in place behind it, without reopening.
 - Images ride the same chunking as their own message: `image_data {file,
  image_id, total, offset, data(base64), last?}` (256 KiB), sent before the
  `add_image` op that keys off `image_id`. Receivers hold the op until the
  last chunk lands (bounded), then materialize the file and place the item.
- Audio rides two chunk streams: `audio_data {file, audio_id, total, offset,
  data(base64), last?}` for the encoded recording and `audio_peaks` with the
  same shape for its waveform bins. Either may arrive first; the op applies
  when the bytes are complete, patching in late peaks. Empty (zero-byte)
  bodies are legal and apply at once.
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

## Note-link permission (guest asks, host approves in UI)

- A tapped note link whose target the guest cannot see asks first:
  `C -> S link_request {id, device}`.
- An already-shared target answers `S -> C link_result {id, ok:true}` at
  once, with no popup. An unknown id answers `{ok:false}` the same way.
- Otherwise the host shows a popup (Allow / Deny, works from the status-bar
  notification with the screen off). Allow grants the target to that peer,
  pushes an `index` delta covering it, and answers `{ok:true}`; the guest
  then fetches and opens it. Deny (or timeout) answers `{ok:false, reason}`.

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

- `add_stroke {page, stroke:{tool, width, color, pressure, samples:[[x,y,p]...], style?}}`
  in page px. `tool`: `pen|pencil|taper|highlighter|...`; `color`: `#rrggbb`; `width`: pt.
  `style` carries the full stroke style so pencil/taper/highlighter/dashed/
  speed/calligraphy/neon render identically everywhere: `{pmin, dir, speed,
  taper, taper_min, neon, neon_s, dash_l, dash_g, hl_alpha, hl_inv, pencil_p,
  pencil_d, pencil_g, pencil_s}` (all optional; absent = tool defaults).
  Old peers ignore `style` and fall back to tool defaults. Live frames carry
  the same `style` alongside `tool/width/color`, and overlays paint through
  the real Stroke painter (grain/taper/multiply match the commit).
  Identity = content fingerprint: SHA-256 hex (first 16) of
  `tool|width(%.3f)|color|samples`, samples as `x.2f,y.2f,p.3f` joined by `;`
  (`,` within). Test vector: `pen|3.000|#00e676|10.00,20.00,0.500;11.50,21.00,0.600`
  → `11812f53989ff4dc`. Retries and double-delivery converge. Pure restyles
  (same points, new style) are detected via full-config compare and replay
  as erase+add, so they propagate despite identical fingerprints.
- `erase_strokes {page, ids:[fingerprint...]}`: exact fingerprint match applies;
  no match = no-op (never an error). Strokes recorded during audio carry a
  `sync:{rid, ms}` moment each; the mark rides the wire but never enters the
  fingerprint, so old peers match the same ink.
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
  angle, flip_x?, flip_y?, part?, uid?}`: place an image on `page` (upserted
  by `uid`, falling back to `image_id` from old peers). The bytes travel first
  as `image_data` chunks (below); the op applies once they are in, and waits
  for them when they are still in flight. `remove_image {page, ids:[uid...]}`
  takes images off. Moves and restyles re-send the same `image_id` with the
  new geometry (an upsert), so no new wire type is needed.
- `add_text {page, text:{uid, x, y, width, height, text, color, point_size,
  face, bold?, italic?, underline?, strike?, align?, math?, link?}}`: place or
  update a text box (upserted by stable `uid`). `remove_text {page, ids:[uid...]}`
  takes boxes off. Colors are `#rrggbb` or `#rrggbbaa`.
- `add_table {page, table:{uid, x, y, w, h, cols:[...], rows:[...],
  stroke_color, stroke_width, cells:[[text...]], link?}}`: place or update a
  table (upserted by stable `uid`). `remove_table {page, ids:[uid...]}` takes
  tables off. Cell edits send the whole grid; the receiver replaces it.
- `add_audio {page, audio:{audio_id, uid, page, x, y, w, h, name, rid, ext,
  duration_ms}}`: place an audio pill (upserted by stable `uid`, falling back
  to `audio_id` from old peers). The encoded bytes travel first as
  `audio_data` chunks and the waveform as `audio_peaks` chunks (below); the op
  applies once the bytes are in (peaks may arrive later and patch the pill).
  `remove_audio {page, ids:[uid...]}` takes pills off. Each side plays its own
  copy: playback position is never synced, so replays stay user-independent.
- Item identity: text/table/image/audio/shape carry a stable `uid`, assigned at
  creation and persisted in the file. Items that predate uids derive one
  deterministically from kind, page, index and content (`legacy-` + 16 hex),
  so every peer that reads the same bytes derives the same id and a
  remove-by-uid converges. Unknown op names are skipped *without* failing the
  batch, but a peer that cannot apply a known op must refetch the file (see
  `resync` below) rather than ack-and-diverge.
- `add_shape {page, shape:{uid, kind, start:[x,y], end:[x,y], stroke_color,
  stroke_width, fill_color?, neon?, neon_strength?, dashed?, dash_length?,
  dash_gap?, rotation?, flip?, adj?, n?, head_start?, head_end?, path?,
  function?, label?, locked?, live_id?}}`: place or update a shape (upserted
  by stable `uid`, covering every family: basic, flowchart, arrows, 3D/math,
  functions, connectors, UML). `shape` is the `ShapeKind` id; `path` is SVG
  for free outlines; `function:{expr, from, to, ymin?, ymax?}` plots graphs.
  `remove_shape {page, ids:[uid...]}` takes shapes off. A snapped shape
  carries the `live_id` of the run it replaces, so the guest drops the
  pre-snap ink the moment the shape lands.
- `add_markup {page, markup:{id, type, color, intensity, quads:[[l,t,r,b,q]...],
  text, note?, link?, created, modified}}`: mark PDF text (`type` is
  `highlight|underline|strikeout|squiggly|note_link`; `color` is `#rrggbb`,
  alpha rides `intensity`). `remove_markup {page, ids:[markup-id...]}` takes
  them off. Redelivery is idempotent (adds skip known ids); both bypass undo.
  A `note_link` markup whose `link` names a file id is a note-to-note link —
  tapping it opens the target when shared, else asks the host (below).
- `page_style {entries:[{index, page_color?, template?, pattern_color?,
  spacing?, accent_color?, params?, colors?, invert_colors?, invert_images?,
  margins?}], doc_style?, doc_margins?}`: set page styles (colors as
  `#rrggbb`, pattern color `#rrggbbaa` with line opacity, margins as
  fractions). Absent fields inherit (leave alone). Invert flags ride along
  but only when explicitly set: null keeps the local view, so guests (who
  always strip them on send) never clobber the host, while a companion's
  explicit toggle lands. Fetched file bytes never carry invert flags; invert
  mirrors over this op only.
- Selection move/resize/rotate/restyle/delete and area-erase splits replay as
  plain `erase_strokes` + `add_stroke` in one batch (pre-change fingerprints,
  post-change strokes), so every peer converges with no new wire type.
  Strokes only; text/table/image/audio/shape selections replay as remove +
  add of their uid-keyed snapshots through the ops above.
  Unknown op names are skipped with a log.
- Undo/redo propagate as inverse committed ops (no new wire type): strokes
  replay as erase+add replacements (fingerprint+config diff), markups as
  remove+add, pages as add/remove, text/table/image/audio/shape as remove+add
  of their uid-keyed snapshots (the undo diff upserts changed items and
  removes missing ones). Anything without a wire form (tape, labels, sticky
  notes, groups, page order) still converges via debounced full-file push on
  the host side. Remote applies bypass undo, so this is the only path by
  which an undo converges. Guest cache tabs show
  `External - {inherited name}`, never empty.
- Forward compatibility: unknown op *names* decode to no-ops and never break
  their batch (known siblings still apply). A peer that cannot apply a known
  op (e.g. a pill whose model it lacks) must refetch the file instead of
  acking and diverging: the next commit's base then misses and the host
  answers `resync`, which reloads the whole file.

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
  stale duplicate beside the committed result. A snap-to-shape replaces the
  run with an `add_shape` carrying its id as `live_id`, so the pre-snap ink
  vanishes the moment the shape lands instead of lingering until reopen.

- Only for the file(s) both sides have open. Frames for a closed file,
  a `stroke_id` never started, or a room drag while the local Insert space
  gesture is active, are dropped.

## Presence and keepalive

- `presence {file, page?}` on open/page-change/close (`page:-1` = closed).
  v1 shows it in logs; UI badges follow.
- `heartbeat {}` every 20 s either way; 60 s silence closes the connection.
  Either side redials/resumes (host redials; companion re-listens).

## Policy (host side)

- Default-deny set of **stable file/folder ids** (never paths). Grants are
  per paired device: the host keeps one shared set per companion fingerprint
  (SQLite, `devices` + `grants` tables, migrated once from the old global
  `{"shared": [...]}` file) and only the active companion's grants gate the
  index, ops and file serving. Sharing a folder shares its whole subtree,
  present and future files.
- The index only ever lists shared ids.
- Rename/move keeps the id: the file stays shared and the companion sees it
  at its new path. Delete retires the id (policy entry pruned); a later file
  at the same path is a new id and stays unshared.
- Unshare mid-session: host sends `index` delta; companion drops the file
  (dirty companion-only state is kept locally, never pushed).
- View-only grants and kick/revoke: v2 (lease + grant in `hello_ack`).

## What is deliberately v2

View-only grants, kick/revoke mid-session, multi-companion fan-out beyond one,
binary frames, mDNS discovery, online relay, conflict-free
undo across sides.

v1 syncs strokes, erases, blank page add/remove, room open/close, image
placement, text/tables/audio/markups/page styles and shapes (all families,
live snap included); stale files catch up through the op journal instead of
refetching, and big files open from a head skeleton that fills in behind.
Local undo of a synced op propagates as inverse ops. Area-erase splits and
sticky-note ink stay local, undo/redo of page commands is local,
select-delete of non-stroke items is mirrored by uid, and pasted page
content, cover flags and page reorder are not carried as ops
(cover/PDF joins push whole-file bytes instead).

## Conformance

- Android (`app/.../share/companion/`) and Linux (`shared/.../sync/` + Qt)
  implement this file independently (different model classes); the spec text is
  the contract.
- Both sides carry framing + handshake + op tests: app `:app:testDebugUnitTest`,
  Linux `:shared:jvmTest` + `:shared:linuxX64Test` and Qt smoke tests.
