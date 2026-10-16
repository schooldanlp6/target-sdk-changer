# TODO

Goal: one Magisk module that adds newer Android APIs to the phone's **own**
stock framework (Sony ROM stays as it is). Apps built for newer Android
then find the methods and classes they call. `Build.VERSION.SDK_INT` stays
at the phone's real level.

Test device: spare Xperia XA1 (stock Sony 8.0, Magisk, TWRP via fastboot).
A bootloop there only means a reflash.

Apps to get running: newer Sony apps, the current Instagram, the current
Snapseed.

## Phase 1: Android 8.0 (API 26) → up to Android 10 (API 29)

- [x] `tools/`: vdexExtractor built for arm/arm64 (NDK), to get the dex
      out of Sony's pre-optimised boot jars (vdex v006)
- [x] API stub jars per level (`build/stubs/android-NN.jar`), generated from
      the SDK's `android.jar` + `api-versions.xml`. Backport code for level L
      is compiled against API L-1, so it can't use anything the phone lacks
- [x] Backport source tree `backports/apiNN/`: one folder per API level,
      plain Java, annotations say where each method goes
- [x] Build script (`build.py`): compile → dex per level → injection list → patcher
- [x] Patcher (`patcher/`), tested on PC against a dex built from the API 26 stub
- [ ] Patcher: static field injection (`@InjectField`)
- [x] Run the patcher on the phone during flashing via `dalvikvm` (in customize.sh, untested on a phone):
      - find each target class in the boot jars
      - add a small bridge method per backported method
      - add the new classes
      - write the patched jars into the module
- [x] Module install script (`module/customize.sh`)
- [x] Deodex *every* boot jar (all of $BOOTCLASSPATH, from boot*.vdex) and
      mask the *whole* boot image (boot*.art/oat/vdex, arm + arm64). Masking
      only boot-framework.* leaves ART unable to rebuild the image -> bootloop
- [x] build.py: package module zip (module/ + out/levels + patcher.jar + tools/bin)
- [ ] API 27 backports
- [ ] API 28 backports
- [ ] API 29 backports
- [ ] Check every level's dex with `apiscan.py`: no unguarded use of APIs
      above the level it was compiled for
- [ ] First flash on the test XA1 (pipeline test, only 6 APIs inside):
      install log OK, phone boots, Sony apps still work, `adb logcat` clean.
      Open risk: preinstalled apps whose APK has no dex (code only in their
      odex) after the boot image changes
- [ ] Instagram and Snapseed: install, collect `logcat` crashes, add the
      missing APIs, repeat

## Next up

- [x] Boards: `boards/*.prop`, `build.py --board`, `make BOARD=` (generic-8, xa1-8)
- [x] Porter: take API 27-30 implementations from AOSP
      (`tools/aosp/android-all-{10,11}`), pull in their dependencies, check
      them against the board's reference framework, emit levels plus a
      report (ported / needs stub / impossible)
- [x] `--maxSDK`, `--experimental` build options
- [x] Porter: static fields with initialisers on existing classes
      (`--experimental all`: AOSP `<clinit>` as `$bp$clinit`)
- [x] Porter: stubs for blocked APIs (native / missing code) so apps get a
      defined result instead of NoSuchMethodError (`--experimental all`)
- [ ] Porter: patch other boot jars too (ICU, core-libart), so members on
      classes outside framework.jar can be ported
- [ ] Porter: core library (java.*) APIs added in 27-30 (not in android-all)
- [ ] Install-time version limit: let the package installer accept apps up
      to the board's `target_api`
- [ ] xa1-8: get Sony's files from the test phone into `boards/xa1-8/`:
      `/system/framework/*.jar`, `arm64/*.vdex`, `oat/arm64/*.vdex`,
      `build.prop` (copy via Magisk root shell or TWRP, see README "Boards").
      The three jars in `imported/` are code-less shells; the vdex files
      are still needed
- [x] Converter: phone framework (jar with dex, or vdex via vdexExtractor
      on the PC) → reference jar of class headers (`tools/RefJar.java`),
      used automatically for `phone_files` / imported boards
- [ ] Converter: test with real Sony vdex files from the XA1
- [x] `make matrix`: all boards × all targets × all modes
- [x] Custom boards: `imported/<name>/version.txt` + phone files,
      `make init`, `make new-board`, `.gitignore`
- [ ] xa1-8: rerun the porter against Sony's framework, compare with AOSP 8.0
      (what Sony added/removed), update the README tables
- [x] Board list: `make boards` / `./build.py --list-boards`, sorted by version;
      README table of available boards
- [x] Boards generic-5/5.1/6/7/7.1 (port-only) and generic-9 (module)
- [x] Download AOSP reference jars for 5.0/5.1/6.0/7.0/7.1/9 (467 MB)
- [x] Porter runs + feature tables for generic-5 ... generic-9 (README)
- [ ] generic-9 module: first flash on an Android 9 device

## Phase 2: Android 8.0 → Android 11 / 12 (API 30-32)

- [ ] API 30, 31, 32 backports
- [ ] Same test loop

## Phase 3: other base versions (any step from 5.0 up to 12)

Every base version stores its boot jars differently, so each one needs its
own extraction step:

- [ ] 8.1 (vdex v010)
- [ ] 9 (vdex v019), 10 (vdex v021, hidden-API flags)
- [ ] 7.x and 6.x (odex/oat inside the boot image)
- [ ] 5.x (oat, ART 1st generation)
- [ ] Patcher on 5.x/6.x: dexlib2 uses java.util.function (API 24+);
      needs desugaring (d8 with core library desugaring) or an older dexlib2
- [ ] 11 → 12

## Phase 4: companion app

- [ ] Small app: shows the phone's level, the installed backport levels,
      lets you pick the target level, and lists the backported APIs
- [ ] Contributor guide: how to add an API (one Java method + annotation)
