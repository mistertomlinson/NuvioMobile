# Rewatch sync — verified branch inventory and VPS contract

Date: 2026-11-09
Status: client-side reconciliation policy only. **No VPS service, networking, credentials, migrations or production writes deployed.**

## Verified source branches

- Android: `mistertomlinson/NuvioTV:design/persistent-simkl-rewatch-cross-device` at initial inspection commit `59fb5f85f`. It already contains `RewatchSessionStore` (profile-scoped DataStore), `RewatchSessionPolicy`, Home replay projection and player replay handoff. Do **not** mistake `dev`'s temporary 3-minute Next Up seed bridge for this branch's persistent replay implementation.
- Apple: `mistertomlinson/NuvioMobile:feature/persistent-simkl-rewatch-0.6.7`. It stores isolated replay sessions in per-profile NSUserDefaults, gates replay tracking, projects Home Continue Watching cards and has a composition-independent save coordinator.
- Both have matching core fields: `ownerKey`, `canonicalSeriesId`, `sessionId`, `episode`, `positionMs`, `durationMs`, `lastCompletedEpisode`, `phase`, `updatedAtEpochMs`.

## Server-owned identity and security

- Authenticate each device to the VPS with a dedicated credential. Do not accept local profile IDs, Simkl usernames, or show IDs as authorization.
- Link authenticated devices to a **server-side person/profile principal**. Android's `local-profile:2` is not automatically the same person as iOS's `local-profile:2`; device-local owner keys must be translated by an explicit binding.
- Prefer a verified stable Simkl series ID, with an alias map for IMDb/TMDB IDs; do not infer show identity by title or apply an alias blindly.
- HTTPS; no Simkl OAuth tokens in replay payloads, VPS logs or server storage. Apply per-profile authorization on every read and mutation.

## Proposed v1 API (not implemented)

- `GET /v1/rewatches`: read authenticated person's replay snapshots, server revisions and tombstones.
- `POST /v1/rewatches/events`: idempotent event with UUID `eventId`, bound `deviceId`, stable show identity, session ID, expected revision, event type, and replay-only state. Server checks owner and expected revision atomically.
- Event types: `START`, `PROGRESS`, `COMPLETE_EPISODE`, `STOP`. Only a confirmed completed episode may advance to Next Up; unavailable metadata produces `AWAITING_METADATA`.
- `GET /v1/rewatches/{sessionId}`: retrieve server state after revision conflict.
- Return `schemaVersion`, `revision`, `sessionId`, `episode`, `phase`, position/duration, last completed episode, and server update timestamp. Revisions are server-issued monotonic integers per owner and series; never rely on client clock ordering.
- A `FINISHED` tombstone prevents old Simkl Next Up cards from reappearing; retain it until a deliberate new replay session replaces it.
- Duplicate event IDs must return the original accepted outcome without applying completion twice. Stale `expectedRevision` must return conflict, not overwrite.

## Client reconciliation (pure policy added to iPad branch)

- Server revision newer + no local pending changes + verified owner/series: apply remote.
- Newer server revision + unacknowledged local changes: **conflict**, do not discard the local cursor.
- Server revision already applied: keep local.
- Remote `FINISHED`: treat as tombstone, not a request to alter Simkl watched history.
- Identity mismatch: conflict and require explicit mapping resolution.
- Do not expose these decisions to Home until transport, per-device pending event queue, and profile binding are implemented and tested.

## Required next implementation gates

1. Choose a dedicated VPS service/repository and credential provisioning strategy, without reusing public addon endpoints.
2. Add server-side persistent storage, transactions, revision compare-and-swap, event deduplication, profile authorization and identity alias mapping.
3. Add opt-in sync clients and durable offline outboxes to **both** forks. Keep replay local-only on network/auth failure.
4. Test simultaneous Android/iPad episode progress, stale disconnect/reconnect, offline completion, restart, profile switching, duplicate events, tombstone propagation, cross-device initial replay start, original Simkl history unchanged.
5. Review tests and deploy to a staging endpoint before any production enablement.

No APK or IPA production release should be made solely on the strength of these policy tests.
