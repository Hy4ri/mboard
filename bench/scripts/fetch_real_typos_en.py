#!/usr/bin/env python3
"""
Fetch public English misspelling corpora and build a real-human typo dataset.
Stdlib only. Run with `python3 -I`.
"""

import os
import random
import re
import sys
import urllib.request
from collections import Counter

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATA_DIR = os.path.join(BASE_DIR, "data")
RAW_DIR = os.path.join(DATA_DIR, "raw", "misspellings")
OUT_TSV = os.path.join(DATA_DIR, "en_real_typos.tsv")
SOURCES_MD = os.path.join(DATA_DIR, "SOURCES.md")
WORDS_EN_TSV = os.path.join(os.path.dirname(BASE_DIR), "app", "src", "main", "assets", "words_en.tsv")

SOURCES = [
    ("aspell.dat", "https://www.dcs.bbk.ac.uk/~ROGER/aspell.dat"),
    ("wikipedia.dat", "https://www.dcs.bbk.ac.uk/~ROGER/wikipedia.dat"),
    ("missp.dat", "https://www.dcs.bbk.ac.uk/~ROGER/missp.dat"),
]

# QWERTY key positions (matching Languages.kt English layout)
EN_ROWS = [
    ("qwertyuiop", 0.0),
    ("asdfghjkl", 0.5),
    ("zxcvbnm", 1.5),
]

POSITIONS = {}
for r, (keys, offset) in enumerate(EN_ROWS):
    for i, char in enumerate(keys):
        POSITIONS[char] = (offset + i + 0.5, r + 0.5)


def are_adjacent(a: str, b: str) -> bool:
    pa = POSITIONS.get(a)
    pb = POSITIONS.get(b)
    if not pa or not pb:
        return False
    # adjacent key on QWERTY: abs(dx) <= 1.05 and abs(dy) <= 1.05
    return abs(pa[0] - pb[0]) <= 1.05 and abs(pa[1] - pb[1]) <= 1.05


def soundex(s: str) -> str:
    if not s:
        return ""
    s = s.upper()
    codes = {
        "B": "1", "F": "1", "P": "1", "V": "1",
        "C": "2", "G": "2", "J": "2", "K": "2", "Q": "2", "S": "2", "X": "2", "Z": "2",
        "D": "3", "T": "3",
        "L": "4",
        "M": "5", "N": "5",
        "R": "6",
    }
    first = s[0]
    tail = s[1:]
    encoded = [codes.get(c, "0") for c in tail]
    res = [first]
    prev = codes.get(first, "0")
    for code in encoded:
        if code != "0" and code != prev:
            res.append(code)
        prev = code
    return ("".join(res) + "000")[:4]


PHONETIC_PAIRS = {
    ("c", "k"), ("k", "c"), ("c", "s"), ("s", "c"),
    ("s", "z"), ("z", "s"), ("f", "v"), ("v", "f"),
    ("f", "ph"), ("ph", "f"), ("x", "cks"), ("x", "cs"),
    ("j", "g"), ("g", "j"),
}

VOWELS = set("aeiouy")


def classify_typo(typed: str, intended: str) -> str:
    """
    Classify typo into one of:
    adjacent | omission | insertion | transposition | doubled | phonetic | other
    """
    lt, li = len(typed), len(intended)

    # 1. Doubled / Insertion (typed length = intended length + 1)
    if lt == li + 1:
        for i in range(lt):
            if typed[:i] + typed[i + 1:] == intended:
                if (i > 0 and typed[i] == typed[i - 1]) or (i < lt - 1 and typed[i] == typed[i + 1]):
                    return "doubled"
                return "insertion"

    # 2. Omission (typed length = intended length - 1)
    if lt == li - 1:
        for i in range(li):
            if intended[:i] + intended[i + 1:] == typed:
                return "omission"

    # 3. Same length checks: Adjacent, Phonetic single-sub, Transposition
    if lt == li:
        diffs = [i for i in range(lt) if typed[i] != intended[i]]
        if len(diffs) == 1:
            i = diffs[0]
            ct, ci = typed[i], intended[i]
            if are_adjacent(ct, ci):
                return "adjacent"
            if (ct, ci) in PHONETIC_PAIRS or (ct in VOWELS and ci in VOWELS):
                return "phonetic"
        elif len(diffs) == 2:
            i, j = diffs
            if j == i + 1 and typed[i] == intended[j] and typed[j] == intended[i]:
                return "transposition"

    # 4. Soundex phonetic match
    if soundex(typed) == soundex(intended):
        return "phonetic"

    return "other"


def download_file(url: str, dest_path: str):
    if os.path.exists(dest_path) and os.path.getsize(dest_path) > 0:
        print(f"Already downloaded: {dest_path}")
        return
    print(f"Downloading {url} -> {dest_path}...")
    headers = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"}
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, timeout=30) as resp, open(dest_path, "wb") as f:
        while chunk := resp.read(64 * 1024):
            f.write(chunk)
    print(f"Downloaded: {dest_path} ({os.path.getsize(dest_path)} bytes)")


def parse_dat_file(file_path: str):
    pairs = []
    current_target = None
    with open(file_path, "r", encoding="utf-8", errors="replace") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            if line.startswith("$"):
                current_target = line[1:].strip().lower()
            elif current_target:
                pairs.append((line.lower(), current_target))
    return pairs


