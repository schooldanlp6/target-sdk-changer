#!/usr/bin/env python3
"""sdkpatch - lower minSdkVersion / change targetSdkVersion of an APK.

Patches the *binary* AndroidManifest.xml inside the APK in place (no
apktool decode/rebuild, so resources and dex stay byte-identical), then
zipaligns and re-signs the result with your own key.

    sdkpatch.py info  app.apk [split.apk ...]
    sdkpatch.py patch app.apk [split.apk ...] --min-sdk 26 [--target-sdk N] -o out/

See README.md for background and limitations.
"""
import argparse
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile

# android.R.attr resource ids
ATTR_MIN_SDK = 0x0101020C
ATTR_TARGET_SDK = 0x01010270
ATTR_MAX_SDK = 0x01010271
ATTR_NAMES = {ATTR_MIN_SDK: "minSdkVersion", ATTR_TARGET_SDK: "targetSdkVersion",
              ATTR_MAX_SDK: "maxSdkVersion"}

# chunk types
RES_STRING_POOL = 0x0001
RES_XML = 0x0003
RES_XML_START_ELEMENT = 0x0102
RES_XML_RESOURCE_MAP = 0x0180

# Res_value data types
TYPE_STRING = 0x03
TYPE_INT_DEC = 0x10

UTF8_FLAG = 0x100
NO_INDEX = 0xFFFFFFFF

SIGNATURE_SUFFIXES = (".SF", ".RSA", ".DSA", ".EC")

DEFAULT_KEYSTORE = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                "keys", "sdkpatch.keystore")
KEY_ALIAS = "sdkpatch"
KEY_PASS = "android"


class ManifestError(Exception):
    pass


# --------------------------------------------------------------------------
# binary XML (AXML)
# --------------------------------------------------------------------------

def _read_string_pool(buf, off):
    _, header_size, _ = struct.unpack_from("<HHI", buf, off)
    count, _, flags, strings_start, _ = struct.unpack_from("<IIIII", buf, off + 8)
    offsets = struct.unpack_from("<%dI" % count, buf, off + header_size)
    base = off + strings_start
    utf8 = bool(flags & UTF8_FLAG)
    out = []
    for o in offsets:
        p = base + o
        if utf8:
            # char length (1-2 bytes), then byte length (1-2 bytes)
            for _ in range(2):
                n = buf[p]
                p += 1
                if n & 0x80:
                    n = ((n & 0x7F) << 8) | buf[p]
                    p += 1
            out.append(bytes(buf[p:p + n]).decode("utf-8", "replace"))
        else:
            n = struct.unpack_from("<H", buf, p)[0]
            p += 2
            if n & 0x8000:
                n = ((n & 0x7FFF) << 16) | struct.unpack_from("<H", buf, p)[0]
                p += 2
            out.append(bytes(buf[p:p + n * 2]).decode("utf-16-le", "replace"))
    return out


