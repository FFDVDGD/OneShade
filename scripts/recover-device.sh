#!/usr/bin/env bash
set -euo pipefail

serial="${1:?Usage: $0 <adb-serial>}"
model="$(adb -s "$serial" shell getprop ro.product.model | tr -d '\r')"
if [[ "$model" != OPD2413 ]]; then
    printf 'Refusing to modify unexpected device: %s (%s)\n' "$serial" "$model" >&2
    exit 1
fi

# Removing this APK leaves LuckyTool, LSPosed, and all other modules intact.
adb -s "$serial" uninstall io.github.opd2413.ctrlcenter
adb -s "$serial" shell 'su -c "kill $(pidof com.android.systemui)"'
printf 'OneShade module removed. SystemUI will restart without its hooks.\n'