def append_sources(total_rows: int, kind_counts: dict):
    lines = [
        "",
        "## en_real_typos.tsv",
        "- Sources:",
        "  - Birkbeck Spelling Error Corpus (`missp.dat`): https://www.dcs.bbk.ac.uk/~ROGER/missp.dat",
        "  - Wikipedia Common Misspellings (`wikipedia.dat`): https://www.dcs.bbk.ac.uk/~ROGER/wikipedia.dat",
        "  - Aspell Testing Set (`aspell.dat`): https://www.dcs.bbk.ac.uk/~ROGER/aspell.dat",
        "- Licensing:",
        "  - Birkbeck corpus: CC BY-NC-SA 3.0 / Roger Mitton & Oxford Text Archive",
        "  - Wikipedia: Creative Commons Attribution-ShareAlike (CC BY-SA)",
        "  - Aspell: GNU Free Documentation License / GPL (Kevin Atkinson)",
        "- Filter and criteria:",
        "  - `intended` word must exist in keyboard dictionary `words_en.tsv`",
        "  - `typed` consists only of lowercase letters [a-z] and apostrophe ('), length >= 2",
        "  - `typed` != `intended` and `typed` NOT in `words_en.tsv` (real-word errors excluded)",
        "  - Deduplicated on `(typed, intended)` pairs",
        "  - Sampled up to 4000 rows deterministically with `random.Random(57)`",
        "- Header: `context\\ttyped\\tintended\\tkind` (context empty)",
        f"- Row count: {total_rows}",
        "- Breakdown by kind:",
    ]
    for kind, cnt in sorted(kind_counts.items(), key=lambda x: -x[1]):
        pct = (cnt / total_rows) * 100 if total_rows > 0 else 0
        lines.append(f"  - `{kind}`: {cnt} ({pct:.1f}%)")

    entry = "\n".join(lines) + "\n"
    mode = "a" if os.path.exists(SOURCES_MD) else "w"
    with open(SOURCES_MD, mode, encoding="utf-8") as f:
        f.write(entry)
    print("Appended en_real_typos.tsv entry to SOURCES.md")


def main():
    os.makedirs(RAW_DIR, exist_ok=True)
    os.makedirs(DATA_DIR, exist_ok=True)

    # 1. Load dictionary
    if not os.path.exists(WORDS_EN_TSV):
        print(f"Error: words_en.tsv not found at {WORDS_EN_TSV}", file=sys.stderr)
        sys.exit(1)

    print(f"Loading word list from {WORDS_EN_TSV}...")
    valid_words = set()
    with open(WORDS_EN_TSV, "r", encoding="utf-8") as f:
        for line in f:
            parts = line.strip().split("\t")
            if parts:
                valid_words.add(parts[0].lower())
    print(f"Loaded {len(valid_words)} valid words.")

    # 2. Download and parse raw corpora
    raw_pairs = []
    sources_used = []
    for filename, url in SOURCES:
        dest_path = os.path.join(RAW_DIR, filename)
        download_file(url, dest_path)
        pairs = parse_dat_file(dest_path)
        print(f"Parsed {len(pairs)} error pairs from {filename}")
        raw_pairs.extend(pairs)
        sources_used.append(url)

    # 3. Filter pairs
    pattern = re.compile(r"^[a-z']{2,}$")
    filtered_pairs = []
    for typed, intended in raw_pairs:
        if intended not in valid_words:
            continue
        if not pattern.match(typed):
            continue
        if typed == intended:
            continue
        if typed in valid_words:
            # Exclude real-word errors
            continue
        filtered_pairs.append((typed, intended))

    print(f"Filtered pairs (pre-dedup): {len(filtered_pairs)}")

    # 4. Deduplicate (typed, intended)
    unique_pairs = list(dict.fromkeys(filtered_pairs))
    print(f"Unique pairs: {len(unique_pairs)}")

    # 5. Deterministic sample up to 4000
    TARGET_ROWS = 4000
    if len(unique_pairs) > TARGET_ROWS:
        rng = random.Random(57)
        sampled_pairs = rng.sample(unique_pairs, TARGET_ROWS)
        # Sort or shuffle deterministically
        rng.shuffle(sampled_pairs)
    else:
        sampled_pairs = unique_pairs

    print(f"Final sampled pairs: {len(sampled_pairs)}")

    # 6. Classify kind and build records
    records = []
    kind_counts = Counter()
    for typed, intended in sampled_pairs:
        kind = classify_typo(typed, intended)
        kind_counts[kind] += 1
        records.append(("", typed, intended, kind))

    # 7. Write en_real_typos.tsv
    print(f"Writing TSV to {OUT_TSV}...")
    with open(OUT_TSV, "w", encoding="utf-8", newline="\n") as f:
        f.write("context\ttyped\tintended\tkind\n")
        for ctx, typed, intended, kind in records:
            f.write(f"{ctx}\t{typed}\t{intended}\t{kind}\n")

    print(f"Wrote {len(records)} rows to {OUT_TSV}")
    print("Kind breakdown:")
    for k, v in kind_counts.most_common():
        print(f"  {k}: {v}")

    # 8. Append to SOURCES.md
    append_sources(len(records), dict(kind_counts))

    return {
        "file": OUT_TSV,
        "rows": len(records),
        "kind_counts": dict(kind_counts),
        "sources_used": sources_used,
        "problems": "",
    }


if __name__ == "__main__":
    main()
