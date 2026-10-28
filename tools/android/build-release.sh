#!/usr/bin/env bash
# Builds the signed InvMgr that goes to Google Play, and checks it.
#
# Run it yourself, in your own terminal:
#
#     tools/android/build-release.sh
#
# It asks for the keystore's two passwords without showing them, builds, and then checks
# what came out instead of trusting it:
#
#   - the APK is not debuggable, which is what Substrate quietly builds if anything about
#     the keystore is wrong;
#   - the APK and the bundle are both signed with the key in the keystore, compared by
#     fingerprint;
#   - the bundle carries the same code as the APK, file by file, so a check of one holds
#     for the other;
#   - nothing ships that tools/shipped-origins.allow has no record of, read out of the
#     APK's own classes and native libraries by tools/check-shipped-licenses.sh;
#
# and then deletes every file under target/ that holds either password. Substrate writes
# them in plain text into the Gradle project it generates, and this cleans up whatever
# else picked them up too, found by searching for the passwords themselves.
#
# The bundle (.aab) is what gets uploaded. Both files are copied to target/release/, named
# for their version, so the next ordinary build cannot overwrite them.
#
# For a dry run against a throwaway key, set INVMGR_KEYSTORE and INVMGR_KEY_ALIAS. The
# output then goes to target/release-TEST-KEY/ instead, so it cannot be mistaken for the
# real thing. Name the key file .jks or .keystore: Substrate checks the extension, and any
# other name makes it build Debug, which the debuggable check below then stops.

set -euo pipefail
cd "$(dirname "$0")/../.."

REAL_KEYSTORE="$HOME/invmgr-release.jks"
KEYSTORE="${INVMGR_KEYSTORE:-$REAL_KEYSTORE}"
ALIAS="${INVMGR_KEY_ALIAS:-invmgr}"
GRAALVM_HOME="${GRAALVM_HOME:-$HOME/graalvm-svm-java17-linux-gluon-22.1.0.1-Final}"
BUILD_TOOLS="$(ls -d "$HOME"/.gluon/substrate/Android/build-tools/*/ | sort -V | tail -1)"
GVM=target/gluonfx/aarch64-android/gvm
LOG=target/release-build.log

if [ "$KEYSTORE" = "$REAL_KEYSTORE" ]; then
    OUT=target/release
else
    OUT=target/release-TEST-KEY
fi

# Everything the build needs, checked before asking for anything. Maven lives in
# ~/.local/bin, which a new terminal does not always have on its PATH: the first real run
# stopped at "mvn: command not found", after the passwords.
MVN="$(command -v mvn || echo "$HOME/.local/bin/mvn")"
for tool in "$MVN" keytool python3 "$BUILD_TOOLS/aapt2" "$BUILD_TOOLS/apksigner"; do
    command -v "$tool" >/dev/null || { echo "Cannot find $tool" >&2; exit 1; }
done
[ -d "$GRAALVM_HOME" ] || { echo "No GraalVM at $GRAALVM_HOME" >&2; exit 1; }
[ -f "$KEYSTORE" ] || { echo "No keystore at $KEYSTORE" >&2; exit 1; }
export GRAALVM_HOME

read -rsp "Keystore password: " INVMGR_STORE_PASSWORD
echo
read -rsp "Key password (press Enter if it is the same): " INVMGR_KEY_PASSWORD
echo
[ -n "$INVMGR_STORE_PASSWORD" ] || { echo "An empty password cannot be right." >&2; exit 1; }
[ -n "$INVMGR_KEY_PASSWORD" ] || INVMGR_KEY_PASSWORD="$INVMGR_STORE_PASSWORD"
export INVMGR_STORE_PASSWORD INVMGR_KEY_PASSWORD

# Prints stdin with both passwords replaced, for anything shown after the build.
mask() {
    python3 -c 'import os, sys
text = sys.stdin.read()
for name in ("INVMGR_STORE_PASSWORD", "INVMGR_KEY_PASSWORD"):
    text = text.replace(os.environ[name], "****")
sys.stdout.write(text)'
}

# Deletes every file under target/ holding either password, and stops the Gradle daemon the
# build started, which keeps them in its environment for hours otherwise. Runs however the
# script ends. The passwords reach grep through a pipe, never its command line.
scrub() {
    local found
    found="$(printf '%s\n%s\n' "$INVMGR_STORE_PASSWORD" "$INVMGR_KEY_PASSWORD" \
        | grep -rlF -f - target/ 2>/dev/null || true)"
    if [ -n "$found" ]; then
        echo "Deleting files that held a password:"
        printf '%s\n' "$found" | sed 's/^/    /'
        printf '%s\n' "$found" | while IFS= read -r file; do rm -f -- "$file"; done
    fi
    if [ -x "$GVM/android_project/gradlew" ]; then
        (cd "$GVM/android_project" && ./gradlew --stop >/dev/null 2>&1) || true
    fi
}
trap scrub EXIT

