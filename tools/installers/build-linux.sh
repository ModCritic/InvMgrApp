#!/usr/bin/env bash
# Builds InvMgr's four Linux downloads from the desktop jar, each carrying its own Java, so
# nobody has to install Java first:
#
#     tools/installers/build-linux.sh [path/to/invmgr-<version>.jar]
#
# With no argument it uses target/invmgr-<version>.jar, which
# `mvn -B -Pdesktop-jar -DskipTests package` makes. Out come, in target/installers/:
#
#   InvMgr-<version>-linux-x64.tar.gz   unpack anywhere and run InvMgr/bin/InvMgr
#   InvMgr-<version>-x86_64.AppImage    one file; mark it executable and run it
#   invmgr_<version>-1_amd64.deb         Debian, Ubuntu and relatives; needs dpkg-deb here
#   invmgr-<version>-1.x86_64.rpm        Fedora, openSUSE and relatives; needs rpmbuild here
#
# A package whose tool is missing is skipped with a note, and the others still build.
#
# The Java inside comes from Eclipse Temurin 17, not from the JDK this machine runs on. A
# distribution's own JDK is built for that distribution: Arch's needs glibc 2.38, and its image
# libraries load the system's libjpeg by a file name Debian and Fedora don't use. Temurin's needs
# glibc 2.9 and carries those libraries itself. jpackage comes from Temurin as well, since the
# launcher it writes into the app is built the same way. Set TEMURIN_HOME to use another copy.
#
# The AppImage is put together by appimagetool around a pinned runtime file: the small program at
# the front of every AppImage that mounts the rest and starts AppRun. Handing it a fixed file,
# rather than letting appimagetool fetch the newest, is what lets a release name the exact runtime
# and the libfuse inside it, whose source each release has to carry. Set APPIMAGETOOL and
# APPIMAGE_RUNTIME to use other copies.
#
# The AppImage also carries the licenses of that runtime and of the libraries built into it,
# copied out of their source archives, the same ones the release's source zip holds. Set
# LINUX_SOURCES to the folder that has them.
#
# Last, every download goes through the license check, tools/licenses/check.py, and a failure stops
# the script. The check matches the jar's own files against the compiled classes of the build that
# made it: target/classes, or set CLASSES. For a release, set INSTALLERS_RELEASE to the release folder,
# which holds the source zips (tools/installers/installer-sources.py bundle makes the installers' one), and
# the check also proves each source the licenses ask for is in them.
set -euo pipefail

cd "$(dirname "$0")/../.."

TEMURIN_HOME="${TEMURIN_HOME:-$HOME/ClaudeWorkspace/InvMgr-build/temurin/jdk-17.0.21+9}"
APPIMAGETOOL="${APPIMAGETOOL:-$HOME/ClaudeWorkspace/InvMgr-build/appimage/tool/appimagetool-x86_64.AppImage}"
APPIMAGE_RUNTIME="${APPIMAGE_RUNTIME:-$HOME/ClaudeWorkspace/InvMgr-build/appimage/runtime-20251108/runtime-x86_64}"
LINUX_SOURCES="${LINUX_SOURCES:-$HOME/ClaudeWorkspace/InvMgr-build/linux-sources}"

# The project's own version, the first <version> after <artifactId>invmgr; the comment between
# them is skipped by the range.
VERSION="$(sed -n '/<artifactId>invmgr<\/artifactId>/,/<version>/s/.*<version>\([^<]*\)<\/version>.*/\1/p' pom.xml | head -1)"
JAR="${1:-target/invmgr-$VERSION.jar}"
ICON=assets/icons/icon-linux-512.png
OUT=target/installers
WORK=target/installers-work

[ -n "$VERSION" ] || { echo "Cannot read the version from pom.xml" >&2; exit 1; }
[ -f "$JAR" ] || { echo "No jar at $JAR. Build it: mvn -B -Pdesktop-jar -DskipTests package" >&2; exit 1; }
[ -x "$TEMURIN_HOME/bin/jpackage" ] || { echo "No Temurin JDK at $TEMURIN_HOME" >&2; exit 1; }
[ -x "$APPIMAGETOOL" ] || { echo "No appimagetool at $APPIMAGETOOL" >&2; exit 1; }
[ -f "$APPIMAGE_RUNTIME" ] || { echo "No AppImage runtime at $APPIMAGE_RUNTIME" >&2; exit 1; }
[ -d "$LINUX_SOURCES" ] || { echo "No source archives at $LINUX_SOURCES" >&2; exit 1; }

rm -rf "$OUT" "$WORK"
mkdir -p "$OUT" "$WORK/input"
cp "$JAR" "$WORK/input/"

# Which parts of Java the app uses, read from the jar each time so a new import is never missed.
# jlink, run by jpackage, adds whatever those parts need in turn.
MODULES="$("$TEMURIN_HOME/bin/jdeps" --ignore-missing-deps --multi-release 17 --print-module-deps "$JAR")"
echo "Java modules: $MODULES"

