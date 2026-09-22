# Third party notices

InvMgr itself is 0BSD; see [`LICENSE`](LICENSE). It ships alongside work by other people,
and this file is where that is acknowledged. **It is part of the distribution
documentation**: keep it with the app, and carry its contents into a store listing or an
about screen wherever one exists.

## FreeType

**Portions of this software are based in part on the work of the FreeType Team.**

That sentence is the requirement, not a courtesy. The Android build bundles
`lib/arm64-v8a/libfreetype.so`, and the FreeType Project License asks binary
redistributors for exactly this disclaimer in their distribution documentation. The
project's page is <https://www.freetype.org>.

FreeType is dual licensed, under the FreeType Project License (FTL) or the GNU General
Public License version 2, and the choice belongs to whoever redistributes it. **This
project elects the FTL.** The library arrives prebuilt inside the JavaFX static SDK that
the Android build uses; it is upstream FreeType 2.14.1, unmodified here.

## Noto Sans Mono and Noto Sans Math

Both typefaces are bundled, under the SIL Open Font License 1.1. `OFL.txt` ships in the
same directory as the two `.ttf` files, in the jar and in the APK, which is what clause 2
of that license requires. Copyright the Noto Project Authors,
<https://github.com/notofonts>.

## OpenJFX

The user interface is JavaFX. OpenJFX is GPLv2 with the Classpath Exception, and that
exception is what allows this project to be 0BSD while linking it.

## The Android build

The generated Android project pulls in AndroidX, the Kotlin standard library and Kotlin
coroutines, all Apache-2.0. None of them was chosen here; the build adds them.

---

Every claim above was checked against the artifact that actually ships rather than against
a badge or a dependency list, which is a distinction this project learned the hard way.
`tools/check-shipped-licenses.sh` reads the built APK and jar and fails on anything whose
origin is not accounted for.
