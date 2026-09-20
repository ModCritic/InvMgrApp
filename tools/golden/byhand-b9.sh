#!/usr/bin/env bash
#
# B9's mutation sweep: break each rule in PanMomentum on purpose and confirm a test notices.
#
# Why by hand rather than in mutation-sweep.sh: nothing here needs a window, so it COULD live in
# the headless sweep. It stayed separate after B9 landed, because running it alone checks the
# coasting rules in one command without waiting on the full sweep, which is now 210 cases.
#
# Two guards, both of them bought the hard way on this project:
#
#   * The md5 check. A pattern that matches nothing leaves the source untouched, the tests pass,
#     and the case reports SURVIVED while having tested nothing at all. Every case here asserts
#     the file actually changed before it runs anything.
#   * Reverting from a copy, never from `git checkout`. M3.7's first sweep destroyed uncommitted
#     work that way and silently compared the old build against the new tests.
#
# Usage:  bash tools/golden/byhand-b9.sh

set -uo pipefail
cd "$(dirname "$0")/../.."

PURE=src/main/java/com/modcritic/invmgr/ui/PanMomentum.java
WIRING=src/main/java/com/modcritic/invmgr/ui/RoomCanvasView.java
PURE_BACKUP=$(mktemp)
WIRING_BACKUP=$(mktemp)
MVN=${MVN:-mvn}

# The default target. Cases against the wiring set SRC and TESTS themselves.
SRC=$PURE
TESTS=PanMomentumTest

cp "$PURE" "$PURE_BACKUP"
cp "$WIRING" "$WIRING_BACKUP"
restore_all() {
    cp "$PURE_BACKUP" "$PURE"
    cp "$WIRING_BACKUP" "$WIRING"
}
# ⚠ BOTH files, always. M6.4's sweep restored only the files it had backed up BY NAME, so the
# first mutation in a new file stayed in the source for the rest of the run and everything after
# it reported "caught" while testing nothing.
trap 'restore_all; rm -f "$PURE_BACKUP" "$WIRING_BACKUP"' EXIT

caught=0
survived=0
broken=0

run_case() {
    local name="$1" pattern="$2"
    local before after

    before=$(md5sum "$SRC" | cut -d' ' -f1)
    sed -i "$pattern" "$SRC"
    after=$(md5sum "$SRC" | cut -d' ' -f1)

    if [ "$before" = "$after" ]; then
        echo "  BROKEN PATTERN  $name  (matched nothing, so nothing was tested)"
        broken=$((broken + 1))
        restore_all
        return
    fi

    if $MVN -B -q test -Dtest="$TESTS" > /tmp/b9-mutation.log 2>&1; then
        echo "  SURVIVED        $name"
        survived=$((survived + 1))
    else
        echo "  caught          $name"
        caught=$((caught + 1))
    fi

    restore_all
}

echo "Baseline first: an unmutated run must PASS, or every case below is meaningless."
if $MVN -B -q test -Dtest="$TESTS" > /tmp/b9-mutation.log 2>&1; then
    echo "  baseline green"
else
    echo "  BASELINE IS RED. Fix that before reading anything below."
    tail -25 /tmp/b9-mutation.log
    exit 1
fi

echo
echo "Mutations:"

# --- the release-speed rule, which is the whole point of B9 -------------------
run_case "nothing is ever too slow to fling" \
    's/if (speed < MIN_FLING_SPEED) {/if (false) {/'

run_case "the speed ceiling is removed" \
    's/if (speed > MAX_FLING_SPEED) {/if (false) {/'

run_case "the ceiling is applied per axis instead of to the resultant" \
    's|vx \*= MAX_FLING_SPEED / speed;|vx = Math.min(vx, MAX_FLING_SPEED);|; s|vy \*= MAX_FLING_SPEED / speed;|vy = Math.min(vy, MAX_FLING_SPEED);|'

# --- the sampling window ------------------------------------------------------
run_case "the window is ignored and the whole gesture is averaged" \
    's/if (sampleTime\[i\] < windowStart) {/if (false) {/'

run_case "the earliest sample contributes its movement again" \
    's/if (sampleTime\[i\] > earliest) {/if (sampleTime[i] >= earliest) {/'

run_case "an empty or zero-length window is measured anyway" \
    's/if (span <= 0) {/if (span < 0) {/'

# --- the coast ----------------------------------------------------------------
run_case "the rectangle rule replaces the integral" \
    's|double travel = (1 - decay) / -Math.log(SPEED_LEFT_AFTER_A_SECOND);|double travel = elapsed;|'

run_case "the coast never decides it has stopped" \
    's/if (Math.hypot(velocityX, velocityY) < STOP_SPEED) {/if (false) {/'

run_case "a frame going backwards is integrated anyway" \
    's/if (elapsed <= 0) {/if (false) {/'

run_case "the velocity never decays" \
    's/velocityX \*= decay;/velocityX *= 1.0;/'

# --- housekeeping -------------------------------------------------------------
# Scoped to stop()'s own body: `coasting = false` appears in released() and advance() too, and a
# bare substitution would mutate whichever came first instead of the one this case is about.
run_case "stop() leaves the coast running" \
    '/public void stop() {/,/^    }/ s/coasting = false;/coasting = true;/'

run_case "began() keeps the last gesture's samples" \
    's/        samplesHeld = 0;/        \/\/ samplesHeld = 0;/'

# --- the wiring, which needs a window --------------------------------------------
SRC=$WIRING
TESTS=PanCoastTest

echo
echo "Wiring (RoomCanvasView, needs a window):"

run_case "the platform's own fling is let through" \
    's/if (event.isInertia()) {/if (false) {/'

run_case "an ordinary scroll is swallowed along with the fling" \
    's/if (event.isInertia()) {/if (true) {/'

run_case "the release never starts a coast" \
    's/coastClock.start();/\/\/ coastClock.start();/'

run_case "the pan is never sampled, so there is no speed to fling with" \
    's/            samplePan(true, before.doubleValue(), after.doubleValue());//'

run_case "a finger landing does not stop the coast" \
    's/^            stopCoasting();$//'

run_case "a box drag's scroll is allowed to start a coast" \
    '/private void trackPanForMomentum/,/^    }$/ s/if (swallowsScrollGesture()) {/if (false) {/'

echo
echo "caught $caught, survived $survived, broken $broken"
[ "$survived" -eq 0 ] && [ "$broken" -eq 0 ]
