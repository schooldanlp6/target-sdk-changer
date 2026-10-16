# API Backport - install script (runs on the phone inside the Magisk app)
#
# On a pre-optimised ROM (Sony stock 8.0) the framework jars hold no dex
# code; it lives in the compiled boot image (/system/framework/<isa>/boot*)
# and in oat/<isa>/*.vdex. Changing one framework class means ART has to
# rebuild the whole boot image from the jars, so:
#
#  1. every jar in /system/framework gets its dex code back (from its vdex)
#  2. the patcher adds the backport levels to framework.jar
#  3. all rebuilt jars go into the module
#  4. the stock boot image and framework odex/vdex files are masked, so ART
#     compiles fresh ones on first boot (takes several minutes)
#
# Every step checks its result; on any error the install aborts and the
# phone is left as it was.

SKIPUNZIP=0
FWDIR=/system/framework

# board.prop is written by build.py --board
prop() { grep "^$1=" "$MODPATH/board.prop" | cut -d= -f2-; }
BOARD=$(prop name)
BOARD_API=$(prop api)
TARGET_API=$(prop target_api)
MODELS=$(prop models)

API=$(getprop ro.build.version.sdk)
MODEL=$(getprop ro.product.model)
ui_print "- Board $BOARD (Android $(prop android)), phone: $MODEL, API $API"
[ "$API" = "$BOARD_API" ] || abort "! This module is for API $BOARD_API, the phone runs API $API"
if [ -n "$MODELS" ]; then
  case " $MODELS " in
    *" $MODEL "*) ;;
    *) abort "! This module is for $MODELS, not $MODEL" ;;
  esac
fi
ui_print "- Adding APIs up to level $TARGET_API"

case "$ARCH" in
  arm64) ABI=arm64-v8a; ISAS="arm64 arm" ;;
  arm)   ABI=armeabi-v7a; ISAS="arm" ;;
  *)     abort "! Unsupported CPU: $ARCH" ;;
esac
ISA=${ISAS%% *}

WORK=$TMPDIR/backport
rm -rf "$WORK"; mkdir -p "$WORK/art/dalvik-cache" "$WORK/out"
EXTRACT=$MODPATH/bin/$ABI/vdexExtractor
chmod 0755 "$EXTRACT"
OUTFW=$MODPATH$FWDIR
mkdir -p "$OUTFW"

run_java() {
  ANDROID_DATA=$WORK/art dalvikvm -Xmx512m -cp "$MODPATH/patcher.jar" "$@"
}

has_dex() {
  unzip -l "$1" 2>/dev/null | grep -q " classes.dex$"
}

# vdex holding the code of jar $1 (boot jars: <isa>/boot[-name].vdex,
# others: oat/<isa>/name.vdex). The first BOOTCLASSPATH entry is plain boot.vdex.
FIRST_BOOT=$(echo "$BOOTCLASSPATH" | cut -d: -f1)
vdex_for() {
  name=$(basename "$1" .jar)
  for cand in "$FWDIR/$ISA/boot-$name.vdex" "$FWDIR/oat/$ISA/$name.vdex"; do
    [ -f "$cand" ] && { echo "$cand"; return; }
  done
  [ "$1" = "$FIRST_BOOT" ] && [ -f "$FWDIR/$ISA/boot.vdex" ] && echo "$FWDIR/$ISA/boot.vdex"
}

