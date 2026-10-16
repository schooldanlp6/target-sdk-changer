#!/usr/bin/env python3
"""apiscan - which Android framework APIs newer than the phone do APKs use?

Reads every classes*.dex in each APK and resolves every referenced framework
method/field against the SDK's api-versions.xml. It reports the ones added
after the device's API level, i.e. what a framework backport onto the stock
(Sony) ROM would have to provide for these apps.

    apiscan.py [--device-api 26] [--top 50] APK_OR_DIR [...]

References are "guarded" (never run on the phone) when the calling method
reads Build.VERSION.SDK_INT, sits in an AndroidX *ApiNNImpl / R8
*ApiModelOutline class, or is only called from such guarded methods. Everything else counts as "unguarded". That is a
heuristic: obfuscated apps hide some guards, so treat it as an estimate.
See README.md.
"""
import argparse
import collections
import glob
import os
import re
import struct
import sys
import xml.etree.ElementTree as ET
import zipfile

SEARCH_API_XML = [
    os.path.join(os.environ.get(v, ""), "platforms", "android-*", "data", "api-versions.xml")
    for v in ("ANDROID_HOME", "ANDROID_SDK_ROOT") if os.environ.get(v)
] + [os.path.expanduser("~/sdktools/platforms/android-*/data/api-versions.xml"),
     os.path.expanduser("~/Android/Sdk/platforms/android-*/data/api-versions.xml")]


# --------------------------------------------------------------------------
# api-versions.xml
# --------------------------------------------------------------------------

def _level(attr, default):
    if attr is None:
        return default
    return int(attr.split(".")[0])


def find_api_xml():
    hits = []
    for pattern in SEARCH_API_XML:
        hits += glob.glob(pattern)
    if not hits:
        return None
    # newest platform has the most complete data
    return max(hits, key=lambda p: int(p.split("android-")[-1].split(os.sep)[0].split("-")[0]
                                       if p.split("android-")[-1].split(os.sep)[0].split("-")[0].isdigit() else 0))


class ApiDb:
    """class descriptor -> since, parents, members {"name(desc)ret" or field name: since}"""

    def __init__(self, path):
        self.classes = {}
        for el in ET.parse(path).getroot().iter("class"):
            since = _level(el.get("since"), 1)
            parents, members = [], {}
            for child in el:
                if child.tag in ("extends", "implements"):
                    if child.get("removed") is None:
                        parents.append("L%s;" % child.get("name"))
                elif child.tag in ("method", "field"):
                    members[child.get("name")] = _level(child.get("since"), since)
            self.classes["L%s;" % el.get("name")] = (since, parents, members)

    def lookup(self, cls, member, seen=None):
        """Return the API level `member` became available on `cls`, or None."""
        entry = self.classes.get(cls)
        if entry is None:
            return None
        seen = seen if seen is not None else set()
        if cls in seen:
            return None
        seen.add(cls)
        since, parents, members = entry
        if member in members:
            return members[member]
        for parent in parents:
            found = self.lookup(parent, member, seen)
            if found is not None:
                return found
        return None


# --------------------------------------------------------------------------
# dex
# --------------------------------------------------------------------------

def _uleb(buf, p):
    result = shift = 0
    while True:
        b = buf[p]
        p += 1
        result |= (b & 0x7F) << shift
        if not b & 0x80:
            return result, p
        shift += 7


def _op_units():
    units = [1] * 256
    def put(lo, hi, n):
        for op in range(lo, hi + 1):
            units[op] = n
    put(0x02, 0x02, 2); put(0x03, 0x03, 3); put(0x05, 0x05, 2); put(0x06, 0x06, 3)
    put(0x08, 0x08, 2); put(0x09, 0x09, 3)
    put(0x13, 0x13, 2); put(0x14, 0x14, 3); put(0x15, 0x16, 2); put(0x17, 0x17, 3)
    put(0x18, 0x18, 5); put(0x19, 0x1A, 2); put(0x1B, 0x1B, 3); put(0x1C, 0x1C, 2)
    put(0x1F, 0x20, 2); put(0x22, 0x23, 2); put(0x24, 0x26, 3); put(0x29, 0x29, 2)
    put(0x2A, 0x2C, 3); put(0x2D, 0x3D, 2); put(0x44, 0x6D, 2); put(0x6E, 0x72, 3)
    put(0x74, 0x78, 3); put(0x90, 0xAF, 2); put(0xD0, 0xE2, 2); put(0xFA, 0xFB, 4)
    put(0xFC, 0xFD, 3); put(0xFE, 0xFF, 2)
    return units


