#!/usr/bin/env python3
"""Build all backport levels and the patcher.

    ./build.py --board xa1-8          # module for one board (see boards/)
    ./build.py --list-boards
    ./build.py --board generic-8 --target-api 29 --clean

Per level NN: compile backports/apiNN against the API (NN-1) stub plus the
earlier levels, dex it, and write inject.txt. Then build out/patcher.dex.
See README.md ("Backport build") for details.
"""
import argparse
import glob
import os
import re
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
BUILD = os.path.join(ROOT, "build")
OUT = os.path.join(ROOT, "out")
SDK = os.environ.get("ANDROID_HOME") or os.path.expanduser("~/sdktools")
PLATFORM = os.path.join(SDK, "platforms", "android-36")
JDK = os.environ.get("JAVA_HOME") or "/usr/lib/jvm/java-21-openjdk-amd64"
ASM = "/usr/share/java/asm-9.8.jar"
LIB = os.path.join(ROOT, "tools", "lib")
DEXLIB = [os.path.join(LIB, "smali-dexlib2-3.0.10.jar"), os.path.join(LIB, "guava-31.1-android.jar")]


def tool(name):
    return os.path.join(JDK, "bin", name)


def d8():
    jars = sorted(glob.glob(os.path.join(SDK, "build-tools", "*", "lib", "d8.jar")))
    if not jars:
        sys.exit("error: no d8.jar under %s/build-tools" % SDK)
    return [tool("java"), "-cp", jars[-1], "com.android.tools.r8.D8"]


def run(cmd):
    print("+", " ".join(os.path.relpath(c, ROOT) if c.startswith(ROOT + "/") else c for c in cmd))
    subprocess.run(cmd, check=True)


def sources(*dirs):
    out = []
    for d in dirs:
        for base, _, files in os.walk(d):
            out += [os.path.join(base, f) for f in files if f.endswith(".java")]
    return sorted(out)


def classes(d, skip_annotations=True):
    out = []
    for base, _, files in os.walk(d):
        for f in files:
            if not f.endswith(".class"):
                continue
            rel = os.path.relpath(os.path.join(base, f), d)
            if skip_annotations and re.fullmatch(r"backport/(Backport|Inject\w*)\.class", rel):
                continue  # compile-time only
            out.append(os.path.join(base, f))
    return sorted(out)


def stub(level):
    path = os.path.join(BUILD, "stubs", "android-%d.jar" % level)
    if not os.path.exists(path):
        run([tool("java"), "-cp", ASM, os.path.join(ROOT, "tools", "StubJar.java"),
             os.path.join(PLATFORM, "android.jar"), os.path.join(PLATFORM, "data", "api-versions.xml"),
             str(level), path])
    return path


def build_levels():
    levels = sorted(int(m.group(1)) for d in os.listdir(os.path.join(ROOT, "backports"))
                    for m in [re.fullmatch(r"api(\d+)", d)] if m)
    common = os.path.join(BUILD, "classes", "common")
    shutil.rmtree(common, ignore_errors=True)
    # annotations + shared helpers, compiled against the oldest supported API (21)
    run([tool("javac"), "-source", "8", "-target", "8", "-Xlint:-options", "-nowarn",
         "-bootclasspath", stub(21), "-d", common]
        + sources(os.path.join(ROOT, "backports", "annotations"), os.path.join(ROOT, "backports", "common")))
    # shared helpers: their own level, installed on every board
    out = os.path.join(OUT, "levels", "common")
    shutil.rmtree(out, ignore_errors=True)
    os.makedirs(out)
    run(d8() + ["--min-api", "21", "--lib", stub(21), "--output", out] + classes(common))
    earlier = [common]
    for level in levels:
        cls = os.path.join(BUILD, "classes", "api%d" % level)
        out = os.path.join(OUT, "levels", "api%d" % level)
        shutil.rmtree(cls, ignore_errors=True)
        shutil.rmtree(out, ignore_errors=True)
        os.makedirs(out)
        run([tool("javac"), "-source", "8", "-target", "8", "-Xlint:-options", "-nowarn",
             "-bootclasspath", stub(level - 1), "-cp", os.pathsep.join(earlier), "-d", cls]
            + sources(os.path.join(ROOT, "backports", "api%d" % level)))
        run([tool("java"), "-cp", ASM, os.path.join(ROOT, "tools", "Indexer.java"),
             cls, os.path.join(PLATFORM, "data", "api-versions.xml"), str(level),
             os.path.join(out, "inject.txt")])
        run(d8() + ["--min-api", "21", "--lib", stub(level - 1),
                    "--classpath", common, "--output", out] + classes(cls))
        earlier.append(cls)
    return levels


