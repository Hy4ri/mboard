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

import argparse
import glob
import hashlib
import os
import re
import sys
import unicodedata
import urllib.request

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

# Normalization regexes matching mboard conventions
RE_HARAKAT = re.compile(r"[\u064B-\u065F\u0670\u0640]")  # Harakat & tatweel
RE_BIDI = re.compile(r"[\u200e\u200f\u202a-\u202e\ufeff]")
RE_WHITESPACE = re.compile(r"\s+")
RE_URL = re.compile(r"https?://\S+|www\.\S+", re.IGNORECASE)
RE_MENTION = re.compile(r"@\w+")
RE_LATIN = re.compile(r"[a-zA-Z]")
RE_HTML = re.compile(r"<[^>]+>")
RE_EMOJI = re.compile(r"[\U00010000-\U0010ffff]|[\u2600-\u26FF]|[\u2700-\u27BF]")
RE_REPEAT = re.compile(r"(.)\1{2,}")


def normalize_for_comparison(text: str) -> str:
    """Normalize text for strict duplicate/overlap comparisons."""
    t = text.strip()
    t = unicodedata.normalize("NFKC", t)
    t = RE_HARAKAT.sub("", t)
    t = t.lower()
    t = RE_BIDI.sub("", t)
    t = RE_WHITESPACE.sub(" ", t)
    return t


def clean_line(line: str) -> str:
    """Clean a single line: strip marks, URLs, mentions, collapse repeats, require >= 2 words."""
    t = unicodedata.normalize("NFKC", line.strip())
    t = RE_URL.sub("", t)
    t = RE_MENTION.sub("", t)
    t = RE_HTML.sub("", t)
    t = RE_EMOJI.sub("", t)
    t = RE_HARAKAT.sub("", t)
    t = RE_BIDI.sub("", t)
    t = RE_REPEAT.sub(r"\1\1", t)  # Collapse >= 3 repetitions to 2
    t = RE_WHITESPACE.sub(" ", t).strip()
    return t


def is_valid_arabic_sentence(cleaned_line: str) -> bool:
    """Validate line: >= 2 words and <= 40 words, Arabic characters, no Latin letters."""
    if not cleaned_line:
        return False
    if RE_LATIN.search(cleaned_line):
        return False
    words = cleaned_line.split()
    if len(words) < 2 or len(words) > 40:
        return False
    # Ensure there are genuine Arabic letters
    if not any("\u0621" <= c <= "\u064A" for c in cleaned_line):
        return False
    return True


def load_all_benchmark_exclusions(repo_root: str) -> set[str]:
    """Load normalized sentences from all bench/data/*.txt evaluation files."""
    data_dir = os.path.join(repo_root, "bench", "data")
    eval_files = sorted(glob.glob(os.path.join(data_dir, "*.txt")))
    norm_set = set()
    for fpath in eval_files:
        with open(fpath, "r", encoding="utf-8", errors="replace") as f:
            for line in f:
                s = line.strip()
                if s:
                    norm_set.add(normalize_for_comparison(s))
    return norm_set


def fetch_file(url: str, dest_path: str) -> None:
    """Download a file if it does not already exist."""
    if os.path.exists(dest_path) and os.path.getsize(dest_path) > 0:
        print(f"  [cached] {os.path.basename(dest_path)}")
        return
    print(f"  [downloading] {url} -> {dest_path}")
    req = urllib.request.Request(url, headers={"User-Agent": "mboard-dialect-fetch/1.0"})
    with urllib.request.urlopen(req) as resp, open(dest_path, "wb") as out:
        out.write(resp.read())


def assign_group_id(index: int, block_size: int = 50) -> int:
    """Group sequential lines into contiguous blocks to preserve conversational context."""
    return index // block_size


def is_block_heldout(group_id: int, salt: str = "mboard_shami_sister_v1", heldout_ratio: float = 0.20) -> bool:
    """
    Deterministically hash block id to train or heldout.
    heldout_ratio = 0.20 means ~20% held-out, ~80% train.
    """
    key = f"{salt}_{group_id}".encode("utf-8")
    h = int(hashlib.sha256(key).hexdigest()[:8], 16)
    threshold = int(heldout_ratio * 0xFFFFFFFF)
    return h < threshold


