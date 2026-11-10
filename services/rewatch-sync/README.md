# Nuvio replay sync — isolated staging prototype

This service is **not connected** to Nuvio's live Android/iPad builds or to Simkl. It has no dependency on other VPS addons.

## Development

Node 20+ required; no npm dependencies. Run `npm test` from this directory.

The service exposes `GET /v1/rewatches`, `GET /v1/rewatches/:sessionId`, and `POST /v1/rewatches/events` for START, PROGRESS, COMPLETE_EPISODE, and STOP. Every event includes an idempotent event ID, expected server revision, session ID, canonical series ID, and token-bound device ID.

Use **separate strong tokens** and explicitly bind multiple devices to the same server-side person ID. Device-local profile indexes never establish identity. The service stores replay state only, never Simkl tokens or watched history.

## Required staging environment

- `REWATCH_CREDENTIALS_FILE`: JSON file with mode 0600, owned by the service account, mapping per-device bearer tokens (32+ characters) to `{"principal":"person-id","deviceId":"ipad-unique"}`.
- `REWATCH_DB_PATH`: private path for staging JSON state, e.g. `/var/lib/nuvio-rewatch-staging/rewatches.json`.
- `REWATCH_BIND`: must be `127.0.0.1` or `::1`.
- `REWATCH_PORT`: defaults to `8765`, subject to checking that the port is free.

Never commit credentials, database contents or real progress histories.

See [staging deployment instructions](../../docs/rewatch-vps-staging-20261110.md).


## Staging-only per-person enrollment

The command-line helper `scripts/enroll-staging-device.mjs` runs **on the VPS only**. It never exposes a public registration endpoint, prints no bearer token, and changes no application automatically.

Run as the dedicated `nuvio-rewatch` service user with `REWATCH_CREDENTIALS_FILE` pointing to its 0600 credentials JSON:

- `node scripts/enroll-staging-device.mjs new-person` creates a random **new person principal**, a distinct device ID and a 256-bit token. The token and person mapping are written only to a 0600 receipt inside the private credentials directory.
- `node scripts/enroll-staging-device.mjs link-device /etc/nuvio-rewatch-staging/receipts/<existing-device-receipt>.json` creates a different token for another device belonging to the **same** person. An existing receipt must match a previously enrolled device.
- The helper prints only the private receipt's pathname and a reminder that credentials must be reloaded. It does not restart the running server; its in-memory credential map changes only on a deliberate restart.
- Never copy receipts, tokens or unredacted credential files into GitHub, chats, terminal shortcut output, logs, or a distributable app. Enrollment receipts must not be served over HTTP.

**Important:** This is an administrator-only provisioning tool for staging. It is not yet an app-side account signup, QR pairing or friend self-enrollment flow. A personal friend must not receive a shared token or someone else's receipt. An HTTPS setup and a safe client pairing mechanism are prerequisites to distribution.

## Not production-ready

This is a **single-process JSON store** rather than a transactional multi-process DB. Episode successors are structurally checked, not verified against authoritative episode inventories. It has no public self-enrollment, secure device-pairing UI, token rotation, outbound queues, canonical alias mapping, reverse-proxy rate limits, or app network clients. Keep on loopback and do not expose this listener publicly. No production rollout or automatic deployment occurs from this branch.
