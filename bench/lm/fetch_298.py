#!/usr/bin/env python3
"""
Fetch, clean, and block-split the Shami Corpus Levantine sister partitions
(Syrian, Palestinian, Lebanese) for mboard dialect evaluation and training research (Card #298).

Sources:
    GU-CLASP/shami-corpus (GitHub)
    - syrian.txt
    - Palestinian.txt
    - Lebanees.txt

License:
    Apache License 2.0 (GU-CLASP/shami-corpus)

Safety rules:
    - Never writes into app/src/main/assets/ or affects production assets.
    - Contiguous ~50-line hash-block splitting (SHA-256 group assignment) to prevent
      conversational leakage between train and held-out evaluation sets.
    - Strict line normalization and exclusion against all existing benchmark sets in bench/data/*.txt.
    - Downloaded raw files are saved to bench/lm/raw/ (gitignored).
"""

import bz2
import json
import glob
import importlib.util
import hashlib
import os
import re
import unicodedata
import urllib.request

# Use exactly the existing Jordanian cleaning policy after removing speaker metadata.
_spec = importlib.util.spec_from_file_location("fetch_shami", os.path.join(os.path.dirname(__file__), "fetch_shami.py"))
_cleaner = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_cleaner)
clean_jordanian = _cleaner.clean_line

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
RAW_DIR = os.path.join(REPO_ROOT, "bench", "lm", "raw")
BENCH_DATA_DIR = os.path.join(REPO_ROOT, "bench", "data")
BENCH_LM_DIR = os.path.join(REPO_ROOT, "bench", "lm")

BASE_URL = "https://raw.githubusercontent.com/GU-CLASP/shami-corpus/master/Data/"
SISTER_FILES = [
    "syrian.txt",
    "Palestinian.txt",
    "Lebanees.txt",
]

RE_HARAKAT = re.compile(r"[\u064B-\u065F\u0670\u0640]")
RE_BIDI = re.compile(r"[\u200e\u200f\u202a-\u202e\ufeff]")
RE_LATIN = re.compile(r"[a-zA-Z]")


def normalize_for_comparison(text):
    text = unicodedata.normalize("NFKC", text.strip()).lower()
    return " ".join(RE_BIDI.sub("", RE_HARAKAT.sub("", text)).split())


def fetch_file(url, path):
    if os.path.isfile(path) and os.path.getsize(path):
        return
    request = urllib.request.Request(url, headers={"User-Agent": "mboard-dialect-fetch/1.0"})
    with urllib.request.urlopen(request, timeout=120) as response:
        data = response.read()
    with open(path, "wb") as output:
        output.write(data)


# The files also contain unlabelled narrative/forum rows. Never discard their first word.
# Only the Syrian fiction partition has a recurring name-as-speaker convention.
# Ambiguous names (عمر، هيا، غزل، شهد) require an unambiguous dialogue opener.
SPEAKERS = set("يارا ساره ليان رنا منال رهف فارس احمد نورس اسيل رفيف منار رنيم دلال جلال طارق ياسمين قصي هاله هبه مصعب ميسم رائد".split())
AMBIGUOUS_SPEAKERS = set("عمر هيا غزل شهد حلا".split())
DIALOGUE_OPENERS = set("اها اي إي لا شو لك طيب والله يعني اكيد خلص".split())


def strip_speaker(line, source):
    parts = line.strip().split(maxsplit=1)
    if source != "syrian.txt" or len(parts) < 2:
        return line
    first, rest = parts
    if first in SPEAKERS or (first in AMBIGUOUS_SPEAKERS and rest.split()[0] in DIALOGUE_OPENERS):
        return rest
    return line


