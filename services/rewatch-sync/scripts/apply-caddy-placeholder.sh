#!/usr/bin/env bash
# Append one fail-closed site to Caddy. Verify preflight, reload, and roll back on failure.
# The rewatch API is NOT proxied. Existing sites are not intentionally modified.
set -euo pipefail

echo "ACTION=REWATCH_CADDY_PLACEHOLDER_GUARDED_INSTALL"

if [[ "$#" -ne 1 || ! "$1" =~ ^[[:xdigit:]]{40}$ ]]; then
  echo "RESULT=STOP_INVALID_PIN"
  exit 1
fi
pin="$1"
script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
repo_root="$(CDPATH= cd -- "$script_dir/../../.." && pwd)"
if [[ "$(git -C "$repo_root" rev-parse HEAD)" != "$pin" ]]; then
  echo "RESULT=STOP_SOURCE_COMMIT_MISMATCH"
  exit 1
fi
echo "SOURCE_HEAD=$(git -C "$repo_root" rev-parse --short HEAD)"

caddy_path="/etc/caddy/Caddyfile"
target_host="nuviorewatch.duckdns.org"
service="nuvio-rewatch-staging.service"
backup_dir=""
work_dir=""
stage_path=""
swapped=0
complete=0

cleanup() {
  local exit_code=$?
  trap - EXIT
  if [[ "$swapped" == 1 && "$complete" != 1 ]]; then
    echo "ROLLBACK=ATTEMPTED"
    local restore_path
    restore_path="$(sudo -n mktemp /etc/caddy/.rewatch-restore.XXXXXXXX)" || restore_path=""
    if [[ -n "$restore_path" ]] &&
       sudo -n cp -p "$backup_dir/Caddyfile" "$restore_path" &&
       sudo -n mv -f -- "$restore_path" "$caddy_path" &&
       sudo -n systemctl reload caddy &&
       [[ "$(systemctl is-active caddy)" == "active" ]]; then
      echo "ROLLBACK=RESTORED_AND_RELOADED"
    else
      echo "ROLLBACK=FAILED_MANUAL_REVIEW_REQUIRED"
      echo "BACKUP_PATH=$backup_dir/Caddyfile"
    fi
  fi
  if [[ -n "$stage_path" ]]; then sudo -n rm -f -- "$stage_path" || true; fi
  if [[ -n "$work_dir" ]]; then rm -rf -- "$work_dir"; fi
  if [[ "$complete" != 1 ]]; then echo "RESULT=CADDY_PLACEHOLDER_INSTALL_FAILED"; fi
  echo "REWATCH_API_PUBLICLY_PROXIED=NO"
  exit "$exit_code"
}
trap cleanup EXIT

for program in sudo caddy git curl getent sha256sum mktemp; do
  if ! command -v "$program" >/dev/null; then
    echo "ERROR=MISSING_DEPENDENCY_$program"
    exit 1
  fi
done
if [[ "$(systemctl is-active caddy)" != "active" ||
      "$(systemctl is-active "$service")" != "active" ]]; then
  echo "ERROR=SERVICE_NOT_ACTIVE"
  exit 1
fi
if sudo -n test -L "$caddy_path" || ! sudo -n test -f "$caddy_path"; then
  echo "ERROR=CADDYFILE_NOT_REGULAR"
  exit 1
fi
if sudo -n grep -Fq "$target_host" "$caddy_path"; then
  echo "ERROR=REWATCH_HOST_ALREADY_IN_CADDYFILE"
  exit 1
fi

new_ip="$(getent ahostsv4 "$target_host" | awk 'NR==1 {print $1}')"
old_ip="$(getent ahostsv4 nuviotraktproxy.duckdns.org | awk 'NR==1 {print $1}')"
if [[ -z "$new_ip" || "$new_ip" != "$old_ip" ]]; then
  echo "ERROR=DNS_TARGET_MISMATCH"
  exit 1
fi
echo "DNS_MATCH=YES"

initial_digest="$(sudo -n sha256sum "$caddy_path" | awk '{print $1}')"
# Strict hostname/handler comparison, from identical adapter config paths.
if ! bash "$script_dir/preview-caddy-placeholder.sh" "$pin"; then
  echo "ERROR=STRICT_PREVIEW_FAILED"
  exit 1
fi
if [[ "$(sudo -n sha256sum "$caddy_path" | awk '{print $1}')" != "$initial_digest" ]]; then
  echo "ERROR=CADDYFILE_CHANGED_DURING_PREVIEW"
  exit 1
fi
echo "PREFLIGHT=PASS"

# Save original with ownership/permissions for an emergency rollback.
backup_dir="$(sudo -n mktemp -d /etc/caddy/.rewatch-backup.XXXXXXXX)"
sudo -n chmod 700 "$backup_dir"
sudo -n cp -p "$caddy_path" "$backup_dir/Caddyfile"
if [[ "$(sudo -n sha256sum "$backup_dir/Caddyfile" | awk '{print $1}')" != "$initial_digest" ]]; then
  echo "ERROR=BACKUP_MISMATCH"
  exit 1
