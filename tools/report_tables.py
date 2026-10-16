#!/usr/bin/env python3
"""Turn out/reports/port-report-*.txt into Markdown feature tables.

    tools/report_tables.py [out/reports] > tables.md

One table per board: rows = Android level added, columns = results of the
three --experimental modes (false / true / all) for the highest maxSDK
built for that board.
"""
import glob
import os
import re
import sys

NAMES = {22: "5.1", 23: "6.0", 24: "7.0", 25: "7.1", 26: "8.0", 27: "8.1", 28: "9", 29: "10", 30: "11"}


def parse(path):
    rows = {}
    for line in open(path):
        m = re.match(r"(api(\d+)|total)\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)", line)
        if m:
            key = int(m.group(2)) if m.group(2) else "total"
            rows[key] = tuple(int(m.group(i)) for i in range(3, 7))
    return rows


def fmt(n):
    return "{:,}".format(n)


def main():
    folder = sys.argv[1] if len(sys.argv) > 1 else "out/reports"
    reports = {}
    for path in glob.glob(os.path.join(folder, "port-report-*.txt")):
        m = re.match(r"port-report-(.+)-api(\d+)(-experimental|-all)?\.txt", os.path.basename(path))
        if m:
            mode = {None: "false", "-experimental": "true", "-all": "all"}[m.group(3)]
            reports[(m.group(1), int(m.group(2)), mode)] = parse(path)

    def board_key(b):
        nums = re.findall(r"[\d.]+", b)
        return (b.split("-")[0], float(nums[0]) if nums else 0)

    for board in sorted({k[0] for k in reports}, key=board_key):
        top = max(k[1] for k in reports if k[0] == board)
        modes = [reports.get((board, top, m)) for m in ("false", "true", "all")]
        if not all(modes):
            continue
        print("##### `%s` → Android %s\n" % (board, NAMES.get(top, top)))
        print("| Level | Public APIs added | Already there (hidden) | Ported: false | Ported: true "
              "| Ported: all | Blocked: all |")
        print("| --- | --- | --- | --- | --- | --- | --- |")
        safe, exp, full = modes
        for key in sorted(k for k in safe if k != "total") + ["total"]:
            total, there, ported, _ = safe[key]
            cells = [fmt(total), fmt(there), fmt(ported), fmt(exp[key][2]), fmt(full[key][2]), fmt(full[key][3])]
            label = "**Total**" if key == "total" else "API %d (%s)" % (key, NAMES.get(key, "?"))
            if key == "total":
                cells = ["**%s**" % c for c in cells]
            print("| %s | %s |" % (label, " | ".join(cells)))
        print()

if __name__ == "__main__":
    main()