def build_patcher():
    cls = os.path.join(BUILD, "classes", "patcher")
    shutil.rmtree(cls, ignore_errors=True)
    run([tool("javac"), "-source", "8", "-target", "8", "-Xlint:-options", "-nowarn",
         "-cp", os.pathsep.join(DEXLIB), "-d", cls] + sources(os.path.join(ROOT, "patcher", "src")))
    out = os.path.join(BUILD, "patcher-dex")
    shutil.rmtree(out, ignore_errors=True)
    os.makedirs(out)
    run(d8() + ["--min-api", "21", "--lib", stub(21), "--output", out] + classes(cls, False) + DEXLIB)
    os.makedirs(OUT, exist_ok=True)
    dexes = sorted(glob.glob(os.path.join(out, "classes*.dex")))
    # several dex files (Guava is big): package them as a jar for dalvikvm -cp
    jar = os.path.join(OUT, "patcher.jar")
    import zipfile
    with zipfile.ZipFile(jar, "w") as z:
        for d in dexes:
            z.write(d, os.path.basename(d))
    print("patcher: %s (%d dex)" % (os.path.relpath(jar, ROOT), len(dexes)))


# custom boards: imported/<name>/version.txt + phone files ($BACKPORT_IMPORTED overrides the folder)
IMPORTED = os.environ.get("BACKPORT_IMPORTED") or os.path.join(ROOT, "imported")
# Android version (imported/<board>/version.txt) -> API level
ANDROID_API = {"5.0": 21, "5.1": 22, "6.0": 23, "7.0": 24, "7.1": 25, "8.0": 26, "8.1": 27, "9": 28,
               "10": 29, "11": 30}
# generic AOSP reference per API level, used when an imported board's files can't be read
AOSP_REF = {21: "android-all-5.0.2_r3-robolectric-r0.jar", 22: "android-all-5.1.1_r9-robolectric-r2.jar",
            23: "android-all-6.0.1_r3-robolectric-r1.jar", 24: "android-all-7.0.0_r1-robolectric-r1.jar",
            25: "android-all-7.1.0_r7-robolectric-r1.jar", 26: "android-all-8.0.0_r4-robolectric-r1.jar",
            28: "android-all-9-robolectric-4913185-2.jar"}


def read_prop(path):
    board = {}
    for line in open(path):
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            board[k.strip()] = v.strip()
    return board


def imported_board(name):
    """imported/<name>/version.txt (+ the phone's framework files) -> board."""
    d = os.path.join(IMPORTED, name)
    version = open(os.path.join(d, "version.txt")).read().strip()
    if version not in ANDROID_API:
        sys.exit("error: imported/%s/version.txt: unknown Android version %r (use one of %s)"
                 % (name, version, ", ".join(ANDROID_API)))
    api = ANDROID_API[version]
    # install path exists for vdex-based releases (8.0, 8.1, 9); 5-7 need oat extraction
    status = "supported" if 26 <= api <= 28 else "port-only" if api < 26 else "planned"
    board = {"name": name, "android": version, "api": str(api), "models": "", "target_api": "30",
             "status": status, "phone_files": os.path.relpath(d, ROOT), "imported": "yes"}
    if api in AOSP_REF:
        board["base_framework_fallback"] = "tools/aosp/" + AOSP_REF[api]
    return board


