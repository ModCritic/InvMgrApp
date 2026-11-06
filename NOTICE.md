# Third party notices

InvMgr itself is 0BSD; see [`LICENSE`](LICENSE). It ships alongside work by other people.
This file is where that is acknowledged. **It is part of the distribution
documentation**: keep it with the app, and carry its contents into a store listing or an
about screen wherever one exists.

## InvMgr's icons

**The app's icons are not under 0BSD: they are copyright Asterkazika LLC, all rights reserved.** That covers
`assets/icons/icon-source.png`, the icons made from it (`assets/icons/icon-play-512.png`,
`assets/icons/icon-linux-512.png` and the Windows `assets/icons/InvMgr.ico`), the launcher images in
`src/android/res/`, and the HTML version's install icons `assets/icons/icon-192.png`,
`icon-512.png` and `icon-512-maskable.png`. They are in this repository so the app builds the way
it ships; using them for anything else needs permission, and a fork or a build of your own should
use icons of its own. `tools/icon/make_icons.py`, which draws the launcher images from the source, is 0BSD like
the rest of the code.

## FreeType

**Portions of this software are based in part on the work of the FreeType Team.**

The Android build bundles `lib/arm64-v8a/libfreetype.so`, and the FreeType Project 
License asks binary redistributors for exactly this disclaimer in their distribution 
documentation. The project's page is <https://www.freetype.org>.

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

## The patched Gluon Substrate in tools/substrate

`tools/substrate/` holds a patch to Gluon Substrate, the build tool GluonFX uses to compile the
Android version. **That folder is licensed under the GNU General Public License, version 3 or
later, like Substrate itself, not 0BSD**; its `COPYING` is the license and its README explains the
patch. It is a separate work kept beside InvMgr in the same repository, and nothing from
Substrate, patched or not, is part of the app.

## The Android build

The generated Android project pulls in AndroidX, the Kotlin standard library and Kotlin
coroutines, all Apache-2.0. None of them were chosen here; the build adds them.
