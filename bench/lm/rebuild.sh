#!/usr/bin/env bash
# Rebuilds every n-gram table from the training sources. Held-out test windows are skipped exactly.
set -euo pipefail
cd "$(dirname "$0")"
OPUS=https://object.pouta.csc.fi/OPUS-OpenSubtitles/v2018/mono
mkdir -p out_ar out_en out_jo
python3 -I prepare_shami_holdout.py
python3 -I build_ngrams.py --lang jo --out-dir out_jo --min-next-total 3 --min-context-total 3 \
  --extra-text jo_shami_train.txt:3 jo_train_1.txt jo_train_2.txt jo_train_3.txt jo_train_4a.txt jo_train_4b.txt jo_train_4c.txt > out_jo/build.log 2>&1
# Serialize the large builds: Arabic alone peaks around 5 GB RAM.
python3 -I build_ngrams.py --lang ar --url "$OPUS/ar.txt.gz" --ranges 0:125000000,145000000:400000000 --out-dir out_ar > out_ar/build.log 2>&1
python3 -I build_ngrams.py --lang en --url "$OPUS/en.txt.gz" --ranges 0:125000000,150000000:325000000 --out-dir out_en > out_en/build.log 2>&1