COMMON=(--name InvMgr --app-version "$VERSION" --vendor "Asterkazika LLC"
        --description "Top-down room inventory manager")

# A desktop matches a running window to its menu entry by the window's X11 class, which JavaFX
# sets to the name of the app's Application class. Without StartupWMClass naming it, the dock
# shows the running app under a generic icon, apart from the menu's InvMgr. The packages' menu
# entry, tools/installers/InvMgr.desktop, holds it, and the AppImage's entry takes it from there,
# so renaming com.modcritic.invmgr.App means changing that one line.
WM_CLASS="$(sed -n 's/^StartupWMClass=//p' tools/installers/InvMgr.desktop)"

# The app image: the folder every other format is made from. bin/InvMgr is the launcher,
# lib/app holds the jar, and lib/runtime is the trimmed Java with its legal/ folder.
"$TEMURIN_HOME/bin/jpackage" --type app-image "${COMMON[@]}" \
    --input "$WORK/input" --main-jar "$(basename "$JAR")" \
    --main-class com.modcritic.invmgr.Launcher \
    --add-modules "$MODULES" --icon "$ICON" --dest "$WORK"

# The runtime's lib/security/cacerts, its list of trusted certificate authorities, is made from
# Mozilla's list and is under the Mozilla Public License 2.0, which asks that whoever gets it be
# told where its source is. Temurin's legal folder has no notice for it, so the runtime gets ours,
# tools/installers/cacerts.md (the Windows build adds the same one), read-only like the rest.
install -m 444 tools/installers/cacerts.md "$WORK/InvMgr/lib/runtime/legal/java.base/cacerts.md"

# Owner and order fixed, so the archive does not carry this machine's user name.
tar -C "$WORK" --owner=0 --group=0 --numeric-owner --sort=name \
    -czf "$OUT/InvMgr-$VERSION-linux-x64.tar.gz" InvMgr

APPDIR="$WORK/InvMgr.AppDir"
mkdir -p "$APPDIR"
cp -a "$WORK/InvMgr" "$APPDIR/InvMgr"
cp "$ICON" "$APPDIR/invmgr.png"
cat > "$APPDIR/AppRun" <<'EOF'
#!/bin/sh
# Starts InvMgr from inside the AppImage, wherever the runtime mounted it.
HERE="$(dirname "$(readlink -f "$0")")"
exec "$HERE/InvMgr/bin/InvMgr" "$@"
EOF
chmod +x "$APPDIR/AppRun"

# The runtime's own MIT license, and the licenses of the six libraries linked into it, which ask
# that every copy carry their notices: libfuse's LGPL-2.1 asks for a copy of the LGPL and a notice
# that libfuse is used, BSD and MIT for their copyright and permission notices. Each file is the
# library's own, unchanged; runtime.c's notice is that file's opening comment.
RUNTIME_SRC=type2-runtime-dd6cebedcbddde9c82f89b011e8e1d40b6e43868
license_file() {    # archive, file inside it, folder under licenses/
    mkdir -p "$APPDIR/licenses/$3"
    tar -xOf "$LINUX_SOURCES/$1" "$2" > "$APPDIR/licenses/$3/$(basename "$2")"
}
license_file type2-runtime-dd6cebed-source.tar.gz "$RUNTIME_SRC/LICENSE" appimage-runtime
tar -xOf "$LINUX_SOURCES/type2-runtime-dd6cebed-source.tar.gz" "$RUNTIME_SRC/src/runtime/runtime.c" \
    | sed -n '1,/^ \*\*\**\/$/p' > "$APPDIR/licenses/appimage-runtime/runtime.c-notice.txt"
license_file fuse-3.15.0.tar.xz fuse-3.15.0/LGPL2.txt libfuse
license_file fuse-3.15.0.tar.xz fuse-3.15.0/LICENSE libfuse
license_file squashfuse-0.5.2.tar.gz squashfuse-0.5.2/LICENSE squashfuse
license_file zstd-1.5.6.tar.gz zstd-1.5.6/LICENSE zstd
license_file zlib-1.3.1.tar.gz zlib-1.3.1/LICENSE zlib
license_file musl-1.2.5.tar.gz musl-1.2.5/COPYRIGHT musl
license_file mimalloc-2.1.7-source.tar.gz mimalloc-2.1.7/LICENSE mimalloc
cat > "$APPDIR/licenses/README.txt" <<'EOF'
Licenses for the AppImage runtime

This AppImage starts with the AppImage runtime, release 20251108 of
https://github.com/AppImage/type2-runtime, the small program that mounts the rest and starts
InvMgr. It is MIT licensed; its notices are in appimage-runtime/. These libraries are built into
it, each under its own license, which is in the folder named for it:

  libfuse 3.15.0    the GNU Lesser General Public License, version 2.1 (libfuse/LGPL2.txt),
                    with the runtime's one change to lib/mount.c
  squashfuse 0.5.2  a BSD license with two conditions
  zstd 1.5.6        a BSD license with three conditions, chosen of its BSD license or GPLv2
  zlib 1.3.1        the zlib license
  musl 1.2.5        the MIT license
  mimalloc 2.1.7    the MIT license