OP_UNITS = _op_units()
SDK_INT = ("Landroid/os/Build$VERSION;", "SDK_INT")
# AndroidX FooCompat$Api29Impl, R8 $$ExternalSyntheticApiModelOutline0, ...
GUARD_CLASS_RE = re.compile(r"Api\d+Impl|ApiModelOutline|\$Api\d+|\$Impl\d+")


class Dex:
    def __init__(self, buf):
        if buf[:3] != b"dex":
            raise ValueError("not a dex file")
        self.buf = buf
        u = lambda o: struct.unpack_from("<I", buf, o)[0]
        n_str, o_str = u(0x38), u(0x3C)
        n_type, o_type = u(0x40), u(0x44)
        n_proto, o_proto = u(0x48), u(0x4C)
        n_field, o_field = u(0x50), u(0x54)
        n_meth, o_meth = u(0x58), u(0x5C)
        n_cls, o_cls = u(0x60), u(0x64)

        self.strings = [self._string(u(o_str + 4 * i)) for i in range(n_str)]
        self.types = [self.strings[u(o_type + 4 * i)] for i in range(n_type)]
        self.protos = []
        for i in range(n_proto):
            _, ret, params_off = struct.unpack_from("<III", buf, o_proto + 12 * i)
            params = ""
            if params_off:
                n = u(params_off)
                params = "".join(self.types[t] for t in
                                 struct.unpack_from("<%dH" % n, buf, params_off + 4))
            self.protos.append("(%s)%s" % (params, self.types[ret]))
        self.fields = []
        for i in range(n_field):
            c, _, name = struct.unpack_from("<HHI", buf, o_field + 8 * i)
            self.fields.append((self.types[c], self.strings[name]))
        self.methods = []
        for i in range(n_meth):
            c, proto, name = struct.unpack_from("<HHI", buf, o_meth + 8 * i)
            self.methods.append((self.types[c], self.strings[name] + self.protos[proto]))

        # app-defined classes: superclass, interfaces, declared members, code
        self.class_defs = {}
        self.code = {}
        for i in range(n_cls):
            cls, _, sup, ifaces_off, _, _, data_off, _ = struct.unpack_from("<8I", buf, o_cls + 32 * i)
            parents = [] if sup == 0xFFFFFFFF else [self.types[sup]]
            if ifaces_off:
                n = u(ifaces_off)
                parents += [self.types[t] for t in struct.unpack_from("<%dH" % n, buf, ifaces_off + 4)]
            declared, code = self._class_data(data_off)
            self.class_defs[self.types[cls]] = (parents, declared)
            self.code[self.types[cls]] = code

    def _string(self, off):
        _, p = _uleb(self.buf, off)
        end = self.buf.index(b"\0", p)
        return bytes(self.buf[p:end]).decode("utf-8", "replace")

    def _class_data(self, off):
        names, code = set(), []
        if not off:
            return names, code
        counts = []
        p = off
        for _ in range(4):
            n, p = _uleb(self.buf, p)
            counts.append(n)
        for k, n in enumerate(counts):
            idx = 0
            for _ in range(n):
                diff, p = _uleb(self.buf, p)
                _, p = _uleb(self.buf, p)  # access flags
                if k >= 2:
                    code_off, p = _uleb(self.buf, p)
                idx += diff
                if k < 2:
                    names.add(self.fields[idx][1])
                else:
                    names.add(self.methods[idx][1])
                    if code_off:
                        code.append((self.methods[idx], code_off))
        return names, code

    def refs(self, code_off):
        """Yield ("f"|"m", index) for every field/method reference in one method."""
        buf = self.buf
        n = struct.unpack_from("<I", buf, code_off + 12)[0]
        base = code_off + 16
        pc = 0
        while pc < n:
            unit = struct.unpack_from("<H", buf, base + 2 * pc)[0]
            op = unit & 0xFF
            if op == 0x00 and unit in (0x0100, 0x0200, 0x0300):
                size = struct.unpack_from("<H", buf, base + 2 * pc + 2)[0]
                if unit == 0x0100:
                    pc += 4 + size * 2
                elif unit == 0x0200:
                    pc += 2 + size * 4
                else:
                    count = struct.unpack_from("<I", buf, base + 2 * pc + 4)[0]
                    pc += 4 + (size * count + 1) // 2
                continue
            if 0x52 <= op <= 0x6D:
                yield "f", struct.unpack_from("<H", buf, base + 2 * pc + 2)[0]
            elif 0x6E <= op <= 0x72 or 0x74 <= op <= 0x78 or op in (0xFA, 0xFB):
                yield "m", struct.unpack_from("<H", buf, base + 2 * pc + 2)[0]
            pc += OP_UNITS[op]


