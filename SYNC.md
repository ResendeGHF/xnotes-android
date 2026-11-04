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

```
C -> S  hello {t, seq, app, version:1, device_name, host_cert_fp}
S -> C  hello_ack {t, seq, in_reply_to, companion_cert_fp, paired:bool, code_required:bool}
# unpaired:
C -> S  pair_request {t, seq, device_name, host_cert_fp, code}
S -> C  pair_result {t, seq, ok, reason?}      # after desktop approval; both sides pin now
# paired (or after pair_result ok):
C -> S  index_subscribe {}
S -> C  index {files:[{id, kind:file|dir, version, size, mtime}], full:bool}
```

`host_cert_fp` / `companion_cert_fp`: lowercase hex SHA-256 of cert DER.
`device_name`: e.g. `Pixel 8`; the companion shows `Guest-<device-name>` only
for not-yet-paired devices (paired ones show their pinned name).

## Files

- `id`: path relative to the shared root, `/`-separated (`notes/todo.xnote`,
  `pdfs/book.pdf`). Stable and human-meaningful. A shared **folder** shares its
  whole subtree: the index lists the dir, and new files under it appear in
  later `index` deltas (`full:false`).
- `version`: host's per-file commit counter (integer, starts at 1, +1 per
  committed batch). Companion caches `(id -> version, bytes)`.
- Fetch: `C -> S fetch {id, offset?, length?}` / `S -> C file {id, version,
  total, offset, data(base64), last?}`. Chunked (256 KiB) so big PDFs stream.
- The companion opens a fetched file as a normal note; its bytes round-trip
  through the same codec, so a file the companion never edits stays
  byte-identical.

## Committed ops (both directions, host-authoritative)

```
C -> S  op {file, base_version, ops:[...], dedupe}
S -> C  op {file, base_version, ops:[...], dedupe}     # host's own commits + fan-out
S -> C  op_ack {file, version}                          # accepted; version is the new head
S -> C  resync {file, version}                          # base_version stale; refetch, then retry once
```

- `dedupe`: client-generated UUID per batch; a retried batch never double-applies.
- Host applies a companion batch iff `base_version == head`; else `resync`.
- Companion applies host batches unconditionally (host is authority), including
  full-file `resync` (drop open doc state for that file, reload bytes).
- Mirror-first: each side applies accepted ops to its open document immediately,
  then persists (host) / marks dirty (companion, host persists on op receipt —
  companion never writes the host's files directly).

Op set v1 (strokes first; pages/text/markups follow the same envelope):

- `add_stroke {page, stroke:{tool, width, color, pressure, samples:[[x,y,p]...]}}`
  in page px. `tool`: `pen|highlighter|...`; `color`: `#rrggbb`; `width`: pt.
  Identity = content fingerprint (SHA-256 of canonical encoding, first 16 hex):
  retries and double-delivery converge.
- `erase_strokes {page, ids:[fingerprint...]}`: exact fingerprint match applies;
  no match = no-op (never an error).
- Page add/remove, text-box add, markup add: same `{file, base_version, ops}`
  envelope, defined when implemented; unknown op names are skipped with a log.

Undo of a remote op is local-only (undo removes what *you* committed; a
companion undo sends a compensating op, e.g. `erase_strokes` for your stroke).

## Live strokes (ephemeral, both directions)

```
C <-> S  live {file, page, stroke_id, samples:[[x,y]...], done}
```

- While drawing, the drawer emits its **full current run** ~20 Hz
  (`done:false`); receivers show it as an overlay keyed by `(sender, stroke_id)`,
  *replacing* the previous batch. `done:true` carries the final run.
- Live batches never touch the model or versions; the committed `add_stroke`
  op follows through the normal path (dedupe keys this to the live `stroke_id`
  so pen-up never double-draws).
- Only for the file(s) both sides have open. Stale batches (older `seq` for the
  same `stroke_id`) are dropped.

## Presence and keepalive

- `presence {file, page?}` on open/page-change/close (`page:-1` = closed).
  v1 shows it in logs; UI badges follow.
- `heartbeat {}` every 20 s either way; 60 s silence closes the connection.
  Either side redials/resumes (host redials; companion re-listens).

## Policy (host side)

- Default-deny per-file/per-folder set, persisted as `{"shared": [...]}` —
  the existing `SharePolicy` model. The index only ever lists shared ids.
- Unshare mid-session: host sends `index` delta; companion drops the file
  (dirty companion-only state is kept locally, never pushed).
- View-only grants and kick/revoke: v2 (lease + grant in `hello_ack`).

## What is deliberately v2

View-only grants, kick/revoke mid-session, multi-companion fan-out beyond one,
binary frames, mDNS discovery, online relay, text/markup/page ops, conflict-free
undo across sides.

## Conformance

- Android (`app/.../share/companion/`) and Linux (`shared/.../sync/` + Qt)
  implement this file independently (different model classes); the spec text is
  the contract.
- Both sides carry framing + handshake + op tests: app `:app:testDebugUnitTest`,
  Linux `:shared:jvmTest` + `:shared:linuxX64Test` and Qt smoke tests.
