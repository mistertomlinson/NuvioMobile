#!/usr/bin/env bash
# Update only the existing loopback staging service. Roll back on failure.
set -euo pipefail

source_dir="$(cd -- "$(dirname -- "\${BASH_SOURCE[0]}")/.." && pwd)"
repo_root="$(cd -- "$source_dir/../.." && pwd)"
target_dir="/opt/nuvio-rewatch-staging/services/rewatch-sync"
service="nuvio-rewatch-staging.service"
expected_head="\${1:-}"
backup_dir=""
installed=0
success=0
rollback_ok=0

finish() {
  local code=$?
  trap - EXIT
  if [[ "$installed" == 1 && "$success" != 1 ]]; then
    echo "ROLLBACK=ATTEMPTED"
    if sudo -n install -o root -g root -m 0644 \
      "$backup_dir/server.mjs" "$target_dir/server.mjs" &&
      sudo -n systemctl restart "$service"; then
      sudo -n rm -f -- "$target_dir/scripts/enroll-staging-device.mjs" || true
      sudo -n rmdir -- "$target_dir/scripts" 2>/dev/null || true
      rollback_ok=1
      echo "ROLLBACK=SERVER_CODE_RESTORED"
    else
      echo "ROLLBACK=FAILED_MANUAL_REVIEW_REQUIRED"
    fi
  fi
  if [[ -n "$backup_dir" ]]; then
    if [[ "$success" == 1 || "$rollback_ok" == 1 || "$installed" == 0 ]]; then
      sudo -n rm -rf -- "$backup_dir" || true
    else
      echo "BACKUP_RETAINED=$backup_dir"
    fi
  fi
  if [[ "$success" == 1 ]]; then
    echo "RESULT=STAGING_UPDATED_AND_HEALTHY"
  else
    echo "RESULT=STAGING_UPDATE_FAILED"
  fi
  echo "PRODUCTION_ADDONS=UNTOUCHED"
  exit "$code"
}
trap finish EXIT

echo "ACTION=REWATCH_STAGING_GUARDED_UPDATE"
if [[ ! "$expected_head" =~ ^[[:xdigit:]]{40}$ ]] ||
   [[ "$(git -C "$repo_root" rev-parse HEAD)" != "$expected_head" ]]; then
  echo "ERROR=SOURCE_COMMIT_NOT_PINNED"
  exit 1
fi
echo "SOURCE_HEAD=$(git -C "$repo_root" rev-parse --short HEAD)"

for filename in server.mjs store.mjs package.json scripts/enroll-staging-device.mjs; do
  test -f "$source_dir/$filename" || { echo "ERROR=MISSING_SOURCE_$filename"; exit 1; }
done
if [[ ! -f "$target_dir/server.mjs" || ! -f "$target_dir/store.mjs" ]]; then
  echo "ERROR=EXISTING_STAGING_SERVICE_MISSING"
  exit 1
fi
if [[ "$(systemctl is-active "$service")" != "active" ]]; then
  echo "ERROR=EXISTING_STAGING_SERVICE_NOT_ACTIVE"
  exit 1
fi
if [[ "$(systemctl is-enabled "$service" 2>/dev/null || true)" != "disabled" ]]; then
  echo "ERROR=STAGING_AUTOSTART_STATE_NOT_DISABLED"
  exit 1
fi
if [[ -e "$target_dir/scripts/enroll-staging-device.mjs" ||
      -L "$target_dir/scripts/enroll-staging-device.mjs" ]]; then
  echo "ERROR=ENROLLMENT_SCRIPT_ALREADY_PRESENT"
  exit 1
fi
# No schema changes or existing data migration.
if ! cmp -s "$source_dir/store.mjs" "$target_dir/store.mjs"; then
  echo "ERROR=STORE_SCHEMA_DIFFERENCE_NEEDS_REVIEW"
  exit 1
fi
echo "PRECHECK=PASS"

(
  cd "$source_dir"
  node --check server.mjs
  node --check scripts/enroll-staging-device.mjs
  npm test
) || { echo "ERROR=SOURCE_TESTS_FAILED"; exit 1; }
echo "SOURCE_TESTS=PASS"

backup_dir="$(sudo -n mktemp -d /tmp/nuvio-rewatch-staging-rollback.XXXXXXXX)"
sudo -n cp -p "$target_dir/server.mjs" "$backup_dir/server.mjs"
sudo -n install -d -o root -g root -m 0755 "$target_dir/scripts"
sudo -n install -o root -g root -m 0644 \
  "$source_dir/server.mjs" "$target_dir/server.mjs"
installed=1
sudo -n install -o root -g root -m 0644 \
  "$source_dir/scripts/enroll-staging-device.mjs" \
  "$target_dir/scripts/enroll-staging-device.mjs"

sudo -n systemctl restart "$service"
ready=0
for attempt in $(seq 1 15); do
  status="$(curl -s --max-time 2 -o /dev/null -w '%{http_code}' \
    http://127.0.0.1:8765/v1/rewatches || true)"
  if [[ "$status" == "401" && "$(systemctl is-active "$service")" == "active" ]]; then
    ready=1
    break
  fi
  sleep 1
done
if [[ "$ready" != 1 ]]; then
  echo "ERROR=LOOPBACK_AUTH_GUARD_NOT_READY"
  exit 1
fi

# Never print credentials, account identities or raw response payloads.
sudo -n -u nuvio-rewatch node --input-type=module <<'NODE'
import { readFileSync } from 'node:fs';
const config = JSON.parse(readFileSync('/etc/nuvio-rewatch-staging/credentials.json', 'utf8'));
const token = Object.keys(config)[0];
if (!token) throw Error('Missing staging test credential');
const response = await fetch('http://127.0.0.1:8765/v1/rewatches', {
  headers: { Authorization: 'Bearer ' + token }
});
const body = await response.json();
if (response.status !== 200 || !Array.isArray(body.items)) {
  throw Error('Authenticated staging smoke check failed');
}
console.log('AUTH_HTTP=200');
console.log('SESSION_COUNT=' + body.items.length);
NODE

if ! ss -lnt | grep -Eq '127[.]0[.]0[.]1:8765[[:space:]]'; then
  echo "ERROR=STAGING_LISTENER_NOT_LOOPBACK"
  exit 1
fi
echo "UNAUTH_HTTP=401"
echo "SERVICE_ACTIVE=$(systemctl is-active "$service")"
echo "SERVICE_ENABLED=$(systemctl is-enabled "$service" 2>/dev/null || true)"
echo "CREDENTIALS_CHANGED=NO"
echo "DATABASE_MIGRATED=NO"
echo "PUBLIC_PORT_OPENED=NO"
success=1
