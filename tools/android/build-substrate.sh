#!/usr/bin/env bash
# Builds the patched Gluon Substrate the Android build needs, and installs it into ~/.m2.
#
# pom.xml asks for Substrate 0.0.69-invmgr3: Gluon's own 0.0.69 release with the patch in
# tools/substrate applied, which keeps Gluon's Android host code (GPL-3.0) out of the APK. The
# patch is GPL-3.0-or-later like Substrate itself; see tools/substrate/README.md.
#
# usage: tools/android/build-substrate.sh
# Needs git, a JDK and network access for the clone and for Gradle's own dependencies. Run it once
# per machine, before the first Android build.
#
# Two things it downloads are checked against fingerprints written below, and it stops rather
# than build from anything else: Gluon's source, by its commit, and the Gradle that builds it, by
# its SHA-256. This matters more than for most build tools because of what Substrate does with the
# result: it links and packages the APK, and a release build hands it the upload keystore and both
# of its passwords so it can sign. Substrate's own library dependencies are fetched by Gradle and
# are not checked here.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
PATCH="$HERE/../substrate/0001-leave-gluon-android-host-code-out.patch"
UPSTREAM="https://github.com/gluonhq/substrate.git"
TAG="0.0.69"
# The commit TAG led to on 2026-10-07, read with `git ls-remote`. A tag is only a name, and anyone
# who can push to Gluon's repository can point it at different code. A commit hash is worked out
# from the code itself, so it cannot be made to mean anything else.
COMMIT="f361a52d10a6fc3ff807cc15600f716c6e40a6bf"
# The SHA-256 of gradle-8.13-bin.zip, the Gradle that COMMIT's build downloads. Three sources
# agreed on 2026-10-07: services.gradle.org's .sha256 file, gradle.org/release-checksums, and a
# copy this machine downloaded on 2026-08-30. Gradle's wrapper refuses a download that differs.
# It checks only what it downloads: a Gradle 8.13 already in ~/.gradle is used as it is, unchecked.
GRADLE_SHA256="20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78"
VERSION="0.0.69-invmgr3"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# The -c turns off git's advice about a detached HEAD, which cloning a tag prints even with --quiet
# and which would otherwise bury the one line that matters if the check below stops.
git -c advice.detachedHead=false clone --quiet --depth 1 --branch "$TAG" "$UPSTREAM" "$work/substrate"
cd "$work/substrate"
got="$(git rev-parse HEAD)"
if [ "$got" != "$COMMIT" ]; then
    echo "STOPPED: Gluon's $TAG tag now leads to commit $got, not $COMMIT." >&2
    echo "Nothing was built. Find out why the tag moved before trusting what it leads to." >&2
    exit 1
fi
git apply "$PATCH"

# The checksum is for 8.13. A new COMMIT that asks for a different Gradle needs a new checksum,
# and this is what says so, rather than the wrapper refusing a download with no explanation.
WRAPPER=gradle/wrapper/gradle-wrapper.properties
if ! grep -q '/gradle-8\.13-bin\.zip$' "$WRAPPER"; then
    echo "STOPPED: this commit's build asks for a Gradle other than 8.13:" >&2
    grep '^distributionUrl' "$WRAPPER" >&2
    echo "GRADLE_SHA256 is 8.13's. Look up the new version's checksum before changing it." >&2
    exit 1
fi
# The leading newline is in case the file does not end in one; a blank line is harmless there.
printf '\ndistributionSha256Sum=%s\n' "$GRADLE_SHA256" >> "$WRAPPER"
# Signing is for Gluon's own releases to Maven Central, and needs their key.
./gradlew --quiet publishToMavenLocal -x sign -x signMavenPublication

jar="$HOME/.m2/repository/com/gluonhq/substrate/$VERSION/substrate-$VERSION.jar"
if [ ! -f "$jar" ]; then
    echo "the build finished but $jar is not there" >&2
    exit 1
fi
echo "installed $jar"