def find_sdk_attrs(buf):
    """Return {attr_id: (offset_of_attribute_record, dataType, data)} for <uses-sdk>."""
    typ, header_size, total = struct.unpack_from("<HHI", buf, 0)
    if typ != RES_XML:
        raise ManifestError("not a binary XML manifest (chunk type 0x%04x)" % typ)
    strings, resmap = [], []
    found = {}
    off = header_size
    while off < min(total, len(buf)):
        ctype, chdr, csize = struct.unpack_from("<HHI", buf, off)
        if csize < 8:
            raise ManifestError("corrupt chunk at 0x%x" % off)
        if ctype == RES_STRING_POOL:
            strings = _read_string_pool(buf, off)
        elif ctype == RES_XML_RESOURCE_MAP:
            resmap = struct.unpack_from("<%dI" % ((csize - chdr) // 4), buf, off + chdr)
        elif ctype == RES_XML_START_ELEMENT:
            ext = off + chdr
            _, name, attr_start, attr_size, attr_count = struct.unpack_from("<IIHHH", buf, ext)
            if name < len(strings) and strings[name] == "uses-sdk":
                for i in range(attr_count):
                    a = ext + attr_start + i * attr_size
                    _, aname, _, _, _, dtype, data = struct.unpack_from("<IIIHBBI", buf, a)
                    rid = resmap[aname] if aname < len(resmap) else None
                    if rid in ATTR_NAMES:
                        if dtype == TYPE_STRING:
                            data = strings[data]  # preview codename, e.g. "R"
                        found[rid] = (a, dtype, data)
        off += csize
    return found


def set_sdk_attr(buf, rec_off, value):
    """Overwrite one attribute record with an integer value (same size, in place)."""
    # rawValue -> none, Res_value -> {size=8, res0=0, INT_DEC, value}
    struct.pack_into("<IHBBI", buf, rec_off + 8, NO_INDEX, 8, 0, TYPE_INT_DEC, value)


# --------------------------------------------------------------------------
# APK handling
# --------------------------------------------------------------------------

def read_manifest(apk):
    with zipfile.ZipFile(apk) as z:
        return bytearray(z.read("AndroidManifest.xml"))


def is_signature_file(name):
    if not name.startswith("META-INF/"):
        return False
    return name == "META-INF/MANIFEST.MF" or name.upper().endswith(SIGNATURE_SUFFIXES)


def rewrite_apk(src, dst, manifest):
    """Copy src -> dst with a new manifest, dropping the old v1 signature."""
    with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, "w") as zout:
        for info in zin.infolist():
            if is_signature_file(info.filename):
                continue
            data = manifest if info.filename == "AndroidManifest.xml" else zin.read(info)
            new = zipfile.ZipInfo(info.filename, info.date_time)
            new.compress_type = info.compress_type
            new.external_attr = info.external_attr
            new.extra = b""  # old alignment padding; zipalign redoes it
            zout.writestr(new, data)


def ensure_keystore(path):
    if os.path.exists(path):
        return
    os.makedirs(os.path.dirname(path), exist_ok=True)
    print("creating signing key %s" % path)
    subprocess.run(["keytool", "-genkeypair", "-keystore", path, "-alias", KEY_ALIAS,
                    "-storepass", KEY_PASS, "-keypass", KEY_PASS, "-keyalg", "RSA",
                    "-keysize", "2048", "-validity", "10000",
                    "-dname", "CN=sdkpatch"], check=True)


def align_and_sign(unsigned, out, keystore):
    aligned = unsigned + ".aligned"
    subprocess.run(["zipalign", "-p", "-f", "4", unsigned, aligned], check=True)
    subprocess.run(["apksigner", "sign", "--ks", keystore, "--ks-key-alias", KEY_ALIAS,
                    "--ks-pass", "pass:" + KEY_PASS, "--key-pass", "pass:" + KEY_PASS,
                    "--out", out, aligned], check=True)
    os.remove(aligned)


def describe(found):
    parts = []
    for rid in (ATTR_MIN_SDK, ATTR_TARGET_SDK, ATTR_MAX_SDK):
        if rid in found:
            parts.append("%s=%s" % (ATTR_NAMES[rid], found[rid][2]))
    return ", ".join(parts) or "no <uses-sdk> values"


# --------------------------------------------------------------------------
# commands
# --------------------------------------------------------------------------

def cmd_info(args):
    for apk in args.apks:
        print("%s: %s" % (apk, describe(find_sdk_attrs(read_manifest(apk)))))


def cmd_patch(args):
    os.makedirs(args.out, exist_ok=True)
    if not args.no_sign:
        ensure_keystore(args.keystore)
    tmp = tempfile.mkdtemp(prefix="sdkpatch-")
    try:
        for apk in args.apks:
            manifest = read_manifest(apk)
            found = find_sdk_attrs(manifest)
            print("%s\n  before: %s" % (apk, describe(found)))
            wanted = {ATTR_MIN_SDK: args.min_sdk, ATTR_TARGET_SDK: args.target_sdk}
            for rid, value in wanted.items():
                if value is None:
                    continue
                if rid not in found:
                    # Adding a missing attribute means growing the binary XML;
                    # not supported. A missing minSdk already means "1" anyway.
                    print("  %s not present, left alone" % ATTR_NAMES[rid])
                    continue
                set_sdk_attr(manifest, found[rid][0], value)
            print("  after:  %s" % describe(find_sdk_attrs(manifest)))

            base = os.path.splitext(os.path.basename(apk))[0]
            unsigned = os.path.join(tmp, base + ".unsigned.apk")
            rewrite_apk(apk, unsigned, bytes(manifest))
            if args.no_sign:
                out = os.path.join(args.out, base + "-sdkpatched-unsigned.apk")
                shutil.copyfile(unsigned, out)
            else:
                out = os.path.join(args.out, base + "-sdkpatched.apk")
                align_and_sign(unsigned, out, args.keystore)
            print("  wrote:  %s" % out)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)  # only our own mkdtemp dir


def main():
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)

    pi = sub.add_parser("info", help="show min/target/max SDK of APKs")
    pi.add_argument("apks", nargs="+")
    pi.set_defaults(func=cmd_info)

    pp = sub.add_parser("patch", help="rewrite SDK values and re-sign")
    pp.add_argument("apks", nargs="+",
                    help="APK(s); pass base + all splits together so they share one key")
    pp.add_argument("--min-sdk", type=int, help="new minSdkVersion (XA1 on Android 8.0: 26)")
    pp.add_argument("--target-sdk", type=int, help="new targetSdkVersion (usually leave alone)")
    pp.add_argument("-o", "--out", required=True, help="output directory")
    pp.add_argument("--keystore", default=DEFAULT_KEYSTORE,
                    help="keystore to sign with (created if missing, default %(default)s)")
    pp.add_argument("--no-sign", action="store_true", help="write unsigned, unaligned APKs")
    pp.set_defaults(func=cmd_patch)

    args = p.parse_args()
    if args.cmd == "patch" and args.min_sdk is None and args.target_sdk is None:
        p.error("patch needs --min-sdk and/or --target-sdk")
    try:
        args.func(args)
    except (ManifestError, zipfile.BadZipFile, KeyError) as e:
        sys.exit("error: %s" % e)


if __name__ == "__main__":
    main()
