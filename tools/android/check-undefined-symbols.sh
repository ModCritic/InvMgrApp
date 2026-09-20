#!/usr/bin/env bash
#
# Will the phone actually be able to load this library?
#
# WHY THIS EXISTS
#
# Android resolves every symbol when a library is loaded, not when it is first called. One
# missing symbol is therefore not a bug you meet later, it is a crash before a single line
# of the app runs:
#
#     java.lang.UnsatisfiedLinkError: dlopen failed: cannot locate symbol "JNI_OnLoad_awt"
#         at com.modcritic.invmgr.android.InvMgrActivity.<clinit>
#
# That is exactly what M6.5b's first APK did. The host layer had been checked with
# `nm -D -u libInvMgr.so | grep '^Java_'`, which is a subset of what has to resolve: JNI's
# static linking convention also produces JNI_OnLoad_<library> entry points, and two of
# those were missing. Everything else about that build was correct, and none of the static
# checks noticed, because they were all asking about the wrong set of symbols.
#
# The link does not catch this either. A shared library is allowed to have undefined
# symbols; the linker assumes something else will provide them at load time. On Android
# nothing does.
#
# WHAT IT DOES
#
# Takes every undefined symbol in the linked library and subtracts everything the phone
# will actually have: the NDK's system libraries for our target API, and libfreetype, which
# ships in the APK beside us. Anything still standing is a symbol nobody will provide, and
# the script exits 1 naming it.
#
#   ./tools/android/check-undefined-symbols.sh
#   ./tools/android/check-undefined-symbols.sh path/to/some.so
#
# Run it after every Android link, before building the APK. It takes about a second, and
# the alternative is a ten minute build, a sideload, and reading a stack trace.

set -euo pipefail

cd "$(dirname "$0")/../.."

LIB="${1:-target/gluonfx/aarch64-android/libInvMgr.so}"
if [ ! -f "$LIB" ]; then
    echo "No library at $LIB. Build it first with -Pandroid gluonfx:build."
    exit 1
fi

NDK_ROOT="$(find "$HOME/.gluon/substrate/Android/ndk" -maxdepth 1 -mindepth 1 -type d 2>/dev/null | sort -V | tail -1)"
if [ -z "$NDK_ROOT" ]; then
    echo "!! Android NDK not found under ~/.gluon. Cannot check."
    exit 1
fi

# minSdkVersion 21, which is what the APK targets, so the API 21 stubs are the right ones.
SYSROOT="$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/21"
if [ ! -d "$SYSROOT" ]; then
    echo "!! No API 21 sysroot at $SYSROOT"
    exit 1
fi

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# What the phone provides. The NDK ships stub libraries whose only purpose is to declare
# exactly this, which is why they are the right thing to check against rather than a
# hand-written list of "things libc probably has".
# Some entries here are linker scripts rather than ELF files (libc++.so is one), and nm
# fails on those. That is expected, so the failure is tolerated rather than fatal.
#
# ⚠ Strip the @VERSION suffix on BOTH sides. Bionic's stub libraries export versioned names
# like abort@@LIBC, while the references to them are plain. Comparing one against the other
# reports the whole of libc as missing, which is how the first version of this script
# behaved and why it is worth a comment.
for so in "$SYSROOT"/*.so; do
    nm -D --defined-only "$so" 2>/dev/null | awk '$2 ~ /^[TWBDRVi]$/ {print $3}' || true
done | sed 's/@.*//' > "$tmp/provided.txt"

# libfreetype ships in the APK next to us and satisfies the FT_* references.
FREETYPE="$HOME/.gluon/substrate/javafxStaticSdk/21-ea+11.3/android-aarch64/sdk/lib/libfreetype.so"
if [ -f "$FREETYPE" ]; then
    nm -D --defined-only "$FREETYPE" 2>/dev/null | awk '{print $3}' | sed 's/@.*//' >> "$tmp/provided.txt" || true
fi

sort -u "$tmp/provided.txt" -o "$tmp/provided.txt"

# Android's linker matches on the bare name, so strip any @VERSION suffix before comparing.
nm -D -u "$LIB" 2>/dev/null | awk '{print $NF}' | sed 's/@.*//' | sort -u > "$tmp/needed.txt"

