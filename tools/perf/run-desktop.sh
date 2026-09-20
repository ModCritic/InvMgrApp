#!/usr/bin/env bash
# M6.7b's desktop lanes: the latency bench, on one screen, in both arms.
#
#   tools/perf/run-desktop.sh baseline xvfb      # the headless Xvfb at 2560x1440
#   tools/perf/run-desktop.sh baseline plasma    # ⚠ the real session, see below
#   tools/perf/run-desktop.sh baseline both
#
# TWO ARMS, ALWAYS BOTH. `felt` is the app as it ships, capped at 60 frames a second, which is
# what a hand experiences. `work` adds -Djavafx.animation.fullspeed=true and is the app's own
# work with the cap out of the way. Measured on Xvfb: every scenario in a small room reported a
# median of 24 ms in the felt arm, from one keystroke to a 2,310-sample slider drag, because the
# cap was the whole reading. The same six came back at 1.1 to 1.6 ms in the work arm, spread out
# by what each actually does. Reading one arm alone is how an optimization looks worthless.
#
# ⚠ THE PLASMA LANE TAKES OVER THE POINTER AND THE KEYBOARD for the length of the run, about
# four minutes, and opens a window that has to stay on top. TestFX drives the real X session with
# real input; typing during it corrupts the run and the run corrupts your typing. Walk away from
# the machine, or use the Xvfb lane.
set -euo pipefail

LABEL="${1:?usage: run-desktop.sh <label> <xvfb|plasma|both>}"
WHICH="${2:-xvfb}"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO"

start_xvfb() {
    if pgrep -f "Xvfb :99" >/dev/null; then return; fi
    # 2560x1440 and nothing else. CLAUDE.md §10: at 1280x800 twenty-nine tests fail for
    # reasons that have nothing to do with the code, and a bench on a screen that small would
    # be measuring a differently laid out app.
    Xvfb :99 -screen 0 2560x1440x24 -nolisten tcp &
    sleep 2
}

one_arm() {
    local display="$1" arm="$2"
    local extra=()
    [ "$arm" = work ] && extra=(-Djavafx.animation.fullspeed=true)
    echo
    echo "=== $LABEL, DISPLAY=$display, $arm arm ==="
    DISPLAY="$display" mvn -B test -Dtest=LatencyBench \
        -Dinvmgr.perf.label="$LABEL" "${extra[@]}" \
        | grep -E "^PERF|Tests run:|ERROR" || true
}

run_screen() {
    local display="$1"
    one_arm "$display" felt
    one_arm "$display" work
}

case "$WHICH" in
    xvfb)   start_xvfb; run_screen :99 ;;
    plasma) run_screen :0 ;;
    both)   start_xvfb; run_screen :99; run_screen :0 ;;
    *)      echo "second argument must be xvfb, plasma or both" >&2; exit 1 ;;
esac

echo
echo "Now: python3 tools/perf/perfstat.py report $LABEL"
