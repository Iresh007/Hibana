#!/usr/bin/env bash
# Runs inside the emulator (invoked by android-emulator-runner). Installs the
# debug APK, runs a 60 second monkey session and collects logs into ./emulator-logs.
set -u
PKG=com.hibana.app
OUT=emulator-logs
mkdir -p "$OUT"

APK=$(ls app/build/outputs/apk/debug/*.apk | head -n 1)
echo "Installing $APK"
adb logcat -c
adb install -r -g "$APK" 2>&1 | tee "$OUT/install.txt"

# Start capturing before launch so startup crashes are included.
adb logcat -v threadtime > "$OUT/logcat.txt" &
LOGCAT_PID=$!

adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 8
adb exec-out screencap -p > "$OUT/screenshot-after-launch.png" || true

# ~60 s of random UI events: 100 ms throttle x 600 events. Monkey stops by itself
# when the app crashes or hits ANR, which is what we want to detect.
timeout 120 adb shell monkey -p "$PKG" \
  --throttle 100 --pct-syskeys 0 --pct-appswitch 0 \
  -s 42 -v -v 600 2>&1 | tee "$OUT/monkey.txt"
MONKEY_STATUS=${PIPESTATUS[0]}

adb exec-out screencap -p > "$OUT/screenshot-after-monkey.png" || true
sleep 2
kill "$LOGCAT_PID" 2>/dev/null || true

grep -E "FATAL EXCEPTION|AndroidRuntime|ANR in|Process: $PKG" "$OUT/logcat.txt" > "$OUT/crashes.txt" || true
# Make the files available at the repo root too, under the names requested.
cp "$OUT/logcat.txt" logcat.txt
cp "$OUT/crashes.txt" crashes.txt

echo "monkey exit status: $MONKEY_STATUS"
echo "crash lines: $(wc -l < "$OUT/crashes.txt")"
if grep -q "FATAL EXCEPTION" "$OUT/logcat.txt" || grep -q "// CRASH" "$OUT/monkey.txt"; then
  echo "App crashed during the test."
  exit 1
fi
exit 0
