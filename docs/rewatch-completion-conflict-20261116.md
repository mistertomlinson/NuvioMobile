# Rewatch completion conflict — November 16, 2026

## Observed failure

The installed Android build was 2066ea2ef9e7d444fb2066356f7bdd4d0bd931e0. The user supplied this consistent live state:

| Source | Episode | State | Position | Revision |
| --- | --- | --- | --- | --- |
| VPS, independently read | S5E17 | PLAYING | 31,948 ms | 1044 |
| Android authenticated GET | S5E17 | verified | — | 1044 |
| Android stored cursor | S6E5 | NEXT_UP | — | local generation 1045 |
| Android outbox head | completion of prior episode | COMPLETE_EPISODE, one pending event | — | expected 1040 |

Android repeatedly logged upload=CONFLICT, then fetched revision 1044 successfully, but the progress-only recovery declined COMPLETE_EPISODE. The pending event therefore blocked local application and Home reconciliation. CW and Details both displayed S6E5. These logs establish a client reconciliation failure; the VPS correctly refuses the stale write.

The queued completion's complete payload/session ID is not exposed in the log. Production recovery validates those values before archiving anything. It will preserve and decline a malformed or different-session event rather than guessing.

## Change

The internal operation is now archiveSupersededPlayback. It validates an obsolete, contiguous chain of automatic PROGRESS, COMPLETE_EPISODE and RESOLVE_METADATA events by replaying the actual transition rules and comparing every resulting cursor field with the current local state. This includes the last completed episode, phase, position and duration. A completion cannot advance an unstarted NEXT_UP, and metadata resolution requires the prior awaiting state.

Recovery still requires a fresh authenticated, newer server revision, the same profile/account/device/session, valid payload identities, an unchanged local snapshot/generation, and archive capacity. Manual REPOSITION, STOP, START, IMPORT, malformed events and unexplained local changes stay pending. Different positions within the same PLAYING episode also remain protected; identical already-published cursors and completed NEXT_UP handoffs can reconcile.

The exact original queued events are preserved in recoveryArchive. The existing archivedProgress receipt field retains its serialized name and now validates PLAYING, NEXT_UP, AWAITING_METADATA and completion-derived FINISHED cursors. It supports a crash between archival and atomic local application. A subsequent local mutation invalidates that receipt. After applying and certifying the server cursor, the acknowledged revision becomes 1044; ordinary future progress is planned against that revision, rather than 1040.

No guessed replacement completion or REPOSITION is posted. No Simkl history, login, profile, VPS code or live database is changed by this source work. Home polling, navigation/trailer guards, and the earlier Details cursor implementation remain included and are unchanged.

## Validation

- Regression first run against installed source: 7 expected failures, including the exact completion-at-1040 / remote-1044 case. This establishes that the previous progress-only implementation did not handle the reported state.
- Android host JVM: 222 tests, including actual foreground/outbox/reconciler/merge policy, the actual Details target resolver, completion archive/restart, strict chain validation, profile/concurrency protection and future progress anchoring. Log/storage adapters are substituted; the Video model is extracted from source with an unused Stream stub. This is not the full Android native suite.
- Mobile shared Kotlin host JVM: 200 tests, including archive restart and atomic application of a NEXT_UP receipt. Platform profile/storage/clock adapters are substituted. This is not an iOS native build or simulator run.
- The real TV and mobile Kotlin implementations both reproduce COMPLETE_EPISODE@1040 against the unchanged Node VPS source on loopback. The fixture generates all 1044 revisions through real store operations, ending at S5E17 PLAYING, position 31,948 ms. Both clients archive the exact completion, survive queue restart, apply S5E17, certify revision 1044, reject a late S6E4 save, and plan subsequent ordinary progress at 1044. Handoff itself makes no accepted VPS write. The revision-1-to-666 manual and earlier passive-progress contracts also remain covered.
- Native Android full suite/build and the actual installed-app readback remain required. Expected native test total is at least 701, with zero failures/errors/skips.
- No new IPA build or GitHub Actions run has been started. Matching mobile source is preserved on a separate branch for native validation after Android readback succeeds.

## Device acceptance starts with the existing failure

Do not select another episode as a workaround. After the new build is locally tested and safely installed, open Nuvio Home on the existing profile. It should archive the obsolete completion, fetch and apply the existing VPS cursor, and update CW and Details to that episode. If the VPS remains at the supplied state, the expected target is S5E17 near 00:32, with server revision still 1044.

Read the normal diagnostics again. Expected sequence includes queued_playback_handoff=ARCHIVED, foreground_conflict_pull=REMOTE_APPLIED, and then clean/completed reconciliation. A later check should show no old completion pending. If validation declines, retain its output and existing data; do not clear the queue or change the VPS cursor.

Only after that baseline works should playback and the reverse-device direction be tested. Passing host contracts does not establish that the installed apps now agree.
