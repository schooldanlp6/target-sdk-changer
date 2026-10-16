# Changelog

## [Unreleased]

### Added
- README: explains why a "native Android 11 support" Magisk module isn't
  feasible (it would be a full ROM port), and what a module can do instead
  (installer hook, compat shims).
- `apiscan.py`: static scanner that lists the framework APIs newer than the
  phone's API level that APKs call without a version check, ranked across
  APKs. Parses dex bytecode, resolves references through the class
  hierarchy against the SDK's `api-versions.xml`, and filters
  SDK_INT-guarded code (direct checks, version helpers, AndroidX/R8 helper
  classes, guarded call chains).
- README: plan for keeping the stock Sony ROM and backporting the missing
  APIs into its own `framework.jar` via a Magisk module (installer check off,
  backported APIs, SDK_INT stays 26), plus scanner docs and sample results.
- API backport module (work in progress, untested on a phone):
  `backports/` (annotation-based backport sources), `tools/StubJar.java`
  (per-level API stub jars), `tools/Indexer.java` (injection list +
  api-versions check), `patcher/` (adds forwarding methods + backport dex to
  framework dex, dexlib2), `module/` (Magisk installer: deodex all
  framework jars, patch, mask boot image), `build.py` + `Makefile` with
  `--board` / `BOARD=` and `boards/*.prop` (generic-8, xa1-8; generic-7,
  xa1-7 planned).
- First backports: `Handler.createAsync` (x2), `TextView` first/last
  baseline getters and setters (API 28).
- `TODO.md`: phased plan (8.0 → 10/11, then other base versions, then an app).
- `tools/`: vdexExtractor source (Apache 2.0) + arm/arm64 build script;
  `tools/lib`: smali-dexlib2 3.0.10, Guava 31.1-android (smali-util 3.0.10
  downloaded too, not needed); `tools/aosp`: Robolectric android-all
  8.0/10/11 (AOSP framework reference). All checked against published SHA-1.

- `tools/Porter.java`: AOSP porter. Classifies every API added after the
  board's version (already there / portable / blocked with root cause),
  follows dependencies recursively, emits new classes (anonymous ones
  renamed) and partial classes for merging, and writes a report.
- `build.py` / `Makefile`: `--maxSDK` / `MAXSDK=` (Android 8.1/9/10/11 or API
  level), `--experimental true|false` / `EXPERIMENTAL=`, `--no-port`; zips are
  named `api-backport-<board>-api<N>[-experimental]-<version>.zip`.
- Patcher: merges partial classes (`merge.txt`) into the phone's classes,
  writes the complete new dex set with automatic splitting at the 64K
  limits, refuses class/interface mismatches and abstract methods on
  concrete classes.

- Boards `generic-5`, `generic-5.1`, `generic-6`, `generic-7`, `generic-7.1`
  (status `port-only`: ported levels + report, no module zip until on-phone
  oat extraction exists) and `generic-9` (full module, untested). Each needs
  its AOSP reference jar in `tools/aosp/` (see `tools/aosp/README.md`).
- README: feature tables (first analysis, current safe, current
  experimental) for generic-8 → Android 11, plus a matrix and per-level
  tables for every generic board (5.0 … 9) against Android 9/10/11.
- `tools/aosp/`: AOSP reference jars for 5.0, 5.1, 6.0, 7.0, 7.1 and 9
  (Robolectric android-all, SHA-1 verified); `tools/aosp/README.md`.
- `tools/report_tables.py`: porter reports → Markdown tables.
- Porter reports now go to `out/reports/port-report-<board>-api<N>[-experimental|-all].txt`.
- `--experimental all` / `EXPERIMENTAL=all`: additionally ports AOSP static
  initialisers (`$bp$clinit`, called at the end of the phone's `<clinit>` by
  the patcher) and stubs framework methods that are native or missing in
  the AOSP jar (void: no-op, others: `UnsupportedOperationException`);
  static/instance taken from AOSP code, call sites or the SDK `android.jar`.
- Porter safety checks: no merges/stubs on classes outside framework.jar
  (ICU, android.system, OkHttp, Conscrypt, BouncyCastle, ...), no ported
  `super(...)`/`super.x()` calls into a superclass that changed between
  versions (found via d8 on `CellIdentityLte` & co.). Patcher refuses
  merges into classes that aren't in the jar.
- README "Boards": table of all available boards, how to list/select them,
  how to add a device, which files to copy from a phone (framework.jar,
  boot-framework.vdex, build.prop) and how without adb.
- `build.py`: board list sorted by Android version.
- `make matrix` / `tools/build-matrix.sh`: every buildable board × every
  target (8.1, 9, 10, 11 or `TARGETS=`) × `false/true/all`, log in
  `out/matrix.log`, summary and non-zero exit on failures.
- `tools/RefJar.java`: phone framework (dex, or vdex unpacked by
  vdexExtractor) → reference jar of class headers for the porter.
- Custom boards: `imported/<name>/version.txt` + the phone's framework
  files become a board automatically; `make init` (folders, vdexExtractor
  for phone and PC), `make new-board NAME= VERSION=`; boards with
  `phone_files` (xa1-8, xa1-7) convert their files automatically.
- `.gitignore`: build/, out/, imported/, keys/, downloaded AOSP jars,
  vdexExtractor source and binaries.
- `tools/build-vdexextractor.sh` also builds the PC version (tools/bin/host).
- README: "What blocked means" table, three-mode feature tables for all
  boards, PC verification results for generic-8/generic-9 `all` modules.

### Changed
- `--target-api` replaced by `--maxSDK`.
- Patcher output is now the full dex set for framework.jar (was: changed
  dex files + backport.dex); `module/customize.sh` adjusted.
- README: dropped the "LineageOS 17.1 + Sony reskin" recommendation. The
  stock Sony ROM must stay.
- README: removed the EdXposed installer-hook section. The installer check
  is now part of the framework patch, so Xposed isn't needed.

## [0.1.0] - 2026-10-14

### Added
- `sdkpatch.py`: stdlib-only Python tool to show (`info`) and change (`patch`)
  `minSdkVersion` / `targetSdkVersion` in an APK's binary manifest in place,
  then zipalign and re-sign with a local key (`keys/sdkpatch.keystore`,
  created on first use). Handles base + split APKs in one run.
- README: feasibility write-up for running newer apps on the Xperia XA1
  (Android 8.0 / API 26), covering APK patching vs. root hook vs. faking the
  system SDK vs. a custom ROM, plus the tool's internals and usage.

### Tested
- Lowered minSdk on Sony Music 9.4.15 (28 -> 21) and Tempus 4.26.1
  (24 -> 21). `aapt dump badging` shows the new values, `apksigner verify`
  and `zipalign -c` pass. Not yet installed on a real XA1.
