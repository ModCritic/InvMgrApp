#!/usr/bin/env bash
#
# What code is actually inside the thing we are about to ship?
#
# WHY THIS EXISTS
#
# CLAUDE.md §8 says to verify with `mvn dependency:list -DincludeScope=runtime`, and
# that command has always answered "only org.openjfx". It was telling the truth about
# the question it was asked, and the answer was still wrong, because it lists MAVEN
# DEPENDENCIES and the problem was never a dependency.
#
# The Android build runs gluonfx-maven-plugin, which runs Gluon Substrate, which writes
# its OWN Java classes into the APK's dex and links its OWN C into libsubstrate.so.
# None of that is a dependency of this project. It is injected at package time by the
# build tool, so it appears in no dependency tree, and it carries a GPL-3.0-or-later
# header with no classpath exception. See docs/LICENSE-AUDIT.md.
#
# §8 had already learned this once with the bundled fonts: "shipped assets are not
# dependencies, and mvn dependency:list cannot see them". This is the third category:
# code the toolchain adds behind the build's back.
#
# WHAT IT DOES
#
# Opens the built artifact and reports every distinct code origin actually inside it,
# then compares that against tools/shipped-origins.allow. Anything not on the allowlist
# is printed and the script exits 1.
#
# It inspects the ARTIFACT, never the build files. That is the whole point: a check that
# reads pom.xml can only ever confirm what we already believe.
#
#   ./tools/check-shipped-licenses.sh                      # newest APK + the desktop jar
#   ./tools/check-shipped-licenses.sh path/to/some.apk     # one named artifact
#
# Adding a legitimate new origin means adding a line to the .allow file WITH its license
# and a reason, which is the same discipline MANUAL.md's audit table asks for. The point
# is that adding it has to be a deliberate act.

set -euo pipefail

cd "$(dirname "$0")/.."
ALLOW="tools/shipped-origins.allow"

# dexdump reads the compiled classes out of a .dex. Both build-tools copies work; take
# the newest. If the Android SDK is not installed this check cannot run on an APK, and
# says so rather than silently passing.
DEXDUMP="$(find "$HOME/.gluon/substrate/Android/build-tools" -maxdepth 2 -name dexdump 2>/dev/null | sort -V | tail -1 || true)"

fail=0

# Turn a list of class names into the distinct origins a human would recognize.
#
# Two steps, and the first one matters: drop the trailing class name, recognized by its
# leading capital. Without it "kotlinx.coroutines.ThreadState" counts as its own origin
# and the report becomes one line per class. Then keep at most three package segments,
# which is what separates com.gluonhq.helloandroid from com.gluonhq.substrate while
# still collapsing com.modcritic.invmgr.ui and .engine into one row.
origins() {
    sed 's#^L##; s#;$##; s#/#.#g' \
        | sed 's#\.[A-Z][A-Za-z0-9_$]*$##' \
        | cut -d. -f1-3 | sort -u
}

check_origin() {
    local origin="$1" where="$2"
    # A line matches if the allowlist prefix is a prefix of this origin.
    if grep -v '^\s*#' "$ALLOW" | grep -v '^\s*$' | cut -d'|' -f1 | tr -d ' ' \
         | while read -r p; do case "$origin" in "$p"*) echo hit; break;; esac; done | grep -q hit; then
        return 0
    fi
    printf '  NOT ALLOWED  %-45s (in %s)\n' "$origin" "$where"
    fail=1
}

inspect_apk() {
    local apk="$1"
    echo "== APK: $apk"

    if [ -z "$DEXDUMP" ]; then
        echo "  !! dexdump not found, so the dex files cannot be read. Install the Android SDK."
        fail=1
    else
        local tmp; tmp="$(mktemp -d)"
        trap 'rm -rf "$tmp"' RETURN
        unzip -qo "$apk" '*.dex' -d "$tmp"
        for dex in "$tmp"/*.dex; do
            "$DEXDUMP" "$dex" 2>/dev/null | awk '/Class descriptor/ {print $NF}' | tr -d "'"
        done | origins > "$tmp/origins.txt"

        local total=0
        echo "  code origins in dex:"
        while read -r o; do
            [ -z "$o" ] && continue
            total=$((total + 1))
            check_origin "$o" "$(basename "$apk") dex"
        done < "$tmp/origins.txt"
        echo "      ($total distinct package origins; only the unallowed are listed above)"
    fi

    # Native libraries are code too, and the biggest single thing in the APK.
    echo "  native libraries:"
    unzip -l "$apk" | awk '/lib\/.*\.so$/ {printf "      %-45s %s bytes\n", $4, $1}'
    while read -r so; do
        [ -z "$so" ] && continue
        check_origin "$(basename "$so")" "$(basename "$apk") lib"
    done < <(unzip -l "$apk" | awk '/lib\/.*\.so$/ {print $4}')
}

inspect_jar() {
    local jar="$1"
    echo "== JAR: $jar"
    unzip -l "$jar" | awk '{print $4}' | grep '\.class$' \
        | sed 's#/[^/]*$##; s#/#.#g' | cut -d. -f1-3 | sort -u > /tmp/jar-origins.$$
    local total=0
    while read -r o; do
        [ -z "$o" ] && continue
        total=$((total + 1))
        check_origin "$o" "$(basename "$jar")"
    done < /tmp/jar-origins.$$
    echo "      ($total distinct package origins; only the unallowed are listed above)"
    rm -f /tmp/jar-origins.$$

    # Fonts and other shipped assets carry licenses and no dependency tree shows them;
    # this is the §8 fonts lesson. OpenJFX's own skin images are excluded: they arrive
    # under the same CE as its code and would otherwise bury the few that need a row.
    echo "  shipped assets needing a license row (OpenJFX's own skin files excluded):"
    unzip -l "$jar" | awk '{print $4}' | grep -Ei '\.(ttf|otf|woff2?|png|svg|jpg)$' \
        | grep -v '^com/sun/javafx/' | sed 's#^#      #' || echo "      (none)"
}

if [ $# -gt 0 ]; then
    for a in "$@"; do
        case "$a" in
            *.apk) inspect_apk "$a" ;;
            *.jar) inspect_jar "$a" ;;
            *) echo "Don't know how to inspect $a"; fail=1 ;;
        esac
    done
else
    newest_apk="$(ls -t ../InvMgr-*.apk 2>/dev/null | head -1 || true)"
    [ -n "$newest_apk" ] && inspect_apk "$newest_apk" || echo "== no APK found in the parent directory, skipping"
    shaded="$(ls -t target/*.jar 2>/dev/null | grep -v original | head -1 || true)"
    [ -n "$shaded" ] && inspect_jar "$shaded" || echo "== no jar in target/, skipping"
fi

echo
if [ "$fail" -ne 0 ]; then
    echo "FAIL: something ships that is not on the allowlist. Either it should not be"
    echo "there, or tools/shipped-origins.allow needs a row with its license and a reason."
    exit 1
fi
echo "OK: every origin in the artifact is on the allowlist."