def read_board(name):
    official = os.path.join(ROOT, "boards", name + ".prop")
    custom = os.path.join(IMPORTED, name, "version.txt")
    if os.path.exists(official) and os.path.exists(custom):
        sys.exit("error: imported/%s clashes with the official board %s; rename the folder" % (name, name))
    if os.path.exists(official):
        board = read_prop(official)
    elif os.path.exists(custom):
        board = imported_board(name)
    else:
        sys.exit("error: unknown board %s (see ./build.py --list-boards)" % name)
    board["api"] = int(board["api"])
    board["target_api"] = int(board["target_api"])
    return board


def all_boards():
    names = [os.path.basename(p)[:-5] for p in glob.glob(os.path.join(ROOT, "boards", "*.prop"))]
    names += [os.path.basename(os.path.dirname(p)) for p in glob.glob(os.path.join(IMPORTED, "*", "version.txt"))]
    boards = [read_board(n) for n in sorted(set(names))]
    boards.sort(key=lambda b: (b.get("imported", ""), b["name"].split("-")[0], b["api"], b["name"]))
    return boards


def list_boards():
    for b in all_boards():
        print("%-14s Android %-4s (API %d) -> up to API %d  [%s]%s%s" % (
            b["name"], b["android"], b["api"], b["target_api"], b["status"],
            "  models: " + b["models"] if b.get("models") else "",
            "  (imported/%s)" % b["name"] if b.get("imported") else ""))


def phone_inputs(folder):
    """Usable framework code in a folder of files copied from a phone.

    Returns (dex/jar inputs for tools/RefJar.java, vdex files, jars without code)."""
    import zipfile
    inputs, vdex, empty = [], [], []
    for base, _, files in os.walk(folder):
        for f in sorted(files):
            path = os.path.join(base, f)
            if f.endswith(".vdex"):
                vdex.append(path)
            elif f.endswith(".dex"):
                inputs.append(path)
            elif f.endswith(".jar"):
                with zipfile.ZipFile(path) as z:
                    if any(re.fullmatch(r"classes\d*\.dex", n) for n in z.namelist()):
                        inputs.append(path)
                    else:
                        empty.append(path)
    return inputs, vdex, empty


def convert_phone_framework(board):
    """Phone files (jar with dex, or jar + vdex) -> build/ref/<board>.jar, or None."""
    folder = os.path.join(ROOT, board["phone_files"])
    inputs, vdex, empty = phone_inputs(folder)
    if not inputs and not vdex:
        return None
    work = os.path.join(BUILD, "ref", board["name"])
    shutil.rmtree(work, ignore_errors=True)
    os.makedirs(work)
    if vdex:
        host = os.path.join(ROOT, "tools", "bin", "host", "vdexExtractor")
        if not os.path.exists(host):
            sys.exit("error: %s has .vdex files; build the PC extractor first: make init" % board["phone_files"])
        for v in vdex:
            out = os.path.join(work, os.path.basename(v)[:-5])
            os.makedirs(out)
            run([host, "-i", v, "-o", out])
            got = sorted(glob.glob(os.path.join(out, "*.dex")))
            if not got:
                sys.exit("error: vdexExtractor found no dex in %s" % v)
            inputs += got
    # jars with neither dex nor a matching vdex: their classes would be missing from the reference
    names = {re.sub(r"^boot-", "", os.path.basename(v)[:-5]) for v in vdex}
    missing = [j for j in empty if os.path.basename(j)[:-4] not in names]
    for j in missing:
        print("WARNING: %s has no code and no matching .vdex (look for boot-%s.vdex or oat/<arch>/%s.vdex "
              "on the phone); its classes are left out of the reference"
              % (os.path.relpath(j, ROOT), os.path.basename(j)[:-4], os.path.basename(j)[:-4]))
    ref = os.path.join(BUILD, "ref", board["name"] + ".jar")
    run([tool("java"), "-Xmx4g", "-cp", os.pathsep.join(DEXLIB + [ASM]), os.path.join(ROOT, "tools", "RefJar.java"),
         ref] + inputs)
    return ref


_reference = {}


def base_framework(board):
    """Reference for the porter (computed once per board)."""
    if board["name"] not in _reference:
        _reference[board["name"]] = find_reference(board)
    return _reference[board["name"]]


