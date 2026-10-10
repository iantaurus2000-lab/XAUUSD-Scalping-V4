#!/usr/bin/env bash
set -euo pipefail

mkdir -p emulator-artifacts
ADB="${ADB:-adb}"
APK="app/build/outputs/apk/debug/app-debug.apk"

capture_diagnostics() {
  "$ADB" devices -l > emulator-artifacts/adb-devices.txt 2>&1 || true
  "$ADB" shell getprop > emulator-artifacts/getprop.txt 2>&1 || true
  "$ADB" logcat -b all -d -v time > emulator-artifacts/logcat-boot.txt 2>&1 || true
  "$ADB" shell dumpsys activity activities > emulator-artifacts/activities.txt 2>&1 || true
  "$ADB" shell dumpsys package > emulator-artifacts/package-manager.txt 2>&1 || true
}

wait_for_package_manager() {
  local attempt boot
  for attempt in $(seq 1 45); do
    boot="$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
    if [[ "$boot" == "1" ]] && "$ADB" shell pm list packages > /dev/null 2>&1; then
      echo "Package Manager ready (attempt $attempt)"
      return 0
    fi
    sleep 2
  done
  return 1
}

"$ADB" wait-for-device
if ! wait_for_package_manager; then
  echo "Package Manager not ready; saving diagnostics and rebooting emulator once."
  capture_diagnostics
  "$ADB" reboot || true
  "$ADB" wait-for-device
  if ! wait_for_package_manager; then
    echo "ERROR: Package Manager did not recover after reboot."
    capture_diagnostics
    exit 1
  fi
fi

"$ADB" shell pm list packages > emulator-artifacts/packages.txt
test -s "$APK"
"$ADB" install -r "$APK" 2>&1 | tee emulator-artifacts/install.txt
# Grant the Android 13+ notification runtime permission before launch, so the
# captured image shows the app UI rather than the system permission dialog.
"$ADB" shell pm grant com.xauusd.mobileengine android.permission.POST_NOTIFICATIONS 2>&1 || true
"$ADB" shell am start -W -n com.xauusd.mobileengine/.MainActivity 2>&1 | tee emulator-artifacts/launch.txt
sleep 15
"$ADB" exec-out screencap -p > emulator-artifacts/rayyan4-home.png
"$ADB" shell dumpsys activity activities > emulator-artifacts/activities.txt 2>&1 || true
"$ADB" logcat -b all -d -v time > emulator-artifacts/logcat.txt 2>&1 || true
"$ADB" shell pidof com.xauusd.mobileengine > emulator-artifacts/app-pid.txt 2>&1 || true
"$ADB" shell dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity' > emulator-artifacts/resumed-activity.txt || true

if ! grep -Eq '[0-9]+' emulator-artifacts/app-pid.txt; then
  grep -n -A 8 -B 4 'FATAL EXCEPTION\|AndroidRuntime' emulator-artifacts/logcat.txt > emulator-artifacts/crash-summary.txt || true
  echo "ERROR: APK installed, but its process exited after launch."
  exit 1
fi

if grep -q 'com.google.android.permissioncontroller' emulator-artifacts/resumed-activity.txt; then
  echo "ERROR: system permission controller still covers the app screen."
  exit 1
fi

echo "PASS: APK installed; MainActivity is running; app screenshot and logs saved."
