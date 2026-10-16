# targetsdk-adder-and-changer

Run apps that need a newer Android on an old phone, here a **Sony Xperia XA1**
stuck on **Android 8.0 (API 26)**, by lowering the APK's `minSdkVersion`.

## The short answer: how simple is it?

| Approach | Effort | Does it work? |
|---|---|---|
| **Patch the APK's `minSdkVersion`** (this repo, `sdkpatch.py`) | Done. One command per app | Installs every time. Whether the app **runs** depends on the app (see below) |
| **Root hook that skips the installer's minSdk check** (Xposed/EdXposed module) | Medium: a small hook module, but Android 8 needs the old EdXposed + Riru stack | Same runtime limits as patching. Keeps the original signature |
| **Fake the system version** (`resetprop ro.build.version.sdk 30`, build.prop edit) | Trivial to do | **Don't.** The framework, ART and vendor blobs branch on that value. Expect a bootloop or random breakage, and apps still crash on the missing APIs |
| **Actually run Android 11** (custom ROM / GSI) | Hard. The XA1 is MediaTek (Helio P20) and was never Treble-enabled from the factory | The only real fix, if a working build exists for your exact model |

**Why no patch can turn Android 8 into Android 11:** the version number is
just a label. What an app actually needs are the *classes and methods* added
in API 27-30. They aren't on the phone, so the app fails with `NoSuchMethodError` /
`ClassNotFoundException` the first time it touches one. A patch can only get
the app past the installer. After that, the app works only if it doesn't use
anything newer than what the phone has.

### Which apps are likely to work after patching

- **Good odds:** apps whose minSdk was raised for policy or maintenance
  reasons rather than for new APIs. Small open-source apps, utilities,
  media players. Also apps that only need 1-2 levels more (minSdk 27/28).
- **Bad odds:** apps with a high minSdk (29+) built with R8. R8 assumes
  `SDK_INT >= minSdk` and **deletes** the old-Android fallback branches,
  so the new APIs get called without any check.
- **Won't work:** apps that verify their own signature, use Play Integrity /
  SafetyNet, banking apps, or apps that need a newer Google Play Services
  than the XA1 can get.

## How `sdkpatch.py` works

Requirements: `python3`, plus `zipalign`, `apksigner` and `keytool` on
`PATH` (all present on this machine). No apktool needed.

1. Reads the compiled (binary XML) `AndroidManifest.xml` out of the APK.
2. Walks its chunks, decodes the string pool and resource map, and finds
   the `<uses-sdk>` element's `minSdkVersion` / `targetSdkVersion` attributes
   by their `android.R.attr` resource ids (`0x0101020c` / `0x01010270`).
3. Overwrites the 4-byte integer value **in place**. The file stays the same
   size, and nothing else (dex, resources, native libs) is touched or
   recompiled, so there's no apktool rebuild that can break.
4. Repacks the zip. It drops the old v1 signature files from `META-INF/`
   and keeps each entry's compression (stored stays stored).
5. Runs `zipalign -p 4` and signs v1+v2+v3 with `apksigner`, using
   `keys/sdkpatch.keystore`. That key is created on first use with the
   password `android`, and it is **your** key, so keep it if you want to
   install updates later.

### Usage

```sh
# what does an APK require?
./sdkpatch.py info app.apk

# lower minSdk to the XA1's level (Android 8.0 = 26, 8.1 = 27)
./sdkpatch.py patch app.apk --min-sdk 26 -o out/

# split APKs (from .apks/.xapk bundles): unzip the bundle, pass base + every
# split together so all of them get the same signature
./sdkpatch.py patch base.apk split_config.arm64_v8a.apk split_config.xxhdpi.apk \
    --min-sdk 26 -o out/
adb install-multiple out/*.apk
```

To check the phone's real API level: `adb shell getprop ro.build.version.sdk`.

Leave `--target-sdk` alone unless you have a specific reason to change it.
Android 8 happily installs apps that target a newer SDK. *Lowering*
targetSdk changes runtime behaviour (permissions, storage, background
limits) and can break things.

### Things to know