def find_reference(board):
    """The phone's own framework if provided (converted), else the AOSP jar."""
    if board.get("phone_files") and os.path.isdir(os.path.join(ROOT, board["phone_files"])):
        ref = convert_phone_framework(board)
        if ref:
            return ref
        print("note: %s has no usable framework code yet (see README \"Boards\")" % board["phone_files"])
    for key in ("base_framework", "base_framework_fallback"):
        rel = board.get(key)
        if rel and os.path.exists(os.path.join(ROOT, rel)):
            if key.endswith("fallback"):
                print("note: using the generic reference %s" % rel)
            return os.path.join(ROOT, rel)
    if board.get("imported"):
        sys.exit("error: imported/%s: no usable framework code and no AOSP fallback for Android %s"
                 % (board["name"], board["android"]))
    sys.exit("error: %s missing (AOSP reference for %s; see tools/aosp/README.md)"
             % (board.get("base_framework"), board["name"]))


# Android version -> API level, for --maxSDK
VERSIONS = {"8.1": 27, "9": 28, "10": 29, "11": 30}
# AOSP framework the porter takes code from: the oldest one that has the level
AOSP = [(29, "android-all-10-robolectric-5803371.jar"), (30, "android-all-11-robolectric-6757853.jar")]
ASM_ALL = [ASM, "/usr/share/java/asm-tree-9.8.jar", "/usr/share/java/asm-commons-9.8.jar"]


def max_sdk(value):
    """--maxSDK takes an Android version (10, 11) or an API level (29, 30)."""
    if value in VERSIONS:
        return VERSIONS[value]
    if value.isdigit() and int(value) >= 21:
        return int(value)
    sys.exit("error: --maxSDK %s: use an Android version (%s) or an API level"
             % (value, ", ".join(VERSIONS)))


def build_ported(board, experimental):
    """experimental: "false", "true" or "all" (see --experimental)."""
    """Port AOSP implementations of API (board api+1)..target_api -> out/levels/ported/."""
    ref = base_framework(board)
    if ref is None:
        sys.exit("error: board %s has no reference framework" % board["name"])
    newest = next((jar for api, jar in AOSP if api >= board["target_api"]), None)
    if newest is None:
        sys.exit("error: no AOSP framework for API %d in tools/aosp" % board["target_api"])
    suffix = {"false": "", "true": "-experimental", "all": "-all"}[experimental]
    work = os.path.join(BUILD, "ported", board["name"] + suffix)
    out = os.path.join(OUT, "levels", "ported")
    shutil.rmtree(work, ignore_errors=True)
    shutil.rmtree(out, ignore_errors=True)
    os.makedirs(out)
    core = stub(board["api"])
    report = os.path.join(OUT, "reports", "port-report-%s-api%d%s.txt" % (
        board["name"], board["target_api"], suffix))
    os.makedirs(os.path.dirname(report), exist_ok=True)
    run([tool("java"), "-Xmx4g", "-cp", os.pathsep.join(ASM_ALL), os.path.join(ROOT, "tools", "Porter.java"),
         ref, os.path.join(ROOT, "tools", "aosp", newest), os.path.join(PLATFORM, "data", "api-versions.xml"),
         str(board["api"] + 1), str(board["target_api"]), report, core, "--emit", work,
         "--api-jar", os.path.join(PLATFORM, "android.jar")]
        + {"false": [], "true": ["--experimental"], "all": ["--all"]}[experimental])
    classes_dir = os.path.join(work, "classes")
    files = os.path.join(work, "files.txt")
    with open(files, "w") as f:
        for base, _, names in os.walk(classes_dir):
            for n in names:
                f.write(os.path.join(base, n) + "\n")
    run(d8() + ["--min-api", str(board["api"]), "--lib", core, "--classpath", ref, "--output", out, "@" + files])
    shutil.copy(os.path.join(work, "merge.txt"), out)
    shutil.copy(os.path.join(work, "clinit.txt"), out)
    print("ported: report %s" % os.path.relpath(report, ROOT))


