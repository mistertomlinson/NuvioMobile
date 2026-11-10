#!/usr/bin/env bash
# Read-only preview of a NEW, fail-closed HTTPS hostname. Never alters /etc/caddy.
set -euo pipefail

if [[ "$#" -ne 1 || ! "$1" =~ ^[[:xdigit:]]{40}$ ]]; then
  echo "RESULT=STOP_INVALID_PIN"
  exit 1
fi
expected_head="$1"
script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
repo_root="$(CDPATH= cd -- "$script_dir/../../.." && pwd)"
actual_head="$(git -C "$repo_root" rev-parse HEAD)"
if [[ "$actual_head" != "$expected_head" ]]; then
  echo "RESULT=STOP_SOURCE_COMMIT_MISMATCH"
  exit 1
fi

echo "ACTION=REWATCH_CADDY_PLACEHOLDER_PREVIEW"
echo "SOURCE_HEAD=$(git -C "$repo_root" rev-parse --short HEAD)"
for cmd in caddy python3 mktemp sudo; do
  command -v "$cmd" >/dev/null || { echo "RESULT=STOP_TOOL_MISSING"; exit 1; }
done
if [[ "$(systemctl is-active caddy)" != "active" ]]; then
  echo "RESULT=STOP_CADDY_NOT_ACTIVE"
  exit 1
fi
if [[ "$(systemctl is-active nuvio-rewatch-staging.service)" != "active" ]]; then
  echo "RESULT=STOP_REWATCH_STAGING_NOT_ACTIVE"
  exit 1
fi

tmp="$(mktemp -d /tmp/nuvio-rewatch-caddy-preview.XXXXXXXX)"
trap 'rm -rf -- "$tmp"' EXIT
# Adapt BOTH snapshots from one identical temporary path.
# Caddy file_server adds the config path to its automatic hide[] list.
# Using different names would incorrectly flag that safe generated value.
sudo -n cat /etc/caddy/Caddyfile > "$tmp/Caddyfile"
chmod 600 "$tmp/Caddyfile"
if ! sudo -n caddy adapt --config "$tmp/Caddyfile" --adapter caddyfile > "$tmp/before.json" 2>/dev/null; then
  echo "RESULT=STOP_EXISTING_ADAPT_FAILED"
  exit 1
fi
cat >> "$tmp/Caddyfile" <<'CADDY_SITE'

# REWATCH-STAGING-PLACEHOLDER-START: deliberately NO reverse_proxy
nuviorewatch.duckdns.org {
    respond "Rewatch sync is not available for public access." 503
}
# REWATCH-STAGING-PLACEHOLDER-END
CADDY_SITE

if ! sudo -n caddy adapt --config "$tmp/Caddyfile" --adapter caddyfile > "$tmp/after.json" 2>/dev/null; then
  echo "RESULT=STOP_CANDIDATE_ADAPT_FAILED"
  exit 1
fi
if ! python3 - "$tmp/before.json" "$tmp/after.json" <<'PY'
import json, sys
before, after = [json.load(open(p)) for p in sys.argv[1:]]
def routes(conf):
    result = []
    for server in conf.get("apps", {}).get("http", {}).get("servers", {}).values():
        result.extend(server.get("routes", []))
    return result

def hosts(route):
    return {h for matcher in route.get("match", []) for h in matcher.get("host", [])}

old_routes, new_routes = routes(before), routes(after)
old_hosts = {h for route in old_routes for h in hosts(route)}
new_hosts = {h for route in new_routes for h in hosts(route)}
target = "nuviorewatch.duckdns.org"
required = {
    "nuviotraktproxy.duckdns.org",
    "watchlyselfhost.duckdns.org",
    "watchlyselfhost2.duckdns.org"
}
if target in old_hosts or not required.issubset(old_hosts):
    sys.exit("Existing Caddy host inventory does not match preflight")
if new_hosts != old_hosts | {target}:
    sys.exit("Unexpected hostname changed in candidate")
old_by_host = {
    h: sorted(json.dumps(r, sort_keys=True) for r in old_routes if h in hosts(r))
    for h in old_hosts
}
new_by_host = {
    h: sorted(json.dumps(r, sort_keys=True) for r in new_routes if h in hosts(r))
    for h in old_hosts
}
if old_by_host != new_by_host:
    sys.exit("Existing hostname routing changed in candidate")
target_routes = [r for r in new_routes if target in hosts(r)]
if len(target_routes) != 1:
    sys.exit("New hostname must have exactly one route")

def handlers(obj):
    if isinstance(obj, dict):
        if isinstance(obj.get("handler"), str):
            yield obj
        for v in obj.values():
            yield from handlers(v)
    elif isinstance(obj, list):
        for v in obj:
            yield from handlers(v)

all_handlers = list(handlers(target_routes[0]))
if any(h.get("handler") == "reverse_proxy" for h in all_handlers):
    sys.exit("Refusing public upstream proxy")
if not any(h.get("handler") == "static_response" and str(h.get("status_code")) == "503"
           for h in all_handlers):
    sys.exit("New site does not return static 503")

print("EXISTING_HOSTS_PRESERVED=YES")
print("NEW_HOST_ONLY=" + target)
print("PUBLIC_API_UPSTREAM=NONE")
print("NEW_HOST_STATUS=503")
PY
then
  echo "RESULT=STOP_ROUTE_GUARD_FAILED"
  exit 1
fi
if ! sudo -n caddy validate --config "$tmp/Caddyfile" --adapter caddyfile > /dev/null 2>&1; then
  echo "RESULT=STOP_CANDIDATE_VALIDATION_FAILED"
  exit 1
fi
echo "CADDY_VALIDATE=PASS"
echo "CADDY_SERVICE=$(systemctl is-active caddy)"
echo "REWATCH_STAGING=$(systemctl is-active nuvio-rewatch-staging.service)"
echo "CADDY_CONFIG_CHANGED=NO"
echo "SERVICE_RELOADED=NO"
echo "RESULT=CADDY_503_PLACEHOLDER_PREVIEW_PASSED"