def process_sister_corpus(
    block_size: int = 50,
    heldout_ratio: float = 0.20,
    out_train_path: str = None,
    out_heldout_path: str = None,
) -> None:
    os.makedirs(RAW_DIR, exist_ok=True)

    if out_train_path is None:
        out_train_path = os.path.join(BENCH_LM_DIR, "jo_sister_train.txt")
    if out_heldout_path is None:
        out_heldout_path = os.path.join(BENCH_DATA_DIR, "levantine_sister_heldout.txt")

    print("Step 1: Downloading raw sister partitions from GU-CLASP/shami-corpus...")
    raw_paths = []
    for fname in SISTER_FILES:
        dest = os.path.join(RAW_DIR, f"shami_{fname}")
        fetch_file(BASE_URL + fname, dest)
        raw_paths.append((fname, dest))

    print("\nStep 2: Loading benchmark exclusion set...")
    eval_exclusions = load_all_benchmark_exclusions(REPO_ROOT)
    print(f"  Loaded {len(eval_exclusions)} normalized sentences to protect from leakage.")

    print("\nStep 3: Cleaning, deduplicating, and contiguous block-splitting...")
    seen_normalized = set()
    total_raw_lines = 0
    kept_train_lines = []
    kept_heldout_lines = []

    global_line_index = 0

    for fname, path in raw_paths:
        file_lines = 0
        file_kept_train = 0
        file_kept_heldout = 0
        with open(path, "r", encoding="utf-8", errors="replace") as f:
            for line in f:
                total_raw_lines += 1
                file_lines += 1
                cleaned = clean_line(line)
                if not is_valid_arabic_sentence(cleaned):
                    continue

                norm = normalize_for_comparison(cleaned)
                if norm in seen_normalized or norm in eval_exclusions:
                    continue
                seen_normalized.add(norm)

                group_id = assign_group_id(global_line_index, block_size=block_size)
                global_line_index += 1

                if is_block_heldout(group_id, heldout_ratio=heldout_ratio):
                    kept_heldout_lines.append(cleaned)
                    file_kept_heldout += 1
                else:
                    kept_train_lines.append(cleaned)
                    file_kept_train += 1

        print(
            f"  {fname}: {file_lines} raw lines -> {file_kept_train} train, {file_kept_heldout} heldout"
        )

    print(f"\nStep 4: Writing output partitions (strictly outside app assets):")
    print(f"  Train: {out_train_path} ({len(kept_train_lines)} lines)")
    with open(out_train_path, "w", encoding="utf-8") as f:
        for l in kept_train_lines:
            f.write(l + "\n")

    print(f"  Held-out: {out_heldout_path} ({len(kept_heldout_lines)} lines)")
    with open(out_heldout_path, "w", encoding="utf-8") as f:
        for l in kept_heldout_lines:
            f.write(l + "\n")

    print(f"\nCompleted successfully.")
    print(f"Total raw lines: {total_raw_lines}")
    print(f"Unique clean lines kept: {len(kept_train_lines) + len(kept_heldout_lines)}")
    print(f"  Train split (~{(1.0 - heldout_ratio) * 100:.0f}%): {len(kept_train_lines)} lines")
    print(f"  Held-out split (~{heldout_ratio * 100:.0f}%): {len(kept_heldout_lines)} lines")


def main() -> None:
    parser = argparse.ArgumentParser(description="Fetch and block-split Shami sister partitions.")
    parser.add_argument("--block-size", type=int, default=50, help="Contiguous lines per block (default: 50)")
    parser.add_argument("--heldout-ratio", type=float, default=0.20, help="Fraction for held-out split (default: 0.20)")
    parser.add_argument("--out-train", type=str, default=None, help="Custom output train path")
    parser.add_argument("--out-heldout", type=str, default=None, help="Custom output heldout path")
    args = parser.parse_args()

    process_sister_corpus(
        block_size=args.block_size,
        heldout_ratio=args.heldout_ratio,
        out_train_path=args.out_train,
        out_heldout_path=args.out_heldout,
    )


if __name__ == "__main__":
    main()
