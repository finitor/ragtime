#!/bin/sh
# Run against a separately started ragtime_fake_air, never a real modem.
#
#   tools/android-smoke.sh SERIAL [PORT_OFFSET]
#
# PORT_OFFSET moves the bench's ports on the device (10000: 18100, 18300...),
# for a phone whose own Data2G TNC holds 8100/8300. The app's saved Radio &
# host settings are put back afterwards.
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
serial=${1:-emulator-5554}
offset=${2:-0}
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [ -z "$sdk" ]; then echo 'Set ANDROID_HOME to the Android SDK.' >&2; exit 1; fi
adb="$sdk/platform-tools/adb"
package=org.ragtime.radio
cd "$root/android"
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
# The APKs are named by version and build (app/build.gradle.kts): take the
# ones this build's metadata names, not a stale one beside them.
apk() { sed -n 's/.*"outputFile": *"\([^"]*\)".*/\1/p' "$1/output-metadata.json"; }
app=app/build/outputs/apk/debug
tests=app/build/outputs/apk/androidTest/debug
mkdir -p "$root/build-android-results"
saved="$root/build-android-results/radio-settings-$serial.xml"
rm -f "$saved"
"$adb" -s "$serial" shell am force-stop "$package" || true
"$adb" -s "$serial" exec-out run-as "$package" cat shared_prefs/radio.xml > "$saved" 2>/dev/null || rm -f "$saved"
grep -q '<map' "$saved" 2>/dev/null || rm -f "$saved"
"$adb" -s "$serial" install -r "$app/$(apk "$app")"
"$adb" -s "$serial" install -r "$tests/$(apk "$tests")"
# Connect asks for this on Android 13 and later; its system dialog would
# cover the screen the UI tests drive.
"$adb" -s "$serial" shell pm grant "$package" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
ports='8100 8300 8301 8110 8310 8311'
cleanup() {
    for port in $ports; do "$adb" -s "$serial" reverse --remove "tcp:$((port + offset))" >/dev/null 2>&1 || true; done
    "$adb" -s "$serial" shell am force-stop "$package" >/dev/null 2>&1 || true
    if [ -f "$saved" ]; then
        "$adb" -s "$serial" shell run-as "$package" sh -c "'cat > shared_prefs/radio.xml'" < "$saved" || true
    else
        "$adb" -s "$serial" shell run-as "$package" rm -f shared_prefs/radio.xml >/dev/null 2>&1 || true
    fi
}
trap cleanup EXIT HUP INT TERM
for port in $ports; do "$adb" -s "$serial" reverse "tcp:$((port + offset))" "tcp:$port"; done
log="$root/build-android-results/instrumentation-$serial.log"
"$adb" -s "$serial" shell am instrument -w -r -e portOffset "$offset" \
    "$package.test/androidx.test.runner.AndroidJUnitRunner" > "$log" 2>&1 < /dev/null
cat "$log"
grep -q '^OK (' "$log"
