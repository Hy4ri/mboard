#!/usr/bin/env bash
# Run from repository root inside nix develop while holding the shared bench lock.
set -euo pipefail
for spec in p8:8:2:6 p12:12:3:4 p18:18:4:4; do
  IFS=: read -r name total follower width <<< "$spec"
  python3 -I bench/lm/prune_298.py --input bench/reports/298-levantine/half-assets/jordanian_next.tsv --output app/src/main/assets/jordanian_next.tsv --min-total "$total" --min-follower "$follower" --width "$width"
  ./gradlew :app:compileTables :app:compileWords assembleDebug
  stat -c %s app/build/outputs/apk/debug/app-debug.apk > "bench/reports/298-levantine/$name-apk-bytes.txt"
  mkdir -p "bench/reports/298-levantine/$name-assets"
  cp -a app/src/main/assets/. "bench/reports/298-levantine/$name-assets/"
  cp -a app/build/generated/tableAssets/. "bench/reports/298-levantine/$name-assets/"
  cp -a app/build/generated/wordAssets/. "bench/reports/298-levantine/$name-assets/"
  (cd bench; bash run.sh "crew298-$name" --assets "$PWD/reports/298-levantine/$name-assets")
  python3 -I bench/reports/298-levantine/compare.py bench/results/crew298-base "bench/results/crew298-$name" > "bench/reports/298-levantine/$name-comparison.json"
  python3 -I bench/scripts/summary.py "bench/results/crew298-$name" bench/results/crew298-base > "bench/reports/298-levantine/$name-summary.txt"
done
