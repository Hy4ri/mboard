#!/usr/bin/env bash
# Run from repo root, inside nix develop under the shared lock.
set -euo pipefail
for updates in 1500 6000; do
  name="u$updates"
  python3 -I bench/lm/prune_298.py --input bench/reports/298-levantine/half-assets/jordanian_next.tsv --output app/src/main/assets/jordanian_next.tsv --min-total 6 --min-follower 1 --width 8 --baseline-revision be17dc98692fbf8f1fafc277b9734c0b925cf799 --max-updates "$updates"
  ./gradlew :app:clean assembleDebug
  stat -c %s app/build/outputs/apk/debug/app-debug.apk > "bench/reports/298-levantine/$name-apk-bytes.txt"
  mkdir -p "bench/reports/298-levantine/$name-assets"
  cp -a app/src/main/assets/. "bench/reports/298-levantine/$name-assets/"
  cp -a app/build/generated/tableAssets/. "bench/reports/298-levantine/$name-assets/"
  cp -a app/build/generated/wordAssets/. "bench/reports/298-levantine/$name-assets/"
  (cd bench
   for dataset in jo_human_fresh jo_tatoeba_fresh levantine_sister_heldout jo_chat; do
     label="$dataset"
     args=()
     if test "$dataset" = jo_chat; then label=jo_chat_dialect; args+=(--typos data/jo_chat_typos.tsv); fi
     if test "$dataset" = jo_human_fresh; then args+=(--typos data/jo_human_fresh_typos.tsv); fi
     build/install/mboard-bench/bin/mboard-bench --lang ar --dialect --sentences "data/$dataset.txt" --label "$label" --out "results/crew298-$name" --assets "$PWD/reports/298-levantine/$name-assets" "${args[@]}"
   done)
  python3 -I bench/scripts/summary.py "bench/results/crew298-$name" bench/results/crew298-base > "bench/reports/298-levantine/$name-summary.txt"
done