# Known good, and not guesses.
#
# The NDK stubs describe API 21, which is this app's minSdk, while the phone runs something
# far newer and its libc exports more than the stubs admit to. These three are referenced by
# the M6.4i library as well, and that build loaded and ran on the user's device, which is the
# evidence for letting them through. Check any addition the same way before adding it:
#
#     nm -D -u <a known-good libsubstrate.so> | grep <symbol>
cat > "$tmp/known.txt" <<'KNOWN'
getgrgid_r
stderr
stdout
KNOWN
sort -u "$tmp/known.txt" -o "$tmp/known.txt"

comm -23 "$tmp/needed.txt" "$tmp/provided.txt" | comm -23 - "$tmp/known.txt" > "$tmp/missing.txt"

count=$(wc -l < "$tmp/missing.txt")
echo "library:   $LIB"
echo "undefined: $(wc -l < "$tmp/needed.txt") symbols, of which $count are unprovided"

# ---------------------------------------------------------------------------------------
# Part two: symbols nothing references, which the check above cannot see.
#
# ⚠ THIS SECTION EXISTS BECAUSE PART ONE PASSED ON A BUILD THAT SHOWED A BLACK SCREEN.
#
# JavaFX calls System.loadLibrary("javafx_font"). Inside a native image there is no separate
# library, so the runtime looks up the builtin entry point named after it,
# JNI_OnLoad_javafx_font, BY NAME. Nothing links against that name. It appears in no
# undefined-symbol list, the linker is happy, part one above is happy, and the app dies on
# the phone with a NullPointerException inside PrismFontLoader that names nothing relevant.
#
# The only way to catch a name resolved at runtime is to compare against a build known to
# have worked on real hardware. That is what this does.
REFERENCE_APK="${REFERENCE_APK:-$HOME/ClaudeWorkspace/InvMgr-M6.4i.apk}"

if [ -f "$REFERENCE_APK" ]; then
    unzip -qo "$REFERENCE_APK" 'lib/arm64-v8a/libsubstrate.so' -d "$tmp/ref" 2>/dev/null || true
    REF_LIB="$tmp/ref/lib/arm64-v8a/libsubstrate.so"
fi

if [ -n "${REF_LIB:-}" ] && [ -f "$REF_LIB" ]; then
    nm -D --defined-only "$REF_LIB" 2>/dev/null | awk '{print $3}' | sed 's/@.*//' | sort -u > "$tmp/ref-exports.txt"
    nm -D --defined-only "$LIB"     2>/dev/null | awk '{print $3}' | sed 's/@.*//' | sort -u > "$tmp/our-exports.txt"

    # Only the families that get looked up by name. Everything else in the reference is
    # Gluon's own internals, which we replaced on purpose and must NOT reintroduce.
    comm -23 "$tmp/ref-exports.txt" "$tmp/our-exports.txt" \
        | grep -E '^(JNI_OnLoad_|Java_java_)' \
        | grep -v '^Java_com_gluonhq' > "$tmp/runtime-missing.txt" || true

    rt=$(wc -l < "$tmp/runtime-missing.txt")
    echo "runtime:   $rt entry points the known-good build exports and this one does not"

    if [ "$rt" -ne 0 ]; then
        echo
        echo "MISSING, and these fail at runtime with no symbol error to point at them:"
        sed 's/^/    /' "$tmp/runtime-missing.txt"
        echo
        echo "Reference: $REFERENCE_APK"
        echo "Add a stub to native/invmgr_compat.c for each, then link again."
        exit 1
    fi
else
    echo "runtime:   SKIPPED, no reference APK at $REFERENCE_APK"
    echo "           Set REFERENCE_APK to the last build known to run on hardware."
fi

if [ "$count" -eq 0 ]; then
    echo
    echo "OK: every undefined symbol is provided, and no runtime entry point is missing."
    exit 0
fi

echo
echo "MISSING, and each one of these is a crash on load:"
sed 's/^/    /' "$tmp/missing.txt"
echo
echo "Add a stub to native/invmgr_compat.c for each, then link again."
exit 1
