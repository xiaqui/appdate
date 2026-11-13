#!/usr/bin/env bash
set -euo pipefail
pkg=jp.xiaqui.artifact.store.preview
runner="$pkg.test/androidx.test.runner.AndroidJUnitRunner"
test_class=jp.xiaqui.artifact.PostPhaseTest
instrument() {
  local method=$1
  shift
  adb shell am instrument -w -e class "$test_class#$method" "$@" "$runner" | tee "$RUNNER_TEMP/inst.log"
  grep -Fq 'OK (1 test)' "$RUNNER_TEMP/inst.log"
}
gradle connectedDebugAndroidTest
# Gradle may uninstall the target and instrumentation after connected checks.
gradle installDebug installDebugAndroidTest
instrument processSeed
adb shell am force-stop "$pkg"
instrument processRead -e restartCheck true
adb shell wm size 480x800
adb shell wm density 240
adb shell settings put system font_scale 1.3
instrument sevenRowsAndDetails
adb exec-out run-as "$pkg" cat files/seven-rows.png > seven-rows.png
test -s seven-rows.png
