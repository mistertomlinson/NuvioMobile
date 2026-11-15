# Cross-device replay audit and repair — November 15, 2026

## Verified deployment

The running staging VPS is active. The owner's read-only capture shows one
record, revision 666, Office S5E26 NEXT_UP after S5E25, position/duration zero,
and 666 accepted event results. No live database changes were made during this
audit. All five deployed module SHA-256 hashes exactly match the server source
at `e81369088351a58f511a33acb77b59e343831d7d`.

The exact server source passed its 63 tests. Its authenticated account/device
binding, optimistic revision checks, idempotent retry results, manual backward
reposition, durable writes, and restart behavior do not need a server patch for
the reproduced failure.

## Client repairs

- Both clients can replace a validated stale replay queue after a NEW explicit,
  watched-verified episode selection and a fresh authenticated VPS read. The
  original events are retained byte-for-byte in the durable recovery archive.
- Recovery handles stale PROGRESS, REPOSITION, and validated replay completion
  or metadata-resolution events. Malformed payloads, different identities,
  different sessions, broken revision chains, future revisions, and STOP or
  new-session transitions fail closed without modifying the queue.
- The replacement queue and its server revision anchor are written together.
  Player progress saved before the replacement queue uploads can append at the
  new revision instead of being rejected against the original revision 1.
- An explicit move which missed enqueue while its checkpoint was loading can
  still publish once the authenticated lookup is available.
- Android carries manual-selection approval through the committed-save sink,
  distinguishes invalid progress from stale revisions, and invokes the same
  archive-preserving recovery. Normal saves cannot authorize repositioning.
- Android's main Details button does not authorize a manual rewatch move;
  explicit episode-row clicks do. The separate Details cursor/label change is
  deferred and is NOT included in this branch based on Android `f60c1aa89958`.
- iOS playback timestamps now use milliseconds, matching the sync clock.
  Previously a millisecond remote-apply timestamp could make a subsequent
  whole-second player timestamp appear stale. Verified manual moves on both
  clients advance local timestamps monotonically even within the same tick.
  VPS revisions remain the cross-device ordering authority.

## Verification completed

| Check | Result |
| --- | --- |
| Exact deployed server's Node test suite | 63 passed |
| Android replay-domain JVM tests, Kotlin 2.3.0/project dependency versions | 161 passed |
| Mobile common replay JVM tests, Kotlin 2.4.10/project dependency versions | 176 passed |
| Real Kotlin clients talking to exact Node server over loopback | Passed both directions |
| Exact archival, queue drain, post-rebase progress append, clean baseline | Passed |
| Full Android application unit suite and APK compilation | Pending owner's iMac run |
| iOS native compilation and physical iPad/TV verification | Pending |

The mobile host runner substitutes only platform account, preference-storage,
and clock backends. It does not claim an iOS simulator pass. Run
`scripts/rewatch-contract-audit/run_audit.py` to reproduce the client/server
contract with a temporary database and fake credentials.

The contract fixture reaches revision 666 via real store operations. The real
iPad queue/API code selects S5E16 at 34 seconds, Android reads the same cursor,
Android selects S5E15 at 34 seconds, and iPad reads that cursor. No live VPS,
Simkl, installed application, or original checkout is touched by this test.

## Delivery and physical verification

Android work is isolated on `fix/cross-device-rewatch-sync-20261115`, based on
`f60c1aa89958`. iPad work is isolated on
`fix/cross-device-rewatch-audit-20261115`, based on `3d364bb8019c`. The older
Details-button branch is parked. No existing branch is reset, rebased, merged,
deleted, or force-updated.

The iPad workflow has no push trigger for the audit branch. A deliberately
requested Release build uses the existing bundle identifiers, the dedicated
Simkl V1 registration, and a 120-minute budget. It does not repeat the expensive
whole-app simulator relink or claim native tests have passed.

The next gate is the full Android sideload suite and APK build in a separate
worktree on the owner's iMac. Installation must preserve data and match the
installed signing identity. The existing database must remain in place.
Repair each device's legacy queue with an explicit episode selection if it is
blocked, then prove that a selection on either clean device advances the VPS
and the other device's Continue Watching cursor and resume position.

Passing the source-level tests is not the final success criterion. Both actual
devices and the live VPS must agree on the deliberate replay cursor.
