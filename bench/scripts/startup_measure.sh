#!/usr/bin/env bash
# Startup measurement harness for mboard IME.
# Measures cold-start to first suggestion latency and memory usage on physical device.
set -euo pipefail

SERIAL="${1:-}"
ROUNDS="${2:-5}"

if [ -z "$SERIAL" ]; then
    echo "Usage: $0 <adb-serial> [rounds]"
    exit 1
fi

echo "=== Mboard Startup Measurement on device $SERIAL ($ROUNDS rounds) ==="

# Temporary files to store run numbers
TMP_DIR=$(mktemp -d)
trap 'rm -rf "$TMP_DIR"' EXIT

TOTAL_COLD_TIMES=()
ON_CREATE_TO_FIRST_TIMES=()
ENGINE_READY_TIMES=()
PSS_VALUES=()
HEAP_VALUES=()

for round in $(seq 1 "$ROUNDS"); do
    echo "--- Round $round / $ROUNDS ---"

    # Force stop app and clear logcat
    adb -s "$SERIAL" shell "am force-stop com.m57.mboard"
    sleep 1
    adb -s "$SERIAL" logcat -c

    # Launch SetupActivity
    LAUNCH_MS=$(date +%s%3N)
    adb -s "$SERIAL" shell "am start -n com.m57.mboard/.SetupActivity" > /dev/null
    sleep 1

    # Navigate to PLAYGROUND ("Try your keyboard" button is at bounds [65,2324][1135,2573], tap ~500 2400)
    adb -s "$SERIAL" shell "input tap 500 2400" > /dev/null
    sleep 1

    # Focus text playground (EditText is at bounds [65,948][1135,1416], tap ~500 1100)
    FOCUS_MS=$(date +%s%3N)
    adb -s "$SERIAL" shell "input tap 500 1100" > /dev/null

    # Wait for engine ready and first suggestion
    MAX_WAIT=15
    WAITED=0
    LOG_OUTPUT=""
    while [ "$WAITED" -lt "$MAX_WAIT" ]; do
        sleep 1
        WAITED=$((WAITED + 1))
        LOG_OUTPUT=$(adb -s "$SERIAL" logcat -d -s mboard.startup 2>/dev/null || true)
        if echo "$LOG_OUTPUT" | grep -q "first suggestion rendered"; then
            break
        fi
    done

    echo "Logs:"
    echo "$LOG_OUTPUT" | grep "mboard.startup:" || echo "No mboard.startup logs found!"

    # Memory info
    MEMINFO=$(adb -s "$SERIAL" shell "dumpsys meminfo com.m57.mboard" 2>/dev/null || true)
    TOTAL_PSS=$(echo "$MEMINFO" | grep "TOTAL PSS:" | awk '{print $3}')
    JAVA_HEAP=$(echo "$MEMINFO" | grep "Java Heap:" | awk '{print $3}')

    echo "Memory:"
    echo "  TOTAL PSS: ${TOTAL_PSS:-unknown} KB"
    echo "  Java Heap: ${JAVA_HEAP:-unknown} KB"

    # Extract timings from logcat
    ONCREATE_RT=$(echo "$LOG_OUTPUT" | grep "service onCreate start:" | sed -n 's/.*elapsedRealtime=\([0-9]*\).*/\1/p' | head -n 1)
    FIRST_RT=$(echo "$LOG_OUTPUT" | grep "first suggestion rendered:" | sed -n 's/.*elapsedRealtime=\([0-9]*\).*/\1/p' | head -n 1)
    ENGINE_DUR=$(echo "$LOG_OUTPUT" | grep "engine ready:" | sed -n 's/.*duration=\([0-9]*\)ms.*/\1/p' | head -n 1)

    if [ -n "$ONCREATE_RT" ] && [ -n "$FIRST_RT" ]; then
        OC_TO_FIRST=$((FIRST_RT - ONCREATE_RT))
        echo "Phase timings:"
        echo "  service onCreate -> first suggestion: ${OC_TO_FIRST} ms"
        [ -n "$ENGINE_DUR" ] && echo "  engine load duration: ${ENGINE_DUR} ms"
        ON_CREATE_TO_FIRST_TIMES+=("$OC_TO_FIRST")
        [ -n "$ENGINE_DUR" ] && ENGINE_READY_TIMES+=("$ENGINE_DUR")
    fi

    [ -n "$TOTAL_PSS" ] && PSS_VALUES+=("$TOTAL_PSS")
    [ -n "$JAVA_HEAP" ] && HEAP_VALUES+=("$JAVA_HEAP")
    echo ""
done

# Function to compute median and max from an array of numbers
summary_stats() {
    local name="$1"
    shift
    local arr=("$@")
    if [ ${#arr[@]} -eq 0 ]; then
        echo "  $name: no data"
        return
    fi
    local sorted=($(printf '%s\n' "${arr[@]}" | sort -n))
    local count=${#sorted[@]}
    local max="${sorted[$((count - 1))]}"
    local mid=$((count / 2))
    local median="${sorted[$mid]}"
    echo "  $name: median = $median, max = $max (samples: ${sorted[*]})"
}

echo "=== SUMMARY ACROSS $ROUNDS ROUNDS ==="
summary_stats "service onCreate -> first suggestion (ms)" "${ON_CREATE_TO_FIRST_TIMES[@]}"
summary_stats "engine ready duration (ms)" "${ENGINE_READY_TIMES[@]}"
summary_stats "TOTAL PSS (KB)" "${PSS_VALUES[@]}"
summary_stats "Java Heap (KB)" "${HEAP_VALUES[@]}"
