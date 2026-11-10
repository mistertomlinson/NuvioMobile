# Rewatch sync: verified Simkl identity, no second login

Decision: **reuse the user's existing Simkl sign-in**. No Nuvio-sync password, operator-issued token, manual pairing code, or shared friend credential. Keep the experimental PIN/invitation work disabled and never expose it through Caddy.

## Verified app capabilities

- NuvioMobile `SimklAuthRepository.kt` already holds a per-profile access token, checks token expiry, and exposes a token for authorized Simkl calls. `SimklAuthModels.kt` contains `accountId` in both UI and stored auth state.
- NuvioTV `SimklAuthRepository.kt` already stores a scoped auth token and parses `SimklUserSettingsResponse.account.id`. `SimklAuthModels.kt` stores `accountId`.
- Both clients are on different Simkl login implementations (iPad authorization-code + PKCE, Android TV PIN). Do not reauthorize solely to start private rewatch sync.
- Official Simkl API documents authenticated `POST https://api.simkl.com/users/settings` with `account.id` and `account.type` in the response. This **account ID is an identifier, not proof** without a verified Simkl token.
- Official Simkl AUTH V2 is recommended and V1 retirement is anticipated around April 2027. Keep auth migration separate from replay identity binding.
- Native Simkl rewatch sessions are documented as PRO/VIP only. The private replay store must remain standalone so free Simkl accounts still work and canonical watched/ratings remain untouched.

## Trust and identity model

1. User signs into Simkl using **Nuvio's existing flow**. Playback and existing Simkl sync remain unchanged.
2. Client opts in to sending replay data to the personal VPS and posts its **already-issued Simkl access token** over HTTPS to a **bootstrap-only endpoint**. Never put the token in URL/query strings or app logs. A client may also include its registered public Simkl client ID; server accepts only a reviewed allowlist of client IDs.
3. VPS contacts Simkl *server to server* with that access token and allowed client ID and calls `POST /users/settings`. It accepts only Simkl's successful authenticated response containing a valid stable `account.id`. **Do not trust any account ID supplied by the app.**
4. On success, the VPS binds the device to a canonical principal `simkl:<account.id>`, issues an opaque random, device-specific, **24-hour** replay-only session credential, and discards the Simkl token without persistence. Store only a cryptographic hash of the replay session credential in server-side registry.
5. Replay APIs accept **only** the scoped VPS credential. The Simkl token does not accompany progress updates. Replay session credentials cannot call Simkl and cannot query another principal.
6. Every device signed into the same verified Simkl account derives the same server principal automatically; different accounts are strictly isolated. Users on the same iPad/TV must get profile-scoped credentials and local replay state.
7. For session renewal, repeat Simkl verification using an existing valid Simkl token. A sign-out, profile switch, auth failure, server-side revoke, or account mismatch immediately clears the device's replay-only credential and local in-flight outbox binding. Never send one profile's events using another profile's principal.
8. If Simkl or the VPS is offline, existing local rewatch continues and pending changes stay in a per-profile offline outbox. Never silently switch to unauthenticated global sync.
9. Keep replay reads/writes outside Simkl history/ratings/scrobble writes. In particular, do **not** enable Simkl's PRO-only `?allow_rewatch=yes` flag in this design.
10. For limited friends-only distribution (approximately three trusted friends): **no extra privacy settings screen, consent dialog, opt-in switch, account registration, or pairing UI**. Once the existing Simkl account is connected and verified, replay sync operates automatically. The maintainer can tell friends directly that rewatch progress is hosted on the personal VPS. Keep server-side device revocation and data deletion available administratively if needed; do not weaken authentication or mix accounts.

## Implementation security gates

- HTTPS required at public edge; fail-closed 503 placeholder stays until explicitly reviewed deployment.
- Bootstrap request limit, tight payload bounds, timeout, no redirect, fixed upstream host `api.simkl.com`, network failure handling, strict JSON parsing, and no response body/token logging.
- Test 401/403, mismatched client IDs, invalid/revoked Simkl tokens, malformed account IDs, concurrent bootstraps, two devices/one account, two accounts isolation, switching profiles, short-lived tokens, token expiration/renewal, deduplication, offline retry, revoke, and restart.
- Never rely on reported account name, email, profile index, device ID, or arbitrary `accountId` in the request as an authentication claim.
- Avoid long-lived or static shared secrets embedded in APK/IPA.
- Verify compatibility against Simkl's V1 and V2 user settings behavior with **non-sensitive test credentials** during staging. Never paste an access token or entire settings response into ChatGPT.
- Use proper transactional credential and replay-state storage before public production, not the prototype's single-process JSON files.
- No change to Caddy or live service in this documentation commit.

## First planned code step

Implement an isolated, dependency-injected **Simkl token verifier** with a fake upstream in tests, a per-device replay credential registry with TTL, and a separate loopback-only HTTP bootstrap route disabled by default. Test it without touching the installed staging daemon. Only after that should we connect app-side network clients and revisit public Caddy routing.

## Staged implementation, November 10, 2026

- `simkl-identity.mjs`: fixed HTTPS upstream, POST settings, server-allowlisted app client IDs, authenticated token verification, strict `account.id`, no trust in app-supplied IDs, network failure and redirect rejection. Test upstream is injected; no real Simkl credentials are used in CI.
- `simkl-sessions.mjs`: random replay-only tokens hashed at rest, issued per device for 24 hours and revocable. The user's Simkl access token never enters replay JSON.
- `server.mjs`: optional `/v1/simkl/bootstrap` and `/v1/simkl/revoke` routes; **both are disabled unless explicitly configured**. They are separate from Simkl's watched/ratings APIs.
- Existing Android TV and iPad apps already store Simkl account identity; automatic silent initialization on in-place upgrade must reuse the saved authorization, without re-running PIN or browser OAuth.
- Current public `nuviorewatch.duckdns.org` remains a **static HTTPS 503 placeholder**. No public replay endpoint, client credential flow, or app behavior is changed by these commits.
