#!/usr/bin/env bash
# Puts the Gluon SDK's platform-tools on PATH and pins the test phone's serial.
# Source it, don't run it:   . tools/android/adb-env.sh
#
# There is nothing to install. adb ships inside ~/.gluon (Gluon pulled it down
# with the Android SDK during the M6.1 toolchain setup), and systemd-logind
# already grants the active seat an ACL on the USB node via the uaccess udev
# tag, so no root, no android-udev package, no rules file.

ADB_HOME="$HOME/.gluon/substrate/Android/platform-tools"

if [ ! -x "$ADB_HOME/adb" ]; then
    echo "adb missing from $ADB_HOME -- the Gluon SDK is not where it was" >&2
    return 1 2>/dev/null || exit 1
fi

case ":$PATH:" in
    *":$ADB_HOME:"*) ;;
    *) PATH="$ADB_HOME:$PATH" ;;
esac
export PATH

# Pin the serial so a second phone plugged in later can never be the target.
#
# The value lives OUTSIDE the repo, in ~/.config/invmgr/test-phone, because it is a
# hardware identifier for somebody's own phone and this tree is published. Keeping it
# in a file git cannot see beats remembering to scrub it before every push.
#
# Set it up once:  mkdir -p ~/.config/invmgr && adb devices | sed -n 2p | cut -f1 > ~/.config/invmgr/test-phone
#
# ⚠ It fails rather than falling back to "whatever is plugged in". Guessing is the one
# thing pinning exists to prevent, and a suite that silently drove the wrong phone would
# be worse than one that refused to start.
PHONE_FILE="${INVMGR_PHONE_FILE:-$HOME/.config/invmgr/test-phone}"

if [ -z "${ANDROID_SERIAL:-}" ] && [ -r "$PHONE_FILE" ]; then
    ANDROID_SERIAL="$(tr -d '[:space:]' < "$PHONE_FILE")"
fi

if [ -z "${ANDROID_SERIAL:-}" ]; then
    echo "no test phone pinned. Put its adb serial in $PHONE_FILE, or set" >&2
    echo "ANDROID_SERIAL yourself. 'adb devices' lists the serials." >&2
    return 1 2>/dev/null || exit 1
fi
export ANDROID_SERIAL

echo "adb $("$ADB_HOME/adb" version | sed -n 2p | cut -d' ' -f2)  target $ANDROID_SERIAL"
adb devices -l | sed 1d
