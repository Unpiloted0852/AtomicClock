#!/usr/bin/env bash
# Set the phone's clock to exact internet (NTP) time over adb. No root needed.
#
# Why: Android only corrects its clock when it is 2 s or more off, so smaller
# errors stay. This script asks the phone for a fresh NTP reading and feeds it
# back through the phone's "external" time source. To get past the 2 s rule it
# first suggests a time 3 s away, then the true time, so the clock is off by
# 3 s for a fraction of a second while it runs.
#
# The reading is a pair of (phone uptime, true time), so USB/adb delay does not
# affect accuracy.
#
# Usage: ./sync-phone-clock.sh        (phone plugged in, USB debugging allowed)
#        ./sync-phone-clock.sh check  (only measure, change nothing)
set -euo pipefail

pair() { sed -E 's/.*mElapsedRealtimeMillis=([0-9]+), mUnixEpochTimeMillis=([0-9]+).*/\1 \2/' | head -1; }

# Prints: <error of phone clock in ms> <ntp uptime ms> <ntp unix ms>
measure() {
  local ne nu se su
  adb shell cmd network_time_update_service force_refresh >/dev/null
  read -r ne nu < <(adb shell cmd time_detector get_network_time | tr -d '\r' | pair)
  read -r se su < <(adb shell cmd time_detector get_time_state | tr -d '\r' | pair)
  [[ "$ne" =~ ^[0-9]+$ && "$nu" =~ ^[0-9]+$ && "$se" =~ ^[0-9]+$ && "$su" =~ ^[0-9]+$ ]] \
    || { echo "Could not read time from the phone (is it online?)" >&2; exit 1; }
  echo "$(( (su - se) - (nu - ne) )) $ne $nu"
}

read -r err ne nu < <(measure)
echo "Before: phone clock is ${err} ms off (positive = fast)"
[[ "${1:-}" == check ]] && exit 0

# Push the clock 3 s to the far side of the true time first, so both steps exceed 2 s.
if (( err >= 0 )); then jump=-3000; else jump=3000; fi

# Whatever happens, leave the phone using internet time only.
restore() { adb shell device_config put system_time time_detector_origin_priorities_override network; }
trap restore EXIT
adb shell "device_config put system_time time_detector_origin_priorities_override external \
  && cmd time_detector suggest_external_time --elapsed_realtime $ne --unix_epoch_time $((nu + jump)) \
  && cmd time_detector suggest_external_time --elapsed_realtime $ne --unix_epoch_time $nu" >/dev/null
restore

read -r err _ _ < <(measure)
echo "After:  phone clock is ${err} ms off"
