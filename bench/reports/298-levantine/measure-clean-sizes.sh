#!/usr/bin/env bash
# Run from repo root inside nix develop while holding bench.lock.
set -euo pipefail
for name in base half p8 p12 p18; do
  cp "bench/reports/298-levantine/$name-assets/jordanian_next.tsv" app/src/main/assets/jordanian_next.tsv
  ./gradlew :app:clean assembleDebug
  python3 - "$name" <<'PY'
import hashlib,sys
from pathlib import Path
from zipfile import ZipFile
name=sys.argv[1]; root=Path('bench/reports/298-levantine'); p=Path('app/build/outputs/apk/debug/app-debug.apk')
data=p.read_bytes();blob=ZipFile(p).read('assets/jordanian_next.tsv.bin')
assert blob == (root/f'{name}-assets/jordanian_next.tsv.bin').read_bytes()
(root/f'{name}-clean-apk-bytes.txt').write_text(str(len(data))+'\n')
print(name,len(data),hashlib.sha256(data).hexdigest(),flush=True)
PY
done