- **Uninstall the original app first.** The patched APK is signed with a
  different key, so it can't update an existing install
  (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`). This wipes the app's data.
- The Play Store won't update a patched app. Patch every new version yourself
  and sign it with the same key.
- If an app crashes, `adb logcat | grep -E "NoSuchMethod|NoClassDef|ClassNotFound"`
  tells you which newer API it tried to call. If that call is in the
  app's main path, patching won't fix the app.
- Not supported: *adding* a `<uses-sdk>` attribute that isn't there. That
  would mean growing the binary XML. A missing `minSdkVersion` already
  means "1", so for the XA1 use case this doesn't matter.

## Why there is no "upgrade the system to Android 11" Magisk module

The goal would be *native* support: one system-side change, after which
newer apps just work. A Magisk module can't deliver that, for these reasons:

- **The API level is the whole OS, not one file.** The Android 9-11 APIs
  live in `framework.jar`. They are backed by `services.jar` (system_server),
  call into native JNI libraries (`libandroid_runtime`, ...), and run on a
  matching ART, bionic and SELinux policy. All of these are built together,
  so you can't drop Android 11's `framework.jar` onto Android 8.
- **Replacing all of it is a ROM.** Magisk can overlay the entire `/system`.
  But a module that does that *is* an Android 11 port, with all the porting
  work that implies.
- **The porting work is the blocker, not the packaging.** On the XA1 the
  hard part is getting Android 11 userspace to talk to the Oreo-era MediaTek
  vendor blobs and the old kernel (check with `adb shell uname -r`). Newer
  Android releases set minimum kernel versions. That work is why no XA1
  Android 10/11 ROM exists.

What a Magisk module *can* realistically do:

1. **Installer hook** (one-time, covers every app): skip the minSdk check so
   original APKs install with their signature, updates and data intact.
   Apps run as far as Android 8's APIs carry them.
2. **Compat shims** (open-ended): add the few missing framework methods
   that actually crash your apps, found via `logcat`. Each new app can need
   new shims, so this never reaches "done".

## Plan: keep the Sony ROM, backport the missing APIs into it

**Goal:** the stock Sony Android 8.0 ROM stays exactly as it is (system, skin,
Sony apps). It just learns the newer APIs that apps need. No reskin, no
LineageOS. (Running the LineageOS 17.1 port with a Sony-style theme on top
was considered and rejected for this reason.)

Swapping in a newer framework would throw away Sony's own framework changes.
Instead, the plan is to **add** to Sony's framework, delivered as one
Magisk module:

1. **Installer check off.** Patch `PackageParser` (in `framework.jar` on
   Android 8) so APKs with a higher minSdk install as they are, with their
   original signature, updates and data. No Xposed needed.
2. **Backported APIs.** Add the missing methods and classes to Sony's own
   `framework.jar`. Many are trivial (`Handler.createAsync(looper)` is
   `new Handler(looper)` plus a flag, `AccessibilityNodeInfo.setHeading` can
   store a flag), others are small data classes (`android.graphics.Insets`).
   Some can only be stubbed. Some depend on new system services or new
   kernel/driver features and can't be backported at all.
3. **`SDK_INT` stays 26.** Apps keep taking their Android 8 code paths
   wherever they have them. Only the APIs they call *without* a version check
   need to exist.

### How the module would be built (not implemented yet)

- Pull `framework.jar` and its Oreo odex/vdex from the phone. Sony stock is
  pre-optimised, so the dex has to be extracted from the vdex first.
- Disassemble it (baksmali), add the backport methods/classes, reassemble
  (smali). Simple new classes can be written in Java, compiled with `d8`
  (`~/sdktools/build-tools/*/d8`) and merged in.
- The Magisk module overlays the patched `framework.jar` and hides the
  stock pre-compiled boot image (`/system/framework/arm64/boot*`). ART then
  compiles a fresh boot image into `/data/dalvik-cache` on first boot,
  which is how deodexed ROMs work. The first boot is slow, and a bad patch
  means a bootloop, so remove the module from recovery or Magisk safe mode.
- This is device-specific, so it is generated from *your* phone's framework
  and isn't a module others can reuse.

Tools still missing on this machine: baksmali/smali and a vdex extractor
(downloads, not done yet).

### Measuring the work first: `apiscan.py`

How big the backport has to be depends on the apps. `apiscan.py` answers
that statically, without the phone:

```sh
./apiscan.py --device-api 26 ~/apks/            # whole folder, ranked list
./apiscan.py -v app.apk                          # every API one app needs
```

How it works:

- Parses every `classes*.dex` (string/type/proto/field/method tables,
  class data, and the bytecode itself) and collects every framework
  method/field reference, attributed to the method that makes it.
- Resolves each reference through the app's own class hierarchy to the
  framework class that provides it, then looks up when that API was added
  in the SDK's `api-versions.xml` (newest found under `~/sdktools/platforms`).
- Throws away references that never run on Android 8, because the calling
  method checks `Build.VERSION.SDK_INT` (directly, through an `isAtLeastX()`
  style helper or a cached field), sits in an AndroidX `*ApiNNImpl` / R8
  `*ApiModelOutline` class, or is only reached from such guarded code (up to
  6 call levels, including calls through superclasses/interfaces).
- The rest is "unguarded" and ranked by how many APKs need it. That ranking
  is the backport to-do list.

Accuracy: this is a heuristic. Apps that already run on Android 7/8
(minSdk 24) still show a noise floor of roughly 0-70 "unguarded" APIs
(guards it can't see: deeper call chains, objects picked by version,
reflection). Treat the numbers as an estimate and confirm on the phone with
`adb logcat | grep -E "NoSuchMethod|NoClassDef"`. Native code (`.so` files
built against newer bionic) isn't covered.

Sample run (2026-10-14, device API 26):

| APK | minSdk | newer APIs referenced | unguarded |
|---|---|---|---|
| Sony Music 9.4.15.A.0.22 | 28 | 231 | 21 (all API 28) |
| Kore 33 | 24 | 226 | 1 (noise) |
| ZArchiver 1.0.9 | 14 | 45 | 6 (noise) |
| Tempus 4.26.1 | 24 | 511 | 37 (noise) |
| Equalizer 0.0.21 | 24 | 610 | 42 (noise) |
| Shelter 445 | 24 | 227 | 66 (noise) |

Sony Music needs 21 API-28 APIs, including `AppComponentFactory`,
`Handler.createAsync`, a few accessibility setters,
`TextView.setFirst/LastBaselineTo*Height` and `MediaSessionManager.RemoteUserInfo`.
That's a realistic backport. Apps with minSdk 29-30 will need more.

## API backport module (work in progress)

Adds newer Android APIs to the phone's **own** framework as a Magisk
module. The stock ROM stays. `SDK_INT` keeps the phone's real value. Plan
and status: [TODO.md](TODO.md).

### Build

```sh
make boards                                        # list targets
make BOARD=generic-8 MAXSDK=11 EXPERIMENTAL=false  # safe set up to Android 11
make BOARD=generic-8 MAXSDK=10 EXPERIMENTAL=true   # + new fields on existing classes
make BOARD=generic-8 MAXSDK=11 EXPERIMENTAL=all    # as much as possible
./build.py --board generic-8 --maxSDK 11 --experimental all   # same as the line above
make all-boards                                    # every supported board once, with its defaults
make matrix                                        # every buildable board x every target x all 3 modes
make matrix TARGETS="10 11"                        # same, only these targets
```

`make matrix` (`tools/build-matrix.sh`) builds every board that is
`supported` or `port-only` (official and imported) for each target above
the board's own version (8.1, 9, 10, 11 by default) in `false`, `true` and
`all` mode. It skips impossible combinations (e.g. `generic-9` →
Android 9), keeps going after a failure, writes `out/matrix.log` and ends
with a summary; the exit code is 1 if any build failed.

Output: `out/api-backport-<board>-api<N>[-experimental|-all]-<version>.zip`
and `out/reports/port-report-<board>-api<N>[-experimental|-all].txt` (every
API with its verdict: `PORT` or `STOP <- reason`).

| option | meaning |
|---|---|
| `--board` / `BOARD=` | target, see `boards/` (default `generic-8`) |
| `--maxSDK` / `MAXSDK=` | highest Android version to add: `8.1`, `9`, `10`, `11` (or an API level) |
| `--experimental` / `EXPERIMENTAL=` | `false` (default): only APIs that are hidden on the phone or portable without touching existing object layouts. `true`: also APIs that add new instance fields to classes the phone already has (their old constructors never set them). `all`: as `true`, plus AOSP static initialisers for new static fields on existing classes, plus stubs for framework methods that are native or whose code isn't available (see below) |
| `--no-port` | only the hand-written backports |

(`make` only wraps `./build.py`; run `make init` once first.) Requirements: JDK 17+,
Android SDK with `build-tools` and `platforms/android-36` under
`~/sdktools` (or `$ANDROID_HOME`), ASM (`/usr/share/java/asm-9.8.jar`),
`tools/lib` (dexlib2, Guava) and `tools/bin` (run
`tools/build-vdexextractor.sh` once).

### Boards

A board is one build target: which Android version the phone must run,
which phone models may install the module, how far up it adds APIs, and
which framework the porter compares against ("what the phone already
has").

There are two kinds:

- **official boards**, hard-coded in `boards/<name>.prop` (format:
  [boards/README.md](boards/README.md)). Their phone files, if any, live in
  `boards/<name>/` (e.g. Sony's framework for `xa1-8`).
- **custom (imported) boards**, for your own device:
  `imported/<name>/version.txt` with the phone's Android version plus the
  framework files copied from the phone. `imported/` is in `.gitignore`.

Set up, list and select:

```sh
make init                                  # creates imported/ build/ out/, builds vdexExtractor (phone + PC)
make new-board NAME=myphone VERSION=8.0    # creates imported/myphone/version.txt
make boards                                # or: ./build.py --list-boards
make BOARD=xa1-8 MAXSDK=11 EXPERIMENTAL=false
./build.py --board myphone --maxSDK 11 --experimental all
```

Official boards (2026-10-16):

| Board | Phone | Android (API) | Models | Reference framework | Status |
| --- | --- | --- | --- | --- | --- |
| `generic-5` | any stock AOSP | 5.0 (21) | any | AOSP 5.0 | port-only |
| `generic-5.1` | any stock AOSP | 5.1 (22) | any | AOSP 5.1 | port-only |
| `generic-6` | any stock AOSP | 6.0 (23) | any | AOSP 6.0 | port-only |
| `generic-7` | any stock AOSP | 7.0 (24) | any | AOSP 7.0 | port-only |
| `generic-7.1` | any stock AOSP | 7.1 (25) | any | AOSP 7.1 | port-only |
| `generic-8` | any stock AOSP | 8.0 (26) | any | AOSP 8.0 | supported |
| `generic-9` | any stock AOSP | 9 (28) | any | AOSP 9 | supported (untested) |
| `xa1-8` | Sony Xperia XA1, stock Sony | 8.0 (26) | G3121 G3123 G3125 G3112 G3116 | Sony's own from `boards/xa1-8/` when present, AOSP 8.0 until then | supported |
| `xa1-7` | Sony Xperia XA1, stock Sony | 7.1 (25) | G3121 G3123 G3125 G3112 G3116 | Sony's own from `boards/xa1-7/` when present, AOSP 7.1 until then | planned |

Custom boards get their settings from `version.txt`: models = any,
`target_api` = 30, status supported for 8.0/8.1/9, port-only for 5.0–7.1,
planned for 10 and newer. A custom board may not reuse an official name.

Status:
- **supported**: builds a flashable module zip.
- **port-only**: builds the ported levels and the report, no zip. Android 5–7
  keep framework code inside the compiled boot image (oat), and the on-phone
  extraction for that isn't written yet.
- **planned**: defined, but the build stops with a message.

The installer double-checks on the phone: wrong Android version or a model
not in the list aborts the install without changes.

#### Copying a phone's framework files

The porter should compare against what the phone really has. ROMs like
Sony's change the framework, so AOSP is only an approximation. Copy these
from the phone into `imported/<name>/` (custom board) or `boards/<name>/`
(official board):

| On the phone | Why |
| --- | --- |
| `/system/framework/*.jar` | the framework jars (`framework.jar`, vendor jars like `mediatek-framework.jar`, `com.sonymobile.*.jar`) |
| `/system/framework/arm64/*.vdex` (or `arm/` if there's no `arm64/`) | the code of the boot jars on pre-optimised ROMs; there the jars are near-empty shells (e.g. 126 bytes) |
| `/system/framework/oat/arm64/*.vdex` (or `oat/arm/`) | the code of the other framework jars on pre-optimised ROMs |
| `/system/build.prop` | not used by the build, but documents version, build and model |

Folder layout inside the board folder doesn't matter: everything is
searched recursively. Without adb:
- **Magisk root shell** (terminal app, then `su`):
  `mkdir -p /sdcard/Download/fw && cp -r /system/framework/*.jar /system/framework/arm64 /system/framework/oat /system/build.prop /sdcard/Download/fw/`
  then move `fw/` to the PC over USB (MTP) or an SD card.
- **TWRP** (booted with `fastboot boot twrp.img`): Mount → System, then
  Advanced → File Manager, copy the same files to the SD card.

What the build does with them (`build.py`, `tools/RefJar.java`):
1. Jars that contain `classes*.dex` are used directly. `.vdex` files are
   unpacked to dex with the PC build of vdexExtractor (`make init`).
2. All dex code is turned into a reference jar of class headers
   (`build/ref/<board>.jar`: classes, superclasses, interfaces, fields,
   methods; no code). When a class exists twice, the first one wins, as on
   the phone.
3. The porter uses that reference instead of AOSP.

Jars with neither code nor a matching `.vdex` get a warning naming the vdex
to look for; their classes are left out. A board folder without any usable
code falls back to the AOSP jar of the same version (with a note).

Checked on the PC (2026-10-16): the AOSP 8.0 framework as phone-style dex
in a custom board gives exactly the same porter result as the AOSP
reference (4,550 / 517 / 3,550 / 483 in `all` mode). Not checked yet: a
real Sony `.vdex` (vdexExtractor supports the 8.0 vdex format).

### How it works

1. **Backports** (`backports/apiNN/`): plain Java, one class per framework
   class. Annotations say what gets added:
   `@Backport("android.widget.TextView")` on the class, and
   `@Inject` (instance method, first parameter is `self`), `@InjectStatic`,
   `@InjectConstructor(delegate=...)` or `@InjectField("NAME")` on its methods.
   Shared helpers live in `backports/common/`.
2. **Build** (`build.py`):
   - Generates an API stub jar per level (`tools/StubJar.java`) from the
     SDK `android.jar` and `api-versions.xml`.
   - Compiles level L against the stub of L-1. javac rejects anything the
     phone might not have.
   - `tools/Indexer.java` turns the annotations into `inject.txt` and checks
     every entry against `api-versions.xml`: wrong name, signature or level
     means a build error.
   - d8 makes `classes.dex` per level. The patcher (`patcher/`, using
     dexlib2) becomes `patcher.jar`. Everything is zipped with
     `module/` and `board.prop`.
3. **Install** (`module/customize.sh`, runs on the phone in the Magisk app):
   - Checks board, Android version and model.
   - Gets dex code back for every `/system/framework` jar (from its vdex via
     vdexExtractor on pre-optimised ROMs).
   - Runs the patcher with the phone's own `dalvikvm`. Each backported
     method becomes a small forwarding method in the real framework class,
     and the backport code is added as an extra dex in `framework.jar`.
   - Writes the jars into the module and masks the stock pre-compiled boot
     image and framework odex/vdex, so ART compiles new ones on first boot
     (slow first boot).
   - Any failure aborts the install without changes.

### AOSP porter (`tools/Porter.java`)

Takes the real implementation of every public API added after the board's
version from AOSP (`tools/aosp/android-all-{10,11}*.jar`, Robolectric's
builds of the AOSP framework) and checks it against the board's reference
framework (AOSP 8.0 for `generic-8`, Sony's jar for `xa1-8` once provided):

- **already there**: the phone has it, usually as a hidden API. Android 8
  and older don't enforce hidden-API restrictions, so apps can call it as
  is. Android 9 (`generic-9`) does restrict hidden APIs for apps; whether
  those restrictions still apply after the patcher rewrites the framework
  dex is unverified until it runs on an Android 9 phone.
- **portable**: the code and everything it references (followed
  recursively, including hidden `com.android.internal` helpers) runs on the
  phone. New classes are copied whole. Anonymous classes are renamed
  (`Foo$1` → `Foo$Bp1`) because compiler numbering differs between versions.
  New members of existing classes go into partial classes that the patcher
  merges into the phone's real class (`merge.txt`).
- **blocked**: the API can't be ported automatically. It stays missing on
  the phone, exactly as without the module (see "What blocked means").

`--experimental all` additionally:

- **ports static initialisers**: AOSP's `<clinit>` of an existing class is
  added as `$bp$clinit()`, with stores into fields the phone already has
  turned into pops (the phone keeps its values). The patcher makes the
  phone's own `<clinit>` call it at the end. The ported fields lose `final`
  because they're assigned outside `<clinit>`. Side effect: everything else
  in AOSP's initialiser runs once more.
- **stubs** framework methods that are native in AOSP or whose code isn't
  in the AOSP jar: `void` methods do nothing, others throw
  `UnsupportedOperationException("... is not available on this Android
  version (backport stub)")`. Apps get a defined Java exception instead of
  `NoSuchMethodError`, and a feature behind a void stub silently does
  nothing. Static vs. instance comes from the AOSP code, a call site, or
  the SDK `android.jar`. Without that information there's no stub.

Safety checks (in the porter, and repeated in the patcher where possible):
nothing is merged into or stubbed on classes outside `framework.jar`
(`java.*`, `dalvik.*`, ICU, `android.system`, OkHttp, Conscrypt,
BouncyCastle, ...), and nothing is ported into a class whose hierarchy
changed between versions (class ↔ interface, different superclass for
`super(...)` calls) or that would get an abstract method while concrete on
the phone.

#### What blocked means

Blocked reasons for `generic-8` → Android 11, per mode (2026-10-15):

| Reason | false | true | all | Meaning |
| --- | --- | --- | --- | --- |
| New instance field on an existing class | 799 | 0 | 0 | AOSP stores data in a field the phone's version of the class doesn't have; the phone's constructors never set it. Allowed from `true` on |
| New static field that needs AOSP's static initialiser | 146 | 212 | 0 | Ported in `all` (see above) |
| Native method | 138 | 185 | 0 | Implemented in C/C++ inside Android's system libraries; Java patching can't add it. `all` stubs it |
| Code not found in the AOSP jar | 144 | 177 | 199 | Mostly Java core library (`java.lang`, `java.util.concurrent`, `sun.misc`, `java.nio`), which isn't part of the AOSP framework jars and isn't in `framework.jar` on the phone |
| New member on a class outside `framework.jar` | 161 | 166 | 247 | ICU, `android.system`, ...: those classes live in other system jars, which the module doesn't patch |
| Class hierarchy changed | 24 | 29 | 29 | Class ↔ interface, or a different superclass (e.g. `CellIdentityLte`, `RemoteViews.OnClickHandler`) |
| Class / field missing in AOSP jar | 8 | 8 | 8 | Leftovers |

Numbers can rise from one mode to the next because once an earlier
obstacle is lifted, the porter follows the API further and may hit the
next one. The total always falls (1,420 → 777 → 483).

Hand-written backports (`backports/`) are applied first and win over
ported code.

#### Feature table: AOSP 8.0 (`generic-8`) → Android 11

First analysis (2026-10-14, before the safety checks below were added):

| Level | Public APIs added | Already in 8.0 (hidden) | Portable automatically | Blocked |
| --- | --- | --- | --- | --- |
| API 27 (8.1) | 109 | 14 | 61 | 34 |
| API 28 (9) | 1,307 | 278 | 761 | 268 |
| API 29 (10) | 1,553 | 139 | 965 | 449 |
| API 30 (11) | 1,581 | 86 | 1,032 | 463 |
| **Total** | **4,550** | **517** | **2,819** | **1,214** |

The current numbers are lower than in the first analysis because checks
were added after verifier findings: class/interface and superclass
changes, static fields that need AOSP's initialiser, and classes outside
`framework.jar` (ICU etc.). Per-API details:
`out/reports/port-report-<board>-api<N>[-experimental|-all].txt`.

#### Feature tables for all boards

Every `generic-*` board against Android 9, 10 and 11 in all three modes
(2026-10-15). A cell reads `APIs added: already there + ported (false) /
ported (true) / ported (all)`. Example: `4,550: 517+2,613 / 3,256 / 3,550`
means Android 11 adds 4,550 APIs; 517 are already hidden on the phone;
`--experimental false` ports 2,613 more, `true` 3,256, `all` 3,550. The
rest is blocked.

| Board | → Android 9 | → Android 10 | → Android 11 | Output |
| --- | --- | --- | --- | --- |
| `generic-5` | 6,219: 430+3,161 / 3,953 / 4,304 | 7,772: 496+4,020 / 4,996 / 5,480 | 9,353: 546+4,653 / 5,715 / 6,221 | port-only |
| `generic-5.1` | 6,079: 424+3,079 / 3,834 / 4,174 | 7,632: 495+3,941 / 4,879 / 5,345 | 9,213: 545+4,571 / 5,607 / 6,102 | port-only |
| `generic-6` | 4,957: 500+2,367 / 3,027 / 3,261 | 6,510: 596+3,240 / 4,083 / 4,454 | 8,091: 650+4,054 / 5,012 / 5,466 | port-only |
| `generic-7` | 3,233: 361+1,705 / 2,077 / 2,264 | 4,786: 469+2,631 / 3,210 / 3,541 | 6,367: 536+3,475 / 4,220 / 4,647 | port-only |
| `generic-7.1` | 3,127: 319+1,653 / 2,017 / 2,202 | 4,680: 427+2,580 / 3,151 / 3,479 | 6,261: 498+3,420 / 4,157 / 4,581 | port-only |
| `generic-8` | 1,416: 292+778 / 971 / 1,049 | 2,969: 431+1,702 / 2,137 / 2,344 | 4,550: 517+2,613 / 3,256 / 3,550 | module zip |
| `generic-9` | — | 1,553: 187+914 / 1,165 / 1,291 | 3,134: 334+1,861 / 2,333 / 2,539 | module zip (untested) |

`port-only` boards (Android 5–7) build the ported levels and the report,
but no module zip yet: their framework code sits inside the compiled boot
image (oat), and the on-phone extraction for that isn't written.

Verified on the PC (AOSP framework converted to dex, patched exactly like
on the phone, every output dex checked with `dexdump` dex-file
verification, 0 errors):

| Module | Merged members | New classes | Static initialisers hooked |
| --- | --- | --- | --- |
| generic-8 → Android 11, `all` | 4,580 (+ 6 hand-written) | 1,856 | 36 |
| generic-9 → Android 11, `all` | 3,494 | 1,393 | 33 |

Not verifiable on the PC: ART's bytecode verifier and runtime behaviour.

Per board, at the highest target (Android 11), per level:

##### `generic-5` → Android 11

| Level | Public APIs added | Already there (hidden) | Ported: false | Ported: true | Ported: all | Blocked: all |
| --- | --- | --- | --- | --- | --- | --- |
| API 22 (5.1) | 140 | 23 | 77 | 106 | 111 | 6 |
| API 23 (6.0) | 1,122 | 121 | 615 | 770 | 886 | 115 |
| API 24 (7.0) | 1,724 | 69 | 821 | 985 | 1,045 | 610 |
| API 25 (7.1) | 106 | 2 | 69 | 91 | 91 | 13 |
| API 26 (8.0) | 1,711 | 34 | 795 | 1,009 | 1,059 | 618 |
| API 27 (8.1) | 109 | 3 | 61 | 74 | 77 | 29 |
| API 28 (9) | 1,307 | 178 | 592 | 725 | 818 | 311 |
| API 29 (10) | 1,553 | 66 | 812 | 986 | 1,101 | 386 |
| API 30 (11) | 1,581 | 50 | 811 | 969 | 1,033 | 498 |
| **Total** | **9,353** | **546** | **4,653** | **5,715** | **6,221** | **2,586** |

##### `generic-5.1` → Android 11

| Level | Public APIs added | Already there (hidden) | Ported: false | Ported: true | Ported: all | Blocked: all |
| --- | --- | --- | --- | --- | --- | --- |
| API 23 (6.0) | 1,122 | 133 | 611 | 763 | 876 | 113 |
| API 24 (7.0) | 1,724 | 70 | 820 | 984 | 1,044 | 610 |
| API 25 (7.1) | 106 | 2 | 69 | 91 | 91 | 13 |
| API 26 (8.0) | 1,711 | 37 | 795 | 1,008 | 1,058 | 616 |
| API 27 (8.1) | 109 | 3 | 61 | 74 | 77 | 29 |
| API 28 (9) | 1,307 | 179 | 594 | 728 | 819 | 309 |
| API 29 (10) | 1,553 | 71 | 810 | 989 | 1,104 | 378 |
| API 30 (11) | 1,581 | 50 | 811 | 970 | 1,033 | 498 |
| **Total** | **9,213** | **545** | **4,571** | **5,607** | **6,102** | **2,566** |

##### `generic-6` → Android 11

| Level | Public APIs added | Already there (hidden) | Ported: false | Ported: true | Ported: all | Blocked: all |
| --- | --- | --- | --- | --- | --- | --- |
| API 24 (7.0) | 1,724 | 235 | 761 | 939 | 1,008 | 481 |
| API 25 (7.1) | 106 | 4 | 71 | 93 | 97 | 5 |
| API 26 (8.0) | 1,711 | 53 | 827 | 1,068 | 1,122 | 536 |
| API 27 (8.1) | 109 | 4 | 66 | 78 | 81 | 24 |
| API 28 (9) | 1,307 | 204 | 586 | 747 | 855 | 248 |
| API 29 (10) | 1,553 | 96 | 838 | 1,014 | 1,153 | 304 |
| API 30 (11) | 1,581 | 54 | 905 | 1,073 | 1,150 | 377 |
| **Total** | **8,091** | **650** | **4,054** | **5,012** | **5,466** | **1,975** |

##### `generic-7` → Android 11

| Level | Public APIs added | Already there (hidden) | Ported: false | Ported: true | Ported: all | Blocked: all |
| --- | --- | --- | --- | --- | --- | --- |
| API 25 (7.1) | 106 | 50 | 47 | 53 | 54 | 2 |
| API 26 (8.0) | 1,711 | 91 | 860 | 1,076 | 1,165 | 455 |
| API 27 (8.1) | 109 | 6 | 66 | 78 | 89 | 14 |
| API 28 (9) | 1,307 | 214 | 677 | 808 | 902 | 191 |
| API 29 (10) | 1,553 | 108 | 893 | 1,097 | 1,240 | 205 |
| API 30 (11) | 1,581 | 67 | 932 | 1,108 | 1,197 | 317 |
| **Total** | **6,367** | **536** | **3,475** | **4,220** | **4,647** | **1,184** |

##### `generic-7.1` → Android 11

| Level | Public APIs added | Already there (hidden) | Ported: false | Ported: true | Ported: all | Blocked: all |
| --- | --- | --- | --- | --- | --- | --- |
| API 26 (8.0) | 1,711 | 98 | 855 | 1,069 | 1,158 | 455 |
| API 27 (8.1) | 109 | 6 | 66 | 78 | 89 | 14 |
| API 28 (9) | 1,307 | 215 | 677 | 808 | 901 | 191 |
| API 29 (10) | 1,553 | 108 | 894 | 1,098 | 1,240 | 205 |
| API 30 (11) | 1,581 | 71 | 928 | 1,104 | 1,193 | 317 |
| **Total** | **6,261** | **498** | **3,420** | **4,157** | **4,581** | **1,182** |

##### `generic-8` → Android 11

| Level | Public APIs added | Already there (hidden) | Ported: false | Ported: true | Ported: all | Blocked: all |
| --- | --- | --- | --- | --- | --- | --- |
| API 27 (8.1) | 109 | 14 | 61 | 84 | 89 | 6 |
| API 28 (9) | 1,307 | 278 | 693 | 858 | 935 | 94 |
| API 29 (10) | 1,553 | 139 | 894 | 1,132 | 1,268 | 146 |
| API 30 (11) | 1,581 | 86 | 965 | 1,182 | 1,258 | 237 |
| **Total** | **4,550** | **517** | **2,613** | **3,256** | **3,550** | **483** |

##### `generic-9` → Android 11

| Level | Public APIs added | Already there (hidden) | Ported: false | Ported: true | Ported: all | Blocked: all |
| --- | --- | --- | --- | --- | --- | --- |
| API 29 (10) | 1,553 | 187 | 887 | 1,138 | 1,273 | 93 |
| API 30 (11) | 1,581 | 147 | 974 | 1,195 | 1,266 | 168 |
| **Total** | **3,134** | **334** | **1,861** | **2,333** | **2,539** | **261** |

### Status

Untested on a phone. Missing: the install-time version limit (apps that
require a newer Android still don't install), `xa1-8` against Sony's own
framework.jar, on-phone extraction for Android 5–7 boards, Java core
library APIs (`java.*`). See [TODO.md](TODO.md).
