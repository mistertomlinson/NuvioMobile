> Historical audit for the November 15 source. See [the November 16 completion-conflict correction](rewatch-completion-conflict-20261116.md) for the currently implemented completion recovery.

# Paused Android / iPad rewatch handoff

This branch extends installed Android commit 44bb0660edb8b7bd0a2bc7f45f5f54afde2b0606. Its Details cursor and explicit-selection safety fixes remain included. No live app, VPS, login, or Simkl history was changed while preparing this source.

## Evidence and limits

The user confirmed that Android's S6E4 was legitimate viewing by his wife, and that Android was paused when the iPad test ran. The supplied Android log later reports LOCAL_CONFLICT without a remote-roster fetch. Foreground synchronization uploads first and immediately returns on a rejected upload. Therefore a stale pending event can prevent Android from ever fetching the other device's cursor. The existing log does not identify that pending event's type or revision; the new build logs these non-secret fields. This source fix reproduces that failure for a passive PROGRESS queue. It does not establish that this is the exact currently blocked queue until device readback verifies it.

Android Home previously refreshed on composition/load/player-return keys, without an activity-resume hook. Leaving via the TV Home button can retain that composition. The periodic player save loop already skips unmoved positions; additional pause/exit callbacks can still produce timestamp-only saves.

## Changes

- On a fresh authenticated GET, a newer server episode can supersede an obsolete chain containing only PROGRESS events. The envelope and payload identities, contiguous original revisions, current local episode/position/duration, authenticated profile/account/device/session, archive capacity, and unchanged local generation are checked. The local cursor must be explained by the queued progress.
- All superseded events are preserved verbatim in recoveryArchive. A durable archivedProgress receipt permits recovery after a crash between archiving and local application. The replay DataStore then compares the local cursor and mutation generation atomically. A concurrent save, changed account, or changed session prevents application.
- Foreground synchronization makes one bounded recovery attempt after a conflict, then retries the remaining queue. It does not POST a guessed REPOSITION or rebase paused progress onto the other device's new episode.
- Duplicate position/duration saves are exact no-ops. Clean proofs for other, untouched titles remain usable with a fresh generation observation; an older in-flight observation is still rejected.
- Home refreshes on actual foreground return. Recurring checks run at most every 15 seconds while Home is RESUMED. Requests coalesce. Polls defer during hero/card trailer playback or buffering, popup dialogs, and for two seconds after Home key input. Input tracking is a plain field write, without Compose state changes. Player instances are not created by poll eligibility checks. Poll deferral can delay remote updates until Home is idle; entry/foreground checks still run.
- Identical session projections are filtered before Home's Continue Watching pipeline. A sync-proof-only update cannot retrigger its metadata work. There is no catalog-refresh call.

## Deliberately preserved conflicts

Queued explicit REPOSITION, completion, stop, import, malformed or discontinuous events, future revisions, changed sessions/accounts, same-episode conflicts, unexplained local changes, and full archives remain intact. A fresh explicit user selection retains the existing validated manual-recovery path. These cases are not silently treated as passive progress. Older installed builds do not understand the new optional receipt field; do not use APK rollback as a sync recovery or clear data.

## Validation

- Android host JVM: 202 tests passed, including the real foreground synchronizer, durable outbox, reconciler, and atomic merge policy. Android Log and storage adapters are substituted for host execution; this is not the full Android Gradle or on-device suite.
- Mobile shared Kotlin host JVM: 187 tests passed. Platform profile/storage/clock adapters are substituted; this is not an iOS simulator or native IPA build.
- Both real Kotlin client API/outbox/merge implementations passed against the unchanged Node VPS source on loopback with a temporary database. Existing revision-1-to-666 manual recovery still passes. Both paused handoffs preserved exact queued events, survived an outbox restart, adopted the fetched cursor, blocked an old-episode save, and made no new server write during handoff.
- The live VPS source matched the five supplied hashes in the earlier audit. No new VPS deployment is needed for this client-side recovery.
- Full Android native tests/build, trailer/navigation performance, and the real device handoff remain required. No new IPA build or Actions workflow was started.

## Device acceptance

1. Run full native Android tests and build on the isolated audit worktree. Install with matching signer and install -r only.
2. Keep the existing iPad IPA for the first one-way verification. Pause Android on its current episode. Deliberately select another watched episode on iPad and let it play long enough to publish.
3. Read the VPS cursor and revision, then return Android to Home. Confirm foreground_conflict_pull=REMOTE_APPLIED or a normal REMOTE_APPLIED, the same episode/position in Continue Watching and Details, and correct playback target. Check that waiting or exiting the old paused player does not restore its episode on the VPS.
4. Test Home navigation and trailers on the TV; confirm polling defers and unchanged cursors do not refresh the row. Source-level scheduling checks do not prove frame-time performance.
5. The matching mobile branch contains symmetric passive-progress recovery and an actual foreground lifecycle hook. Native-verify it before a further IPA build; verify the reverse direction with the iPad paused after installation. Passing local contracts alone does not satisfy the system acceptance test.