fi

work_dir="$(mktemp -d /tmp/rewatch-caddy-apply.XXXXXXXX)"
chmod 700 "$work_dir"
sudo -n cat "$caddy_path" > "$work_dir/Caddyfile"
chmod 600 "$work_dir/Caddyfile"
cat >> "$work_dir/Caddyfile" <<'CADDY_SITE'

# REWATCH-STAGING-PLACEHOLDER-START: NO upstream proxy
nuviorewatch.duckdns.org {
    respond "Rewatch sync is not available for public access." 503
}
# REWATCH-STAGING-PLACEHOLDER-END
CADDY_SITE
if ! sudo -n caddy validate --config "$work_dir/Caddyfile" --adapter caddyfile >/dev/null 2>&1; then
  echo "ERROR=CADDY_CANDIDATE_INVALID"
  exit 1
fi
if [[ "$(sudo -n sha256sum "$caddy_path" | awk '{print $1}')" != "$initial_digest" ]]; then
  echo "ERROR=CADDYFILE_CHANGED_BEFORE_SWAP"
  exit 1
fi
stage_path="$(sudo -n mktemp /etc/caddy/.rewatch-stage.XXXXXXXX)"
owner="$(sudo -n stat -c '%u' "$caddy_path")"
group="$(sudo -n stat -c '%g' "$caddy_path")"
mode="$(sudo -n stat -c '%a' "$caddy_path")"
sudo -n install -o "$owner" -g "$group" -m "$mode" "$work_dir/Caddyfile" "$stage_path"
sudo -n mv -f -- "$stage_path" "$caddy_path"
stage_path=""
swapped=1

if ! sudo -n systemctl reload caddy; then
  echo "ERROR=CADDY_RELOAD_FAILED"
  exit 1
fi
if [[ "$(systemctl is-active caddy)" != "active" ||
      "$(systemctl is-active "$service")" != "active" ]]; then
  echo "ERROR=SERVICE_UNHEALTHY_AFTER_RELOAD"
  exit 1
fi
echo "CADDY_RELOAD=SUCCESS"

# Confirm the new HTTPS route is a 503 only. Certificate acquisition can take
# time; use -k to test the route, then separately report trust verification.
route_code=""
for attempt in $(seq 1 15); do
  route_code="$(curl -k -sS --connect-timeout 2 --max-time 5 -o /dev/null -w '%{http_code}' \
    --resolve "$target_host:443:127.0.0.1" "https://$target_host/" 2>/dev/null || true)"
  if [[ "$route_code" == "503" ]]; then break; fi
  sleep 2
done
if [[ "$route_code" != "503" ]]; then
  echo "ERROR=HTTPS_503_ROUTE_NOT_READY"
  exit 1
fi
echo "NEW_HOST_HTTPS_ROUTE=503"

# Sanity-check that the loopback-only origin is still private and unauthorized.
listener="$(ss -lnt | grep ':8765[[:space:]]' || true)"
if [[ "$listener" != *'127.0.0.1:8765'* ]] ||
   [[ "$listener" == *'0.0.0.0:8765'* ]] ||
   [[ "$listener" == *'[::]:8765'* ]]; then
  echo "ERROR=PRIVATE_ORIGIN_LISTENER_CHANGED"
  exit 1
fi
origin_code="$(curl -sS --max-time 5 -o /dev/null -w '%{http_code}' \
  http://127.0.0.1:8765/v1/rewatches 2>/dev/null || true)"
if [[ "$origin_code" != "401" ]]; then
  echo "ERROR=PRIVATE_ORIGIN_AUTH_GUARD_FAILED"
  exit 1
fi
echo "PRIVATE_ORIGIN_AUTH_HTTP=401"
if curl -sS --connect-timeout 2 --max-time 8 -o /dev/null \
  --resolve "$target_host:443:127.0.0.1" "https://$target_host/" 2>/dev/null; then
  echo "HTTPS_CERTIFICATE_TRUST=PASS"
else
  # Do not claim verified public TLS when a new certificate is pending.
  # This is not sufficient on its own to undo an otherwise fail-closed site.
  echo "HTTPS_CERTIFICATE_TRUST=NOT_YET_VERIFIED"
fi
echo "CADDY_SERVICE=$(systemctl is-active caddy)"
echo "REWATCH_STAGING_SERVICE=$(systemctl is-active "$service")"
echo "BACKUP_PATH=$backup_dir/Caddyfile"
echo "EXISTING_SITE_ROUTES_PRESERVED_BY_PREFLIGHT=YES"
echo "REWATCH_API_PUBLICLY_PROXIED=NO"
echo "RESULT=CADDY_503_PLACEHOLDER_INSTALLED"
complete=1
