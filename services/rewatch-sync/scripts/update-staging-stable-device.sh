#!/usr/bin/env bash
# Guarded replacement of ONLY the already-installed server and Simkl session module.
# Automatic Simkl bootstrap stays OFF and the public HTTPS host stays static 503.
set -euo pipefail

echo "ACTION=REWATCH_SIMKL_STABLE_DEVICE_GUARDED_UPDATE"
if [[ "$#" -ne 1 || ! "$1" =~ ^[[:xdigit:]]{40}$ ]]; then
  echo "RESULT=STOP_INVALID_SOURCE_PIN"
  exit 1
fi
pin="$1"
source_dir="$(cd -- "$(dirname -- "$0")/.." && pwd)"
root="$(cd -- "$source_dir/../.." && pwd)"
install_dir="/opt/nuvio-rewatch-staging/services/rewatch-sync"
service="nuvio-rewatch-staging.service"
host="nuviorewatch.duckdns.org"
old_server_blob="b76752ea40f77c5badf83f8686eb32386863eca4"
old_sessions_blob="cd612dd12bcbe38c04c5de92f05a7f0315c9cde3"
backup=""
temp_server=""
touched=0
complete=0
rollback_success=0

finish() {
  local rc=$?
  trap - EXIT
  if [[ "$touched" == 1 && "$complete" != 1 ]]; then
    echo "ROLLBACK=ATTEMPTED"
    if sudo -n install -o root -g root -m 0644 "$backup/simkl-sessions.mjs" "$install_dir/simkl-sessions.mjs" &&
       sudo -n install -o root -g root -m 0644 "$backup/server.mjs" "$install_dir/server.mjs" &&
       sudo -n systemctl restart "$service"; then
      for attempt in $(seq 1 12); do
        local code
        code="$(curl --noproxy '*' -s --max-time 2 -o /dev/null -w '%{http_code}' http://127.0.0.1:8765/v1/rewatches || true)"
        if [[ "$code" == "401" && "$(systemctl is-active "$service")" == "active" ]]; then
          rollback_success=1
          break
        fi
        sleep 1
      done
    fi
    if [[ "$rollback_success" == 1 ]]; then
      echo "ROLLBACK=RESTORED_AND_HEALTHY"
    else
      echo "ROLLBACK=FAILED_MANUAL_REVIEW_REQUIRED"
      echo "BACKUP_PATH=$backup"
    fi
  fi
  if [[ -n "$temp_server" ]]; then sudo -n rm -f -- "$temp_server" || true; fi
  if [[ "$complete" == 1 ]]; then
    echo "RESULT=SIMKL_STABLE_DEVICE_INSTALLED_DISABLED"
  else
    echo "RESULT=SIMKL_STABLE_DEVICE_UPDATE_FAILED"
  fi
  echo "CADDY_CONFIG_CHANGED=NO"
  echo "SIMKL_HISTORY_CHANGED=NO"
  echo "PUBLIC_API_EXPOSED=NO"
  exit "$rc"
}
trap finish EXIT

if [[ "$(git -C "$root" rev-parse HEAD)" != "$pin" ]]; then
  echo "ERROR=SOURCE_COMMIT_MISMATCH"
  exit 1
fi
echo "SOURCE_HEAD=$(git -C "$root" rev-parse --short HEAD)"
for name in server.mjs simkl-sessions.mjs simkl-identity.mjs store.mjs package.json; do
  test -f "$source_dir/$name" || { echo "ERROR=SOURCE_MISSING_$name"; exit 1; }
  test -f "$install_dir/$name" || { echo "ERROR=INSTALLED_MISSING_$name"; exit 1; }
done
if [[ "$(systemctl is-active "$service")" != "active" ||
      "$(systemctl is-active caddy)" != "active" ]]; then
  echo "ERROR=STAGING_SERVICE_OR_CADDY_NOT_ACTIVE"
  exit 1
fi
if [[ "$(systemctl is-enabled "$service" 2>/dev/null || true)" != "disabled" ]]; then
  echo "ERROR=STAGING_AUTOSTART_STATE_CHANGED"
  exit 1
fi
if [[ "$(git hash-object "$install_dir/server.mjs")" != "$old_server_blob" ||
      "$(git hash-object "$install_dir/simkl-sessions.mjs")" != "$old_sessions_blob" ]]; then
  echo "ERROR=INSTALLED_CODE_UNEXPECTED_VERSION"
  exit 1
fi
for name in store.mjs package.json simkl-identity.mjs; do
  if ! cmp -s "$install_dir/$name" "$source_dir/$name"; then
    echo "ERROR=OTHER_INSTALLED_MODULE_DIFFERS"
    exit 1
  fi
done
environment="$(systemctl show "$service" -p Environment --value)"
if [[ "$environment" == *REWATCH_SIMKL_* || "$environment" == *REWATCH_PAIRING_DB_PATH* ]]; then
  echo "ERROR=SIMKL_OR_PAIRING_ALREADY_ENABLED"
  exit 1
