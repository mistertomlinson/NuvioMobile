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

## Not production-ready

This is a **single-process JSON store** rather than a transactional multi-process DB. Episode successors are structurally checked, not verified against authoritative episode inventories. It has no signed device provisioning, credential rotation, outbound queues, canonical alias mapping, reverse-proxy rate limits, or app network clients. Keep on loopback and do not expose this listener publicly. No production rollout or automatic deployment occurs from this branch.
