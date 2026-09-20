#!/bin/bash
#
# Mutation sweep for the 2D engine and the persistence layer.
#
# WHY THIS EXISTS
#
# A passing test suite proves the tests pass, not that they would notice if the code broke.
# During M2 that distinction was not academic: the 250-scenario differential suite was green,
# and deliberately reintroducing the original app's known "the Y slide uses the pre-slide X"
# bug ALSO left it green: random scenarios essentially never line up so that the difference
# is observable. The same was true of relaxing the strict `<` in rectsOverlap.
#
# So this script breaks one documented rule at a time and checks that the suite fails. A
# mutation that SURVIVES is a blind spot: the rule it breaks is not actually being tested.
#
# USAGE
#   bash tools/golden/mutation-sweep.sh          # from the project root
#
# ⚠ RUN IT ON A TREE NOBODY IS TOUCHING. Not in the background while the milestone is still being
# written. Two reasons, and both turn the whole run into a lie rather than into an error:
#   1. A source file that does not compile makes `mvn test` fail, and this script cannot tell a
#      failure caused by its own mutation from a failure caused by somebody's half-finished edit.
#      Every case from that moment on reports "caught" while testing nothing at all, the same
#      shape of false success as a -Dtest= pattern that matches nothing, which is what the baseline
#      guard and the md5 guard already exist to catch. There is no guard for this one.
#   2. It compiles into target/, and so does the editor's own build. Two Maven runs in one tree
#      race each other's class files.
# Started once during M6.4 and killed for exactly this. Finish the milestone, then sweep.
#
# ⚠ AND IF YOU DO KILL IT, CHECK THE TREE. The EXIT trap restores every file, but a `pkill` that
# lands between the mutation and the restore leaves that one mutation sitting in the source. M6.4
# lost half an hour to `double spread = lastSeparation - separation;` left behind by an interrupted
# run: a real test failed with a real number, and the code it named was not the code that had been
# written. `git diff` the mutated packages after any interrupted run.
#
# Runs in about two minutes. It deliberately runs only the headless tests; see the comment
# on SUITE below for why, and for the two guards that stop that shortcut turning into a lie.
#
# Expected result as of M6.7: 210 mutations, 209 caught, 1 survivor, 0 broken patterns. RUN AND
# RECORDED 2026-09-16, not predicted, which is a habit this line has needed more than once: it
# once said 171/170 and had never been executed since the run that produced it was killed.
#
# ⚠ AND THE RUN BEFORE THIS ONE REPORTED 208/2/1, WHICH WAS A LIE IN BOTH DIRECTIONS. A case
# naming `return Math.min(inUse, forMedianMs(medianMs));` had been written against the whole of
# RenderScale.factorFor, and the two-slow-flights rule replaced that body an hour later. The
# pattern then matched nothing and printed SURVIVED, which reads exactly like a real blind spot.
# Only the md5 guard separated them. THE LESSON IS NOT THE ONE ALREADY WRITTEN ABOVE: testing a
# NEW pattern against a copy, which this file demands in capitals, does nothing to protect an OLD
# pattern from your own refactor. A case is a copy of a line of source, and editing that line
# breaks the case in silence. The
# survivor ("recompute skips zeroing heights first") is NOT a gap: that pass is provably
# redundant, because stackInOrder assigns a height to every item anyway. See Stacking.java.
#
# M6.7 added eight cases for the repeating grid tile and the vignette, and the first run of them
# reported SEVEN BROKEN PATTERNS: every one had an unescaped "(" or "?" and matched nothing, so
# each printed "SURVIVED <-- BLIND SPOT" while changing no code at all. The two look identical in
# the output and only the md5 guard separates them. Test a new pattern against a copy of the file
# before trusting a run that contains it.
#
# ⚠ THAT HAPPENED THREE TIMES IN ONE MILESTONE, so it is not a slip, it is the shape of the job:
# these patterns are perl regexes but they are WRITTEN by copying a line of Java, and Java is full
# of ( ) { } | . and /, every one of which means something else to perl. Do not hand-escape. Take
# the line, and escape every one of \ ^ $ . | ? * + ( ) [ ] { } / in it. Then check it applies to
# a copy of the file before running anything:
#
#   cp FILE /tmp/m.java && before=$(md5sum /tmp/m.java)
#   perl -0pi -e 'YOUR PATTERN' /tmp/m.java
#   [ "$before" = "$(md5sum /tmp/m.java)" ] && echo "matched nothing"
#
# M6.3 added the 3D touch cases and the run that added them reported TWO BROKEN PATTERNS, which
# is the md5 guard below doing exactly the job it exists for: `step`'s inline sin and cos had
# moved into shared forwardX/rightX helpers, so two M5.3 cases naming the old expression
# character for character matched nothing at all. Without the guard they would have reported
# "caught" forever. **Re-derive a case when the code it names changes shape. Do not port the
# old text across.**
#
# M4 added the autosave cases. One of them survived on its first run ("Windows ignores
# %APPDATA%") because the test asserted the result merely CONTAINED "AppData/Roaming", which
# the fallback path also does. The test now asserts the whole path. That is what this script
# is for, and it earned its keep the day it was extended.
#
# The autosave rules that live in App.java are NOT here: they need a window, and this script
# runs headless on purpose. They are mutated by hand against AutosaveAppTest; see CLAUDE.md §5.
#
# Extend this whenever the engine grows. A rule that is written down but not mutated here is a
# rule nothing is checking.
#
# The script restores every file it touches, including on failure.

set -u
cd "$(dirname "$0")/../.." || exit 1

ENG=src/main/java/com/modcritic/invmgr/engine
PER=src/main/java/com/modcritic/invmgr/persist
THREED=src/main/java/com/modcritic/invmgr/threed
UI=src/main/java/com/modcritic/invmgr/ui
BK=$(mktemp -d)