fi
caddy_before="$(sudo -n sha256sum /etc/caddy/Caddyfile | awk '{print $1}')"
public_before="$(curl --noproxy '*' -s --max-time 8 --resolve "$host:443:127.0.0.1" -o /dev/null -w '%{http_code}' "https://$host/" || true)"
private_before="$(curl --noproxy '*' -s --max-time 5 -o /dev/null -w '%{http_code}' http://127.0.0.1:8765/v1/rewatches || true)"
bootstrap_before="$(curl --noproxy '*' -s --max-time 5 -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' -d '{}' http://127.0.0.1:8765/v1/simkl/bootstrap || true)"
if [[ "$public_before" != "503" || "$private_before" != "401" ||
      "$bootstrap_before" != "401" ]]; then
  echo "ERROR=PUBLIC_OR_PRIVATE_PRECHECK_FAILED"
  exit 1
fi
echo "PREFLIGHT=PASS"

(
  cd "$source_dir"
  node --check server.mjs
  node --check simkl-sessions.mjs
  node --test > /tmp/rewatch-simkl-stable-tests-$$.log 2>&1
) || {
  echo "ERROR=SOURCE_TESTS_FAILED"
  tail -n 16 "/tmp/rewatch-simkl-stable-tests-$$.log" || true
  rm -f -- "/tmp/rewatch-simkl-stable-tests-$$.log"
  exit 1
}
tail -n 10 "/tmp/rewatch-simkl-stable-tests-$$.log"
rm -f -- "/tmp/rewatch-simkl-stable-tests-$$.log"
echo "SOURCE_TESTS=PASS"

backup="$(sudo -n mktemp -d /tmp/nuvio-rewatch-stable-rollback.XXXXXXXX)"
sudo -n cp -p "$install_dir/server.mjs" "$backup/server.mjs"
sudo -n cp -p "$install_dir/simkl-sessions.mjs" "$backup/simkl-sessions.mjs"
echo "BACKUP_CREATED=YES"

touched=1
sudo -n install -o root -g root -m 0644 "$source_dir/simkl-sessions.mjs" "$install_dir/simkl-sessions.mjs"
temp_server="$(sudo -n mktemp "$install_dir/.server-stable.XXXXXXXX")"
sudo -n install -o root -g root -m 0644 "$source_dir/server.mjs" "$temp_server"
sudo -n mv -f -- "$temp_server" "$install_dir/server.mjs"
temp_server=""

if ! sudo -n systemctl restart "$service"; then
  echo "ERROR=STAGING_RESTART_FAILED"
  exit 1
fi
ready=0
for attempt in $(seq 1 15); do
  private="$(curl --noproxy '*' -s --max-time 2 -o /dev/null -w '%{http_code}' http://127.0.0.1:8765/v1/rewatches || true)"
  if [[ "$private" == "401" && "$(systemctl is-active "$service")" == "active" ]]; then
    ready=1
    break
  fi
  sleep 1
done
if [[ "$ready" != 1 ]]; then
  echo "ERROR=PRIVATE_API_NOT_READY"
  exit 1
fi

# Authenticated smoke uses existing private credentials without showing them.
sudo -n -u nuvio-rewatch node --input-type=module <<'NODE'
import { readFileSync } from 'node:fs';
const credentials = JSON.parse(readFileSync('/etc/nuvio-rewatch-staging/credentials.json', 'utf8'));
const token = Object.keys(credentials)[0];
if (!token) throw Error('Missing staging-only credential');
const response = await fetch('http://127.0.0.1:8765/v1/rewatches', {
  headers: { Authorization: 'Bearer ' + token }
});
const payload = await response.json();
if (response.status !== 200 || !Array.isArray(payload.items)) {
  throw Error('Authenticated private staging smoke failed');
}
console.log('AUTH_HTTP=200');
console.log('SESSION_COUNT=' + payload.items.length);
NODE

bootstrap_after="$(curl --noproxy '*' -s --max-time 5 -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' -d '{}' http://127.0.0.1:8765/v1/simkl/bootstrap || true)"
public_after="$(curl --noproxy '*' -s --max-time 8 --resolve "$host:443:127.0.0.1" -o /dev/null -w '%{http_code}' "https://$host/" || true)"
if [[ "$bootstrap_after" != "401" || "$public_after" != "503" ||
      "$(sudo -n sha256sum /etc/caddy/Caddyfile | awk '{print $1}')" != "$caddy_before" ]]; then
  echo "ERROR=PUBLIC_OR_BOOTSTRAP_GUARD_FAILED"
  exit 1
fi
if ! ss -lnt | grep -Eq '127[.]0[.]0[.]1:8765[[:space:]]'; then
  echo "ERROR=LISTENER_NOT_LOOPBACK"
  exit 1
fi
if [[ "$(git hash-object "$install_dir/server.mjs")" != "$(git hash-object "$source_dir/server.mjs")" ||
      "$(git hash-object "$install_dir/simkl-sessions.mjs")" != "$(git hash-object "$source_dir/simkl-sessions.mjs")" ]]; then
  echo "ERROR=DEPLOYED_CODE_HASH_MISMATCH"
  exit 1
fi
echo "SIMKL_BOOTSTRAP_HTTP=401"
echo "PUBLIC_HTTPS_HTTP=503"
echo "REWATCH_SERVICE=$(systemctl is-active "$service")"
echo "REWATCH_AUTOSTART=$(systemctl is-enabled "$service" 2>/dev/null || true)"
echo "DATABASE_MIGRATED=NO"
echo "CREDENTIALS_CHANGED=NO"
echo "BACKUP_PATH=$backup"
complete=1
