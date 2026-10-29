#!/usr/bin/env bash
set -euo pipefail

repo_root=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
sdk_dir=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-"${HOME}/Library/Android/sdk"}}
adb_bin=${ADB:-${sdk_dir}/platform-tools/adb}
apk=${1:-"${repo_root}/app/build/outputs/apk/release/app-release.apk"}

if [[ ! -x "${adb_bin}" ]]; then
    echo "adb not found at ${adb_bin}; set ADB or ANDROID_HOME." >&2
    exit 1
fi
if [[ ! -f "${apk}" ]]; then
    echo "APK not found at ${apk}; run tools/build_local.sh first." >&2
    exit 1
fi

# The APK's package: com.droiddeck.launcher, or a side-by-side build such as
# com.droiddeck.launcher.mali (DROIDDECK_APP_ID_SUFFIX=.mali). DROIDDECK_PACKAGE overrides it.
package=${DROIDDECK_PACKAGE:-}
if [[ -z "${package}" ]]; then
    aapt2_bin=$(find "${sdk_dir}/build-tools" -mindepth 2 -maxdepth 2 -name aapt2 -print 2>/dev/null | sort -V | tail -1)
    [[ -z "${aapt2_bin}" ]] || package=$("${aapt2_bin}" dump packagename "${apk}" 2>/dev/null | tr -d '\r' || true)
fi
package=${package:-com.droiddeck.launcher}

serial=$(python3 "${repo_root}/tools/adb_device.py" --adb "${adb_bin}")

"${adb_bin}" -s "${serial}" install -r --no-incremental "${apk}"
installed_path=$("${adb_bin}" -s "${serial}" shell pm path "${package}" | tr -d '\r')
printf 'Installed on %s: %s\n' "${serial}" "${installed_path}"