# ⚠ THIS EATS ANY EDIT YOU MAKE WHILE THE SWEEP IS RUNNING, AND SAYS NOTHING.
#
# The copy below is taken once, at the start, and covers EVERY .java in engine, persist,
# threed and ui, not just the files a case mutates. restore_all then copies all of them
# back after every case. So an edit to any file in those four packages, made at any point
# during the run, is silently overwritten by a version from before you made it.
#
# There is no warning because there cannot be one: restoring a file it deliberately
# changed is exactly this script's job, and it has no way to tell your edit from its own
# mutation. The loss is invisible afterwards. The build still compiles, the suite still
# passes, the commit still succeeds, and the only trace is that the change you thought you
# made is not in the diff.
#
# Learned at M6.7c on 2026-09-19: a one-paragraph comment fix to ui/TouchType.java went in
# while the sweep was running and vanished, and the commit message claiming it went out
# anyway. The header above already says "NEVER run this while still editing the tree"; it
# said so about a DIFFERENT hazard, a momentarily uncompilable file making every case a
# false "caught". This is the second reason, and it is the quieter one.
#
# Edit docs/, tools/ or native/ while it runs if you must. Not these four packages.
restore_all() {
  cp "$BK"/engine/*.java "$ENG"/ 2>/dev/null
  cp "$BK"/persist/*.java "$PER"/ 2>/dev/null
  cp "$BK"/threed/*.java "$THREED"/ 2>/dev/null
  cp "$BK"/root/Launcher.java src/main/java/com/modcritic/invmgr/ 2>/dev/null
  cp "$BK"/root/Verbose.java src/main/java/com/modcritic/invmgr/ 2>/dev/null
  cp "$BK"/ui/*.java "$UI"/ 2>/dev/null
}
trap 'restore_all; rm -rf "$BK"' EXIT

mkdir -p "$BK"/engine "$BK"/persist "$BK"/threed "$BK"/ui
cp $ENG/*.java "$BK"/engine/
cp $PER/*.java "$BK"/persist/
# Only the top level of threed/ (threed/jfx/ needs a window and is mutated by hand).
cp $THREED/*.java "$BK"/threed/
# Launcher.java sits at the top of the source tree rather than in one of the packages above, and
# ⚠ THE WARNING BELOW IS WHY THIS LINE EXISTS: a file with a case but no backup line is never put
# back. M6.7 shipped exactly that once already, one directory down in threed/jfx/.
mkdir -p "$BK"/root
cp src/main/java/com/modcritic/invmgr/Launcher.java "$BK"/root/
cp src/main/java/com/modcritic/invmgr/Verbose.java "$BK"/root/
# A few files out of ui/, BY NAME rather than by wildcard. These hold no JavaFX and their
# tests need no window, so they belong in the headless sweep; the other fifty files in that
# package do need one, and `cp $UI/*.java` would pull every one of them in. The sweep would then
# take over an hour and get quietly abandoned, which is the failure mode this whole script's
# narrowing exists to avoid.
#
# ⚠ ADD A FILE HERE THE MOMENT YOU ADD A CASE FOR IT. `restore_all` copies back only what was
# backed up, so a file mutated but not listed above is never put back, and then every later case
# runs against broken code and reports "caught" while testing nothing, which is the false-success
# shape this script exists to avoid. M6.4 shipped exactly that: Fluid and TouchType had cases but
# no backup line, so their first mutation stayed in the source for the rest of the run, the next
# pattern found nothing to match, and two mutations were still sitting in the tree when it
# finished. The md5 guard is what surfaced it, by reporting the follow-on patterns as broken.
cp $UI/SystemInsets.java $UI/Device.java $UI/TouchGesture.java \
   $UI/Fluid.java $UI/TouchType.java "$BK"/ui/

caught=0
survived=0
broken=0

# Only the headless tests are run, not the whole suite.
#
# Every mutation below is in engine/, and the engine tests are the ones written to catch
# them, but the full suite now includes ~50 TestFX tests that drive a real pointer, and
# running those 32 times took over an hour. A sweep that slow is a sweep nobody runs, which
# is the same as not having one.
#
# The danger with narrowing a -Dtest= pattern is the one CLAUDE.md §2 names: a pattern that
# matches nothing makes Surefire error, which looks exactly like "the mutation was caught",
# and every case then reports a false success. Two guards below: the baseline must pass, and
# it must report at least MIN_TESTS of them.
# The pure ui classes are named individually, not as com.modcritic.invmgr.ui.*Test: that pattern
# would sweep in every TestFX test in the package and the sweep would take over an hour.
SUITE='com.modcritic.invmgr.LauncherTest,com.modcritic.invmgr.VerboseTest,com.modcritic.invmgr.engine.*Test,com.modcritic.invmgr.model.*Test,com.modcritic.invmgr.persist.*Test,com.modcritic.invmgr.threed.*Test,com.modcritic.invmgr.ui.SystemInsetsTest,com.modcritic.invmgr.ui.DeviceTest,com.modcritic.invmgr.ui.TouchGestureTest,com.modcritic.invmgr.ui.FluidTest,com.modcritic.invmgr.ui.TouchTypeTest'

# A floor, not an exact count, so adding tests does not break the script. Raise it when the
# headless suite grows substantially. 140 at M3; M4's persistence tests took the baseline to
# 185, so the floor moves up with it: a floor far below the real count stops catching the
# thing it exists to catch. M5.1's arithmetic added 45 more, and M5.3's walking and picking
# another 42; the headless baseline is 296 as of M5.3, so this floor sits just under it.
MIN_TESTS=330

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
echo "baseline green: $ran tests."
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

# A mutation that does not APPLY looks exactly like a mutation that survived: the file is
# unchanged, the suite passes, and the line reads "SURVIVED <-- BLIND SPOT". Five of the M5.2
# cases did this on their first run, because perl treats `(` as a capture group and the patterns
# had unescaped parens; one of them was not even valid perl and printed its error into the
# scroll-back where it was easy to miss. So the file is now compared before and after, and a
# no-op is reported as the broken TEST it is rather than as a finding about the code.
#
# This is the same trap CLAUDE.md §2 records for `-Dtest=` patterns that match nothing: check
# that the check can fail.
mutate() {
  local before after
  before=$(md5sum "$2" | cut -d' ' -f1)
  perl -0pi -e "$1" "$2" || { echo "BROKEN PATTERN (perl error) on $2: $1"; broken=$((broken + 1)); }
  after=$(md5sum "$2" | cut -d' ' -f1)
  if [ "$before" = "$after" ]; then
    echo "BROKEN PATTERN (matched nothing) on $2: $1"
    broken=$((broken + 1))
  fi
}

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
# SURVIVED, exactly the trap this script's own header warns about. Both replacements were
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
mutate 's/return new java\.math\.BigInteger\(left\)\.compareTo\(new java\.math\.BigInteger\(right\)\);/return BASE_LETTERS.compare(left, right);/' $ENG/TextFormat.java
run_case "numbers in names sort as text, so item #10 comes before item #2"

# --- the separator rule, D-24 and the bug it fixed --------------------------------
# Added 2026-09-11 with the rule itself. The case above had to be rewritten in the same
# change: it named the local variables `l` and `r`, which this work renamed, so it quietly
# started matching nothing and reported a blind spot that was not there. That is the trap
# the header of this file describes, caught by the md5 check rather than by anyone noticing.
mutate 's/            return -1;\n        \}\n        if \(isSeparator\(right\)\) \{/            return 1;\n        }\n        if (isSeparator(right)) {/' $ENG/TextFormat.java
run_case "a separator sorts after text, so -b comes after a"
mutate "s/token\.charAt\(0\) == ' ' \? 0 : 1/token.charAt(0) == ' ' ? 1 : 0/" $ENG/TextFormat.java
run_case "a hyphen sorts before a space, so a-b comes before a b"
mutate "s/while \(i < text\.length\(\) && text\.charAt\(i\) == ' '\) \{\s*\n\s*i\+\+;\s*\n\s*\}//" $ENG/TextFormat.java
run_case "leading spaces are counted, so ' z' floats above every other name (D-24)"
mutate 's/return BASE_LETTERS\.compare\(left, right\);/return left.compareToIgnoreCase(right);/' $ENG/TextFormat.java
run_case "letters compare by code point, which puts ~ and { on the wrong side of a"
mutate 's/text\.append\(exportLine\(state, item\)\)\.append\(.\\n.\);/text.append(exportLine(state, item));/' $ENG/TextFormat.java
run_case "export runs every line together with no newline"

# --- search (M3) ---------------------------------------------------------------------
mutate 's/private static final double EPSILON_IN = 0\.01;/private static final double EPSILON_IN = 0;/' $ENG/Search.java
run_case "measurement search demands an exact match, breaking metric"
mutate 's/String trimmed = query\.trim\(\)\.toLowerCase\(Locale\.ROOT\);/String trimmed = query.trim();/' $ENG/Search.java
run_case "search becomes case-sensitive"
mutate 's/double wanted = state\.metricMode \? Units\.cmToIn\(typed\) : typed;/double wanted = typed;/' $ENG/Search.java
run_case "a measurement word is read as inches even in metric"
# Anchoring is enforced twice over: by the ^...$ in the pattern AND by matches(), which
# anchors implicitly in Java. Either alone is unobservable, so breaking only one produces a
# meaningless survivor. This breaks the rule, not one of its two guards.
mutate 's/Pattern\.compile\("\^/Pattern.compile("/; s/\?\)\$"\)/?)")/; s/if \(measurement\.matches\(\)\) \{/if (measurement.find()) {/' $ENG/Search.java
run_case "measurement matching is no longer anchored, so a name like w20x becomes a filter"

# --- item operations (M3) ------------------------------------------------------------
mutate 's/COLOR_SATURATION_PERCENT = 55;/COLOR_SATURATION_PERCENT = RANDOM.nextInt(100);/' $ENG/Items.java
run_case "box colors randomize saturation as well as hue"
mutate 's/if \(item\.planned\) \{\n            item\.x_px = centered/if (false) {\n            item.x_px = centered/' $ENG/Items.java
run_case "planned ghosts hunt for an open spot they will never be drawn in"
mutate 's/if \(!changed\) \{\n            return false;\n        \}//' $ENG/Items.java
run_case "an edit that changed nothing still records an undo entry"
mutate 's/double centerX = item\.x_px \+ Units\.inchesToPx\(item\.w_in\) \/ 2;\n        double centerY = item\.y_px \+ Units\.inchesToPx\(item\.l_in\) \/ 2;/double centerX = item.x_px + Units.inchesToPx(w_in) \/ 2;\n        double centerY = item.y_px + Units.inchesToPx(l_in) \/ 2;/' $ENG/Items.java
run_case "a resized box keeps its corner instead of its middle"
mutate 's/item\.w_in = Units\.clampDimension\(w_in\);/item.w_in = w_in;/' $ENG/Items.java
run_case "a new box's width is not held to the legal range"

# --- where the autosave lives (M4) ---------------------------------------------------
mutate 's/String appData = env\.apply\("APPDATA"\);/String appData = null;/' $PER/AppDataDir.java
run_case "Windows ignores %APPDATA% and always uses the default location"
mutate 's/if \(candidate\.isAbsolute\(\)\) \{/if (true) {/' $PER/AppDataDir.java
run_case "a relative XDG_DATA_HOME is honored, putting data in the working directory"
mutate 's/if \(os\.contains\("mac"\)\) \{/if (false) {/' $PER/AppDataDir.java
run_case "macOS falls through to the Linux layout"
mutate 's/return notBlank\(appData\)/return (appData != null)/' $PER/AppDataDir.java
run_case "a blank %APPDATA% is treated as set, resolving to the working directory"

# --- the autosave file itself (M4) ---------------------------------------------------
#
# The temporary file's DIRECTORY is the rule being broken here, not its existence. A rename is
# atomic only within one filesystem, so putting the temporary file in the system temp directory
# silently turns the rename into a copy, and a copy has exactly the half-written window the
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

# --- the 3D view's axes, winding and textures (M5.1) ---------------------------------
#
# Every case here is arithmetic with no JavaFX in it, which is the whole reason threed/ was
# split from threed/jfx/. The three rules that live in threed/jfx/ (releasing the SubScene's
# root and camera, and the camera's own angles) need a window and are mutated by hand
# against Room3dAppearanceTest, the same way App.java's autosave rules are.
#
# The first four exist because the shipped M5.1 build had the room reflected east-to-west and
# every test in the project agreed with it. Negating y alone is a MIRROR; negating y and z
# together is a rotation. Nothing here could tell the difference, because every test aimed the
# camera straight at the thing it was checking, and a mirror leaves the center of the screen
# exactly where it was.
mutate 's/points\[at\+\+\] = \(float\) -\(face\.oz/points[at++] = (float) (face.oz/' $THREED/BoxGeometry.java
run_case "a box's z is not negated, so the room is mirrored east-to-west"
mutate 's/points\[at\+\+\] = \(float\) -\(face\.oy/points[at++] = (float) (face.oy/' $THREED/BoxGeometry.java
run_case "a box's y is not negated, so it hangs below the floor"
mutate 's/corners\[i \* 3 \+ 2\] = \(float\) -spec\[from \+ 2\];/corners[i * 3 + 2] = (float) spec[from + 2];/' $THREED/RoomGeometry.java
run_case "the room's z is not negated, so the walls are mirrored"
mutate 's/corners\[i \* 3 \+ 1\] = \(float\) -spec\[from \+ 1\];/corners[i * 3 + 1] = (float) spec[from + 1];/' $THREED/RoomGeometry.java
run_case "the room's y is not negated, so the walls go down instead of up"

mutate 's/at = triangle\(out, at, p0, p1, p2, block\);\n                at = triangle\(out, at, p0, p2, p3, block\);/at = triangle(out, at, p0, p2, p1, block);\n                at = triangle(out, at, p0, p3, p2, block);/' $THREED/BoxGeometry.java
run_case "a box's triangles are wound the other way, so it is inside out"
mutate 's/int from = i \* 3;/int from = (3 - i) * 3;/' $THREED/RoomGeometry.java
run_case "the room's corners are reversed, so every wall faces outward"
mutate 's/surface\("room-floor", w, l, false,\n                    0, 0, l,\n                    w, 0, l,\n                    w, 0, 0,\n                    0, 0, 0\)/surface("room-floor", w, l, false,\n                    0, 0, 0,\n                    0, 0, l,\n                    w, 0, l,\n                    w, 0, 0)/' $THREED/RoomGeometry.java
run_case "the floor's texture is laid on sideways, so its squares are not square"

# --- the face shading (M5.1) ---------------------------------------------------------
mutate 's/TOP = 1\.22;/TOP = 1.25;/'             $THREED/Shades.java; run_case "the top face's multiplier drifts"
mutate 's/EAST_WEST = 0\.80;/EAST_WEST = 0.85;/' $THREED/Shades.java; run_case "the side faces' multiplier drifts"
mutate 's/BOTTOM = 0\.55;/BOTTOM = 0.50;/'       $THREED/Shades.java; run_case "the bottom face's multiplier drifts"
mutate 's/EDGE = 0\.45;/EDGE = 0.40;/'           $THREED/Shades.java; run_case "the edge frame's multiplier drifts"
mutate 's/long rounded = Math\.round\(lightness \* multiplier\);/long rounded = (long) (lightness * multiplier);/' $THREED/Shades.java
run_case "lightness is truncated instead of rounded"

# --- the wall and floor vignette (M5.1, and what M6.7 left of it) ----------------------
mutate 's/MAX_TEXTURE_PX = 512;/MAX_TEXTURE_PX = 2048;/' $THREED/SurfaceMetrics.java
run_case "the vignette cap goes back to the original's 2048"
mutate 's/VIGNETTE_RADIUS_FRACTION = 0\.7;/VIGNETTE_RADIUS_FRACTION = 0.5;/' $THREED/SurfaceMetrics.java
run_case "the surface vignette closes in"
mutate 's/int h = \(int\) Math\.max\(2, Math\.round\(heightFt \* s\)\);/int h = (int) Math.max(2, Math.round(widthFt * s));/' $THREED/SurfaceMetrics.java
run_case "the vignette picture stops keeping the wall's proportions, so its circle goes oval"

# --- the grid tile that replaced the painted-on grid (M6.7) ---------------------------
#
# The blur these fix was the user's own report: a 200 ft room's floor read as a soft smear while
# the boxes on it stayed sharp. Every case here is invisible in a small room, which is the same
# trap the M5.2b cases below sit in, so they use big rooms and fractional ones on purpose.
mutate 's/LINE_WIDTH_PX = 2;/LINE_WIDTH_PX = 3;/' $THREED/GridTile.java
run_case "a grid line is half as heavy again as the flat view draws it"
mutate 's/double stepFt = metric \? 1 \/ Units\.M_PER_FT : 1;/double stepFt = 1;/' $THREED/GridTile.java
run_case "metric mode stops moving the grid to one meter"
mutate 's/return new GridTile\(stepFt, \(int\) Math\.round\(stepFt \* PX_PER_FOOT\)\);/return new GridTile(stepFt, (int) Math.round(stepFt * PX_PER_FOOT \/ 2));/' $THREED/GridTile.java
run_case "the tile is drawn at half the app's own resolution, so the blur comes back"
mutate 's/        if \(!mirroredGridX\) \{/        if (true) {/' $THREED/GridTile.java
run_case "the mirrored walls count their grid from their own edge, so it lands on half feet"
mutate 's/        return lengthFt \/ stepFt;/        return lengthFt;/' $THREED/GridTile.java
run_case "the tile is laid down once per foot regardless of the grid spacing"

# --- the lens (M5.1) -------------------------------------------------------------------
mutate 's/OVERHEAD_MARGIN = 1\.06;/OVERHEAD_MARGIN = 1.0;/' $THREED/Perspective.java
run_case "the overhead view stops backing off by 6%"
mutate 's/return Math\.round\(Math\.max\(MIN_PERSPECTIVE_PX, viewportHeightPx \* PERSPECTIVE_RATIO\)\);/return Math.max(MIN_PERSPECTIVE_PX, viewportHeightPx * PERSPECTIVE_RATIO);/' $THREED/Perspective.java
run_case "the perspective distance is not rounded to a whole pixel"

# --- where you may stand (M5.1) --------------------------------------------------------
mutate 's/PITCH_LIMIT_RAD = 1\.55;/PITCH_LIMIT_RAD = 1.5707963267948966;/' $THREED/CameraPose.java
run_case "the pitch limit reaches straight up, where yaw stops meaning anything"
mutate 's/ROOM_SLACK_FT = 60;/ROOM_SLACK_FT = 0;/' $THREED/CameraPose.java
run_case "you can no longer walk out through a wall and look back"

# --- the way in and the way out (M5.2) --------------------------------------------------
#
# The first case here is the one that matters most in the whole 3D milestone. Giving pitch the
# same curve as height is the mistake SPEC-3D-VIEW.md §3 says the original made and had to
# unlearn, it is invisible in a screenshot, and every other 3D test stays green through it.
mutate 's/return new CameraTween\(overhead, landed, Map\.of\(CameraTween\.Channel\.PITCH, Easing\.IN_CUBIC\)\);/return new CameraTween(overhead, landed);/' $THREED/Transitions.java
run_case "the descent gives pitch the same curve as height, so it reads front-loaded"
mutate 's/CameraTween\.Channel\.PITCH, Easing\.OUT_CUBIC,/CameraTween.Channel.PITCH, Easing.IN_CUBIC,/' $THREED/Transitions.java
run_case "the ascent's pitch eases in rather than out, so orientation lags instead of leading"
mutate 's/CameraTween\.Channel\.YAW, Easing\.OUT_CUBIC\)\);/CameraTween.Channel.YAW, Easing.IN_CUBIC));/' $THREED/Transitions.java
run_case "the ascent's yaw eases in rather than out"
mutate 's/start\.yaw = CameraPose\.normYaw\(start\.yaw\);//' $THREED/Transitions.java
run_case "the exit stops normalizing yaw, so the camera unwinds every turn on the way out"
mutate 's/DESCENT_MS = 2230;/DESCENT_MS = 2000;/' $THREED/Transitions.java
run_case "the descent's duration drifts"
mutate 's/ASCENT_MS = 1920;/ASCENT_MS = 2230;/' $THREED/Transitions.java
run_case "the ascent takes as long as the descent instead of being quicker"
mutate 's/PANEL_SLIDE_MS = 220;/PANEL_SLIDE_MS = 350;/' $THREED/Transitions.java
run_case "the side panels slide at the bars' speed, which they never did"
mutate 's/BARS_LEAVE_LAYOUT_MS = 370;/BARS_LEAVE_LAYOUT_MS = 200;/' $THREED/Transitions.java
run_case "the bars leave the layout mid-slide, so the room jumps outward behind them"
mutate 's/SLIDE_FRACTION = 1\.05;/SLIDE_FRACTION = 1.0;/' $THREED/Transitions.java
run_case "the chrome slides exactly its own size, leaving its border on the edge"
mutate 's/ASCENT_FADE_FROM = 0\.7;/ASCENT_FADE_FROM = 0.5;/' $THREED/Transitions.java
run_case "the picture starts fading out halfway up instead of over the last third"
mutate 's/double p = Math\.max\(0, Math\.min\(1, progress\)\);/double p = progress;/' $THREED/CameraTween.java
run_case "a tween stops clamping, so a late frame overshoots the pose it was flying to"
mutate 's/IN_CUBIC = Easing::inCubic/IN_CUBIC = Easing::outCubic/' $THREED/Easing.java
run_case "the two cubic curves are swapped"
mutate 's/return u \* u \* u;/return u * u;/' $THREED/Easing.java
run_case "the in-cubic curve becomes a square"
mutate 's/return -\(Math\.cos\(Math\.PI \* u\) - 1\) \/ 2;/return u;/' $THREED/Easing.java
run_case "the default curve becomes a straight line, so nothing eases at all"

# --- the grid on a room that is not a whole number of feet across (M5.2b) ----------------
#
# Reported by the user 2026-08-05, and the same bug the original HTML app had and fixed with
# `flipGridX`. A whole-foot room is unaffected by every one of these, which is exactly why it
# went unnoticed for so long: the sweep's cases have to use fractional dimensions to see it.
mutate 's/        double fraction = -span\(widthFt\) % 1;/        double fraction = span(widthFt) % 1;/' $THREED/GridTile.java
run_case "the mirrored walls count their leftover the wrong way round"
mutate 's/surface\("room-south", w, h, true,/surface("room-south", w, h, false,/' $THREED/RoomGeometry.java
run_case "the south wall is not marked as mirrored"
mutate 's/surface\("room-east", l, h, true,/surface("room-east", l, h, false,/' $THREED/RoomGeometry.java
run_case "the east wall is not marked as mirrored"

# --- the verbose logging flag, and the flight clock it prints (M6.7 follow-up) ---------
#
# This exists so that a bug report from somebody else's machine arrives with numbers in it. Every
# case here is a way for that to fail QUIETLY, which is the only way it can fail: a log that says
# nothing looks exactly like a program with nothing to say.
#
# There was an eighth case here, deleting `|| value.isBlank()` from the null check, and it
# SURVIVED. Not a blind spot: that half of the condition genuinely did nothing, because every
# blank form reaches the loop as parts that trim to empty and get skipped. The code is gone and so
# is the case. That is the second time this script has found dead code rather than a missing test;
# see flatMaterial in JfxRenderer3D for the first.
mutate 's/                unknown\.add\(trimmed\);/                ;/' src/main/java/com/modcritic/invmgr/Verbose.java
run_case "an unknown topic is ignored in silence, so a typo looks like a quiet app"
mutate 's/                wanted\.addAll\(EnumSet\.allOf\(Topic\.class\)\);/                ;/' src/main/java/com/modcritic/invmgr/Verbose.java
run_case "the word all stops turning anything on"
mutate 's/            String trimmed = word\.trim\(\)\.toLowerCase\(Locale\.ROOT\);/            String trimmed = word.trim();/' src/main/java/com/modcritic/invmgr/Verbose.java
run_case "the topic word becomes case sensitive, so -Dinvmgr.verbose=3D says nothing"
mutate 's/        if \(previousNs != 0 && count < ROOM\) \{/        if (count < ROOM) {/' src/main/java/com/modcritic/invmgr/threed/FlightClock.java
run_case "the first stamp counts as a frame, putting one meaningless number in every median"
mutate 's/                what, count, 1000 \/ medianMs, medianMs,/                what, count, 1000 \/ (sorted[(int) (count * 0.9)] \/ 1e6), medianMs,/' src/main/java/com/modcritic/invmgr/threed/FlightClock.java
run_case "the rate is worked out from the 90th rather than the median, so jitter reads as slowness"
mutate 's/        return sorted\[count \/ 2\] \/ 1e6;/        return (double) java.util.Arrays.stream(gapsNs, 0, count).sum() \/ count \/ 1e6;/' src/main/java/com/modcritic/invmgr/threed/FlightClock.java
run_case "the median becomes a mean, so one 150 ms hitch describes the whole flight"
mutate 's/        if \(previousNs != 0 && count < ROOM\) \{/        if (previousNs != 0) {/' src/main/java/com/modcritic/invmgr/threed/FlightClock.java
run_case "the flight clock will grow without limit on a slow enough machine"

# --- drawing the room at fewer pixels (M6.7 item 6) -----------------------------------
#
# RenderScale is the decision and has no JavaFX in it, so it sweeps here. ScaledSurface, which
# does the drawing, needs a window and is mutated by hand against ScaledSurfaceTest, the same
# arrangement threed/jfx/ already uses.
#
# Every pattern below was tested against a copy before this block was committed, which the
# header demands in capitals and which this milestone has now needed four times.

mutate 's/HALVE_ABOVE_MS = 1000\.0 \/ 30;/HALVE_ABOVE_MS = 1000.0 \/ 10;/' $THREED/RenderScale.java
run_case "the halve threshold drifts to 100 ms, so a machine at 60 a second reads as struggling"
mutate 's/QUARTER_ABOVE_MS = 1000\.0 \/ 15;/QUARTER_ABOVE_MS = 1000.0 \/ 5;/' $THREED/RenderScale.java
run_case "the quarter threshold drifts to 200 ms, so nothing real ever reaches it"
# ⚠ THIS CASE WAS BROKEN FOR ONE RUN AND REPORTED "SURVIVED" WHILE TESTING NOTHING. It named
# `return Math.min(inUse, forMedianMs(medianMs));`, which was the whole of factorFor until the
# two-slow-flights rule replaced that body, and a pattern naming source text character for
# character is exactly what an edit breaks in silence. The md5 guard is the only thing that told
# the two apart. Re-verified against a copy.
mutate 's/            slowFlightsInARow = 0;\n            return inUse;/            slowFlightsInARow = 0;\n            return wanted;/' $THREED/RenderScale.java
run_case "auto is allowed back up again, so the room changes sharpness on every entry"
mutate 's/        if \(asked < SMALLEST \|\| asked > FULL\) \{/        if (false) {/' $THREED/RenderScale.java
run_case "a factor outside the range is accepted in silence"
mutate 's/        return auto \? FULL : pinned;/        return FULL;/' $THREED/RenderScale.java
run_case "a pinned fraction is ignored until something measures a flight"
mutate 's/            complain\.accept\("\\"" \+ value \+ "\\" is not " \+ OFF \+ ", " \+ AUTO/            if (false) complain.accept("\\"" + value + "\\" is not " + OFF + ", " + AUTO/' $THREED/RenderScale.java
run_case "a value that means nothing is ignored without saying so"
mutate 's/        if \(value == null\) \{\n            return new RenderScale\(true, FULL\);/        if (value == null) {\n            return new RenderScale(false, FULL);/' $THREED/RenderScale.java
run_case "the default goes back to off, so nobody gets the reduction without typing a flag"
mutate 's/    static final int SLOW_FLIGHTS_NEEDED = 2;/    static final int SLOW_FLIGHTS_NEEDED = 1;/' $THREED/RenderScale.java
run_case "one slow flight is enough, so a passing stall costs the rest of the session"
mutate 's/        if \(slowFlightsInARow < SLOW_FLIGHTS_NEEDED\) \{\n            return inUse;\n        \}\n//' $THREED/RenderScale.java
run_case "the run of slow flights is counted and then never looked at"
mutate 's/            slowFlightsInARow = 0;\n            return inUse;/            return inUse;/' $THREED/RenderScale.java
run_case "a healthy flight does not break the run, so two slow ones an hour apart still count"

# --- asking for the graphics card, and the way out of it (M6.7 follow-up) --------------
#
# One line in Launcher decides whether `java -jar` gets a 3D view at all on a machine whose
# driver Prism refuses. Every case here is invisible on a machine with a graphics card Prism
# already likes, which is every machine the tests run on, so they are mutations of the DECISION
# rather than of the outcome.
mutate 's/        if \(android \|\| alreadySet != null\) \{/        if (android) {/' src/main/java/com/modcritic/invmgr/Launcher.java
run_case "an explicit -Dprism.forceGPU on the command line is overruled by the default"
mutate 's/        return !"false"\.equalsIgnoreCase\(override\);/        return true;/' src/main/java/com/modcritic/invmgr/Launcher.java
run_case "the opt-out stops working, so a machine it breaks has no way back"
mutate 's/        return !"false"\.equalsIgnoreCase\(override\);/        return override == null;/' src/main/java/com/modcritic/invmgr/Launcher.java
run_case "any value at all opts out, so a typo silently loses the 3D view"
mutate 's/        if \(android \|\| alreadySet != null\) \{/        if (alreadySet != null) {/' src/main/java/com/modcritic/invmgr/Launcher.java
run_case "a phone gets the graphics card forced on it too"
mutate 's/PRISM_FORCE_GPU = "prism\.forceGPU";/PRISM_FORCE_GPU = "prism.forcegpu";/' src/main/java/com/modcritic/invmgr/Launcher.java
run_case "the Prism property is misspelled, which nothing at runtime would report"

# --- the vignette floating off the grid it darkens (M6.7) ------------------------------
#
# Two quads in exactly the same plane fight over the depth buffer; a vignette lifted the WRONG
# way ends up behind its own wall, where the room simply stops being darkened at the edges and
# every pixel test that samples the middle of a wall stays green.
mutate 's/\+ nz \* \(target\[2\] - corners\[2\]\) < 0\) \{/+ nz * (target[2] - corners[2]) > 0) {/' $THREED/RoomGeometry.java
run_case "the vignette is lifted out through the wall instead of into the room"
#
# The matching case for JfxRenderer3D's LAYER_LIFT_FT is NOT here, and it was here for exactly
# one run, which is how this warning got written. threed/jfx/ is not backed up above, so the
# mutation was never put back: it sat in the source for the rest of the run and was still there
# when the run finished. The md5 guard surfaced it on the NEXT run, as the same pattern suddenly
# matching nothing. Same trap as M6.4's Fluid and TouchType, one directory further down.
#
# It belongs with the by-hand rules (see SUITE below): setting LAYER_LIFT_FT to 0 fails
# Room3dAppearanceTest.everythingThatShouldBeThereIs, which needs a window.
#
# Two more by-hand rules live in threed/jfx/ for the same reason, and the SUITE pattern above
# genuinely does not reach that package (checked, not assumed: `-Dtest=...threed.*Test` runs
# fourteen classes and none of them is in jfx). Lowering MAX_TEXTURE_PX below 512 fails
# SurfaceTextureTest.theSmallerVignetteIsTheSamePicture, and painting the tile's grid line at one
# edge instead of half at each fails theTileIsBuiltForRepeating.
mutate 's/surface\("room-north", w, h, false,/surface("room-north", w, h, true,/' $THREED/RoomGeometry.java
run_case "the WRONG wall is mirrored, moving the mismatch to the opposite corner"

# --- walking and looking (M5.3) ---------------------------------------------------------
#
# Every case here is about DIRECTION as much as distance. A test that walks nine feet and finds
# it walked nine feet cannot see a sign error: §5.7 item 2's lesson in an M5.3 costume. Two of
# these were genuine blind spots when first run: reversing WalkInput's strafe and vertical axes
# changed nothing any test could see, because the only cases touching them held both keys at
# once, and a canceled pair cancels whichever way round the axis is.
# ⚠ These two were rewritten at M6.3 and the rewrite is the lesson. `step` used to compute
# sin and cos of the yaw inline; M6.3 moved them into forwardX/forwardZ/rightX/rightZ so that
# `pan` and `dolly` could read the same copy, and the two patterns below (which named the old
# expression character for character) silently stopped matching anything. The sweep reported
# them as BROKEN PATTERN, which is exactly what that guard exists for: without it they would
# have read "caught" forever while testing nothing at all. Re-derive a case when the code it
# names changes shape; do not port the old text across.
mutate 's/cam\.z \+= forwardZ\(cam\.yaw\) \* fwd \* sp/cam.z += -forwardZ(cam.yaw) * fwd * sp/' $THREED/Walk.java
run_case "forward walks backward"
mutate 's/cam\.x \+= forwardX\(cam\.yaw\) \* fwd \* sp \+ rightX\(cam\.yaw\) \* str \* sp;/cam.x += rightX(cam.yaw) * fwd * sp + forwardX(cam.yaw) * str * sp;/' $THREED/Walk.java
run_case "forward and sideways are swapped, so walking ignores where you are looking"
mutate 's/cam\.y \+= vert \* sp;/cam.y += vert * sp * Math.cos(cam.pitch);/' $THREED/Walk.java
run_case "up follows the nose instead of the world, so looking down stops you rising"
mutate 's/        cam\.clampPosition\(room\);//' $THREED/Walk.java
run_case "walking is never clamped, so you can leave the world entirely"
mutate 's/MOVE_SPEED_FT_PER_S = 9/MOVE_SPEED_FT_PER_S = 12/' $THREED/Walk.java
run_case "the walking speed is not the original's nine feet a second"
mutate 's/BOOST_MULT = 2/BOOST_MULT = 3/' $THREED/Walk.java
run_case "Shift is not exactly double"
mutate 's/\(boost \? BOOST_MULT : 1\)/BOOST_MULT/' $THREED/Walk.java
run_case "everything moves at boosted speed whether Shift is held or not"
mutate 's/MAX_FRAME_SECONDS = 0\.05/MAX_FRAME_SECONDS = 1.0/' $THREED/Walk.java
run_case "a stuttered frame is believed, so a hitch teleports you across the room"
mutate 's/        if \(lastNanos == 0\) \{\n            return 0;\n        \}//' $THREED/Walk.java
run_case "the first frame of a run is handed the time since the machine booted"
mutate 's/        if \(seconds < 0\) \{\n            return 0;\n        \}//' $THREED/Walk.java
run_case "a clock that goes backwards walks the camera backwards"
mutate 's/cam\.pitch -= dyPx \* sensitivity;/cam.pitch += dyPx * sensitivity;/' $THREED/Walk.java
run_case "the mouse look is not inverted, so dragging down looks up"
mutate 's/cam\.yaw \+= dxPx \* sensitivity;/cam.yaw -= dxPx * sensitivity;/' $THREED/Walk.java
run_case "dragging right turns left"
mutate 's/        cam\.clampPitch\(\);//' $THREED/Walk.java
run_case "the pitch is never clamped, so looking up far enough turns the view over"
mutate 's/MOUSE_SENS_RAD_PER_PX = 0\.0022/MOUSE_SENS_RAD_PER_PX = 0.0044/' $THREED/Walk.java
run_case "the look sensitivity is twice the original's"
mutate 's/DRAG_LOOK_MULT = 1\.4/DRAG_LOOK_MULT = 1.0/' $THREED/Walk.java
run_case "the drag fallback loses its 1.4x compensation"
mutate 's/double sp = speedFtPerS/double mag = Math.max(1, Math.hypot(fwd, str)); fwd \/= mag; str \/= mag; double sp = speedFtPerS/' $THREED/Walk.java
run_case "diagonals are normalized, which the original does not do"
mutate 's/return axis\(Control\.RIGHT, Control\.LEFT\);/return axis(Control.LEFT, Control.RIGHT);/' $THREED/WalkInput.java
run_case "the strafe axis is reversed, so D goes west"
mutate 's/return axis\(Control\.UP, Control\.DOWN\);/return axis(Control.DOWN, Control.UP);/' $THREED/WalkInput.java
run_case "the vertical axis is reversed, so Space descends"
mutate 's/return axis\(Control\.FORWARD, Control\.BACK\);/return axis(Control.BACK, Control.FORWARD);/' $THREED/WalkInput.java
run_case "the forward axis is reversed"
mutate 's/return pressed \? held\.add\(control\) : held\.remove\(control\);/held.add(control); if (!pressed) held.remove(control); return true;/' $THREED/WalkInput.java
run_case "every auto-repeat reports as a fresh press"
mutate 's/    public void clear\(\) \{\n        held\.clear\(\);/    public void clear() {/' $THREED/WalkInput.java
run_case "forgetting the held keys forgets nothing, so a lost window walks on forever"

# --- what is under the pointer (M5.3) -----------------------------------------------------
#
# The mirror cases are the ones that matter, and the aspect-ratio pair is worth its own note:
# inverting the aspect and dropping it entirely BOTH left every box test green at first, because
# a box is several feet deep and a ray leaving at slightly the wrong angle still lands somewhere
# inside it. Only asking a ray for its angle directly can see that.
mutate 's/double ndcX = \(xPx \/ w\) \* 2 - 1;/double ndcX = 1 - (xPx \/ w) * 2;/' $THREED/Picking.java
run_case "the pick is mirrored east to west, so a box on the right is found on the left"
mutate 's/double ndcY = 1 - \(yPx \/ h\) \* 2;/double ndcY = (yPx \/ h) * 2 - 1;/' $THREED/Picking.java
run_case "the pick is mirrored top to bottom"
mutate 's/double aspect = w \/ h;/double aspect = h \/ w;/' $THREED/Picking.java
run_case "the aspect ratio is upside down"
mutate 's/double sx = ndcX \* aspect \* tanHalfFov;/double sx = ndcX * tanHalfFov;/' $THREED/Picking.java
run_case "the aspect ratio is dropped, so picking is only right down the middle"
mutate 's/Perspective\.verticalFovDegrees\(h\)/Perspective.verticalFovDegrees(w)/' $THREED/Picking.java
run_case "the field of view is measured off the width instead of the height"
mutate 's/double uy = rz \* fx - rx \* fz;/double uy = rx * fz - rz * fx;/' $THREED/Picking.java
run_case "the camera's up vector points down"
mutate 's/        if \(far <= 0\) \{\n            return Double\.POSITIVE_INFINITY;      \/\/ entirely behind the camera\n        \}//' $THREED/Picking.java
run_case "things behind the camera are pickable"
mutate 's/if \(distance < bestDistance\)/if (distance > bestDistance \&\& distance < Double.POSITIVE_INFINITY)/' $THREED/Picking.java
run_case "the FARTHEST box wins, so you point at what is hidden behind something"
mutate 's/return Math\.max\(near, 0\);/return near;/' $THREED/Picking.java
run_case "a box you are standing inside reports a negative distance"
mutate 's/            if \(far < near\) \{\n                return Double\.POSITIVE_INFINITY;\n            \}//' $THREED/Picking.java
run_case "a ray that misses on one axis is counted as a hit anyway"
mutate 's/                if \(origin\[axis\] < low\[axis\] \|\| origin\[axis\] > high\[axis\]\) \{\n                    return Double\.POSITIVE_INFINITY;\n                \}\n                continue;/continue;/' $THREED/Picking.java
run_case "a ray running exactly along an axis becomes a phantom hit"
mutate 's/return new double\[\] \{dx \/ length, dy \/ length, dz \/ length\};/return new double[] {dx, dy, dz};/' $THREED/Picking.java
run_case "rays are not unit vectors, so a hit distance is not in feet"

# ---- how much of the screen the phone keeps (M6.1b) ----
mutate 's/return new SystemInsets\(ANDROID_STATUS_BAR, 0, navBar, 0\);/return new SystemInsets(navBar, 0, navBar, 0);/' $UI/SystemInsets.java
run_case "the status bar is derived from the same subtraction as the navigation bar"
mutate 's/return new SystemInsets\(ANDROID_STATUS_BAR, 0, navBar, 0\);/return new SystemInsets(navBar, 0, ANDROID_STATUS_BAR, 0);/' $UI/SystemInsets.java
run_case "the top and bottom insets are swapped"
mutate 's/boolean believable = measured > 0 && measured <= MAX_PLAUSIBLE_BAR;/boolean believable = measured > 0;/' $UI/SystemInsets.java
run_case "an absurd computed bar height is believed rather than rejected"
mutate 's/boolean believable = measured > 0 && measured <= MAX_PLAUSIBLE_BAR;/boolean believable = measured <= MAX_PLAUSIBLE_BAR;/' $UI/SystemInsets.java
run_case "a negative bar height is believed, pulling the top bar off the screen"
mutate 's/return top == 0 && right == 0 && bottom == 0 && left == 0;/return top == 0 \&\& right == 0 \&\& left == 0;/' $UI/SystemInsets.java
run_case "areNone ignores the bottom edge"
mutate 's/if \("android"\.equalsIgnoreCase\(override\)\) \{\n            return true;\n        \}/if (override != null) {\n            return true;\n        }/' $UI/Device.java
run_case "any override at all forces the phone layout, so a typo switches it on"
mutate 's/return "android"\.equalsIgnoreCase\(javafxPlatform\);/return javafxPlatform != null;/' $UI/Device.java
run_case "every platform reports as a phone"

# ---- what one finger means (M6.2) ----
# TouchGesture landed at M6.2 with 18 tests and no mutations at all, so none of its four rules had
# ever been broken on purpose. These are that debt cleared. It is the only M6.2 file that can be in
# the automated sweep: everything else needs a window, and those are mutated by hand.
mutate 's/if \(Math\.abs\(x - startX\) > DRAG_THRESHOLD_PX \|\| Math\.abs\(y - startY\) > DRAG_THRESHOLD_PX\) \{\n            moved = true;\n        \}/moved = Math.abs(x - startX) > DRAG_THRESHOLD_PX || Math.abs(y - startY) > DRAG_THRESHOLD_PX;/' $UI/TouchGesture.java
run_case "becoming a drag stops being permanent, so a box dragged back opens a dialog"
mutate 's/if \(!down \|\| moved \|\| deleted \|\| nowMs - startMs < HOLD_TO_DELETE_MS\)/if (!down || moved || nowMs - startMs < HOLD_TO_DELETE_MS)/' $UI/TouchGesture.java
run_case "the hold fires every frame after 1500 ms instead of once"
mutate 's/if \(nowMs - startMs >= TAP_MAX_MS\) \{\n            \/\/ The dead band/if (false) {\n            \/\/ The dead band/' $UI/TouchGesture.java
run_case "the dead band becomes a tap, so a canceled delete opens something"
mutate 's/        if \(moved\) \{\n            \/\/ Movement clears any pending double tap: a drag and then a quick tap elsewhere must\n            \/\/ not be read as two taps\.\n            lastTapMs = 0;/        if (moved) {/' $UI/TouchGesture.java
run_case "a drag leaves the pending tap behind, so a tap elsewhere reads as a double"
mutate 's/> DRAG_THRESHOLD_PX/>= DRAG_THRESHOLD_PX/g' $UI/TouchGesture.java
run_case "the drag threshold is off by one"
mutate 's/return Math\.min\(1, \(nowMs - startMs\) \/ HOLD_TO_DELETE_MS\);/return (nowMs - startMs) \/ HOLD_TO_DELETE_MS;/' $UI/TouchGesture.java
run_case "the ring keeps brightening past full"
mutate 's/return Math\.min\(1, \(nowMs - startMs\) \/ HOLD_TO_DELETE_MS\);/return Math.min(1, (nowMs - startMs) \/ TAP_MAX_MS);/' $UI/TouchGesture.java
run_case "the ring fills in 600 ms and then sits full for nearly a second"
mutate 's/        if \(!isHolding\(\)\) \{\n            return 0;\n        \}\n        return Math\.min/        return Math.min/' $UI/TouchGesture.java
run_case "the ring goes on filling after the finger has moved or lifted"

# ---- fingers in the 3D room (M6.3) ----
#
# The pure half only. The wiring (which finger is the joystick's, whether Android's synthesized
# mouse gets past the guards, where the stick sits above the navigation bar) needs a window and
# is mutated by hand against ThreeDTouchTest, the same arrangement M6.2 used for the flat room.

# -- panning and dollying (Walk) --
mutate 's/        cam\.x \+= rightX\(cam\.yaw\) \* rightFt;\n        cam\.z \+= rightZ\(cam\.yaw\) \* rightFt;/        cam.x += forwardX(cam.yaw) * rightFt;\n        cam.z += forwardZ(cam.yaw) * rightFt;/' $THREED/Walk.java
run_case "a two-finger pan slides along the look direction instead of sideways"
mutate 's/        cam\.y \+= upFt;\n        cam\.clampPosition/        cam.y -= upFt;\n        cam.clampPosition/' $THREED/Walk.java
run_case "panning up sinks the camera"
mutate 's/        cam\.y \+= upFt;\n        cam\.clampPosition\(room\);\n    \}/        cam.y += upFt;\n    }/' $THREED/Walk.java
run_case "a pan is not clamped and can leave the world"
mutate 's/        cam\.x \+= forwardX\(cam\.yaw\) \* forwardFt;\n        cam\.z \+= forwardZ\(cam\.yaw\) \* forwardFt;\n        cam\.clampPosition\(room\);/        cam.x += rightX(cam.yaw) * forwardFt;\n        cam.z += rightZ(cam.yaw) * forwardFt;\n        cam.clampPosition(room);/' $THREED/Walk.java
run_case "the pinch dolly goes sideways rather than forward (D-15)"
mutate 's/        cam\.z \+= forwardZ\(cam\.yaw\) \* forwardFt;\n        cam\.clampPosition\(room\);/        cam.z += forwardZ(cam.yaw) * forwardFt * Math.cos(cam.pitch);\n        cam.y += Math.sin(cam.pitch) * forwardFt;\n        cam.clampPosition(room);/' $THREED/Walk.java
run_case "the dolly flies along the nose instead of flat forward (D-15)"
mutate 's/        cam\.x \+= forwardX\(cam\.yaw\) \* forwardFt;\n        cam\.z \+= forwardZ\(cam\.yaw\) \* forwardFt;\n        cam\.clampPosition\(room\);\n    \}/        cam.x += forwardX(cam.yaw) * forwardFt;\n        cam.z += forwardZ(cam.yaw) * forwardFt;\n    }/' $THREED/Walk.java
run_case "a dolly is not clamped"

# -- the one copy of forward and right --
mutate 's/    static double forwardZ\(double yaw\) \{\n        return -Math\.cos\(yaw\);/    static double forwardZ(double yaw) {\n        return Math.cos(yaw);/' $THREED/Walk.java
run_case "north becomes south for walking, panning and the dolly at once"
mutate 's/    static double rightX\(double yaw\) \{\n        return Math\.cos\(yaw\);/    static double rightX(double yaw) {\n        return Math.sin(yaw);/' $THREED/Walk.java
run_case "right stops being at a right angle to forward"

# -- the thumbstick (Joystick) --
mutate 's/return inDeadzone\(\) \? 0 : -knobY \/ RADIUS_PX;/return inDeadzone() ? 0 : knobY \/ RADIUS_PX;/' $THREED/Joystick.java
run_case "the stick walks backwards"
mutate 's/return inDeadzone\(\) \? 0 : knobX \/ RADIUS_PX;/return inDeadzone() ? 0 : -knobX \/ RADIUS_PX;/' $THREED/Joystick.java
run_case "the stick strafes the wrong way"
mutate 's/return Math\.hypot\(knobX, knobY\) \/ RADIUS_PX < DEADZONE;/return Math.abs(knobX) \/ RADIUS_PX < DEADZONE \&\& Math.abs(knobY) \/ RADIUS_PX < DEADZONE;/' $THREED/Joystick.java
run_case "the deadzone is a square rather than a circle"
mutate 's/return Math\.hypot\(knobX, knobY\) \/ RADIUS_PX < DEADZONE;/return false;/' $THREED/Joystick.java
run_case "there is no deadzone at all, so a resting thumb creeps"
mutate 's/        if \(magnitude > RADIUS_PX\) \{\n            knobX = dxPx \/ magnitude \* RADIUS_PX;\n            knobY = dyPx \/ magnitude \* RADIUS_PX;\n        \} else \{\n            knobX = dxPx;\n            knobY = dyPx;\n        \}/        knobX = dxPx;\n        knobY = dyPx;/' $THREED/Joystick.java
run_case "the knob is not clamped to the radius, so sliding off means flying"
mutate 's/        if \(magnitude > RADIUS_PX\) \{\n            knobX = dxPx \/ magnitude \* RADIUS_PX;\n            knobY = dyPx \/ magnitude \* RADIUS_PX;/        if (magnitude > RADIUS_PX) {\n            knobX = Math.min(RADIUS_PX, Math.max(-RADIUS_PX, dxPx));\n            knobY = Math.min(RADIUS_PX, Math.max(-RADIUS_PX, dyPx));/' $THREED/Joystick.java
run_case "the knob is clamped per axis, squaring off a round stick"
mutate 's/return active && this\.touchId == touchId;/return this.touchId == touchId;/' $THREED/Joystick.java
run_case "a released stick still claims the finger numbered 0"
mutate 's/    public void moveTo\(double dxPx, double dyPx\) \{\n        if \(!active\) \{\n            return;\n        \}/    public void moveTo(double dxPx, double dyPx) {/' $THREED/Joystick.java
run_case "a stick nobody is holding can still be deflected"
mutate 's/        active = false;\n        touchId = 0;\n        knobX = 0;\n        knobY = 0;/        active = false;\n        touchId = 0;/' $THREED/Joystick.java
run_case "letting go leaves the knob where it was"

# -- look, pan, pinch and tap (WalkGesture) --
mutate 's/public static final double LOOK_SENS_RAD_PER_PX = 0\.006;/public static final double LOOK_SENS_RAD_PER_PX = 0.0022;/' $THREED/WalkGesture.java
run_case "one-finger look uses the mouse's sensitivity, which a phone has no room for"
mutate 's/public static final double PINCH_SENS_FT_PER_PX = 0\.015;/public static final double PINCH_SENS_FT_PER_PX = 0.03;/' $THREED/WalkGesture.java
run_case "the pinch is as strong as the pan, so the same hand movement moves twice as far"
mutate 's/double spread = separation - lastSeparation;/double spread = lastSeparation - separation;/' $THREED/WalkGesture.java
run_case "spreading two fingers moves you backwards"
mutate 's/                Walk\.pan\(cam, dx \* PAN_SENS_FT_PER_PX, -dy \* PAN_SENS_FT_PER_PX, room\);/                Walk.pan(cam, dx * PAN_SENS_FT_PER_PX, dy * PAN_SENS_FT_PER_PX, room);/' $THREED/WalkGesture.java
run_case "dragging two fingers up the screen lowers the camera"
mutate 's/                Walk\.dolly\(cam, spread \* PINCH_SENS_FT_PER_PX, room\);//' $THREED/WalkGesture.java
run_case "the pinch does nothing, which is what the original does (D-15)"

# The 2026-08-11 amendment to D-15: two fingers do one job at a time. Note the two cases above
# were RE-DERIVED here rather than ported; both calls moved inside an if/else and gained four
# spaces of indentation, which would have made the old patterns match nothing and report
# "caught" while testing nothing. Same lesson as the M5.3 pair M6.3 had to re-derive.
mutate 's/public static final double TWO_FINGER_DECIDE_PX = 12;/public static final double TWO_FINGER_DECIDE_PX = 0;/' $THREED/WalkGesture.java
run_case "two resting fingers decide what they are for by trembling"
mutate 's/                if \(Math\.max\(slid, separated\) < TWO_FINGER_DECIDE_PX\) \{/                if (Math.min(slid, separated) < TWO_FINGER_DECIDE_PX) {/' $THREED/WalkGesture.java
run_case "a pure slide never decides, because its separation never changes"
mutate 's/                job = slid >= separated \? Job\.SLIDE : Job\.PINCH;/                job = slid <= separated ? Job.SLIDE : Job.PINCH;/' $THREED/WalkGesture.java
run_case "the pair takes whichever job the hand did LESS of"
mutate 's/                job = slid >= separated \? Job\.SLIDE : Job\.PINCH;/                job = slid > separated ? Job.SLIDE : Job.PINCH;/' $THREED/WalkGesture.java
run_case "a dead-level hand gets the pinch rather than the original's slide"
mutate 's/        lastSeparation = Math\.hypot\(xy\[2\] - xy\[0\], xy\[3\] - xy\[1\]\);\n        job = Job\.UNDECIDED;/        lastSeparation = Math.hypot(xy[2] - xy[0], xy[3] - xy[1]);/' $THREED/WalkGesture.java
run_case "a pair that has lost a finger keeps the job the old pair had"
mutate 's/    public void canceled\(\) \{\n        mode = Mode\.NONE;\n        job = Job\.UNDECIDED;/    public void canceled() {\n        mode = Mode.NONE;/' $THREED/WalkGesture.java
run_case "a canceled gesture still reports the job it was doing"
mutate 's/            tapPending = true;\n            tapX = xy\[0\];/            tapPending = false;\n            tapX = xy[0];/' $THREED/WalkGesture.java
run_case "a quick still touch never becomes a tap"
mutate 's/        mode = Mode\.TWO_FINGERS;\n        followTwo\(xy\);\n        tapPending = false;\n    \}/        mode = Mode.TWO_FINGERS;\n        followTwo(xy);\n    }/' $THREED/WalkGesture.java
run_case "a second finger leaves the tap candidate standing"
mutate 's/            if \(tapPending && Math\.hypot\(xy\[0\] - tapX, xy\[1\] - tapY\) > TAP_MOVE_TOLERANCE_PX\) \{\n                tapPending = false;\n            \}//' $THREED/WalkGesture.java
run_case "a finger can wander the whole screen and still count as a tap"
mutate 's/            tap = new Tap\(tapX, tapY\);/            tap = new Tap(lastX, lastY);/' $THREED/WalkGesture.java
run_case "the tap names where the finger left rather than where it landed"
mutate 's/        if \(tapPending && nowMs - tapStartMs < TAP_MAX_MS\) \{/        if (tapPending) {/' $THREED/WalkGesture.java
run_case "a touch held for a minute is still a tap when it lifts"
mutate 's/        if \(mode == Mode\.LOOK && fingers == 1\) \{/        if (mode == Mode.LOOK \&\& fingers >= 1) {/' $THREED/WalkGesture.java
run_case "a two-finger movement drives the one-finger look"
mutate 's/        int fingers = xy\.length \/ 2;\n        if \(fingers == 0\) \{\n            return;\n        \}\n        if \(fingers == 1\) \{\n            mode = Mode\.LOOK;/        int fingers = xy.length \/ 2;\n        if (fingers <= 1) {\n            mode = Mode.LOOK;/' $THREED/WalkGesture.java
run_case "a thumb landing on the stick restarts the look somebody else is in the middle of"
mutate 's/        \} else \{\n            mode = Mode\.TWO_FINGERS;\n            followTwo\(xy\);\n        \}\n        return tap;/        } else {\n            mode = Mode.TWO_FINGERS;\n        }\n        return tap;/' $THREED/WalkGesture.java
run_case "lifting one of three fingers keeps a midpoint that no longer exists"
# RE-DERIVED at M6.4, not ported. The M6.3 text named three consecutive lines, and the pinch
# amendment (§5.5 D-15) inserted `job = Job.UNDECIDED;` between the first two, so the old pattern
# matched nothing and its case reported SURVIVED, a false finding about working code. Naming the
# one line the rule is actually about is also what stops it going stale the next time a line lands
# in that method.
mutate 's/    public void canceled\(\) \{\n        mode = Mode\.NONE;/    public void canceled() {\n        mode = Mode.NONE;\n        if (true) {\n            return;\n        }/' $THREED/WalkGesture.java
run_case "a canceled gesture can still become a tap on the way out"

# --- M6.4: fluid type, and the touch override table --------------------------------
#
# Both are pure and headless, which is exactly why they were pulled out of the width listener
# and out of the six files that used to hold these numbers. A rule that needs a window is
# mutated by hand instead; those are recorded in CLAUDE.md's M6.4 row.
mutate 's/return Math\.max\(floorPx, Math\.min\(wanted, ceilingPx\)\);/return Math.min(wanted, ceilingPx);/' $UI/Fluid.java
run_case "fluid type has no floor, so a narrow phone gets unreadable buttons"
mutate 's/return Math\.max\(floorPx, Math\.min\(wanted, ceilingPx\)\);/return Math.max(floorPx, wanted);/' $UI/Fluid.java
run_case "fluid type has no ceiling, so a wide screen grows past the design size"
mutate 's/percent \/ 100;/percent \/ 10;/' $UI/Fluid.java
run_case "a percentage of the container is read as a tenth instead of a hundredth"
mutate 's/return Math\.max\(floorPx, Math\.min\(wanted, ceilingPx\)\);/return Math.min(ceilingPx, Math.max(wanted, floorPx));/' $UI/Fluid.java
run_case "clamp resolves in the wrong order, so a ceiling below the floor wins"

mutate 's/return Device\.isTouch\(\) \? touch : desktop;/return desktop;/' $UI/TouchType.java
run_case "the whole touch override table quietly returns the desktop number"
mutate 's/return Device\.isTouch\(\) \? touch : desktop;/return Device.isTouch() ? desktop : touch;/' $UI/TouchType.java
run_case "the touch override table is the wrong way round"
mutate 's/return pick\(Tokens\.FONT_LIST_ROW, Tokens\.FONT_LIST_ROW_TOUCH\);/return pick(Tokens.FONT_LIST_ROW, Tokens.FONT_LIST_ROW);/' $UI/TouchType.java
run_case "one entry in the table forgets to change on a phone"
# ⚠ RE-DERIVED 2026-09-04, not ported. The old pattern matched a ternary that returned
# Region.USE_COMPUTED_SIZE on touch; M6.4 replaced it with an if and a worked-out height, so the
# pattern silently matched nothing and the case reported SURVIVED for years. The intent is
# unchanged: make a phone take the desktop pin instead of the height computed for it.
mutate 's/return Tokens\.TOUCH_TEXT_LINE_EM \* dialogButtonFont\(\)\n                \+ 2 \* dialogButtonPaddingV\(\) \+ 2 \* Tokens\.TOUCH_BUTTON_BORDER;/return Tokens.DIALOG_BUTTON_HEIGHT;/' $UI/TouchType.java
run_case "a dialog button takes the desktop pin on a phone instead of its own height"

echo
echo "caught $caught, survived $survived, broken patterns $broken"
[ "$broken" -eq 0 ] || echo "WARNING: $broken pattern(s) changed nothing. Those cases are NOT results."
[ "$survived" -le 1 ] || echo "NOTE: more survivors than the documented 1; the suite has new blind spots."
