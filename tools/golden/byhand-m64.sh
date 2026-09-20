#!/bin/bash
#
# The M6.4 rules that need a window, mutated by hand.
#
# RUN AND RECORDED 2026-08-12: 17 cases, 17 caught, 0 broken patterns, over a baseline of 38.
# The first run caught 16 and left one survivor, "the bar keeps its padding while folding",
# which was a real hole rather than a redundant rule: the bar's HEIGHT is driven to zero
# directly, so a stuck padding does not change where the bar ends, only what the fold looks
# like on the way. TouchLayoutTest.theFoldTakesThePaddingToo now pins it, and restoring the
# mutation fails it with 10.0 against 0.0.
#
# The headless sweep cannot reach these: a folding bar, a re-parented button, a sliding drawer and
# a reserved strip all need a real stage. Same discipline, smaller harness: break one rule, check
# the windowed tests notice.
#
# Restores from copies, never from git: the working tree may hold uncommitted work.

set -u
cd "$(dirname "$0")/../.." || exit 1

UI=src/main/java/com/modcritic/invmgr/ui
BK=$(mktemp -d)
FILES="$UI/TopBar.java $UI/TouchIsland.java $UI/TouchDrawers.java $UI/TopWrap.java $UI/NumberField.java"

restore_all() { for f in $FILES; do cp "$BK/$(basename "$f")" "$f"; done; }
trap 'restore_all; rm -rf "$BK"' EXIT
for f in $FILES; do cp "$f" "$BK/"; done

SUITE='com.modcritic.invmgr.ui.TouchLayoutTest,com.modcritic.invmgr.ui.ChromeAppearanceTest,com.modcritic.invmgr.ui.SafeAreaTest'
MIN_TESTS=35

run_suite() { DISPLAY=${DISPLAY:-:99} mvn -B test -Dtest="$SUITE" -DfailIfNoSpecifiedTests=true "$@"; }

echo "checking baseline..."
if ! run_suite >/tmp/byhand-baseline.log 2>&1; then
  echo "ABORT: the suite already fails before any mutation."; exit 1
fi
ran=$(grep -oP 'Tests run: \K[0-9]+' /tmp/byhand-baseline.log | tail -1)
if [ -z "$ran" ] || [ "$ran" -lt "$MIN_TESTS" ]; then
  echo "ABORT: baseline ran ${ran:-0} tests, expected at least $MIN_TESTS."; exit 1
fi
echo "baseline green: $ran tests."
echo

caught=0; survived=0; broken=0

mutate() {
  local before after
  before=$(md5sum "$2" | cut -d' ' -f1)
  perl -0pi -e "$1" "$2" || { echo "BROKEN PATTERN (perl error) on $2"; broken=$((broken + 1)); }
  after=$(md5sum "$2" | cut -d' ' -f1)
  [ "$before" = "$after" ] && { echo "BROKEN PATTERN (matched nothing) on $2: $1"; broken=$((broken + 1)); }
  return 0
}

run_case() {
  if run_suite -q >/tmp/byhand-run.log 2>&1; then
    echo "SURVIVED  <-- BLIND SPOT: $1"; survived=$((survived + 1))
  else
    echo "caught    $1"; caught=$((caught + 1))
  fi
  restore_all
}

# --- the folding bar --------------------------------------------------------------
mutate 's/        if \(!touch\) \{\n            return super\.computeMinWidth\(height\);\n        \}/        if (true) {\n            return super.computeMinWidth(height);\n        }/' $UI/TopBar.java
run_case "the bar takes FlowPane's minimum width, so it can never get narrower than it has been"
mutate 's/            setPrefHeight\(showing \* naturalHeight\);/            setPrefHeight(naturalHeight);/' $UI/TopBar.java
run_case "the fold does not actually change the bar's height"
mutate 's/        setMouseTransparent\(showing <= 0\);\n//' $UI/TopBar.java
run_case "a folded bar is invisible but still takes taps meant for the room"
mutate 's/            setMinHeight\(USE_COMPUTED_SIZE\);/            setMinHeight(0);/' $UI/TopBar.java
run_case "the folding minimum is left off permanently, so a big room squashes the bar"
mutate 's/        setPadding\(barPadding\(showing\)\);/        setPadding(barPadding(1));/' $UI/TopBar.java
run_case "the bar keeps its padding while folding, so it stops short of nothing"

