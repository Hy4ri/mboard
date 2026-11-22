#!/usr/bin/env bash
# Builds the bench and replays every data set; results land in bench/results/<label>/.
# Usage: bench/run.sh [label] [--limit N]
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"

has_java21() {
  command -v java > /dev/null 2>&1 || return 1
  local out
  out="$(java -version 2>&1 || true)"
  [[ "$out" =~ version\ \"([0-9]+) ]] && [ "${BASH_REMATCH[1]}" -ge 21 ]
}

# The JDK comes from the dev shell; enter it once if the caller is not already on Java 21+.
if ! has_java21; then
  [ -z "${MBOARD_IN_NIX:-}" ] || { echo "run.sh: Java >= 21 not found inside nix develop either" >&2; exit 1; }
  command -v nix > /dev/null 2>&1 || { echo "run.sh: requires Java >= 21 on PATH or 'nix' to enter nix develop" >&2; exit 1; }
  exec 3>&1
  exec env MBOARD_IN_NIX=1 nix develop "$here/.." -c bash -c 'exec bash "$0" "$@" 1>&3 3>&-' "$here/run.sh" "$@" 1>&2
fi

cd "$here"
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
have en_space_typos.tsv && { run --lang en --typos data/en_space_typos.tsv --label en_space_typos "$@"; }
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
for set in jo_tatoeba_fresh levantine_sister_heldout; do
  have "$set.txt" && run --lang ar --dialect --sentences "data/$set.txt" --label "$set" "$@"
done
if have jo_human_fresh.txt; then
  args=(--lang ar --dialect --sentences data/jo_human_fresh.txt --label jo_human_fresh)
  have jo_human_fresh_typos.tsv && args+=(--typos data/jo_human_fresh_typos.tsv)
  run "${args[@]}" "$@"
fi
have en_chat.txt && { run --lang en --learning --sentences data/en_chat.txt --label en_chat_learning "$@"; }
for p in "${pids[@]}"; do wait "$p" || exit "$?"; done
python3 -I scripts/summary.py "$out"
