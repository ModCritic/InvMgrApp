# InvMgr: Inventory Manager

A top-down inventory manager for keeping track of boxes in a to-scale map of your space, be it a garage, attic, or a storage-unit-because-you-moved-years-ago-and-still-don't-have-enough-space-for-your-stuff-and-you-have-no-idea-where-anything-is (couldn't be me). Of course, you can organize non-boxes the same way, since it's mainly a record of what's where. You create items to scale in your to-scale room and stack them the way they're stacked in real life. You can plan items in the list before deciding where they go, and you can walk through the room in 3D to check how well it matches up.

This is the Java rewrite of the original single-file HTML app, [InvMgr](https://github.com/ModCritic/InvMgr). It runs on desktop (Windows, Linux, and macOS) and on Android. No account, no ads, no analytics, and no network calls at runtime. What you make stays on your device unless you export it.

<p align="center">
  <img src="https://raw.githubusercontent.com/ModCritic/AsteroidRawFileEmbeds/main/desktopinvmgrapp.webp" height="356">
  <img src="https://raw.githubusercontent.com/ModCritic/AsteroidRawFileEmbeds/main/mobileinvmgrapp.webp" height="356">
</p>

## Features

### Room & Display
- Enter your room's width, length, and height; the 2D view is a true-to-scale top-down layout of the room.
- Fit toggle to see the whole room at once.
- Imperial or metric, switched in the Menu.

### Items
- Add items with an optional name, their actual width, length, and height, and an optional ID (not shown in tooltips or the item list, but included in the *exported* item list, for SKUs, barcodes, asset tags, or just saying it's a box rather than a bin).
- Drag items to move them.
- Items settle onto whatever is under their footprint, so dragging one item onto another stacks it on top. Turn on **Layer Collision** to keep every item at its current height instead; items at overlapping heights then block each other, so you can place things more precisely.
- **Planned Items**: put an item in the list before it has a spot in the room, then drag it from the list into the room when it does.
- **Presets**: save named width/length/height combinations for sizes you use often (like standard U-Haul boxes), and apply one to the Add Item dialog with one tap.
- Click an item in the room, or double-click it in the list, to edit it (double-tap on a phone). The Edit dialog has a button that turns the item 90 degrees, in case you entered it the wrong way around.
- Delete an item with a right-click on desktop, or by holding it for 1.5 seconds on a phone.

### Layer Slider
Peel back the room by height to see what's stacked underneath, like looking at a cross-section from above.

### Item List
A searchable list of everything in the room. Search by name, or by size: `W18` finds everything 18 inches wide, and `W18 H16` narrows that down further. Export the whole list as a plain-text file, one line per item with its name, dimensions, and ID.

### Save & Recovery
- The room saves itself as you work and is back the next time you open the app. The last five sessions are also kept as timestamped backups.
- Save and load the whole room as a JSON file, to keep a copy or move it to another device. The HTML version reads these files and the other way around.

### 3D View
See the room from directly overhead, then descend into a first-person view you can walk around in.

- **Desktop:** WASD to move, drag to look, Shift to go faster, Space and C (or Ctrl) for up and down, Esc to go back to 2D.
- **Touch:** on-screen joystick to move, drag to look, two-finger drag to pan, pinch to move forward.
- Hover or tap any item in 3D to see the same tooltip you'd get in the 2D view.

### Undo
Undo for adding, moving, editing, and deleting items, up to 500 steps back.

### Menu
Metric, Settings (on Android, hiding the phone's status and navigation bars), Feedback, About with the credits, and Reset Room to start over with an empty room.

## Getting Started

The [Releases](https://github.com/ModCritic/InvMgrApp/releases) page has installers for Windows and Linux, the desktop jar, and the Android APK.

### Android
Install the APK from Releases, or get it on [Google Play](https://play.google.com/store/apps/details?id=com.asterkazika.invmgr). [Privacy policy](https://invmgr.asterkazika.com/privacy/).

It needs a 64-bit phone. 32-bit phones can't install it at all, because GraalVM, which compiles the Android version, only produces 64-bit code. The app asks for Android 5.0 or newer, but it has only been tested on Android 10 (a Galaxy S9) and on Android 15 and 16 emulators.

### Windows
Download `InvMgr-<version>-windows-x64-setup.exe` and run it. It installs for your account without asking for admin rights (it offers to install for everyone instead), puts InvMgr in the Start menu, and carries its own Java, so there's nothing else to install. Uninstall it from Windows' list of installed apps; your rooms stay where they are.

It needs 64-bit Windows 10 or newer. The setup isn't signed, so Windows may show a SmartScreen warning the first time you run it. It's been tested on Windows 10 in a virtual machine.

### Linux
Four downloads, each carrying its own Java:

- `InvMgr-<version>-x86_64.AppImage`: one file; mark it executable and run it.
- `InvMgr-<version>-linux-x64.tar.gz`: unpack it anywhere and run `InvMgr/bin/InvMgr`.
- `invmgr_<version>-1_amd64.deb` for Debian, Ubuntu and their relatives: `sudo apt install ./invmgr_<version>-1_amd64.deb`
- `invmgr-<version>-1.x86_64.rpm` for Fedora and its relatives: `sudo dnf install ./invmgr-<version>-1.x86_64.rpm`

The two packages install to `/opt/invmgr` and add InvMgr to the app menu. All four have been tested on Debian testing, Ubuntu 26.04 and Fedora 45, on 64-bit x86.

### Any desktop, with Java
Download the jar from Releases and run it with Java 17 or newer:

```bash
java -jar invmgr-<version>.jar
```

The jar carries JavaFX for Windows, Linux, and Apple Silicon Macs, so Java is the only thing to install. It's been tested on Linux and Windows, but not yet on a Mac.

To build it yourself instead, you need JDK 17 or newer and Maven:

```bash
mvn -B -Pdesktop-jar -DskipTests package
java -jar target/invmgr-<version>.jar
```

Or run it straight from the source with `mvn javafx:run`.

## How It's Built

Java 17 and JavaFX 21, built with Maven. The 3D view uses JavaFX's own 3D, with no game engine.

The Android version is compiled ahead of time with GraalVM Native Image through GluonFX. It runs on a small Android host layer written for this project (`native/` and `src/android/`) instead of Gluon's own, so none of Gluon's GPL-3.0 code ends up in the app. Building it needs a patched Gluon Substrate: three small changes that leave Gluon's Android code out of the build, kept in `tools/substrate/` as a patch on Gluon's 0.0.69 release. `tools/android/build-substrate.sh` applies it, builds it and installs it where Maven looks, once per machine, before the first Android build.

The Windows and Linux downloads are made by `jpackage` from the desktop jar, with a Java runtime cut from Eclipse Temurin 17. `tools/installers/build-linux.sh` makes the four Linux downloads on Linux, and `tools/installers/build-windows.cmd` makes the Windows setup on Windows, with Inno Setup 7. Before a download is published, `tools/licenses/check.py` proves where every file in it came from and checks what every license it carries asks for.

## Status

1.0.0 is the first release of the rewrite. It does everything the HTML version does, and it's where new features go from here. If you find a bug, open an issue.

## License

Original code is released under the 0BSD license (see `LICENSE`), with two exceptions. `tools/substrate/`, a patch to Gluon Substrate, is GPL-3.0-or-later like Substrate itself; it's a build tool, and nothing from it ends up in the app. And the app's icons are copyright Asterkazika LLC, all rights reserved: they're here so the app builds the way it ships, but a fork should use its own. The app also ships work by others: the Noto fonts, OpenJFX, FreeType in the Android build, and the AndroidX and Kotlin libraries the Android build pulls in. See `NOTICE.md` for those. The Windows and Linux downloads also carry Eclipse Temurin's Java runtime, which is GPL-2.0 with the Classpath Exception; each download includes the licenses of what's inside it, and every Release has the source code those licenses ask for.
