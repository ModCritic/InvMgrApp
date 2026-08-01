#!/bin/bash
#
# Mutation sweep for the 2D engine and the persistence layer.
#
# WHY THIS EXISTS
#
# A passing test suite proves the tests pass, not that they would notice if the code broke.
# During M2 that distinction was not academic: the 250-scenario differential suite was green,
# and deliberately reintroducing the original app's known "the Y slide uses the pre-slide X"
# bug ALSO left it green — random scenarios essentially never line up so that the difference
# is observable. The same was true of relaxing the strict `<` in rectsOverlap.
#
# So this script breaks one documented rule at a time and checks that the suite fails. A
# mutation that SURVIVES is a blind spot: the rule it breaks is not actually being tested.
#
# USAGE
#   bash tools/golden/mutation-sweep.sh          # from the project root
#
# Runs in about two minutes. It deliberately runs only the headless tests — see the comment
# on SUITE below for why, and for the two guards that stop that shortcut turning into a lie.
#
# Expected result as of M4: 48 mutations, 47 caught, 1 survivor. The survivor
# ("recompute skips zeroing heights first") is NOT a gap — that pass is provably redundant,
# because stackInOrder assigns a height to every item anyway. See the comment in Stacking.java.
#
# M4 added the autosave cases. One of them survived on its first run — "Windows ignores
# %APPDATA%" — because the test asserted the result merely CONTAINED "AppData/Roaming", which
# the fallback path also does. The test now asserts the whole path. That is what this script
# is for, and it earned its keep the day it was extended.
#
# The autosave rules that live in App.java are NOT here: they need a window, and this script
# runs headless on purpose. They are mutated by hand against AutosaveAppTest — see CLAUDE.md §5.
#
# Extend this whenever the engine grows. A rule that is written down but not mutated here is a
# rule nothing is checking.
#
# The script restores every file it touches, including on failure.

set -u
cd "$(dirname "$0")/../.." || exit 1

