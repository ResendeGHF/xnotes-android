# Shared notes — plan (LAN first, online later)

Goal: two or more users, no matter the platform, edit the same note
simultaneously — Samsung-Notes-style shared notes — starting on the LAN,
expandable to online later.

## Where things live

- `android-downstream` (this repo's old `downstream`, reset to `44bbfd1e`):
  the Android app. Stays Android.
- `linux-downstream` (this branch, fork of `DarkL0gos/xnotes-linux`
  master @ `e4e1f8f7`): the Linux host (KMP `shared/` → `libxnotes.so`
  via `capi/` → Qt 6 `linux-qt/` app). This is where active work happens.
- `archive/share-jvm-spike`: the previous JVM-only share attempt (TLS
  server, op stream, Qt companion). Kept for reference — the protocol
  design below reuses its lessons, not its code.
- Upstream `shardulvs/xnotes-android` (active, master @ Oct 31) and the
  DarkL0gos fork (stale since Oct 11) are remotes only. We never merge
  from DarkL0gos again; upstream merges go into `-downstream` branches
  deliberately, never blindly.

## Locked decisions

1. **Revert**: `android-downstream` == `44bbfd1e`; share work archived,
   not deleted.
2. **Must work on Linux** (full editing): strokes, pencil engine, Samsung
   notes format, PGP import/export (encrypted *folders* may stay
   Android-only for now; single encrypted notes must round-trip).
   **Preserve-only** (Linux must open without corrupting, no editor):
   image scan, stroke tracing, math studio, and everything else
   device-specific.
3. **Format compat is the gate**: a set of reference notes exercising the
   custom features must open identically on Android and Linux before any
   merge lands. (Owner to produce the set.)
4. **Protocol is a fresh implementation** (the old live-stroke streaming
   never worked reliably). Same threat model and semantics, new code —
   see "Sync protocol" below.
5. **Identity**: two modes from day one. (a) Friend share: guest shows as
   `Guest-<device-name>`, owner approves per session. (b) Companion mode:
   persistently paired devices (TOFU certs) that join without approval.
6. **Permissions**: per-note opt-in (Policy screen); guests get edit by
   default with a view-only toggle; owner can revoke/kick mid-session
   (drops lease + connection).
7. **Scope**: both whole-folder share (new files auto-shared) and
   single-note share.
8. **Testing**: real hardware — this desktop + SM-X510 over adb. Loopback
   and unit tests here; device interop is owner-tested.
9. **Upstream**: hard independent fork, move fast. No merges from
   DarkL0gos ever again.
10. **Division of labor**: owner forks + gets the pristine fork building
    (`capi` lib + `linux-qt` app). Implementation here once the env is
    confirmed green.

## Measured risks (Nov 2026)

- Syncing `linux-downstream` with upstream Oct-31 master was attempted on
  a scratch branch: **181 conflicted files**, concentrated in the shared
  core both sides rewrote (`core/model`, `core/text`, `canvas`,
  `format`). NOT done — deferred until a working Qt build exists to
  verify against. The fork builds as-is; that is the baseline.
- This sandbox has JDK/CMake/GCC but **no Qt6 headers**, so the Qt app
  itself can only be verified on the owner's machine. The KMP `shared`
  module (`:shared:jvmTest`, `:shared:linuxX64Test`) can be verified
  here, and all sync logic must live there with tests on both targets.

## Sync protocol (fresh design, draft v0)

Transport: TLS 1.2+ only, no plaintext listener. TOFU server cert
pinning + per-device identity certs; every request carries a bearer
token; repeated 401s are delayed (brute-force resistance on hostile
LANs). Discovery via mDNS (`_xnoteshare._tcp`), direct connect by URL.

- **Host = owner.** The host serves the note's committed bytes; guests
  never write files directly.
- **Live strokes**: ephemeral full-run batches while drawing
  (~20 Hz, replace semantics), `done` carries the final run.
- **Committed ops** (the realtime channel, not polling): stroke add by
  content fingerprint, erase by content match (no-match = no-op, never
  an error), page add/remove with index + size, text-box add, markup
  add, targeted single-page resync (undo/redo). Ops fan out to every
  watcher with a per-note sequence; overflow forces a clean refetch.
- **Writes**: per-file edit lease (TTL + heartbeat) + base-version check
  (`409` → refetch once → retry); duplicate keys dedupe (retried pen-ups
  never double-draw). No silent overwrites, no forks: mirror-first
  application into the open document on both ends.
- **Permissions**: share offers carry an edit or view-only grant; the
  owner can revoke mid-session (lease dropped, connection closed).
- Keep the wire free of LAN-only assumptions (no raw sockets, no
  broadcast dependence) so a relay can replace mDNS+direct-TLS later
  without changing op semantics.

## Phases

- **0. Setup (done)**: revert, archive, branch map, remotes, this plan.
- **1. Baseline + compat proof**: pristine fork builds on owner hardware;
  reference notes open byte-identically on both apps; format gaps fixed
  in fork `shared/` (open + preserve, no editors yet).
- **2. Merge for compat**: port the must-survive features' *model +
  codec* support into fork `shared/` (Android keeps its UI).
- **3. Share MVP**: one note, owner + one guest, live strokes + commits
  + presence, LAN only, both apps. Verified on real hardware.
- **4. Multi-user hardening**: folders, view-only grants, kick/revoke,
  N guests, reconnect/resync, conflict-free undo.
- **5. Online**: relay design (deferred; protocol already relay-shaped).

## Open items (owner)

- Reference-note set for the compat gate (Q2).
- Confirm pristine fork builds on your machine (`capi` lib + `linux-qt`
  app) — the "env ready" signal for Phase 1.
- GitHub fork under your account for pushing (local `linux-downstream`
  branch exists here; pushing needs your auth).
