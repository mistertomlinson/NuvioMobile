#!/usr/bin/env bash
# Read-only, redacted structural diagnostic for the Caddy route preview guard.
set -euo pipefail
if [[ "$#" -ne 1 || ! "$1" =~ ^[[:xdigit:]]{40}$ ]]; then
  echo "RESULT=STOP_INVALID_PIN"
  exit 1
fi
script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
repo_root="$(CDPATH= cd -- "$script_dir/../../.." && pwd)"
if [[ "$(git -C "$repo_root" rev-parse HEAD)" != "$1" ]]; then
  echo "RESULT=STOP_SOURCE_COMMIT_MISMATCH"
  exit 1
fi
echo "ACTION=REWATCH_CADDY_ROUTE_DIFF_READONLY"
echo "SOURCE_HEAD=$(git -C "$repo_root" rev-parse --short HEAD)"
temp="$(mktemp -d /tmp/nuvio-caddy-diff.XXXXXXXX)"
trap 'rm -rf -- "$temp"' EXIT
sudo -n cat /etc/caddy/Caddyfile > "$temp/existing"
chmod 600 "$temp/existing"
cp "$temp/existing" "$temp/candidate"
cat >> "$temp/candidate" <<'CADDY'

# Proposed fail-closed placeholder, NOT installed
nuviorewatch.duckdns.org {
    respond "Rewatch sync is not available for public access." 503
}
CADDY
if ! sudo -n caddy adapt --config "$temp/existing" --adapter caddyfile > "$temp/before.json" 2>/dev/null; then
  echo "RESULT=STOP_EXISTING_ADAPT_FAILED"
  exit 1
fi
if ! sudo -n caddy adapt --config "$temp/candidate" --adapter caddyfile > "$temp/after.json" 2>/dev/null; then
  echo "RESULT=STOP_CANDIDATE_ADAPT_FAILED"
  exit 1
fi
python3 - "$temp/before.json" "$temp/after.json" <<'PY'
import json, sys

with open(sys.argv[1]) as handle:
    before = json.load(handle)
with open(sys.argv[2]) as handle:
    after = json.load(handle)

def route_map(configuration):
    routes = {}
    servers = configuration.get("apps", {}).get("http", {}).get("servers", {})
    for server in servers.values():
        for route in server.get("routes", []):
            for match in route.get("match", []):
                for host in match.get("host", []):
                    routes.setdefault(host, []).append(route)
    return routes

def changed_paths(left, right, prefix="$"):
    if left == right:
        return []
    if type(left) is not type(right):
        return [prefix + ":type_changed"]
    if isinstance(left, dict):
        differences = []
        for key in sorted(left.keys() | right.keys()):
            p = prefix + "." + key
            if key not in left or key not in right:
                differences.append(p + ":field_presence")
            else:
                differences.extend(changed_paths(left[key], right[key], p))
        return differences
    if isinstance(left, list):
        differences = []
        if len(left) != len(right):
            differences.append(prefix + ":list_length")
        for i in range(min(len(left), len(right))):
            differences.extend(changed_paths(left[i], right[i], prefix + "[" + str(i) + "]"))
        return differences
    return [prefix + ":value_changed"]

old = route_map(before)
new = route_map(after)
target = "nuviorewatch.duckdns.org"
print("HOST_INVENTORY_ADDITION_ONLY=" + str(set(new) == set(old) | {target}).upper())
for host in sorted(old):
    left = old[host]
    right = new.get(host, [])
    differences = changed_paths(left, right)
    print("HOST=" + host)
    print("ROUTES_BEFORE=" + str(len(left)))
    print("ROUTES_AFTER=" + str(len(right)))
    print("ROUTE_STRUCTURES_EQUAL=" + str(not differences).upper())
    print("DIFFERENCE_PATH_COUNT=" + str(len(differences)))
    for path in differences[:18]:
        # Paths and change kinds only, never config values or credentials.
        print("DIFF_PATH=" + path)
    if len(differences) > 18:
        print("DIFF_PATHS_TRUNCATED=YES")
print("NEW_SITE_ROUTE_COUNT=" + str(len(new.get(target, []))))
print("CONFIG_CONTENT_EMITTED=NO")
print("RESULT=CADDY_ROUTE_DIAGNOSTIC_COMPLETE")
PY
echo "CADDY_CONFIG_CHANGED=NO"
echo "SERVICE_RELOADED=NO"
