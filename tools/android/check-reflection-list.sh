#!/usr/bin/env bash
#
# Is every JavaFX class this project uses visible to the Android build?
#
# WHY THIS EXISTS
#
# Every JavaFX node type has a companion "Helper" class whose static setup calls
# com.sun.javafx.util.Utils.forceInit, and that is Class.forName on a name built at
# runtime. A native image (the Android build) cannot follow a lookup like that, so the
# class has to be registered for reflection in pom.xml's <reflectionList>, even though
# our own code references it directly and it is unquestionably compiled into the binary.
#
# When one is missing, nothing complains at build time. The app runs until it reaches
# that node type and then throws:
#
#     java.lang.AssertionError: java.lang.ClassNotFoundException: javafx.scene.shape.Polyline
#         at com.sun.javafx.util.Utils.forceInit
#         at com.sun.javafx.scene.shape.PolylineHelper.<clinit>
#
# On a phone that is a black screen. M6.1's first APK died exactly this way, on the
# stepper arrows of the room-size fields, and finding it cost a full build cycle.
#
# Gluon pre-registers a curated list of JavaFX classes, and that list has holes:
# Polygon is on it, Polyline is not, and they are siblings. So "Gluon handles JavaFX"
# is not something to rely on.
#
# WHAT IT DOES
#
# Compares the javafx.* classes imported anywhere in src/main/java against the union of
# (a) Gluon's own list, read out of the substrate jar, and (b) our <reflectionList>.
# Anything in neither is reported and the script exits 1.
#
# Run it after adding any new JavaFX import. It takes about a second, where the
# alternative is a ten-minute build and a phone.
#
# It deliberately does NOT try to work out which classes *need* registering. Registering
# one that does not need it costs nothing but metadata; missing one costs a build cycle,
# so the rule here is "list everything we use".
#
# ⚠ SO AN INTERFACE BEING FLAGGED IS NOT A FALSE POSITIVE, and it looks like one every
# time. The crash above needs a companion *Helper, and only javafx.scene node types have
# one, so a reader who checks the mechanism concludes the report is spurious and goes to
# narrow this script. Do not. Five entries in the pom are classes that provably cannot
# crash: ChangeListener, ObservableValue, EventTarget, PixelWriter and PixelReader.
# Exempting them would make this script disagree with a pom that works on hardware,
# while narrowing the one guard that has twice been found short.
#
# Established 2026-09-09, from the Android SDK jar rather than by reasoning: forceInit is
# called on exactly 62 JavaFX classes, the whole javafx.scene.image family contributes
# only Image and ImageView, and there is no PixelReaderHelper in any JavaFX jar on this
# machine. CLAUDE.md §5.8 item 9 carries the working.

set -euo pipefail

cd "$(dirname "$0")/../.."
POM=pom.xml
SRC=src/main/java

SUBSTRATE=$(find ~/.m2/repository/com/gluonhq/substrate -name 'substrate-*.jar' 2>/dev/null | sort | tail -1)
if [ -z "$SUBSTRATE" ]; then
    echo "cannot find the substrate jar in ~/.m2: run the Android build once first" >&2
    exit 2
fi

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# What we use. An import of javafx.scene.shape.Polyline names the class; an import of a
# static member (javafx.scene.paint.Color.WHITE) names one segment too many, so a
# trailing lowercase-initial segment is dropped.
#
# ⚠ AND the classes written out in full instead of imported. Reading import lines alone is
# all this script did until M6.3, and it had been short by five names since M5.2 without
# ever saying so: SequentialTransition, ObservableValue, ActionEvent, CornerRadii and Robot
# are every one of them used fully qualified, and every one of them read as "not used at
# all". A guard that cannot see part of the source is worse than no guard, because it
# prints the same "OK" whether or not the thing it checks is true, the same shape as a
# mutation that never applied reporting SURVIVED.
#
# The second pattern also picks up names inside comments and javadoc. That is not a defect.
# The rule at the top of this file is "list everything we use"; registering a class that
# turns out not to need it is free, and a name too many costs one line of pom where a name
# too few costs a ten-minute build and a phone.
{
    grep -rhoE '^import (static )?javafx\.[A-Za-z0-9_.]+;' "$SRC" \
        | sed 's/^import \(static \)\?//; s/;$//' \
        | sed 's/\.[a-z][A-Za-z0-9_]*$//'

    grep -rhoE 'javafx\.[a-z0-9_]+(\.[a-z0-9_]+)*\.[A-Z][A-Za-z0-9_]*' "$SRC"
} | sort -u > "$work/used"

# What Gluon already registers, plus what we register ourselves.
#
# ⚠ Gluon's half is read from a CHECKED-IN SNAPSHOT, not from the generated config in
# target/. That is deliberate and it is the difference between this script working and
# this script lying. The generated config is Gluon's list PLUS our <reflectionList>,
# so checking against it would mean checking our own answers: delete an entry from the
# pom and the last build's output still vouches for it. The check would pass forever
# and go quietly blind, the same shape of failure as a mutation that never applied.
#
# The snapshot was taken from the generated config of the build immediately BEFORE any
# javafx entry existed in our pom, so every name in it is Gluon's. Refresh it the same
# way if Gluon's version ever changes: temporarily empty our javafx entries, build, and
# copy the javafx names out.
cat tools/android/gluon-registers.txt > "$work/known"
grep -oE '<list>javafx\.[A-Za-z0-9_.]+</list>' "$POM" \
    | sed 's|</\?list>||g' >> "$work/known"
sort -u "$work/known" -o "$work/known"

# $SUBSTRATE is located above only to fail early and loudly if the Android toolchain is
# not present at all; its bundled config covers the software pipeline, not scene classes.
: "$SUBSTRATE"

missing=$(comm -23 "$work/used" "$work/known")

if [ -n "$missing" ]; then
    echo "JavaFX classes used by the app but registered nowhere:"
    echo "$missing" | sed 's/^/    /'
    echo
    echo "Add each to <reflectionList> in $POM. Without it the Android build succeeds and"
    echo "the app dies on a black screen the moment it touches one of them."
    exit 1
fi

echo "reflection list OK: $(wc -l < "$work/used") javafx classes used, all registered"
