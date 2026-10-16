#!/bin/sh
# Build every buildable board (official + imported) for every target above
# its own version, in all three --experimental modes.
#   tools/build-matrix.sh [MAXSDK...]      default: 8.1 9 10 11
# Log: out/matrix.log. Exit code 1 if any build failed.
cd "$(dirname "$0")/.." || exit 1
targets=${*:-8.1 9 10 11}
mkdir -p out
log=out/matrix.log
: > "$log"
ok=0; failed=0; skipped=0

api_of() {   # Android version or API level -> API level
    case "$1" in 8.1) echo 27 ;; 9) echo 28 ;; 10) echo 29 ;; 11) echo 30 ;; *) echo "$1" ;; esac
}

boards=$(python3 build.py --list-boards | awk '/\[(supported|port-only)\]/ {print $1 ":" $5}' | tr -d ')')
for entry in $boards; do
    board=${entry%%:*}
    board_api=${entry##*:}
    for t in $targets; do
        if [ "$(api_of "$t")" -le "$board_api" ]; then
            skipped=$((skipped + 3))
            continue
        fi
        for mode in false true all; do
            if out=$(python3 build.py --board "$board" --maxSDK "$t" --experimental "$mode" 2>&1); then
                ok=$((ok + 1))
                result=$(echo "$out" | grep -E '^total|^module:|port-only' | tr -s ' ' | tr '\n' ' ')
                echo "ok      $board maxSDK=$t experimental=$mode: $result" | tee -a "$log" | cut -c1-150
            else
                failed=$((failed + 1))
                echo "FAILED  $board maxSDK=$t experimental=$mode: $(echo "$out" | tail -3 | tr '\n' ' ')" \
                    | tee -a "$log" | cut -c1-200
            fi
        done
    done
done
echo "matrix: $ok built, $failed failed, $skipped skipped (target not above the board's version); log: $log" \
    | tee -a "$log"
[ "$failed" -eq 0 ]
