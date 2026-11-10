#!/usr/bin/env bash
# Install optional Simkl identity modules privately on existing staging service.
# Does NOT enable Simkl bootstrap, pairing, public HTTP forwarding, or app changes.
set -euo pipefail

echo "ACTION=REWATCH_SIMKL_STAGING_GUARDED_UPDATE"

if [[ "$#" -ne 1 || ! "$1" =~ ^[[:xdigit:]]{40}$ ]]; then
  echo "RESULT=STOP_INVALID_COMMIT_PIN"
  exit 1
fi
pin="$1"
source_dir="$(cd -- "$(dirname -- "$0")/.." && pwd)"
root="$(cd -- "$source_dir/../.." && pwd)"
installed_dir="/opt/nuvio-rewatch-staging/services/rewatch-sync"
unit="nuvio-rewatch-staging.service"
host="nuviorewatch.duckdns.org"
expected_installed_blob="3283e6d23c06b25f670e698a92cae53f08d667d6"
backup=""
temp_server=""
swapped=0
complete=0
rollback_success=0
installed_modules=()

finish() {
  local code=$?
  trap - EXIT
  if [[ "$swapped" == 1 && "$complete" != 1 ]]; then
    echo "ROLLBACK=ATTEMPTED"
    if sudo -n install -o root -g root -m 0644 "$backup/server.mjs" "$installed_dir/server.mjs" &&
       sudo -n systemctl restart "$unit"; then
      for attempt in $(seq 1 12); do
        code401="$(curl --noproxy '*' -s --max-time 2 -o /dev/null -w '%{http_code}' http://127.0.0.1:8765/v1/rewatches || true)"
        if [[ "$code401" == "401" && "$(systemctl is-active "$unit")" == "active" ]]; then
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
    fi
  fi

  if [[ "$complete" != 1 && ( "$swapped" == 0 || "$rollback_success" == 1 ) ]]; then
    # Only remove files THIS invocation installed. Never touch existing files on preflight failure.
    for name in "${installed_modules[@]}"; do
      sudo -n rm -f -- "$installed_dir/$name" || true
    done
  fi
  if [[ -n "$temp_server" ]]; then sudo -n rm -f -- "$temp_server" || true; fi
  if [[ "$complete" != 1 ]]; then echo "RESULT=SIMKL_STAGING_UPDATE_FAILED"; fi
  echo "CADDY_CONFIG_CHANGED=NO"
  echo "SIMKL_HISTORY_CHANGED=NO"
  echo "PUBLIC_REWATCH_API_EXPOSED=NO"
  exit "$code"
}
trap finish EXIT

if [[ "$(git -C "$root" rev-parse HEAD)" != "$pin" ]]; then
  echo "ERROR=SOURCE_HEAD_NOT_PINNED"
  exit 1
fi
echo "SOURCE_HEAD=$(git -C "$root" rev-parse --short HEAD)"
for name in server.mjs store.mjs package.json pairing.mjs simkl-identity.mjs simkl-sessions.mjs; do
  test -f "$source_dir/$name" || { echo "ERROR=MISSING_SOURCE"; exit 1; }
done
if [[ "$(systemctl is-active "$unit")" != "active" ||
      "$(systemctl is-active caddy)" != "active" ]]; then
  echo "ERROR=STAGING_OR_CADDY_NOT_ACTIVE"
  exit 1
fi
if [[ "$(systemctl is-enabled "$unit" 2>/dev/null || true)" != "disabled" ]]; then
  echo "ERROR=STAGING_AUTOSTART_STATE_CHANGED"
  exit 1
fi
if [[ "$(git hash-object "$installed_dir/server.mjs")" != "$expected_installed_blob" ]]; then
  echo "ERROR=INSTALLED_SERVER_UNEXPECTED_VERSION"
  exit 1
fi
if ! cmp -s "$installed_dir/store.mjs" "$source_dir/store.mjs" ||
   ! cmp -s "$installed_dir/package.json" "$source_dir/package.json"; then
  echo "ERROR=STORE_OR_PACKAGE_DIFFERS"
  exit 1
fi
for name in pairing.mjs simkl-identity.mjs simkl-sessions.mjs; do
  if [[ -e "$installed_dir/$name" || -L "$installed_dir/$name" ]]; then
    echo "ERROR=OPTIONAL_MODULE_ALREADY_PRESENT"
    exit 1
  fi
done
env_content="$(systemctl show "$unit" -p Environment --value)"
if [[ "$env_content" == *REWATCH_SIMKL_* || "$env_content" == *REWATCH_PAIRING_DB_PATH* ]]; then
  echo "ERROR=OPTIONAL_IDENTITY_ALREADY_CONFIGURED"
  exit 1
fi
caddy_hash_before="$(sudo -n sha256sum /etc/caddy/Caddyfile | awk '{print $1}')"
public_code="$(curl --noproxy '*' -sS --max-time 8 --resolve "$host:443:127.0.0.1" -o /dev/null -w '%{http_code}' "https://$host/" 2>/dev/null || true)"
if [[ "$public_code" != "503" ]]; then
  echo "ERROR=PUBLIC_503_GUARD_MISSING"
  exit 1