def build_module(levels, board):
    """out/api-backport-<board>-<version>.zip: module/ + levels + patcher + vdexExtractor."""
    import zipfile
    prop = dict(l.split("=", 1) for l in open(os.path.join(ROOT, "module", "module.prop")).read().split("\n") if "=" in l)
    zpath = os.path.join(OUT, "api-backport-%s-api%d%s-%s.zip" % (
        board["name"], board["target_api"],
        {"false": "", "true": "-experimental", "all": "-all"}[board["experimental"]],
        prop["version"]))
    levels = [l for l in levels if board["api"] < l <= board["target_api"]]
    bins = glob.glob(os.path.join(ROOT, "tools", "bin", "*", "vdexExtractor"))
    if len(bins) < 2:
        sys.exit("error: run tools/build-vdexextractor.sh first")
    with zipfile.ZipFile(zpath, "w", zipfile.ZIP_DEFLATED) as z:
        for base, _, files in os.walk(os.path.join(ROOT, "module")):
            for f in files:
                full = os.path.join(base, f)
                z.write(full, os.path.relpath(full, os.path.join(ROOT, "module")))
        ported = ["ported"] if os.path.isdir(os.path.join(OUT, "levels", "ported")) else []
        for name in ["common"] + ["api%d" % l for l in levels] + ported:
            d = os.path.join(OUT, "levels", name)
            for f in os.listdir(d):
                z.write(os.path.join(d, f), "levels/%s/%s" % (name, f))
        z.writestr("board.prop", "".join("%s=%s\n" % (k, board[k])
                                         for k in ("name", "android", "api", "models", "target_api", "experimental")))
        z.write(os.path.join(OUT, "patcher.jar"), "patcher.jar")
        for b in bins:
            abi = os.path.basename(os.path.dirname(b))
            z.write(b, "bin/%s/vdexExtractor" % abi)
    print("module: %s (board %s, API %d -> %d, levels: %s)" % (
        os.path.relpath(zpath, ROOT), board["name"], board["api"], board["target_api"],
        ", ".join(["common"] + ["api%d" % l for l in levels] + ported)))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--board", default="generic-8", help="target board, see boards/ (default generic-8)")
    ap.add_argument("--maxSDK", "--max-sdk", dest="max_sdk",
                    help="highest Android version to add: 8.1, 9, 10, 11 (or API level); default: board's target_api")
    ap.add_argument("--experimental", default="false", choices=["false", "true", "all"],
                    help="false: hidden + safely portable APIs; true: also APIs that add fields to existing"
                         " classes; all: also AOSP static initialisers and stubs for native/missing methods")
    ap.add_argument("--no-port", action="store_true", help="only hand-written backports, skip the AOSP porter")
    ap.add_argument("--list-boards", action="store_true", help="show available boards and exit")
    ap.add_argument("--clean", action="store_true", help="delete build/ and out/ first")
    args = ap.parse_args()
    if args.list_boards:
        list_boards()
        return
    board = read_board(args.board)
    if args.max_sdk:
        board["target_api"] = max_sdk(args.max_sdk)
    experimental = args.experimental
    board["experimental"] = args.experimental
    if board["status"] not in ("supported", "port-only"):
        sys.exit("error: board %s is %s, not buildable yet (see TODO.md)" % (board["name"], board["status"]))
    if board["target_api"] <= board["api"]:
        sys.exit("error: --maxSDK must be above the board's own level (API %d)" % board["api"])
    ref = base_framework(board)
    print("board %s: Android %s (API %d), reference framework: %s" % (
        board["name"], board["android"], board["api"], os.path.relpath(ref, ROOT) if ref else "none"))
    if args.clean:
        for d in (BUILD, OUT):
            shutil.rmtree(d, ignore_errors=True)
    levels = build_levels()
    shutil.rmtree(os.path.join(OUT, "levels", "ported"), ignore_errors=True)
    if not args.no_port:
        build_ported(board, experimental)
    if board["status"] == "port-only":
        print("board %s is port-only: levels and report in %s, no module zip (%s)" % (
            board["name"], os.path.relpath(os.path.join(OUT, "levels"), ROOT),
            "on-phone extraction for Android %s not written yet" % board["android"]))
        return
    build_patcher()
    build_module(levels, board)
    print("levels: %s -> %s" % (", ".join("api%d" % l for l in levels), os.path.relpath(OUT, ROOT)))


if __name__ == "__main__":
    main()