# The key first: that the alias exists, and its fingerprint to hold the build against.
KEY_SHA="$(keytool -list -v -keystore "$KEYSTORE" -alias "$ALIAS" \
    -storepass:env INVMGR_STORE_PASSWORD 2>&1 | mask | awk '/SHA256:/ {print $2; exit}')" || true
if [ -z "$KEY_SHA" ]; then
    echo "Could not read the key \"$ALIAS\" from $KEYSTORE: a wrong password or alias." >&2
    exit 1
fi
KEY_SHA="$(printf '%s' "$KEY_SHA" | tr -d ':' | tr 'A-F' 'a-f')"
echo "Key \"$ALIAS\" found. Building; this takes a few minutes."

if ! "$MVN" -B -Pandroid,release -Dinvmgr.keystore="$KEYSTORE" -Dinvmgr.keyAlias="$ALIAS" \
        gluonfx:build gluonfx:package > "$LOG" 2>&1; then
    echo "The build failed. Its last lines:" >&2
    tail -30 "$LOG" | mask >&2
    exit 1
fi

APK="$GVM/InvMgr.apk"
AAB="$GVM/InvMgr.aab"
BADGING="$("$BUILD_TOOLS/aapt2" dump badging "$APK")"
if printf '%s\n' "$BADGING" | grep -q "application-debuggable"; then
    echo "FAILED: the APK is debuggable, so Substrate built Debug. Its log says why:" >&2
    grep -i "key store\|keystore" "$LOG" | mask >&2 || true
    exit 1
fi

APK_SHA="$("$BUILD_TOOLS/apksigner" verify --print-certs "$APK" \
    | awk -F': ' '/Signer #1 certificate SHA-256 digest/ {print $2; exit}')"
AAB_SHA="$(keytool -printcert -jarfile "$AAB" | awk '/SHA256:/ {print $2; exit}' \
    | tr -d ':' | tr 'A-F' 'a-f')"
if [ "$APK_SHA" != "$KEY_SHA" ] || [ "$AAB_SHA" != "$KEY_SHA" ]; then
    echo "FAILED: not signed with the key \"$ALIAS\"." >&2
    echo "    key $KEY_SHA" >&2
    echo "    apk ${APK_SHA:-none}" >&2
    echo "    aab ${AAB_SHA:-none}" >&2
    exit 1
fi

# The license check reads the APK, and Play gets the bundle. Both come out of one Gradle run;
# this holds them to it for every class file and native library, compared by hash.
if ! python3 - "$APK" "$AAB" <<'EOF'
import hashlib, sys, zipfile

def code(path, prefix):
    found = {}
    with zipfile.ZipFile(path) as z:
        for name in z.namelist():
            if name.startswith(prefix) and name.endswith((".dex", ".so")):
                inside = name[len(prefix):]
                inside = inside[len("dex/"):] if inside.startswith("dex/") else inside
                found[inside] = hashlib.sha256(z.read(name)).hexdigest()
    return found

apk, aab = code(sys.argv[1], ""), code(sys.argv[2], "base/")
sys.exit(0 if apk and apk == aab else 1)
EOF
then
    echo "FAILED: the bundle's code is not the APK's, so the APK's checks say nothing about it." >&2
    exit 1
fi

# Run here because a release is the one build that leaves this machine, and 1.0.0 went out
# without it: the check would have failed then, on a class nobody had recorded.
if ! tools/check-shipped-licenses.sh "$APK" > target/release-licenses.log 2>&1; then
    echo "FAILED: the license check found code it has no record of. Do not upload this build." >&2
    grep "NOT ALLOWED" target/release-licenses.log >&2 || tail -20 target/release-licenses.log >&2
    exit 1
fi

VERSION="$(printf '%s\n' "$BADGING" | sed -n "s/^package: .*versionCode='\([0-9]*\)' versionName='\([^']*\)'.*/\2-\1/p")"
mkdir -p "$OUT"
cp "$AAB" "$OUT/InvMgr-$VERSION.aab"
cp "$APK" "$OUT/InvMgr-$VERSION.apk"
echo "OK: release build, not debuggable, signed with \"$ALIAS\" ($KEY_SHA), licenses checked."
printf '%s\n' "$BADGING" | grep "^package:"
echo "Upload this one: $OUT/InvMgr-$VERSION.aab"
