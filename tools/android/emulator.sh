#!/usr/bin/env bash
# Android emulators for checking InvMgr on the Android versions no phone here runs.
#
#   tools/android/emulator.sh install 30 31 35 36 37   # images and phone-shaped emulators
#   tools/android/emulator.sh start 36                 # boot one, headless; waits for it
#   tools/android/emulator.sh adb 36 shell getprop ro.build.version.release
#   tools/android/emulator.sh suite 36 "settings:"      # the device suite, on the emulator
#   tools/android/emulator.sh stop 36
#   tools/android/emulator.sh list
#
# The number is the API level: 30 is Android 11, 31 is 12, 35 is 15, 36 is 16, 37 is 17.
#
# NO NETWORK. Each emulator runs inside a network namespace of its own that holds nothing but
# a loopback interface, made with `unshare -rn` (no root needed). The emulator reaches the
# guest's network through ordinary sockets on the host, so in there the guest can reach
# nothing: not the internet and not the local network. adb talks to the emulator over that
# same loopback, so `adb` has to run inside the namespace too, which is what the `adb`
# subcommand does. That also gives each emulator its own adb server, apart from the one that
# drives the test phone.
#
# The emulator is shaped like the test phone: 1080 x 2220 at 480 dpi, which is 360 x 740 in
# Android's own units, with on-screen navigation buttons and no hardware keyboard, so a text
# box brings up the on-screen one. InvMgr's APK holds 64-bit ARM code only, and these images
# are x86_64, so the app runs through the ARM translation the Google APIs images carry
# (ro.product.cpu.abilist lists arm64-v8a).
set -euo pipefail

SDK=${ANDROID_SDK_ROOT:-$HOME/.gluon/substrate/Android}
export ANDROID_SDK_ROOT=$SDK
export ANDROID_AVD_HOME=${ANDROID_AVD_HOME:-$HOME/.gluon/substrate/avd}
export JAVA_HOME=${JAVA_HOME:-$HOME/graalvm-svm-java17-linux-gluon-22.1.0.1-Final}
EMULATOR=$SDK/emulator/emulator
ADB=$SDK/platform-tools/adb

die() { echo "emulator.sh: $*" >&2; exit 1; }

# The Google APIs x86_64 image for each API level.
#
# ⚠ ANDROID 17 IS 37.2, THE 16 KB PAGE SIZE IMAGE. Its point releases come only that way, which
# also runs the app's 16 KB pages on a real 16 KB kernel. But no Android 17 image holds still on
# the emulators tried: system_server aborts whenever it takes a task snapshot, "Assertion failed:
# !rcEnc->featureInfo()->hasReadColorBufferDma", with emulator 37.1.11 and the canary 37.3.2, on
# 37.0 and 37.2, on every renderer, and with GLDMA, GLDMA2 and GLAsyncSwap off (2026-09-29/30).
# InvMgr starts and draws there, and little more can be tested.
image_for() {
    case "$1" in
        3[0-6]) echo "system-images;android-$1;google_apis;x86_64" ;;
        37)     echo "system-images;android-37.2;google_apis_ps16k;x86_64" ;;
        *)      die "no image chosen for API $1; add it to image_for" ;;
    esac
}

name_for()  { echo "invmgr-api$1"; }
state_for() { echo "$ANDROID_AVD_HOME/$(name_for "$1").state"; }

running_pid() {
    local pidfile; pidfile="$(state_for "$1")/ns.pid"
    [ -f "$pidfile" ] || return 1
    local pid; pid=$(cat "$pidfile")
    kill -0 "$pid" 2>/dev/null || return 1
    echo "$pid"
}

# adb inside the emulator's namespace. -U joins its user namespace, -n its network one; the
# namespace is ours, so joining needs no root.
ns_adb() {
    local api=$1; shift
    local pid; pid=$(running_pid "$api") || die "API $api is not running; start it first"
    nsenter --target "$pid" --user --net --preserve-credentials "$ADB" "$@"
}