fi
private_code="$(curl --noproxy '*' -sS --max-time 5 -o /dev/null -w '%{http_code}' http://127.0.0.1:8765/v1/rewatches 2>/dev/null || true)"
if [[ "$private_code" != "401" ]]; then
  echo "ERROR=PRIVATE_AUTH_GUARD_MISSING"
  exit 1
fi
echo "PRECHECK=PASS"

(
  cd "$source_dir"
  node --check server.mjs
  node --check pairing.mjs
  node --check simkl-identity.mjs
  node --check simkl-sessions.mjs
  npm test
) || { echo "ERROR=SOURCE_TEST_FAILURE"; exit 1; }
echo "SOURCE_TESTS=PASS"

backup="$(sudo -n mktemp -d /tmp/nuvio-rewatch-simkl-rollback.XXXXXXXX)"
sudo -n cp -p "$installed_dir/server.mjs" "$backup/server.mjs"
echo "BACKUP_CREATED=YES"
for name in pairing.mjs simkl-identity.mjs simkl-sessions.mjs; do
  sudo -n install -o root -g root -m 0644 "$source_dir/$name" "$installed_dir/$name"
  installed_modules+=("$name")
done

# Replace the entrypoint atomically after all import dependencies are staged.
temp_server="$(sudo -n mktemp "$installed_dir/.server-simkl.XXXXXXXX")"
sudo -n install -o root -g root -m 0644 "$source_dir/server.mjs" "$temp_server"
sudo -n mv -f -- "$temp_server" "$installed_dir/server.mjs"
temp_server=""
swapped=1
if ! sudo -n systemctl restart "$unit"; then
  echo "ERROR=STAGING_RESTART_FAILED"
  exit 1
fi

ready=0
for attempt in $(seq 1 15); do
  private_code="$(curl --noproxy '*' -s --max-time 2 -o /dev/null -w '%{http_code}' http://127.0.0.1:8765/v1/rewatches || true)"
  if [[ "$private_code" == "401" && "$(systemctl is-active "$unit")" == "active" ]]; then
    ready=1
    break
  fi
  sleep 1
done
if [[ "$ready" != 1 ]]; then
  echo "ERROR=PRIVATE_API_NOT_READY"
  exit 1
fi

# Verify original staging credentials still work, without printing tokens.
sudo -n -u nuvio-rewatch node --input-type=module <<'NODE'
import { readFileSync } from 'node:fs';
const config = JSON.parse(readFileSync('/etc/nuvio-rewatch-staging/credentials.json', 'utf8'));
const token = Object.keys(config)[0];
if (!token) throw Error('Missing existing staging credential');
const response = await fetch('http://127.0.0.1:8765/v1/rewatches', {
  headers: { Authorization: 'Bearer ' + token }
});
const body = await response.json();
if (response.status !== 200 || !Array.isArray(body.items)) {
  throw Error('Staging authenticated request failed');
}
console.log('AUTH_HTTP=200');
console.log('SESSION_COUNT=' + body.items.length);
NODE

bootstrap_code="$(curl --noproxy '*' -sS --max-time 5 -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' -d '{}' http://127.0.0.1:8765/v1/simkl/bootstrap 2>/dev/null || true)"
if [[ "$bootstrap_code" != "401" ]]; then
  echo "ERROR=BOOTSTRAP_NOT_DISABLED"
  exit 1
fi
public_after="$(curl --noproxy '*' -sS --max-time 8 --resolve "$host:443:127.0.0.1" -o /dev/null -w '%{http_code}' "https://$host/" 2>/dev/null || true)"
if [[ "$public_after" != "503" ||
      "$(sudo -n sha256sum /etc/caddy/Caddyfile | awk '{print $1}')" != "$caddy_hash_before" ]]; then
  echo "ERROR=PUBLIC_503_OR_CADDYFILE_CHANGED"
  exit 1
fi
if ! ss -lnt | grep -Eq '127[.]0[.]0[.]1:8765[[:space:]]'; then
  echo "ERROR=PRIVATE_LISTENER_CHANGED"
  exit 1
fi
echo "SIMKL_BOOTSTRAP=DISABLED"
echo "SIMKL_BOOTSTRAP_HTTP=401"
echo "PUBLIC_HTTPS_HTTP=503"
echo "PRIVATE_API_UNAUTH_HTTP=401"
echo "SERVICE_ACTIVE=$(systemctl is-active "$unit")"
echo "SERVICE_ENABLED=$(systemctl is-enabled "$unit" 2>/dev/null || true)"
echo "CREDENTIALS_CHANGED=NO"
echo "DATABASE_MIGRATED=NO"
echo "BACKUP_PATH=$backup/server.mjs"
complete=1
echo "RESULT=SIMKL_MODULES_INSTALLED_DISABLED"
