InvMgr {version}: source for the third-party code in the installers

The four Linux downloads (the .tar.gz, the AppImage, the .deb and the .rpm) and the Windows setup
hold the same app: InvMgr's own jar, a Java runtime and the launcher that starts it, built for each
system from the same source. The AppImage also holds the AppImage runtime, the small program at its
front that mounts the rest. The Windows setup is made by Inno Setup, whose license asks for no
source.

InvMgr's own code is 0BSD. The app's source is the repository at tag v{version}, which GitHub's
"Source code" links on the release page download, and the scripts that build the installers, with
the menu entry and package files they add, are in the repository's tools/installers/ folder. The
OpenJFX inside the jar is covered by the jar's own source archive on the same page. This archive
holds the source of the rest.

The Java runtime and the launcher come from Eclipse Temurin 17.0.21+9, under GPLv2 with the
Classpath Exception. The runtime is Temurin's, cut down to the modules InvMgr uses by jlink;
the launcher is jpackage's, its code unchanged (on Windows it gains InvMgr's icon and version).
The .rpm's package scripts are jpackage's templates with the package's name and paths filled in;
the .deb's are InvMgr's own.

The AppImage runtime is release 20251108 of AppImage/type2-runtime, MIT licensed. It is built
with libfuse 3.15.0 (LGPL-2.1) linked in statically, along with squashfuse, zstd, zlib, musl
and mimalloc. This archive holds all of their source, so the runtime can be rebuilt, or relinked
with a changed libfuse.


FILES

jdk17u-jdk-17.0.21+9_adopt-source.tar.gz
    Temurin's source: https://github.com/adoptium/jdk17u at tag jdk-17.0.21+9_adopt, commit
    551f220d8884e13cfd14f4ec8845d83e6e30d951, the commit the runtime's own release file names.
    It holds the Java runtime, jlink, jpackage, the launcher, the package templates, and the
    build system. The Mozilla Public Suffix List the runtime carries in compiled form
    (lib/security/public_suffix_list.dat, MPL-2.0) is make/data/publicsuffixlist/ in it.

temurin-build-1cf43d7a-source.tar.gz
    The scripts that built it: https://github.com/adoptium/temurin-build at commit
    1cf43d7a3e77886bfea7d550a902e151a87d724d, also named in the runtime's release file. Its
    security/certdata.txt is the source of the runtime's lib/security/cacerts (MPL-2.0).

gcc-10.3.0.tar.xz
    GCC 10.3.0 as released by GNU (https://ftp.gnu.org/gnu/gcc/gcc-10.3.0/). Temurin's native
    code was compiled with it and has its C++ runtime library, libstdc++, linked in.

type2-runtime-dd6cebed-source.tar.gz
    The AppImage runtime: https://github.com/AppImage/type2-runtime at commit
    dd6cebedcbddde9c82f89b011e8e1d40b6e43868, its release 20251108. Its build scripts are
    scripts/, and patches/libfuse/mount.c.diff is the one change it makes to libfuse.

fuse-3.15.0.tar.xz
squashfuse-0.5.2.tar.gz
    The two libraries the runtime's build compiles from source, the same files its
    scripts/common/install-dependencies.sh downloads and checks by SHA-256.

zstd-1.5.6.tar.gz
zlib-1.3.1.tar.gz
musl-1.2.5.tar.gz
mimalloc-2.1.7-source.tar.gz
    The other four libraries in the runtime, from each project's own release. The runtime's
    build took them from Alpine Linux 3.21's packages rather than building them from these
    files.

alpine-3.21-aports-runtime-libs.tar.gz
    Alpine's build recipes and patches for those four packages, as they stood when the runtime
    was built: https://github.com/alpinelinux/aports at the commits its COMMITS file names
    (musl 1.2.5-r9, mimalloc2 2.1.7-r0, zstd 1.5.6-r2, zlib 1.3.1-r2).

SHA256SUMS
    The SHA-256 checksum of each file above.

The commit of each GitHub archive above can be read out of it with git get-tar-commit-id on
the uncompressed archive.