install() {
    [ $# -gt 0 ] || die "install which API levels?"
    local images=() api
    for api in "$@"; do images+=("$(image_for "$api")"); done
    # Reads nothing from the terminal, so a license this SDK has not accepted yet stops the
    # install instead of being accepted: that is the user's to read and agree to.
    sh "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" emulator "${images[@]}" < /dev/null
    mkdir -p "$ANDROID_AVD_HOME"
    for api in "$@"; do
        local name; name=$(name_for "$api")
        if [ -d "$ANDROID_AVD_HOME/$name.avd" ]; then
            echo "$name already exists"
            continue
        fi
        echo no | sh "$SDK/cmdline-tools/latest/bin/avdmanager" create avd --force \
            -n "$name" -k "$(image_for "$api")" -d pixel_2 > /dev/null
        # the test phone's shape; see the top of this file
        cat >> "$ANDROID_AVD_HOME/$name.avd/config.ini" <<EOF
hw.lcd.width=1080
hw.lcd.height=2220
hw.lcd.density=480
hw.keyboard=no
hw.mainKeys=no
hw.ramSize=4096
disk.dataPartition.size=6G
hw.gpu.enabled=yes
skin.name=1080x2220
skin.path=_no_skin
showDeviceFrame=no
EOF
        echo "made $name"
    done
}

start() {
    local api=${1:?start which API level?}
    local name state; name=$(name_for "$api"); state=$(state_for "$api")
    [ -d "$ANDROID_AVD_HOME/$name.avd" ] || die "no emulator for API $api; run: $0 install $api"
    if running_pid "$api" > /dev/null; then echo "$name is already running"; return; fi
    mkdir -p "$state"
    # DRAWING: the emulator's "host" GL on Xvfb :99, the same headless display the desktop tests
    # use, where Mesa draws in software (llvmpipe). Measured 2026-09-29 against the alternatives,
    # time from launch to InvMgr's first frame: SwiftShader, the emulator's own software GL,
    # about 15 s, far too slow for the device suite's waits; llvmpipe here, about 4 s, the same
    # as the real graphics card. The card itself is reachable only through an X display, which
    # would mean the user's own desktop, :0, so it is not used; with no display at all the
    # emulator cannot start its renderer. INVMGR_EMULATOR_GPU picks another -gpu mode.
    if [ ! -S /tmp/.X11-unix/X99 ]; then
        setsid nohup Xvfb :99 -screen 0 2560x1440x24 -nolisten tcp > /dev/null 2>&1 < /dev/null &
        for _ in $(seq 20); do [ -S /tmp/.X11-unix/X99 ] && break; sleep 0.25; done
    fi
    local features=${ANDROID_EMULATOR_FEATURES:-}
    # The shell inside the namespace brings its loopback up, writes its own PID (which is the
    # host's PID too, since only the user and network namespaces are new), then becomes the
    # emulator, so that PID is the emulator's for as long as it runs.
    DISPLAY=:99 ANDROID_EMULATOR_FEATURES=$features setsid unshare --user --map-root-user --net sh -c '
        ip link set lo up
        echo $$ > "$0/ns.pid"
        exec "$1" -avd "$2" -no-window -no-audio -no-boot-anim -no-snapshot \
            -gpu "$3" -camera-back none -camera-front none -no-metrics
    ' "$state" "$EMULATOR" "$name" "${INVMGR_EMULATOR_GPU:-host}" > "$state/emulator.log" 2>&1 < /dev/null &
    for _ in $(seq 40); do running_pid "$api" > /dev/null && break; sleep 0.5; done
    running_pid "$api" > /dev/null || die "the emulator did not start; see $state/emulator.log"
    echo "booting $name (log: $state/emulator.log)"
    ns_adb "$api" wait-for-device
    for _ in $(seq 300); do
        [ "$(ns_adb "$api" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && break
        sleep 2
    done
    [ "$(ns_adb "$api" shell getprop sys.boot_completed | tr -d '\r')" = 1 ] \
        || die "API $api did not finish booting; see $state/emulator.log"
    # Animations off: nothing here measures them, and they make every screenshot a race. Retried,
    # because boot_completed can read 1 a moment before the settings service answers.
    for key in window_animation_scale transition_animation_scale animator_duration_scale; do
        for _ in $(seq 30); do
            ns_adb "$api" shell settings put global "$key" 0 2>/dev/null && break
            sleep 2
        done
    done
    # Three navigation buttons, like the test phone: the images default to gesture navigation,
    # whose bar is 24 units tall against the phone's 48, and every position the device suite
    # taps near the bottom of the screen assumes 48.
    ns_adb "$api" shell cmd overlay enable com.android.internal.systemui.navbar.threebutton
    echo "$name is up: Android $(ns_adb "$api" shell getprop ro.build.version.release | tr -d '\r')"
}

# The device suite, pointed at a running emulator instead of the phone. Any further arguments
# go to the suite: a case name to match, or --no-install.
suite() {
    local api=${1:?run the suite on which API level?}; shift
    running_pid "$api" > /dev/null || die "API $api is not running; start it first"
    INVMGR_ADB="$0 adb $api" ANDROID_SERIAL=emulator-5554 \
        python3 "$(dirname "$0")/device_suite.py" "$@"
}

stop() {
    local api=${1:?stop which API level?}
    local pid; pid=$(running_pid "$api") || { echo "API $api is not running"; return; }
    local netns; netns=$(readlink "/proc/$pid/ns/net")
    ns_adb "$api" emu kill > /dev/null 2>&1 || true
    for _ in $(seq 30); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
    # The recorded PID is the emulator's launcher, and the emulator proper (qemu) is its
    # child. setsid made the launcher a session of its own, so its PID is the session's ID
    # and this ends everything it started, including an emulator that never booted far
    # enough to answer `emu kill`.
    pkill -TERM -s "$pid" 2>/dev/null || true
    # ⚠ AND THE NAMESPACE'S OWN ADB SERVER, which daemonizes into a session of its own, so the line
    # above misses it. Left running it holds the namespace open: 21 of them had piled up after a
    # day of starts and stops (found 2026-09-30). Anything still in the emulator's network
    # namespace is ours to end; the host's own adb server, the phone's, is in another.
    local left
    for left in $(pgrep -f "adb .*fork-server" || true); do
        if [ "$(readlink "/proc/$left/ns/net" 2>/dev/null)" = "$netns" ]; then
            kill "$left" 2>/dev/null || true
        fi
    done
    rm -f "$(state_for "$api")/ns.pid"
    echo "stopped $(name_for "$api")"
}

list() {
    local avd api
    for avd in "$ANDROID_AVD_HOME"/invmgr-api*.avd; do
        [ -d "$avd" ] || continue
        api=${avd##*invmgr-api}; api=${api%.avd}
        if running_pid "$api" > /dev/null; then echo "API $api  running"; else echo "API $api"; fi
    done
}

cmd=${1:-}; shift || true
case "$cmd" in
    install) install "$@" ;;
    start)   start "$@" ;;
    stop)    stop "$@" ;;
    adb)     ns_adb "$@" ;;
    list)    list ;;
    suite)   suite "$@" ;;
    *)       sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
