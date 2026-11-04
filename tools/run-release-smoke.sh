#!/usr/bin/env bash
set -euo pipefail
source tools/stop-ci-emulator.sh
[[ "${GITHUB_ACTIONS:-}" == true && "${RUNNER_OS:-}" == Linux ]] || { echo "Release smoke runs only on the disposable Linux Actions runner"; exit 1; }
for variable in ANDROID_KEYSTORE_PATH ANDROID_KEYSTORE_BASE64 ANDROID_KEY_ALIAS ANDROID_KEYSTORE_PASSWORD ANDROID_KEY_PASSWORD; do
  if [[ -v "$variable" ]]; then echo "Production signing configuration is forbidden in release smoke"; exit 1; fi
done
application_id=org.screencopy.receiver
harness_id=dev.scrcpy.releasecheck.receiver
classes=dev.scrcpy.releasecheck.NativeOpusCheck,dev.scrcpy.releasecheck.TargetResourcesCheck
expected=3
app=app/build/outputs/apk/release/app-release.apk
mapfile -t harness_apks < <(find release-check/build/outputs/apk/release -maxdepth 1 -type f -name '*.apk')
[[ "${#harness_apks[@]}" == 1 ]] || { echo "Standalone harness must produce exactly one APK"; exit 1; }
test_apk="${harness_apks[0]}"
output=publication-output/release-smoke
adb="$ANDROID_HOME/platform-tools/adb"
mkdir -p "$output"
[[ -s app/build/outputs/mapping/release/mapping.txt ]] || { echo "Optimized release mapping missing"; exit 1; }
[[ -s release-check/build/outputs/mapping/release/mapping.txt ]] || { echo "Independent harness R8 mapping missing"; exit 1; }
pwsh -NoProfile -File tools/verify-release-smoke-apks.ps1 -App "$app" -TestApk "$test_apk" -ApplicationId "$application_id" > "$output/apk-policy.txt"
signer() {
  "$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --print-certs "$1" |
    sed -n 's/^Signer #1 certificate SHA-256 digest: //p'
}
app_signer="$(signer "$app")"
[[ "$app_signer" =~ ^[0-9a-fA-F]{64}$ && "$app_signer" == "$(signer "$test_apk")" ]] || { echo "App/test signing certificates must match"; exit 1; }
: > "$output/instrumentation-result.txt"
diagnostics() {
  status=$?
  trap - EXIT
  set +e
  timeout 10 "$adb" -s emulator-5554 logcat -d -v threadtime AndroidRuntime:E TestRunner:V AndroidJUnitRunner:V '*:S' > "$output/test-logcat.txt" 2>&1
  cat "$output/instrumentation-result.txt"
  if [[ -f "$output/disk-space.txt" ]]; then cat "$output/disk-space.txt"; fi
  if [[ -f "$output/emulator.log" ]]; then tail -n 100 "$output/emulator.log"; fi
  if [[ -n "${emulator_pid:-}" ]]; then
    timeout --kill-after=5s 10s "$adb" -s emulator-5554 emu kill ||
      echo "::warning::Emulator console stop did not respond (it may already have exited)" >&2
    if ! stop_ci_emulator "$emulator_pid" && [[ "$status" == 0 ]]; then status=1; fi
  fi
  printf '%s\n' "$status" > "$output/exit-status.txt"
  exit "$status"
}
trap diagnostics EXIT
[[ -r /dev/kvm && -w /dev/kvm ]] || { echo "Disposable emulator requires accessible KVM"; exit 1; }
mkdir -p "$ANDROID_AVD_HOME"
echo no | "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" create avd --force --name release-smoke --package "system-images;android-35;google_apis;x86_64"
bash tools/set-ci-avd-userdata.sh "$ANDROID_AVD_HOME/release-smoke.avd/config.ini"
df -h "$ANDROID_AVD_HOME" > "$output/disk-space.txt"
available_kb="$(df -Pk "$ANDROID_AVD_HOME" | awk 'NR==2 { print $4 }')"
[[ "$available_kb" =~ ^[0-9]+$ && "$available_kb" -ge 3145728 ]] || { echo "At least 3 GiB free is required for 2 GiB userdata plus emulator overhead"; exit 1; }
"$ANDROID_HOME/emulator/emulator" -avd release-smoke -port 5554 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot -accel on > "$output/emulator.log" 2>&1 &
emulator_pid=$!
timeout 180 "$adb" -s emulator-5554 wait-for-device
booted=false
for attempt in $(seq 1 90); do
  kill -0 "$emulator_pid"
  if [[ "$("$adb" -s emulator-5554 shell getprop sys.boot_completed | tr -d '\r')" == 1 ]]; then booted=true; break; fi
  sleep 2
done
[[ "$booted" == true ]] || { echo "Disposable release-smoke emulator did not boot"; exit 1; }
[[ "$("$adb" -s emulator-5554 shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] || { echo "Not an emulator"; exit 1; }
[[ "$("$adb" -s emulator-5554 shell getprop ro.build.version.sdk | tr -d '\r')" == 35 ]] || { echo "Not API35"; exit 1; }
"$adb" -s emulator-5554 install -r "$app"
"$adb" -s emulator-5554 install -r -t "$test_apk"
timeout 300 "$adb" -s emulator-5554 shell am instrument -w -r -e releaseSmoke true -e class "$classes" "$harness_id/dev.scrcpy.releasecheck.ReleaseCheckRunner" > "$output/instrumentation-result.txt" 2>&1
bash tools/check-instrumentation-result.sh "$output/instrumentation-result.txt" "$expected"
"$adb" -s emulator-5554 shell am start -W -n "$application_id/.MainActivity" > "$output/app-launch.txt" 2>&1
grep -q 'Status: ok' "$output/app-launch.txt"
sleep 2
[[ -n "$("$adb" -s emulator-5554 shell pidof "$application_id" | tr -d '\r')" ]] || { echo "Actual optimized app crashed after launch"; exit 1; }
