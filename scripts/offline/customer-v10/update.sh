#!/usr/bin/env bash
set -Eeuo pipefail
set +x
umask 077
[[ ${EUID:-$(id -u)} == 0 ]] || exec sudo bash "$0" "$@"
package_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
package_version=$(sed -n 's/^PACKAGE_VERSION=//p' "$package_dir/VERSION.txt")
[[ $package_version =~ ^v[0-9]+[a-z]?$ ]] || { echo 'UPDATE_BLOCKED stage=PACKAGE reasonCode=VERSION_INVALID'; exit 1; }
log=$(mktemp "$package_dir/update-${package_version}.XXXXXXXX.log")
echo "本次日志：$log"
set +e
bash "$package_dir/update-core.sh" "$@" 2>&1 | tee "$log"
result=${PIPESTATUS[0]}
exit "$result"
