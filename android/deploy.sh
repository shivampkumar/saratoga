#!/usr/bin/env bash
# Install/update saratoga APK. Pushes weights only if missing on phone.
# Usage:
#   ./deploy.sh          # install-or-update APK, push missing weights
#   ./deploy.sh --force  # re-push weights even if present
#   ./deploy.sh --wipe   # uninstall + full reinstall + all weights
set -e

HERE="$(cd "$(dirname "$0")" && pwd)"
APK="$HERE/app/build/outputs/apk/debug/app-debug.apk"
WEIGHTS_SRC="$HERE/../../weights-android"
PKG="com.saratoga"
PHONE_WEIGHTS="/sdcard/Android/data/$PKG/files/weights"

MODE="${1:-normal}"

echo "=== adb devices ==="
adb devices | grep -v "List of"

if [[ "$MODE" == "--wipe" ]]; then
  echo ""
  echo "=== WIPE — uninstalling + clearing all app data ==="
  adb uninstall "$PKG" 2>/dev/null || true
fi

echo ""
echo "=== installing APK (replace, preserves data) ==="
adb install -r "$APK"

# App-external dir only exists after first launch.
echo ""
echo "=== priming app (creates /Android/data/$PKG/files) ==="
adb shell am start -n "$PKG/.MainActivity" >/dev/null
sleep 2
adb shell "mkdir -p $PHONE_WEIGHTS"

push_if_missing() {
  local name="$1"
  local remote="$PHONE_WEIGHTS/$name"
  if [[ "$MODE" != "--force" && "$MODE" != "--wipe" ]]; then
    if adb shell "[ -f $remote/config.txt ]" 2>/dev/null; then
      echo "[skip] $name already on phone"
      return
    fi
  fi
  echo "[push] $name ($(du -sh "$WEIGHTS_SRC/$name" | cut -f1))"
  adb push "$WEIGHTS_SRC/$name" "$PHONE_WEIGHTS/"
}

echo ""
echo "=== pushing weights (~8.6GB total if all missing) ==="
push_if_missing qwen3-embedding-0.6b
push_if_missing gemma-4-e4b-it
push_if_missing moonshine-base

echo ""
echo "=== phone weights dir ==="
adb shell "ls -la $PHONE_WEIGHTS"

echo ""
echo "=== DONE. Open Sidewinder on phone. ==="
