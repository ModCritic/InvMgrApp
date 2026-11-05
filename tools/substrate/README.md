# The patched Gluon Substrate

The Android build uses GluonFX, and GluonFX uses Gluon Substrate to compile the app with GraalVM
Native Image and package it as an APK. Stock Substrate puts some of its own code inside every APK:
Gluon's Android host layer, which is the C that starts the Java VM on the phone and passes JavaFX
the screen, touches and log output, plus two Android Activity classes. That code is GPL-3.0 with
no classpath exception, and InvMgr is 0BSD, so InvMgr brings its own host layer instead
(`native/` and `src/android/`).

`0001-leave-gluon-android-host-code-out.patch` is the change to Substrate that makes that possible:
three small edits to Gluon Substrate's `0.0.69` release, described in the patch itself. It turns
version `0.0.69` into `0.0.69-invmgr3`, which is the version `pom.xml` asks for.

## License

**This folder is not 0BSD.** The patch modifies files from Gluon Substrate, which is licensed
under the GNU General Public License, version 3 or (at your option) any later version, so the
patch is too; `COPYING` is that license. It was modified from Gluon's `0.0.69` release on
2026-10-01, and the patch's own header carries the date and author.

Nothing from Substrate, patched or not, ends up in the app. Substrate is a build tool that runs on
the developer's machine; `tools/licenses/check.py` checks the built APK for it, down to the classes
compiled into its native library.

## Using it

Run this once per machine, before the first Android build:

```bash
tools/android/build-substrate.sh
```

It clones Gluon's own repository at the `0.0.69` tag into a temporary folder, applies the patch,
builds it, and installs it into `~/.m2`, where Maven looks. It needs git, a JDK and network access
for the clone and for Gradle's own dependencies.

It checks two of the things it downloads, and stops rather than build from anything else:

- **Gluon's source, by its commit.** The tag `0.0.69` is only a name, and it could be moved to
  different code. The script checks that it still leads to commit
  `f361a52d10a6fc3ff807cc15600f716c6e40a6bf`, which is what it led to on 2026-10-07.
- **The Gradle that builds it, by its SHA-256.** It adds `distributionSha256Sum` to the clone's
  `gradle-wrapper.properties`, so Gradle's wrapper refuses a download of `gradle-8.13-bin.zip`
  that differs from the one Gradle publishes. A Gradle 8.13 already in `~/.gradle` is used as it
  is, without a check.

Substrate's own library dependencies are fetched by Gradle and are not checked. To move to a newer
Gluon release, change `TAG`, `COMMIT` and, if that release uses a different Gradle,
`GRADLE_SHA256`, looked up from Gradle's own list of checksums; the script stops and says so if
the Gradle version no longer matches the checksum.
