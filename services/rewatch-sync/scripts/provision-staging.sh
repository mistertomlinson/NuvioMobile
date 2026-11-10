#!/usr/bin/env bash
# Prepare a dedicated, loopback-only replay sync staging instance.
# Deliberately NEVER starts/enables the service or changes existing addons.
set -euo pipefail

source_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
target_dir="/opt/nuvio-rewatch-staging/services/rewatch-sync"
credentials_dir="/etc/nuvio-rewatch-staging"
state_dir="/var/lib/nuvio-rewatch-staging"
unit_file="/etc/systemd/system/nuvio-rewatch-staging.service"
service_user="nuvio-rewatch"

echo "ACTION=REWATCH_VPS_PROVISION_STAGING"
echo "SOURCE=$source_dir"

for utility in sudo node npm openssl systemctl git; do
  command -v "$utility" >/dev/null 2>&1 || {
    echo "RESULT=STOP_MISSING_$utility"
    exit 1
  }
done
test -x /usr/sbin/nologin || {
  echo "RESULT=STOP_NO_NLOGIN_SHELL"
  exit 1
}

# Do not repurpose an existing account, directory, or service.
if id "$service_user" >/dev/null 2>&1; then
  echo "RESULT=STOP_STAGING_ACCOUNT_ALREADY_EXISTS"
  exit 1
fi
for path in /opt/nuvio-rewatch-staging "$credentials_dir" "$state_dir" "$unit_file"; do
  if sudo -n test -e "$path" || sudo -n test -L "$path"; then
    echo "RESULT=STOP_STAGING_PATH_EXISTS"
    echo "EXISTING_PATH=$path"
    exit 1
  fi
done
if systemctl is-active --quiet nuvio-rewatch-staging.service; then
  echo "RESULT=STOP_STAGING_ALREADY_ACTIVE"
  exit 1
fi
for filename in package.json store.mjs server.mjs rewatch-staging.service.example; do
  test -f "$source_dir/$filename" || {
    echo "RESULT=STOP_MISSING_SOURCE_$filename"
    exit 1
  }
done

echo "SOURCE_HEAD=$(git -C "$source_dir" rev-parse --short HEAD)"
echo "TESTING_SOURCE=YES"
(
  cd "$source_dir"
  node --check server.mjs
  node --check store.mjs
  npm test
)

echo "TESTS_PASSED=YES"
sudo -n useradd --system --user-group --no-create-home \
  --shell /usr/sbin/nologin "$service_user"
sudo -n install -d -o root -g root -m 0755 "$target_dir"
for filename in package.json store.mjs server.mjs; do
  sudo -n install -o root -g root -m 0644 \
    "$source_dir/$filename" "$target_dir/$filename"
done
sudo -n install -d -o "$service_user" -g "$service_user" -m 0700 \
  "$credentials_dir" "$state_dir"
credential_file="$credentials_dir/credentials.json"
sudo -n install -o "$service_user" -g "$service_user" -m 0600 \
  /dev/null "$credential_file"

# These are random, local TEST credentials only, not app enrollment tokens.
# Never echo them into the shortcut output, log, GitHub, or terminal.
ios_token="$(openssl rand -hex 32)"
android_token="$(openssl rand -hex 32)"
printf '{"%s":{"principal":"staging-only-person","deviceId":"staging-ios"},"%s":{"principal":"staging-only-person","deviceId":"staging-android"}}\n' \
  "$ios_token" "$android_token" | sudo -n tee "$credential_file" >/dev/null
unset ios_token android_token

sudo -n install -o root -g root -m 0644 \
  "$source_dir/rewatch-staging.service.example" "$unit_file"
sudo -n systemctl daemon-reload

test "$(sudo -n stat -c %a "$credential_file")" = "600"
sudo -n test -f "$target_dir/server.mjs"
if systemctl is-active --quiet nuvio-rewatch-staging.service; then
  echo "RESULT=ERROR_UNEXPECTED_SERVICE_RUNNING"
  exit 1
fi

echo "SERVICE_INSTALLED=YES"
echo "SERVICE_STARTED=NO"
echo "PUBLIC_PORT_OPENED=NO"
echo "SIMKL_HISTORY_CHANGED=NO"
echo "RESULT=STAGING_PROVISIONED_NOT_STARTED"
