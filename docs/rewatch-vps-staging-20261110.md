# Oracle VPS: isolated rewatch staging preparation (no deployment)

Branch: `prototype/rewatch-vps-staging-20261110` in `mistertomlinson/NuvioMobile`. It is intentionally separate from the iPad feature branch and live Android dev.

The test service must **not** be exposed publicly or added to your existing addon PM2/Docker processes. There is no live VPS change or automatic deployment.

## Read-only preflight on VPS

Check that Node.js 20+ is installed, `127.0.0.1:8765` is unused, and service resources permit a separate daemon:

```sh
node --version
ss -lntp | grep ':8765 ' || true
```

## Staging implementation plan

1. Review the GitHub Actions `Rewatch VPS staging tests` workflow results before fetching any service code.
2. Create a dedicated OS service identity and private data directory. Use a separate source checkout at `/opt/nuvio-rewatch-staging`, pinned to the reviewed commit on the staging branch. Do not alter `/home/ubuntu/streaming-originals`, `/home/ubuntu/seasonal-spotlight` or `/home/ubuntu/credit-analyzer`.
3. Provision one independently generated, random bearer token for each device, privately, and bind each to a **server-side person principal** and its specific `deviceId`. Only give credentials to authorized clients. The service refuses tokens shorter than 32 characters.
4. Store the credentials JSON in a mode-0600 file readable by the dedicated service account, e.g. `/etc/nuvio-rewatch-staging/credentials.json`. Never paste real tokens into GitHub or chat. Start with a separate `REWATCH_DB_PATH` under `/var/lib/nuvio-rewatch-staging` and explicit loopback bind.
5. Start under a dedicated systemd service (example in `services/rewatch-sync/rewatch-staging.service.example`) only after review. Validate local authenticated GET/POST with disposable sample data. Do not connect real Nuvio apps yet.
6. For any later remote staging access, configure an HTTPS reverse proxy with authorization logging redacted, request limits, and an allowlist. Do not directly expose port 8765. Stage the reverse proxy and client integrations in later changes, not in this commit.
7. Back up the isolated staging state. To roll back, stop/disable only `nuvio-rewatch-staging.service`, preserve its private data directory for audit, and remove only its dedicated checkout/service unit if desired.

## Remaining release gates

- Authoritative metadata verification of successors and series termination.
- SQLite/Postgres transactional storage and migration/backup/recovery tests.
- Strong device enrollment, rotation, and alias resolution.
- Offline event outboxes in both clients; profile mapping and cross-device conflict UX.
- Android APK and signed iPad IPA device tests proving Simkl watched history is unchanged.

The shipped unit is an **example**, not enabled by CI. Operators must inspect environment paths and permissions before use.