# --------------------------------------------------------------------------
# scan
# --------------------------------------------------------------------------

def scan_apk(path, db, device_api):
    """Return ({api: since} for all newer refs, set of APIs referenced unguarded)."""
    dexes = []
    with zipfile.ZipFile(path) as z:
        for name in z.namelist():
            if name.startswith("classes") and name.endswith(".dex") and "/" not in name:
                dexes.append(Dex(z.read(name)))
    app_classes = {}
    for d in dexes:
        app_classes.update(d.class_defs)
    cache = {}

    def resolve(cls, member, seen):
        # walk app classes up to the first framework class
        if cls in seen:
            return None
        seen.add(cls)
        if cls in app_classes:
            parents, declared = app_classes[cls]
            if member in declared:
                return None  # app's own member
            for parent in parents:
                found = resolve(parent, member, seen)
                if found is not None:
                    return found
            return None
        if cls not in db.classes:
            return None  # third-party library not bundled, or hidden API
        member_since = db.lookup(cls, member)
        if member_since is None:
            return None
        return cls, max(member_since, db.classes[cls][0])

    def newer_api(cls, member):
        key = (cls, member)
        if key not in cache:
            found = resolve(cls, member, set())
            cache[key] = (pretty(found[0], member), found[1]) \
                if found and found[1] > device_api else None
        return cache[key]

    # pass 1: per app method, its newer-API hits, SDK_INT read, app callees
    info = {}  # (cls, member) -> [hits, reads_sdk_int, guarded_class]
    callers = collections.defaultdict(set)
    refs_of = {}
    for d in dexes:
        for caller, methods in d.code.items():
            guarded_class = GUARD_CLASS_RE.search(caller) is not None
            for key, code_off in methods:
                hits, reads_sdk_int, app_refs = [], False, []
                for kind, idx in d.refs(code_off):
                    cls, member = d.fields[idx] if kind == "f" else d.methods[idx]
                    if (cls, member) == SDK_INT:
                        reads_sdk_int = True
                        continue
                    if cls in app_classes:
                        app_refs.append((kind, (cls, member)))
                        if kind == "m":
                            callers[(cls, member)].add(key)
                        continue
                    hit = newer_api(cls, member)
                    if hit:
                        hits.append(hit)
                info[key] = [hits, reads_sdk_int, guarded_class]
                refs_of[key] = app_refs

    # version helpers: app methods returning something derived from SDK_INT
    # (isAtLeastQ() style) and app fields caching it. Using one counts as a
    # SDK_INT check.
    sdk_sources = set()
    for key, (hits, reads_sdk_int, _) in info.items():
        if reads_sdk_int and not hits:
            sdk_sources.add(("m", key))
            sdk_sources.update(r for r in refs_of[key] if r[0] == "f")
    for key, app_refs in refs_of.items():
        if any(r in sdk_sources for r in app_refs):
            info[key][1] = True

    # pass 2: guarded = checks SDK_INT itself, is a known helper class, or
    # is only ever called from guarded methods (obfuscated *ApiNNImpl helpers)
    memo = {}

    def callers_of(key):
        # direct calls plus virtual calls made through a superclass/interface
        cls, member = key
        sites, todo, seen = set(), [cls], set()
        while todo:
            c = todo.pop()
            if c in seen or c not in app_classes:
                continue
            seen.add(c)
            sites |= callers.get((c, member), set())
            todo += app_classes[c][0]
        return sites

    def guarded(key, depth=0):
        if key in memo:
            return memo[key]
        _, reads_sdk_int, guarded_class = info.get(key, (None, False, False))
        result = reads_sdk_int or guarded_class
        sites = callers_of(key)
        if not result and depth < 6 and sites:
            memo[key] = False  # break cycles
            result = all(guarded(c, depth + 1) for c in sites)
        memo[key] = result
        return result

    newer, unguarded = {}, set()
    for key, (hits, _, _) in info.items():
        if not hits:
            continue
        safe = guarded(key)
        for api, since in hits:
            newer[api] = since
            if not safe:
                unguarded.add(api)
    return newer, unguarded