# --- the three forced rows --------------------------------------------------------
mutate 's/        line\.prefWidthProperty\(\)\.bind\(\n                widthProperty\(\)\.subtract\(Tokens\.TOP_BAR_PADDING_H_TOUCH \* 2\)\);/        line.prefWidthProperty().bind(\n                widthProperty().subtract(Tokens.TOP_BAR_PADDING_H_TOUCH * 2).divide(4));/' $UI/TopBar.java
run_case "the rows are not full width, so the bar wraps wherever it happens to fit"

# --- the island -------------------------------------------------------------------
mutate 's/        setBarOpen\(false, false\);/        setBarOpen(true, false);/' $UI/TouchIsland.java
run_case "the bar starts open on a phone instead of folded"
mutate 's/        type\(fitButton\(\), toggleSize\);\n        type\(planButton\(\), toggleSize\);\n        type\(unitsButton\(\), toggleSize\);/        type(fitButton(), base);\n        type(planButton(), base);\n        type(unitsButton(), base);/' $UI/TouchIsland.java
run_case "Fit, Plan and Units are set in the base size, losing the island's hierarchy"
mutate 's/        type\(addButton\(\), addSize\);/        type(addButton(), base);/' $UI/TouchIsland.java
run_case "Add is no bigger than the buttons beside it"
mutate 's/    List<Button> islandButtons\(\) \{\n        unpin\(add\);\n        return List\.of\(add, undo, fit, plan, units\);/    List<Button> islandButtons() {\n        unpin(add);\n        return List.of(new Button("■"), new Button("Undo"), new Button("Fit"),\n                new Button("Plan"), new Button("Units"));/' $UI/TopBar.java
run_case "the island builds its own five buttons instead of borrowing the bar's"

# --- the frame --------------------------------------------------------------------
mutate 's/        setPadding\(new Insets\(extra, 0, 0, 0\)\);/        setPadding(Insets.EMPTY);/' $UI/TopWrap.java
run_case "the frame reserves nothing, so the interface sits under the phone's clock"

# --- the drawers ------------------------------------------------------------------
mutate 's/        boolean wasOpen = open == drawer;\n        closeAny\(\);/        boolean wasOpen = open == drawer;/' $UI/TouchDrawers.java
run_case "opening a drawer leaves the other one open too"
mutate 's/        slide\(tab, drawer == slider\n                \? Tokens\.SLIDER_DRAWER_WIDTH : -Tokens\.LIST_PANEL_WIDTH_TOUCH\);/        slide(tab, 0);/' $UI/TouchDrawers.java
run_case "the tab stays behind when its drawer slides out"
mutate 's/        room\.addEventHandler\(MouseEvent\.MOUSE_CLICKED, event -> closeAny\(\)\);/        room.addEventHandler(MouseEvent.MOUSE_CLICKED, event -> { });/' $UI/TouchDrawers.java
run_case "tapping the room no longer shuts an open drawer"
mutate 's/            tab\.setVisible\(visible\);\n            tab\.setManaged\(visible\);/            tab.setVisible(true);\n            tab.setManaged(true);/' $UI/TouchDrawers.java
run_case "the drawer tabs stay on screen over the 3D room"
mutate 's/        slider\.setTranslateX\(-Tokens\.SLIDER_DRAWER_WIDTH\);/        slider.setTranslateX(0);/' $UI/TouchDrawers.java
run_case "the layer slider starts open, covering the room"

# --- the room fields --------------------------------------------------------------
mutate 's/        getChildren\(\)\.remove\(stepper\);/        \/\/ stepper kept/' $UI/NumberField.java
run_case "the touch room fields keep their steppers, so W\/L\/H no longer fit one row"

echo
echo "caught $caught, survived $survived, broken patterns $broken"
[ "$broken" -eq 0 ] || echo "WARNING: $broken pattern(s) changed nothing. Those cases are NOT results."