The source of the runtime and of all six libraries, the change to libfuse included, is in
InvMgr-<version>-installers-third-party-source.zip on the release page this AppImage came from:
https://github.com/ModCritic/InvMgrApp/releases

These licenses are for the runtime. InvMgr itself, in the InvMgr folder, is 0BSD; it is a
separate program stored beside the runtime, and the runtime starts it without linking to it.
EOF
cat > "$APPDIR/invmgr.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=InvMgr
Comment=Top-down room inventory manager
Exec=InvMgr
Icon=invmgr
Categories=Utility;
Terminal=false
StartupWMClass=$WM_CLASS
EOF
# appimagetool is an AppImage too. Extract-and-run starts it without FUSE on this machine.
APPIMAGE_EXTRACT_AND_RUN=1 ARCH=x86_64 "$APPIMAGETOOL" --no-appstream \
    --runtime-file "$APPIMAGE_RUNTIME" "$APPDIR" "$OUT/InvMgr-$VERSION-x86_64.AppImage"

# The two packages install the app image under /opt/invmgr and add a menu entry. --icon has to be
# given again here: without it jpackage replaces the app image's icon with its own, a 32-pixel
# picture of Java's mascot, and that is what the menu shows. The menu entry is ours,
# tools/installers/InvMgr.desktop; jpackage uses a file named <app name>.desktop in --resource-dir
# in place of its own and fills in the capitalized words.
mkdir -p "$WORK/resources"
cp tools/installers/InvMgr.desktop "$WORK/resources/"
PACKAGE=(--app-image "$WORK/InvMgr" "${COMMON[@]}" --icon "$ICON" --linux-package-name invmgr
         --linux-shortcut --resource-dir "$WORK/resources" --dest "$OUT")
if command -v dpkg-deb >/dev/null; then
    # The .deb's control file and its two scripts are ours, in tools/installers/deb/; jpackage uses
    # a file of the same name in --resource-dir in place of its own template and fills in the
    # capitalized words the same way.
    cp tools/installers/deb/control tools/installers/deb/postinst tools/installers/deb/prerm "$WORK/resources/"
    "$TEMURIN_HOME/bin/jpackage" --type deb "${PACKAGE[@]}" \
        --linux-deb-maintainer contact@asterkazika.com --linux-app-category utils
    # jpackage also writes share/doc/copyright into every application .deb, and no option leaves
    # it out: it fills in the license text when given one and says "License: Unknown" when not,
    # under a copyright line that names no one. InvMgr's license asks for no notice, so the file
    # comes out again, and so do the preinst and postrm scripts jpackage always adds, which do
    # nothing. The package is unpacked, the three deleted, and the package built back as it was.
    DEB="$OUT/invmgr_${VERSION}-1_amd64.deb"
    dpkg-deb -R "$DEB" "$WORK/deb"
    rm -r "$WORK/deb/opt/invmgr/share" "$WORK/deb/DEBIAN/preinst" "$WORK/deb/DEBIAN/postrm"
    dpkg-deb --root-owner-group -Zxz -b "$WORK/deb" "$DEB" >/dev/null
else
    echo "Skipped the .deb: dpkg-deb is not installed (Arch: sudo pacman -S dpkg)" >&2
fi
if command -v rpmbuild >/dev/null; then
    "$TEMURIN_HOME/bin/jpackage" --type rpm "${PACKAGE[@]}" --license-file LICENSE --linux-rpm-license-type 0BSD
else
    echo "Skipped the .rpm: rpmbuild is not installed (Arch: sudo pacman -S rpm-tools)" >&2
fi

# The check proves where every file in each download came from and that each license's conditions
# are met. Those about the release's source zips need the release folder: without INSTALLERS_RELEASE
# they are reported as not checked. Anything that fails stops the build, with what failed.
CLASSES="${CLASSES:-target/classes}"
RELEASE_ARGS=()
if [ -n "${INSTALLERS_RELEASE:-}" ]; then
    RELEASE_ARGS=(--release "$INSTALLERS_RELEASE" --version "$VERSION")
fi
echo
for download in "$OUT"/*; do
    log="$WORK/license-check-$(basename "$download").txt"
    if python3 tools/licenses/check.py linux "$download" --classes "$CLASSES" "${RELEASE_ARGS[@]}" > "$log" 2>&1; then
        echo "License check passed${INSTALLERS_RELEASE:+, sources included}: $(basename "$download")"
    else
        sed -n '/^NO PROVEN\|^CLAIMED/,/^$/p; /^PROBLEM/p; /^Traceback/,$p' "$log" >&2
        echo "License check FAILED: $(basename "$download"). The whole report is $log" >&2
        exit 1
    fi
done

echo
(cd "$OUT" && ls -l && sha256sum ./*)