# extract jar $1's dex files into directory $2 as classes.dex, classes2.dex, ...
extract_dex() {
  jar=$1; dir=$2
  mkdir -p "$dir"
  if has_dex "$jar"; then
    unzip -o -q "$jar" 'classes*.dex' -d "$dir" || return 1
    return 0
  fi
  vdex=$(vdex_for "$jar")
  [ -n "$vdex" ] || return 1
  rm -rf "$dir.raw"; mkdir -p "$dir.raw"
  "$EXTRACT" -i "$vdex" -o "$dir.raw" >/dev/null 2>&1 || return 1
  i=1
  for f in $(ls "$dir.raw"/*.dex 2>/dev/null | sort -V); do
    [ $i -eq 1 ] && n=classes.dex || n=classes$i.dex
    mv "$f" "$dir/$n"; i=$((i + 1))
  done
  rm -rf "$dir.raw"
  [ $i -gt 1 ]
}

dex_entries() {
  for f in $(ls "$1"/classes*.dex | sort -V); do printf ' %s=%s' "$(basename "$f")" "$f"; done
}

# --- 1. dex code for every framework jar -----------------------------------
ui_print "- Collecting framework code"
COUNT=0
for jar in "$FWDIR"/*.jar; do
  name=$(basename "$jar" .jar)
  dir=$WORK/dex/$name
  if ! extract_dex "$jar" "$dir"; then
    # jars with neither dex nor vdex (e.g. resource-only) are left alone
    if [ -z "$(vdex_for "$jar")" ] && ! has_dex "$jar"; then continue; fi
    abort "! could not get the code of $name.jar"
  fi
  COUNT=$((COUNT + 1))
done
[ -d "$WORK/dex/framework" ] || abort "! framework.jar code not found"
ui_print "- $COUNT jars"

# --- 2. patch framework.jar --------------------------------------------------
# hand-written levels first (they win), then the code ported from AOSP
LEVELS="$MODPATH/levels/common"
l=$((API + 1))
while [ $l -le $TARGET_API ]; do
  [ -d "$MODPATH/levels/api$l" ] && LEVELS="$LEVELS $MODPATH/levels/api$l"
  l=$((l + 1))
done
[ -d "$MODPATH/levels/ported" ] && LEVELS="$LEVELS $MODPATH/levels/ported"
[ "$LEVELS" != "$MODPATH/levels/common" ] || abort "! no backport levels to add"
ui_print "- Adding levels:$(echo $LEVELS | sed 's#[^ ]*/##g')"
case "$(prop experimental)" in
  true) ui_print "- EXPERIMENTAL build: includes APIs with new fields on existing classes" ;;
  all)  ui_print "- EXPERIMENTAL ALL build: also ported static initialisers and stubs" ;;
esac
FWDEX=$(ls "$WORK"/dex/framework/classes*.dex | sort -V)
run_java backport.patcher.Main "$API" "$WORK/out" $FWDEX -- $LEVELS || abort "! patcher failed"
# the patcher writes the complete new dex set for framework.jar
ls "$WORK"/out/classes.dex >/dev/null 2>&1 || abort "! patcher wrote no output"
rm -f "$WORK"/dex/framework/classes*.dex
cp "$WORK"/out/classes*.dex "$WORK/dex/framework/" || abort "! copying patched dex failed"

# --- 3. write the jars into the module ---------------------------------------
ui_print "- Writing jars"
for dir in "$WORK"/dex/*; do
  name=$(basename "$dir")
  run_java backport.patcher.Pack "$OUTFW/$name.jar" --from "$FWDIR/$name.jar" $(dex_entries "$dir") \
    || abort "! writing $name.jar failed"
done

# --- 4. mask stock compiled code ------------------------------------------------
# boot image (all parts, all ISAs) and framework odex/vdex: ART rebuilds them
mask() {
  mkdir -p "$MODPATH${1%/*}"
  mknod "$MODPATH$1" c 0 0 || abort "! could not mask $1"
}
for isa in $ISAS; do
  for f in "$FWDIR/$isa"/boot*.art "$FWDIR/$isa"/boot*.oat "$FWDIR/$isa"/boot*.vdex \
           "$FWDIR/oat/$isa"/*.odex "$FWDIR/oat/$isa"/*.vdex "$FWDIR/oat/$isa"/*.art; do
    [ -e "$f" ] && mask "$f"
  done
done

rm -rf "$MODPATH/levels" "$MODPATH/bin" "$WORK"
ui_print "- Done. First boot recompiles the framework and takes several minutes."