ENG=src/main/java/com/modcritic/invmgr/engine
PER=src/main/java/com/modcritic/invmgr/persist
BK=$(mktemp -d)
restore_all() {
  cp "$BK"/engine/*.java "$ENG"/ 2>/dev/null
  cp "$BK"/persist/*.java "$PER"/ 2>/dev/null
}
trap 'restore_all; rm -rf "$BK"' EXIT

mkdir -p "$BK"/engine "$BK"/persist
cp $ENG/*.java "$BK"/engine/
cp $PER/*.java "$BK"/persist/

caught=0
survived=0

# Only the headless tests are run, not the whole suite.
#
# Every mutation below is in engine/, and the engine tests are the ones written to catch
# them — but the full suite now includes ~50 TestFX tests that drive a real pointer, and
# running those 32 times took over an hour. A sweep that slow is a sweep nobody runs, which
# is the same as not having one.
#
# The danger with narrowing a -Dtest= pattern is the one CLAUDE.md §2 names: a pattern that
# matches nothing makes Surefire error, which looks exactly like "the mutation was caught",
# and every case then reports a false success. Two guards below: the baseline must pass, and
# it must report at least MIN_TESTS of them.
SUITE='com.modcritic.invmgr.engine.*Test,com.modcritic.invmgr.model.*Test,com.modcritic.invmgr.persist.*Test'

# A floor, not an exact count, so adding tests does not break the script. Raise it when the
# headless suite grows substantially. 140 at M3; M4's persistence tests took the baseline to
# 185, so the floor moves up with it — a floor far below the real count stops catching the
# thing it exists to catch.
MIN_TESTS=175

run_suite() {
  DISPLAY=${DISPLAY:-:99} mvn -B test -Dtest="$SUITE" -DfailIfNoSpecifiedTests=true "$@"
}

# A mutation is only meaningful if the suite passes when nothing is broken.
echo "checking baseline..."
if ! run_suite >/tmp/mutation-baseline.log 2>&1; then
  echo "ABORT: the suite already fails before any mutation. See /tmp/mutation-baseline.log"
  exit 1
fi

# ...and only meaningful if the suite actually ran. A pattern that quietly stopped matching
# would otherwise turn every case below into a false "caught".
ran=$(grep -oP 'Tests run: \K[0-9]+' /tmp/mutation-baseline.log | tail -1)
if [ -z "$ran" ] || [ "$ran" -lt "$MIN_TESTS" ]; then
  echo "ABORT: baseline ran ${ran:-0} tests, expected at least $MIN_TESTS."
  echo "       The -Dtest= pattern has stopped matching what it used to. Every mutation"
  echo "       below would report a false 'caught'. See /tmp/mutation-baseline.log"
  exit 1
fi
echo "baseline green — $ran tests."
echo

run_case() {
  local name="$1"
  if run_suite -q >/tmp/mutation-run.log 2>&1; then
    echo "SURVIVED  <-- BLIND SPOT: $name"
    survived=$((survived + 1))
  else
    echo "caught    $name"
    caught=$((caught + 1))
  fi
  restore_all
}

mutate() { perl -0pi -e "$1" "$2"; }

# --- the overlap rule, one comparison at a time -------------------------------------
mutate 's/x < other\.x2/x <= other.x2/'   $ENG/Rect.java; run_case "overlaps: x < other.x2 becomes <="
mutate 's/x2 > other\.x\b/x2 >= other.x/' $ENG/Rect.java; run_case "overlaps: x2 > other.x becomes >="
mutate 's/y < other\.y2/y <= other.y2/'   $ENG/Rect.java; run_case "overlaps: y < other.y2 becomes <="
mutate 's/y2 > other\.y\b/y2 >= other.y/' $ENG/Rect.java; run_case "overlaps: y2 > other.y becomes >="

# --- stacking ----------------------------------------------------------------------
mutate 's/Comparator\.comparingDouble\(it -> it\.dragOrder\)/Comparator.comparingDouble(it -> it.baseHeight_in)/' $ENG/Stacking.java
run_case "recompute sorts by height instead of dragOrder"
mutate 's/Comparator\.comparingDouble\(it -> it\.baseHeight_in\)/Comparator.comparingDouble(it -> it.dragOrder)/' $ENG/Stacking.java
run_case "settle sorts by dragOrder instead of height"
mutate 's/for \(Item item : sorted\) \{\s*\n\s*item\.baseHeight_in = 0;\s*\n\s*\}//' $ENG/Stacking.java
run_case "recompute skips zeroing heights first (known-redundant, expected to survive)"
mutate 's/if \(state\.layerCollision\) \{\n            return;/if (false) {\n            return;/' $ENG/Stacking.java
run_case "recompute runs even while Layer Collision is on"

# --- collision ---------------------------------------------------------------------
mutate 's/if \(restingOnItem \|\| itemRestingOnOther\) \{/if (false \&\& (restingOnItem || itemRestingOnOther)) {/' $ENG/Collision.java
run_case "zero-gap stacks treated as collisions"
mutate 's/if \(currentRect\.overlaps\(o\)\) \{\n                return new Point\(cx, cy\);/if (false) {\n                return new Point(cx, cy);/' $ENG/Collision.java
run_case "no early return when already overlapping"
mutate 's/double ry = slideAxis\(item\.y_px, cy, lengthPx, rx, widthPx/double ry = slideAxis(item.y_px, cy, lengthPx, item.x_px, widthPx/' $ENG/Collision.java
run_case "the Y slide uses the pre-slide X (the original's known bug)"

# --- layering ----------------------------------------------------------------------
mutate 's/return item\.baseHeight_in < state\.layerFeet \* 12;/return item.baseHeight_in <= state.layerFeet * 12;/' $ENG/Layers.java
run_case "layer slider visibility uses <= instead of <"
# These two replaced the old "ignores Layer Collision" cases on 2026-07-29, when divergence D-5
# removed that branch entirely. The old patterns matched nothing and so reported a false
# SURVIVED — exactly the trap this script's own header warns about. Both replacements were
# checked to actually apply, and to fail the suite.
mutate 's/int byHeight = Double\.compare\(a\.baseHeight_in, b\.baseHeight_in\);/int byHeight = 0;/' $ENG/Layers.java
run_case "paint order ignores height and falls back to dragOrder (the D-5 bug)"
mutate 's/return comparePaint\(item, other\) > 0;/return item.dragOrder > other.dragOrder;/' $ENG/Layers.java
run_case "dim rule uses dragOrder instead of the paint-order rule"

# --- placement ---------------------------------------------------------------------
mutate 's/candidates\.sort\(Comparator\.comparingDouble\(c -> squaredDistance\(c, startX, startY\)\)\);//' $ENG/Placement.java
run_case "findOpenSpot does not sort candidates by distance"
mutate 's/double step = Math\.max\(MIN_STEP_PX, Math\.min\(widthPx, lengthPx\)\);/double step = MIN_STEP_PX;/' $ENG/Placement.java
run_case "findOpenSpot step size ignores item size"

# --- text formats (M3) --------------------------------------------------------------
mutate 's/\+ "  " \+ dimension\(metric, item\.w_in\)/+ " " + dimension(metric, item.w_in)/' $ENG/TextFormat.java
run_case "tooltip uses one space after the name instead of two"
mutate 's/" - Base: "/" - base: "/' $ENG/TextFormat.java
run_case "export writes a lower-case base:, like the tooltip"
mutate 's/return BigDecimal\.valueOf\(value\)\.stripTrailingZeros\(\)\.toPlainString\(\);/return String.valueOf(value);/' $ENG/TextFormat.java
run_case "numbers print Java-style (12.0) instead of JavaScript-style (12)"
mutate 's/collator\.setStrength\(Collator\.PRIMARY\);/collator.setStrength(Collator.TERTIARY);/' $ENG/TextFormat.java
run_case "name ordering becomes case-sensitive"
mutate 's/\? new java\.math\.BigInteger\(l\)\.compareTo\(new java\.math\.BigInteger\(r\)\)/? collator.compare(l, r)/' $ENG/TextFormat.java
run_case "numbers in names sort as text, so item #10 comes before item #2"
mutate 's/text\.append\(exportLine\(state, item\)\)\.append\(.\\n.\);/text.append(exportLine(state, item));/' $ENG/TextFormat.java
run_case "export runs every line together with no newline"

# --- search (M3) ---------------------------------------------------------------------
mutate 's/private static final double EPSILON_IN = 0\.01;/private static final double EPSILON_IN = 0;/' $ENG/Search.java
run_case "measurement search demands an exact match, breaking metric"
mutate 's/String trimmed = query\.trim\(\)\.toLowerCase\(Locale\.ROOT\);/String trimmed = query.trim();/' $ENG/Search.java
run_case "search becomes case-sensitive"
mutate 's/double wanted = state\.metricMode \? Units\.cmToIn\(typed\) : typed;/double wanted = typed;/' $ENG/Search.java
run_case "a measurement word is read as inches even in metric"
# Anchoring is enforced twice over — by the ^...$ in the pattern AND by matches(), which
# anchors implicitly in Java. Either alone is unobservable, so breaking only one produces a
# meaningless survivor. This breaks the rule, not one of its two guards.
mutate 's/Pattern\.compile\("\^/Pattern.compile("/; s/\?\)\$"\)/?)")/; s/if \(measurement\.matches\(\)\) \{/if (measurement.find()) {/' $ENG/Search.java
run_case "measurement matching is no longer anchored, so a name like w20x becomes a filter"

# --- item operations (M3) ------------------------------------------------------------
mutate 's/COLOR_SATURATION_PERCENT = 55;/COLOR_SATURATION_PERCENT = RANDOM.nextInt(100);/' $ENG/Items.java
run_case "box colours randomise saturation as well as hue"
mutate 's/if \(item\.planned\) \{\n            item\.x_px = centred/if (false) {\n            item.x_px = centred/' $ENG/Items.java
run_case "planned ghosts hunt for an open spot they will never be drawn in"
mutate 's/if \(!changed\) \{\n            return false;\n        \}//' $ENG/Items.java
run_case "an edit that changed nothing still records an undo entry"
mutate 's/double centreX = item\.x_px \+ Units\.inchesToPx\(item\.w_in\) \/ 2;\n        double centreY = item\.y_px \+ Units\.inchesToPx\(item\.l_in\) \/ 2;/double centreX = item.x_px + Units.inchesToPx(w_in) \/ 2;\n        double centreY = item.y_px + Units.inchesToPx(l_in) \/ 2;/' $ENG/Items.java
run_case "a resized box keeps its corner instead of its middle"
mutate 's/item\.w_in = Units\.clampDimension\(w_in\);/item.w_in = w_in;/' $ENG/Items.java
run_case "a new box's width is not held to the legal range"

# --- where the autosave lives (M4) ---------------------------------------------------
mutate 's/String appData = env\.apply\("APPDATA"\);/String appData = null;/' $PER/AppDataDir.java
run_case "Windows ignores %APPDATA% and always uses the default location"
mutate 's/if \(candidate\.isAbsolute\(\)\) \{/if (true) {/' $PER/AppDataDir.java
run_case "a relative XDG_DATA_HOME is honoured, putting data in the working directory"
mutate 's/if \(os\.contains\("mac"\)\) \{/if (false) {/' $PER/AppDataDir.java
run_case "macOS falls through to the Linux layout"
mutate 's/return notBlank\(appData\)/return (appData != null)/' $PER/AppDataDir.java
run_case "a blank %APPDATA% is treated as set, resolving to the working directory"

# --- the autosave file itself (M4) ---------------------------------------------------
#
# The temporary file's DIRECTORY is the rule being broken here, not its existence. A rename is
# atomic only within one filesystem, so putting the temporary file in the system temp directory
# silently turns the rename into a copy — and a copy has exactly the half-written window the
# whole design exists to close. Nothing about the finished file would look different.
mutate 's/Path temp = Files\.createTempFile\(dir, "autosave", "\.tmp"\);/Path temp = Files.createTempFile("autosave", ".tmp");/' $PER/Autosave.java
run_case "the temporary file goes to the system temp directory, so the rename can cross filesystems"
mutate 's/backups\.sort\(Comparator\.comparing\(p -> p\.getFileName\(\)\.toString\(\)\)\);/backups.sort(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed());/' $PER/Autosave.java
run_case "rotation keeps the five OLDEST backups and deletes the newest"
mutate 's/for \(int i = 0; i < backups\.size\(\) - BACKUP_COUNT; i\+\+\) \{/for (int i = 0; i < backups.size() - 50; i++) {/' $PER/Autosave.java
run_case "rotation never trims, so backups grow without limit"
mutate 's/&& BACKUP_NAME\.matcher\(entry\.getFileName\(\)\.toString\(\)\)\.matches\(\)//' $PER/Autosave.java
run_case "rotation treats every file in the folder as its own, and deletes strangers"
mutate 's/for \(int n = 2; Files\.exists\(candidate\); n\+\+\) \{/for (int n = 2; false; n++) {/' $PER/Autosave.java
run_case "two sessions in the same second overwrite each other's backup"
mutate 's/if \(!result\.isSuccess\(\)\) \{\n            return Restored\.broken\("Autosave " \+ result\.error\(\)\);\n        \}//' $PER/Autosave.java
run_case "a corrupt autosave is reported as a successful restore"
mutate 's/public static final int BACKUP_COUNT = 5;/public static final int BACKUP_COUNT = 3;/' $PER/Autosave.java
run_case "only three backups are kept instead of the five asked for"

# --- when the autosave writes (M4) ---------------------------------------------------
mutate 's/return nowMillis - lastChangeAt >= QUIET_MILLIS\n                \|\| nowMillis - firstChangeAt >= MAX_WAIT_MILLIS;/return nowMillis - lastChangeAt >= QUIET_MILLIS;/' $PER/AutosavePolicy.java
run_case "the ceiling is dropped, so a long unbroken drag is never written"
mutate 's/return nowMillis - lastChangeAt >= QUIET_MILLIS\n                \|\| nowMillis - firstChangeAt >= MAX_WAIT_MILLIS;/return nowMillis - firstChangeAt >= MAX_WAIT_MILLIS;/' $PER/AutosavePolicy.java
run_case "the quiet period is dropped, so nothing is written for a full minute"
mutate 's/if \(!dirty\) \{\n            dirty = true;\n            firstChangeAt = nowMillis;\n        \}/dirty = true;\n        firstChangeAt = nowMillis;/' $PER/AutosavePolicy.java
run_case "the ceiling restarts on every change, so it can never be reached"
mutate 's/public static final long QUIET_MILLIS = 2_000;/public static final long QUIET_MILLIS = 500;/' $PER/AutosavePolicy.java
run_case "the quiet period is a quarter of what was asked for"
mutate 's/public static final long MAX_WAIT_MILLIS = 60_000;/public static final long MAX_WAIT_MILLIS = 300_000;/' $PER/AutosavePolicy.java
run_case "the ceiling is five minutes instead of the sixty seconds asked for"
mutate 's/return nowMillis - lastChangeAt >= QUIET_MILLIS/return nowMillis - lastChangeAt > QUIET_MILLIS/' $PER/AutosavePolicy.java
run_case "the quiet period boundary is exclusive, so a write lands one tick late"

echo
echo "caught $caught, survived $survived"
[ "$survived" -le 1 ] || echo "NOTE: more survivors than the documented 1 — the suite has new blind spots."