def main():
    os.makedirs(RAW_DIR, exist_ok=True)
    exclusions = set()
    for path in sorted(glob.glob(os.path.join(BENCH_DATA_DIR, "*.txt"))):
        if os.path.basename(path) in {"levantine_sister_heldout.txt", "jo_tatoeba_fresh.txt"}:
            continue
        exclusions.update(normalize_for_comparison(x) for x in open(path, encoding="utf-8") if x.strip())
    # Fresh evaluation must also be disjoint from all pre-existing dialect training.
    training_exclusions = set()
    for path in [os.path.join(BENCH_LM_DIR, "jo_shami_train.txt"), *sorted(glob.glob(os.path.join(BENCH_LM_DIR, "jo_train*.txt")))]:
        training_exclusions.update(normalize_for_comparison(x) for x in open(path, encoding="utf-8") if x.strip())
    exclusions.update(training_exclusions)
    manifest = {"salt": "mboard_shami_sister_v1", "block_size": 50, "raw": [], "groups": []}
    tatoeba, ids = [], []
    seen = set(exclusions)
    for lang in ("ajp", "apc"):
        url = f"https://downloads.tatoeba.org/exports/per_language/{lang}/{lang}_sentences.tsv.bz2"
        path = os.path.join(RAW_DIR, f"{lang}_sentences.tsv.bz2")
        fetch_file(url, path)
        raw = open(path, "rb").read()
        manifest["raw"].append({"url": url, "sha256": hashlib.sha256(raw).hexdigest(), "license": "CC BY 2.0 FR"})
        for row in bz2.decompress(raw).decode("utf-8").splitlines():
            sid, code, text = row.split("\t", 2)
            # Preserve sentence spelling/punctuation; exclude Latin-script and duplicates.
            norm = normalize_for_comparison(text)
            if RE_LATIN.search(text) or not any("\u0621" <= c <= "\u064a" for c in text) or norm in seen:
                continue
            seen.add(norm)
            tatoeba.append(text)
            ids.append({"id": sid, "lang": code, "url": f"https://tatoeba.org/en/sentences/show/{sid}"})
    train, heldout = [], []
    # Held-out-first dedup prevents a repeated message in a train block from leaking.
    partitions = {"train": [], "heldout": []}
    for fname in SISTER_FILES:
        url = BASE_URL + fname
        path = os.path.join(RAW_DIR, f"shami_{fname}")
        fetch_file(url, path)
        raw = open(path, "rb").read()
        manifest["raw"].append({"url": url, "sha256": hashlib.sha256(raw).hexdigest(), "license": "Apache-2.0"})
        lines = raw.decode("utf-8-sig").splitlines()
        for start in range(0, len(lines), 50):
            gid = start // 50
            key = f"mboard_shami_sister_v1:{fname}:{gid}"
            hold = int(hashlib.sha256(key.encode()).hexdigest(), 16) % 5 == 0
            split = "heldout" if hold else "train"
            manifest["groups"].append({"source": fname, "start": start, "end": min(start+50,len(lines)), "split": split})
            for line in lines[start:start+50]:
                cleaned = clean_jordanian(strip_speaker(line, fname))
                if cleaned:
                    partitions[split].append(cleaned)
    for split, target in (("heldout", heldout), ("train", train)):
        for line in partitions[split]:
            norm = normalize_for_comparison(line)
            if norm not in seen:
                seen.add(norm)
                target.append(line)
    # Sample across heldout blocks by a fixed content hash, never put unused heldout rows in training.
    sample = sorted(heldout, key=lambda x: hashlib.sha256(("sample298:"+x).encode()).digest())[:1500]
    outputs = {"bench/lm/jo_sister_train.txt": train, "bench/data/levantine_sister_heldout.txt": sample,
               "bench/data/jo_tatoeba_fresh.txt": tatoeba}
    manifest["outputs"] = {}
    for relative, lines in outputs.items():
        data = ("\n".join(lines)+"\n").encode()
        open(os.path.join(REPO_ROOT,relative), "wb").write(data)
        manifest["outputs"][relative] = {"lines": len(lines), "sha256": hashlib.sha256(data).hexdigest()}
    manifest["heldout_pool_lines"] = len(heldout)
    manifest["tatoeba_sentences"] = ids
    open(os.path.join(BENCH_LM_DIR,"split_298_manifest.json"),"w").write(json.dumps(manifest,ensure_ascii=False,indent=2)+"\n")
    note = "# Card 298 evaluation sources\n\nTatoeba.org contributors, CC BY 2.0 FR (https://creativecommons.org/licenses/by/2.0/fr/). "
    note += "Arabic-script ajp/apc sentences, spelling retained. Sentence IDs and links, raw URLs/hashes: `../lm/split_298_manifest.json`. Eval only.\n\n"
    note += "Shami: Abu Kwaik et al. (2018), GU-CLASP/shami-corpus, Apache-2.0. Recognized Syrian speaker prefixes removed (bounded names; ambiguous names require a dialogue opener), unlabelled first words preserved; existing fetch_shami.py cleaning. "
    note += "50 raw-line groups, fixed source-specific hash salt, 80/20 assignment before cleaning; 1500-row fixed-hash heldout sample. All unused heldout rows remain excluded.\n"
    open(os.path.join(BENCH_DATA_DIR,"jo_tatoeba_fresh_sources.md"),"w").write(note)
    print(json.dumps(manifest["outputs"],indent=2))

if __name__ == "__main__":
    main()