def pretty(cls, member):
    name = cls[1:-1].replace("/", ".")
    return name if member is None else "%s#%s" % (name, member)


def collect_apks(paths):
    for p in paths:
        if os.path.isdir(p):
            for root, _, files in os.walk(p):
                for f in sorted(files):
                    if f.endswith(".apk"):
                        yield os.path.join(root, f)
        else:
            yield p


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("paths", nargs="+", help="APK files or directories of APKs")
    ap.add_argument("--device-api", type=int, default=26, help="phone's API level (default 26)")
    ap.add_argument("--api-versions", help="path to api-versions.xml (default: newest SDK platform)")
    ap.add_argument("--top", type=int, default=50, help="rows in the combined ranking")
    ap.add_argument("-v", "--verbose", action="store_true", help="list every API per APK")
    ap.add_argument("--all", action="store_true",
                    help="rank all references, not only the unguarded ones")
    args = ap.parse_args()

    xml = args.api_versions or find_api_xml()
    if not xml or not os.path.exists(xml):
        sys.exit("error: api-versions.xml not found, pass --api-versions")
    db = ApiDb(xml)

    usage = collections.Counter()
    since_of = {}
    apks = list(collect_apks(args.paths))
    for apk in apks:
        try:
            newer, unguarded = scan_apk(apk, db, args.device_api)
        except (zipfile.BadZipFile, ValueError, struct.error, KeyError, IndexError) as e:
            print("%s: skipped (%s)" % (apk, e))
            continue
        levels = collections.Counter(newer[a] for a in unguarded)
        print("%s: %d newer APIs referenced, %d unguarded %s" % (
            os.path.basename(apk), len(newer), len(unguarded),
            " ".join("api%d:%d" % kv for kv in sorted(levels.items()))))
        counted = newer.keys() if args.all else unguarded
        if args.verbose:
            for api in sorted(counted, key=lambda a: (newer[a], a)):
                print("    %2d  %s" % (newer[api], api))
        usage.update(counted)
        since_of.update(newer)

    by_level = collections.Counter(since_of[a] for a in usage)
    what = "referenced" if args.all else "unguarded"
    print("\n%d APKs, %d distinct %s APIs newer than API %d" % (
        len(apks), len(usage), what, args.device_api))
    print("per level: " + " ".join("api%d:%d" % kv for kv in sorted(by_level.items())))
    print("\nmost used (APKs  since  API):")
    for api, n in usage.most_common(args.top):
        print("  %4d  %4d   %s" % (n, since_of[api], api))


if __name__ == "__main__":
    main()
