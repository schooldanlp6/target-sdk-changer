# Boards

One `.prop` file per build target. `./build.py --board NAME` (or
`make BOARD=NAME`) builds a module for it; `./build.py --list-boards`
shows them all.

| key | meaning |
|---|---|
| `name` | board id, used in the zip name |
| `android`, `api` | Android version the phone must run; the installer checks `ro.build.version.sdk` |
| `phone_files` | folder with framework files copied from the phone (jars, vdex); converted into the reference automatically |
| `base_framework` | reference jar (Java class files), e.g. an AOSP jar from `tools/aosp/` |
| `base_framework_fallback` | used while `phone_files` has no usable code / `base_framework` is missing |
| `models` | allowed `ro.product.model` values, space separated; empty = any |
| `target_api` | highest API level the module adds (and the install limit) |
| `status` | `supported`: full module zip; `port-only`: ported levels + report, no zip (on-phone extraction missing); `planned`: stops with a message |

Official boards are these `.prop` files. Custom boards don't need one:
`make new-board NAME=myphone VERSION=8.0` creates `imported/myphone/version.txt`,
and the phone's framework files go next to it. Board list, which files to
copy from a phone and how: see "Boards" in the main
[README](../README.md#boards).
