#!/usr/bin/env bash
# Safely update ONLY the installed rewatch store module to support REPOSITION.
# Never touches Simkl tokens, Caddy, APK/IPA, or unrelated addons.
set -euo pipefail

echo "ACTION=REWATCH_MANUAL_REPOSITION_GUARDED_DEPLOY"
if [[ "$#" -ne 1 || ! "$1" =~ ^[0-9a-f]{40}$ ]]; then
  echo "RESULT=INVALID_COMMIT_PIN"; exit 1
fi

SOURCE_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
REPO="$(cd -- "$SOURCE_DIR/../.." && pwd)"
INSTALLED="/opt/nuvio-rewatch-staging/services/rewatch-sync"
SERVICE="nuvio-rewatch-staging.service"
OLD_STORE_BLOB="95e523afaa5570ceedf62a3b31013c5520e54613"
backup=""
swapped=0
healthy=0

finish() {
  status=$?
  trap - EXIT
  if [[ "$swapped" == 1 && "$healthy" != 1 ]]; then
    echo "ROLLBACK=STARTED"
    if sudo -n install -o root -g root -m 0644 "$backup/store.mjs" "$INSTALLED/store.mjs" &&
       sudo -n systemctl restart "$SERVICE" &&
       [[ "$(sudo -n systemctl is-active "$SERVICE")" == "active" ]]; then
      echo "ROLLBACK=OLD_STORE_RESTORED"
    else
      echo "ROLLBACK=FAILED_BACKUP_RETAINED"
    fi
  fi
  if [[ -n "$backup" ]]; then
    echo "ROOT_BACKUP_DIR=$backup"
  fi
  echo "PRODUCTION_ADDONS=UNTOUCHED"
  echo "SIMKL_HISTORY=UNTOUCHED"
  echo "CADDY=UNTOUCHED"
  if [[ "$healthy" == 1 ]]; then
    echo "RESULT=MANUAL_REPOSITION_STAGING_DEPLOYED"
  else
    echo "RESULT=NOT_DEPLOYED_OR_ROLLED_BACK"
  fi
  exit "$status"
}
trap finish EXIT

[[ "$(git -C "$REPO" rev-parse HEAD)" == "$1" ]] ||
  { echo "ERROR=SOURCE_COMMIT_MISMATCH"; exit 1; }
[[ -s "$SOURCE_DIR/store.mjs" && -s "$SOURCE_DIR/server.test.mjs" ]] ||
  { echo "ERROR=SOURCE_MISSING"; exit 1; }
[[ -s "$INSTALLED/store.mjs" && -s "$INSTALLED/server.mjs" ]] ||
  { echo "ERROR=INSTALLED_SERVICE_MISSING"; exit 1; }
[[ "$(sudo -n systemctl is-active "$SERVICE")" == "active" ]] ||
  { echo "ERROR=SERVICE_NOT_ACTIVE"; exit 1; }
[[ "$(git hash-object "$INSTALLED/store.mjs")" == "$OLD_STORE_BLOB" ]] ||
  { echo "ERROR=UNEXPECTED_INSTALLED_STORE_VERSION"; exit 1; }

echo "===== SOURCE TESTS ====="
(
  cd "$SOURCE_DIR"
  node --check store.mjs
  node --check server.mjs
  npm test
)
echo "NODE_TESTS=PASSED"

echo "===== BACKUP ====="
backup="$(sudo -n mktemp -d /var/tmp/nuvio-rewatch-manual-rollback.XXXXXXXX)"
sudo -n chmod 0700 "$backup"
sudo -n cp -p "$INSTALLED/store.mjs" "$backup/store.mjs"
sudo -n cp -p /var/lib/nuvio-rewatch-staging/rewatches.json "$backup/rewatches.json"
sudo -n chmod 0600 "$backup/rewatches.json"
echo "BACKUP_CREATED=YES"

echo "===== ATOMIC STORE UPDATE ====="
sudo -n install -o root -g root -m 0644 "$SOURCE_DIR/store.mjs" "$INSTALLED/.store.manual-reposition.tmp"
sudo -n mv -f "$INSTALLED/.store.manual-reposition.tmp" "$INSTALLED/store.mjs"
swapped=1
sudo -n systemctl restart "$SERVICE"

for attempt in $(seq 1 15); do
  status="$(curl --noproxy '*' -s --max-time 2 -o /dev/null -w '%{http_code}' \
      http://127.0.0.1:8765/v1/rewatches || true)"
  if [[ "$status" == "401" ]] &&
      [[ "$(sudo -n systemctl is-active "$SERVICE")" == "active" ]]; then
    break
  fi
  sleep 1
done
[[ "$status" == "401" ]] ||
  { echo "ERROR=UNAUTHENTICATED_HTTP_GUARD_FAILED"; exit 1; }
ss -lnt | grep -Eq '127[.]0[.]0[.]1:8765[[:space:]]' ||
  { echo "ERROR=LISTENER_NOT_LOOPBACK"; exit 1; }
[[ "$(git hash-object "$INSTALLED/store.mjs")" == "$(git hash-object "$SOURCE_DIR/store.mjs")" ]] ||
  { echo "ERROR=NEW_STORE_MISMATCH"; exit 1; }

healthy=1
echo "SERVICE=ACTIVE"
echo "UNAUTHENTICATED_HTTP=401"
echo "DATABASE_SCHEMA=UNCHANGED"
