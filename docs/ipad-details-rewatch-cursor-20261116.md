# iPad Details rewatch cursor — November 16, 2026

## Problem and behavior

Continue Watching and cross-device progress use the independent rewatch cursor, while Details chose its primary action only from normal provider progress/history. Thus the same show could resume S5E17 from CW while Details offered S5E22. Both main Details Play paths also armed manual episode-picker intent for that historical target.

Details now observes the profile-tagged replay snapshot and loads/reconciles it on entry or foreground return. A matching active PLAYING or NEXT_UP cursor supplies the displayed label, exact metadata episode ID, season/episode, canonical parent identity, and resume position together. NEXT_UP carries an explicit zero position. Missing/unavailable/ambiguous episode metadata keeps the replay label and disables its primary action rather than substituting historical playback. With no applicable active replay, the existing normal/shuffle selection remains the fallback.

The common action feeds both tablet and phone layouts and the normal/stream-selection Play handlers. Those primary actions clear abandoned picker intent and do not authorize REPOSITION. Explicit episode-row selections retain their existing intent flow. A stale profile's primary callback does not dispatch playback after a profile switch.

This branch also includes the previously validated automatic completion/progress queue recovery, pause no-op protection, and foreground hooks from the matching mobile sync branch. These preserve queued events in the recovery archive and keep the app's existing logins, storage schema and Simkl original history.

## Validation and remaining work

- 209 host JVM tests passed: 200 replay regressions and nine Details cases covering S5E22 history versus synced S5E17, position and target, Next Up zero, profile/owner isolation, IMDb aliases, metadata failure, fallback preservation and picker intent.
- Host execution uses the real Details resolver and replay code, extracted unchanged model/wrapper declarations, and substitute platform storage/profile/clock/localization/release-time adapters. It does not establish native iOS UI execution.
- The inherited client/server contracts cover the revision-1040 completion versus revision-1044 cursor using the actual Kotlin queue/merge implementations and unchanged Node server in a temporary fixture. No transport protocol change is added by this Details correction.
- Workflow configuration was checked for manual Release packaging only, dedicated iPad V1 Simkl injection, enabled replay gate, a 120-minute job limit, no simulator-linking step on this branch, and no automatic build on branch publication. The source retains the previous app identifiers and runtime configuration requirements.
- Native IPA compilation and actual iPad installation/UI verification remain required. No app uninstall, reset, VPS deployment or database edit is part of this correction.

## Acceptance

Install over the existing iPad app using the same signing identity and bundle identity. On the existing profile, open the same show's Details and confirm its label agrees with CW and the current VPS episode. Press Resume and verify the actual episode and approximate starting position. Recheck that this primary action did not publish a spurious REPOSITION to the historical episode. Keep the isolated branches until both devices pass these presentation and playback checks.
