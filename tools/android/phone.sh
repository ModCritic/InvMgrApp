#!/usr/bin/env bash
# Drives the app on the wired test phone. See tools/android/adb-env.sh for why
# nothing needs installing first.
#
#   phone.sh install          push the freshly built APK, launch it, wait until ready
#   phone.sh launch           force-stop and start, clear the log, wait until ready
#   phone.sh log [pattern]    dump what the app has said since launch
#   phone.sh shot <file>      screenshot to <file>
#   phone.sh tap X Y          one tap
#
# ⚠ Taps within ~50 px of the left or right edge never arrive; Samsung's edge
# gesture handler in SystemUI takes them first. Aim at x=70 or x=1010 for the
# item-list and layer-slider tabs, never x=40.
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$HERE/adb-env.sh" >/dev/null 2>&1 || { echo "adb-env.sh failed" >&2; exit 1; }

APP=com.modcritic.invmgr
ACT=$APP/.android.InvMgrActivity
APK="$HERE/../../target/gluonfx/aarch64-android/gvm/InvMgr.apk"

# Blocks until the app has built its scene. Firing taps before this point is the
# mistake that makes a test look like a regression; the native image takes several
# seconds to start and the taps land on whatever is still on screen.
#
# The marker is the app's own startup line, which it prints once the scene exists.
wait_ready() {
    # The phone's screen goes to sleep between runs, and an app with no surface never
    # starts; the log stops after "InvMgrActivity created" and looks like a hang. A
    # screenshot taken then is a blank white lock screen, which reads as a dead app.
    #
    # Waking is not enough on its own; the keyguard still covers the window, so the
    # surface never arrives. Both are needed, in this order.
    adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
    adb shell wm dismiss-keyguard >/dev/null 2>&1
    local waited=0
    while [ $waited -lt 40 ]; do
        if adb logcat -d 2>/dev/null | grep -q 'android true, reserving SystemInsets'; then
            return 0
        fi
        adb shell sleep 1 >/dev/null 2>&1
        waited=$((waited + 1))
    done
    echo "app did not report a scene within 40s" >&2
    return 1
}

case "${1:-}" in
    install)
        [ -f "$APK" ] || { echo "no APK at $APK -- build first" >&2; exit 1; }
        echo "installing $(stat -c %y "$APK" | cut -d. -f1)"
        adb install -r "$APK" | tail -2
        adb logcat -c
        adb shell am force-stop $APP
        adb shell am start -n $ACT >/dev/null
        wait_ready
        ;;
    launch)
        adb logcat -c
        adb shell am force-stop $APP
        adb shell am start -n $ACT >/dev/null
        wait_ready
        ;;
    log)   adb logcat -d -v brief 2>/dev/null | grep -E "${2:-InvMgr}" ;;
    shot)  adb exec-out screencap -p > "${2:?need a path}" ;;
    tap)   adb shell input tap "${2:?}" "${3:?}" ;;
    *)     sed -n '2,14p' "$0" ;;
esac
