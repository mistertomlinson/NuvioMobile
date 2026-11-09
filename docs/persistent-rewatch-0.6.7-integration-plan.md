# Persistent Rewatch — Apple 0.6.7 full-release fork

Status: **prototype only, no runtime wiring, no released IPA**.
Pinned Apple baseline: upstream `0.6.7-beta` tag, commit `dcade51767a4b76e29bbd28fc067683fb1febf9d`.
Companion Android branch: `mistertomlinson/NuvioTV:design/persistent-simkl-rewatch-cross-device`.

## Scope

This fork is for a **modified and sideloadable 0.6.7 full-release IPA**, not for the moving upstream `cmp-rewrite` branch. The existing `scripts/build-ios-ipa.sh` builds an **unsigned** `full` IPA, which must be appropriately signed during the user's usual sideload workflow. Apple's build requires a compatible Xcode and is unsuitable for the user's macOS Ventura 13.7.1 setup; the upstream `.github/workflows/ios-test-build.yml` uses an Xcode 27.1 macOS runner. Availability/permissions on this fork still need verification when builds begin.

## Code audit: 0.6.7 behavior

- `features/watching/domain/SeriesContinuity.kt` can restart a completed series when `allowRewatch` is selected, but computes the default action using historical watched records; there is no independent durable replay cursor.
- `features/watchprogress/WatchProgressRepository.kt` persists ordinary playback, but does not implement a separate active rewatch session with a cross-device revision.
- `features/player/PlayerScreenRuntimePlaybackActions.kt` sends START/PAUSE/STOP through `TrackingScrobbleCoordinator` to connected tracking providers. Rewatch-aware routing needs to identify a replay **before** scrobble/historical-writing decisions.
- Apple does not consume Android's volatile three-minute Player completion bridge; a server alone cannot alter this installed 0.6.7 IPA.

## Prototype now on this branch

The package `com.nuvio.app.features.rewatch` contains pure `RewatchSessionPolicy` state transitions and common Kotlin unit tests ported from the Android prototype. This ensures the two clients can share the same core replay semantics. Nothing calls the policy yet; no settings, history, scrobbles, network requests or Continue Watching behavior is modified.

## Required changes before a functional IPA

1. Apple local profile-scoped persistence, schema version and migration-safe serialization (do not reuse watched history keys).
2. An unambiguous replay-start policy: explicit action initially, with optional conservative automatic recognition after tests.
3. Integrate replay cursor as a separate high-priority input into `SeriesPlaybackResolver`, Home Next Up, episode click and local player resume; keep normal first-watch behavior unchanged.
4. Integrate authoritative replay completion from the player and autoplay transitions; suppress *Simkl replay history mutations* only for confirmed rewatch sessions, while keeping original watched flags/ratings intact.
5. Implement authenticated versioned VPS sync with account+profile mapping, stable cross-provider show identity and server-controlled revisions, idempotent event IDs and explicit conflict handling.
6. Mirror Android protocol and regression-test cross-device progression, force-stop, offline recovery, episode advancement and historical Simkl immutability.
7. Build unsigned `full` release IPA from this branch, sign/sideload on iPad, validate on device before replacing the installed build.

## Non-goals in prototype

No production server deployment, no Simkl tokens stored on VPS, no app data migration, no reduction of iPad 0.6.7 features, and no branch merge or IPA installation.
