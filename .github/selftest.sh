#!/bin/bash
# Installs the test build on the emulator, starts the app in self-test mode and collects the log.
P=com.sonu4y.mikusan
adb install -r build/miku-san.apk || exit 1
adb shell pm grant $P android.permission.RECORD_AUDIO
adb shell pm grant $P android.permission.POST_NOTIFICATIONS || true
adb shell appops set $P SYSTEM_ALERT_WINDOW allow || true
adb logcat -c
adb shell am start -n $P/com.godot.game.GodotApp --es miku_selftest live
for i in $(seq 1 120); do
  sleep 5
  if adb logcat -d | grep -q "SELFTEST done"; then break; fi
  if ! adb shell pidof $P > /dev/null; then echo "app is not running any more"; break; fi
done
adb logcat -d > logcat.txt
echo "===== self-test output ====="
grep -E "SELFTEST|MikuLive|FATAL EXCEPTION|Process: com.sonu4y|SCRIPT ERROR" logcat.txt | cut -c1-300 | head -200
echo "===== app crash details (if any) ====="
grep -A 25 "Process: com.sonu4y" logcat.txt | head -60
grep -q "SELFTEST done" logcat.txt
