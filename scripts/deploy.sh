#!/usr/bin/env bash
#
# Build, install and launch 3S on a device you pick from a menu.
#
# Wireless adb serials are unusable to type by hand
# (adb-5A211FDCG0003R-wP9kdq._adb-tls-connect._tcp), and VS Code cannot populate a
# dropdown from a shell command without another extension -- so the menu lives here,
# where it can show model names instead of serials.
#
# Usage:
#   scripts/deploy.sh [debug|release] [--logcat] [--serial <serial>]
#
# With one device attached it just uses it. With several it asks.

set -euo pipefail
cd "$(dirname "$0")/.."

PKG="de.freal.threeseconds"
ACTIVITY=".ui.MainActivity"

VARIANT="debug"
WANT_LOGCAT=0
SERIAL="${ANDROID_SERIAL:-}"

while [ $# -gt 0 ]; do
    case "$1" in
        debug|release) VARIANT="$1" ;;
        --logcat) WANT_LOGCAT=1 ;;
        --serial) shift; SERIAL="${1:-}" ;;
        -h|--help) sed -n '2,15p' "$0"; exit 0 ;;
        *) echo "Unknown argument: $1" >&2; exit 2 ;;
    esac
    shift
done

ADB="${ADB:-adb}"
command -v "$ADB" >/dev/null 2>&1 || ADB="$HOME/Library/Android/sdk/platform-tools/adb"

# ---- device selection -------------------------------------------------------

# Each line: "<serial>\t<friendly name>"
devices() {
    "$ADB" devices -l 2>/dev/null | awk '
        NR > 1 && $2 == "device" {
            serial = $1
            model = ""
            for (i = 3; i <= NF; i++) if ($i ~ /^model:/) { model = substr($i, 7); gsub(/_/, " ", model) }
            if (model == "") model = "unknown device"
            printf "%s\t%s\n", serial, model
        }'
}

pick_device() {
    local lines serials names count
    lines="$(devices)"
    if [ -z "$lines" ]; then
        echo "No devices connected. Plug one in, or run: adb connect <host:port>" >&2
        exit 1
    fi

    serials=(); names=()
    while IFS=$'\t' read -r s n; do
        serials+=("$s"); names+=("$n")
    done <<< "$lines"

    count=${#serials[@]}
    if [ "$count" -eq 1 ]; then
        SERIAL="${serials[0]}"
        echo "Using ${names[0]}"
        return
    fi

    echo "Several devices are connected:" >&2
    local i
    for i in "${!serials[@]}"; do
        printf "  %d) %s  (%s)\n" "$((i + 1))" "${names[$i]}" "${serials[$i]}" >&2
    done

    local choice
    while true; do
        read -r -p "Device [1-$count]: " choice < /dev/tty
        if [[ "$choice" =~ ^[0-9]+$ ]] && [ "$choice" -ge 1 ] && [ "$choice" -le "$count" ]; then
            SERIAL="${serials[$((choice - 1))]}"
            echo "Using ${names[$((choice - 1))]}"
            return
        fi
        echo "Pick a number between 1 and $count." >&2
    done
}

if [ -z "$SERIAL" ]; then
    pick_device
else
    echo "Using $SERIAL"
fi
export ANDROID_SERIAL="$SERIAL"

# ---- build, install, launch -------------------------------------------------

case "$VARIANT" in
    debug)   GRADLE_TASK=":app:installDebug" ;;
    release) GRADLE_TASK=":app:installRelease" ;;
esac

echo "Building and installing the $VARIANT build..."
./gradlew "$GRADLE_TASK"

# A plain start, not `am start -D`: nothing waits for a debugger, so the app comes up
# immediately. Use the VS Code "debug" configs when you actually want breakpoints.
"$ADB" -s "$SERIAL" shell am force-stop "$PKG" || true
"$ADB" -s "$SERIAL" shell am start -n "$PKG/$ACTIVITY" >/dev/null
echo "Launched $PKG ($VARIANT)"

if [ "$WANT_LOGCAT" -eq 1 ]; then
    echo "Tailing logcat, Ctrl-C to stop."
    "$ADB" -s "$SERIAL" logcat -c
    "$ADB" -s "$SERIAL" logcat \
        | grep -E 'ThreeSeconds|DailyScheduler|DailyTrigger|RecordingService|ClipRecorder|Mp4FrameWriter|GlassesManager|PromptAction|Notifications|AndroidRuntime|DAT:'
fi
