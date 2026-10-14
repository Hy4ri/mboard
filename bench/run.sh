#!/usr/bin/env bash
# Builds the bench and replays every data set; results land in bench/results/<label>/.
# Usage: bench/run.sh [label] [--limit N]
set -euo pipefail
cd "$(dirname "$0")"
label="${1:-baseline}"
shift || true
out="results/$label"
mkdir -p "$out"
../gradlew -p . installDist -q --console=plain
bin=build/install/mboard-bench/bin/mboard-bench

run() {
  # Large Android tables have a real heap cost. Bound concurrency, and propagate every JVM failure.
  "$bin" --out "$out" "$@" > /dev/null &
  pids+=($!)
  if [ "${#pids[@]}" -ge 2 ]; then
    for p in "${pids[@]}"; do wait "$p" || exit "$?"; done
    pids=()
  fi
}
have() { [ -s "data/$1" ]; }

pids=()
for set in en_subs en_tatoeba en_chat en_fresh; do
  if have "$set.txt"; then
    args=(--lang en --sentences "data/$set.txt" --label "$set")
    have "${set}_typos.tsv" && args+=(--typos "data/${set}_typos.tsv")
    run "${args[@]}" "$@"
  fi
done
have en_real_typos.tsv && { run --lang en --typos data/en_real_typos.tsv --label en_real_typos "$@"; }
for set in ar_subs ar_tatoeba ar_fresh; do
  if have "$set.txt"; then
    args=(--lang ar --sentences "data/$set.txt" --label "$set")
    have "${set}_typos.tsv" && args+=(--typos "data/${set}_typos.tsv")
    run "${args[@]}" "$@"
  fi
done
have ar_real_typos.tsv && { run --lang ar --typos data/ar_real_typos.tsv --label ar_real_typos "$@"; }
if have jo_chat.txt; then
  run --lang ar --dialect --sentences data/jo_chat.txt --typos data/jo_chat_typos.tsv --label jo_chat_dialect "$@"
  run --lang ar --sentences data/jo_chat.txt --label jo_chat_msa "$@"
  run --lang ar --dialect --learning --sentences data/jo_chat.txt --label jo_chat_learning "$@"
fi
if have jo_human_fresh.txt; then
  args=(--lang ar --dialect --sentences data/jo_human_fresh.txt --label jo_human_fresh)
  have jo_human_fresh_typos.tsv && args+=(--typos data/jo_human_fresh_typos.tsv)
  run "${args[@]}" "$@"
fi
have en_chat.txt && { run --lang en --learning --sentences data/en_chat.txt --label en_chat_learning "$@"; }
for p in "${pids[@]}"; do wait "$p" || exit "$?"; done
python3 -I scripts/summary.py "$out"
