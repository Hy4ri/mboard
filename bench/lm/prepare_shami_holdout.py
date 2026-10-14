#!/usr/bin/env python3
"""
Deterministic grouped Shami training/human-validation split generator.

Splits bench/lm/jo_shami.txt into:
- bench/lm/jo_shami_train.txt (training rows from non-heldout groups, minus evaluation overlaps)
- bench/data/jo_human_fresh.txt (held-out candidate validation rows, filtered and deduplicated)
- bench/lm/shami_split_manifest.json (reproducible audit manifest)
- bench/data/jo_human_fresh_sources.md (provenance and attribution notes)

Follows the contract defined in /home/m57/.hermes/cache/scratch/mboard-shami-holdout-contract.md.
Stdlib only. Deterministic and byte-idempotent.
"""

import argparse
import glob
import hashlib
import json
import os
import re
import sys
import unicodedata

# Default paths relative to repo root
REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DEFAULT_SOURCE = os.path.join(REPO_ROOT, "bench", "lm", "jo_shami.txt")
DEFAULT_TRAIN_OUT = os.path.join(REPO_ROOT, "bench", "lm", "jo_shami_train.txt")
DEFAULT_HELDOUT_OUT = os.path.join(REPO_ROOT, "bench", "data", "jo_human_fresh.txt")
DEFAULT_SOURCES_DOC = os.path.join(REPO_ROOT, "bench", "data", "jo_human_fresh_sources.md")
DEFAULT_MANIFEST = os.path.join(REPO_ROOT, "bench", "lm", "shami_split_manifest.json")

# Normalization regexes (matching repository conventions)
RE_HARAKAT = re.compile(r"[\u064B-\u065F\u0670\u0640]")  # harakat and tatweel
RE_BIDI = re.compile(r"[\u200e\u200f\u202a-\u202e\ufeff]")
RE_WHITESPACE = re.compile(r"\s+")

# Filtering regexes for validation hygiene
RE_URL = re.compile(r"https?://\S+|www\.\S+", re.IGNORECASE)
RE_LATIN = re.compile(r"[a-zA-Z]")
RE_HTML = re.compile(r"<[^>]+>")
RE_EMOJI = re.compile(r"[\U00010000-\U0010ffff]|[\u2600-\u26FF]|[\u2700-\u27BF]")
RE_DIGITS_AND_PUNCT = re.compile(r"[\d\s\u0660-\u0669\u06F0-\u06F9\.,!?;:\-_\(\)\[\]\"'/\\]+")


def normalize_text(text: str) -> str:
    """Normalize text for strict duplicate/overlap comparisons."""
    t = text.strip()
    t = unicodedata.normalize("NFKC", t)
    t = RE_HARAKAT.sub("", t)
    t = t.lower()
    t = RE_BIDI.sub("", t)
    t = RE_WHITESPACE.sub(" ", t)
    return t


def compute_sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def compute_file_sha256(filepath: str) -> str:
    h = hashlib.sha256()
    with open(filepath, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()


def load_old_evaluation_exclusions(repo_root: str) -> tuple[set[str], list[str]]:
    """
    Load normalized lines from old bench/data/*.txt files, excluding new files:
    - jo_human_fresh.txt (new target)
    - en_fresh.txt, ar_fresh.txt (future fresh sets owned by parallel writer)
    """
    data_dir = os.path.join(repo_root, "bench", "data")
    txt_files = sorted(glob.glob(os.path.join(data_dir, "*.txt")))
    excluded_names = {"jo_human_fresh.txt", "en_fresh.txt", "ar_fresh.txt"}
    
    considered_files = []
    norm_eval_set = set()
    for fpath in txt_files:
        fname = os.path.basename(fpath)
        if fname in excluded_names:
            continue
        considered_files.append(os.path.relpath(fpath, repo_root))
        with open(fpath, "r", encoding="utf-8", errors="replace") as f:
            for line in f:
                line_str = line.strip()
                if line_str:
                    norm_eval_set.add(normalize_text(line_str))
                    
    return norm_eval_set, considered_files


def is_valid_heldout_candidate(line: str) -> tuple[bool, str]:
    """
    Verify 2-40 words, no Latin, no URLs, no markup, no emoji, not number-only.
    """
    words = line.split()
    if len(words) < 2 or len(words) > 40:
        return False, f"word_count_out_of_range_{len(words)}"
    if RE_URL.search(line):
        return False, "url"
    if RE_LATIN.search(line):
        return False, "latin"
    if RE_HTML.search(line):
        return False, "html_markup"
    if RE_EMOJI.search(line):
        return False, "emoji"
    
    # Check if number-only / punctuation-only
    non_num = RE_DIGITS_AND_PUNCT.sub("", line)
    if not non_num:
        return False, "number_or_punct_only"
        
    return True, "valid"


def build_provenance_doc_content() -> str:
    return """# Shami Human Conversational Validation Provenance & Attribution

## Summary
- Target validation file: `bench/data/jo_human_fresh.txt`
- Split training file: `bench/lm/jo_shami_train.txt`
- Split manifest: `bench/lm/shami_split_manifest.json`
- Generation script: `bench/lm/prepare_shami_holdout.py`
- Upstream source: GU-CLASP/shami-corpus `Data/jordinian.txt` (via cleaned `bench/lm/jo_shami.txt`)
- Source license: Apache License 2.0 (GU-CLASP/shami-corpus)
- Citation:
  Kathrein Abu Kwaik, Motaz Saad, Stergios Chatzikyriakidis, and Simon Dobnik. 2018.
  "Shami: A Corpus of Levantine Arabic Dialects."
  In Proceedings of the Eleventh International Conference on Language Resources and Evaluation (LREC 2018), Miyazaki, Japan.
  European Language Resources Association (ELRA). https://aclanthology.org/L18-1576.pdf

## Group Partitioning Methodology
- Contiguous block grouping: The original cleaned `bench/lm/jo_shami.txt` (6,964 lines) was segmented into contiguous blocks of 50 original cleaned lines.
  *Note*: This contiguous grouping approximates conversational thread / discourse locality; these blocks are not guaranteed to be exact document boundaries.
- Deterministic assignment: Each block was assigned to held-out validation when:
  `int(sha256(f"{seed}:{group_text}".encode("utf-8")).hexdigest(), 16) % 5 == 0` (seed=57485).
- Block integrity: All groups assigned to held-out are strictly excluded from the training split `jo_shami_train.txt`. Even lines within held-out groups that are filtered out of the validation file (e.g. >40 words) remain excluded from training. Training groups are never used to replenish validation counts.

## Filtering & Disjointness Criteria
1. Train set (`bench/lm/jo_shami_train.txt`):
   - Contains all lines from non-heldout groups.
   - Strictly excludes any normalized overlaps against existing evaluation sets (`bench/data/*.txt` excluding fresh sets).
2. Held-out validation set (`bench/data/jo_human_fresh.txt`):
   - Contains candidate lines from held-out groups only.
   - Filtered to natural sentence lengths: 2 to 40 words.
   - Content hygiene: Verified free of Latin characters, URLs, HTML markup, emoji, and numeric/punctuation-only content.
   - Strictly excludes exact or normalized matches against old evaluation benchmarks in `bench/data/*.txt`.
   - Deduplicated on normalized form, preserving original order.
   - Disjointness from training set: Verified 0 verbatim and 0 normalized overlaps with `jo_shami_train.txt`.

## Critical Limitations & Non-Claims
1. **Prior Exposure in Installed Model**: The full `bench/lm/jo_shami.txt` corpus was ALREADY used to train the previous installed model assets. Therefore, evaluating the old installed model against `jo_human_fresh.txt` does **NOT** represent an unseen benchmark. Future model comparisons must train both baseline control and candidate models using `jo_shami_train.txt` (or explicitly note prior exposure).
2. **Lexical vs Continual Evaluation**: Existing general word lexicons and dictionaries may already contain vocabulary words present in this validation set. This benchmark evaluates held-out conversational message continuations and next-word prediction quality, not out-of-vocabulary generalization.
3. **Approximated Thread Independence**: Contiguous blocks of 50 lines only approximate conversation-thread independence, as explicit metadata or conversation IDs are not present in the upstream flat text file.
4. **Sealed Benchmark**: Benchmark harnesses and developers must evaluate against this file without inspecting per-sentence engine prediction failures for ad-hoc fine-tuning.
"""


def main():
    parser = argparse.ArgumentParser(description="Deterministic grouped Shami split")
    parser.add_argument("--source", default=DEFAULT_SOURCE, help="Source jo_shami.txt path")
    parser.add_argument("--block-size", type=int, default=50, help="Contiguous group block size (default: 50)")
    parser.add_argument("--seed", type=int, default=57485, help="Deterministic seed integer (default: 57485)")
    parser.add_argument("--train-out", default=DEFAULT_TRAIN_OUT, help="Path for jo_shami_train.txt")
    parser.add_argument("--heldout-out", default=DEFAULT_HELDOUT_OUT, help="Path for jo_human_fresh.txt")
    parser.add_argument("--sources-doc", default=DEFAULT_SOURCES_DOC, help="Path for jo_human_fresh_sources.md")
    parser.add_argument("--manifest", default=DEFAULT_MANIFEST, help="Path for shami_split_manifest.json")
    args = parser.parse_args()

    if not os.path.exists(args.source):
        print(f"Error: source file not found at {args.source}", file=sys.stderr)
        sys.exit(1)

    # 1. Read source lines
    with open(args.source, "r", encoding="utf-8") as f:
        source_raw_bytes = open(args.source, "rb").read()
        source_lines = [l.strip() for l in f if l.strip()]

    source_sha256 = hashlib.sha256(source_raw_bytes).hexdigest()
    total_source_lines = len(source_lines)

    # 2. Load evaluation exclusions
    norm_eval_exclusions, excluded_file_list = load_old_evaluation_exclusions(REPO_ROOT)

    # 3. Form contiguous blocks of 50 original cleaned lines
    block_size = args.block_size
    seed = args.seed
    num_groups = (total_source_lines + block_size - 1) // block_size

    groups = []
    train_group_ids = []
    heldout_group_ids = []

    train_raw_lines = []
    heldout_raw_lines = []

    group_manifest_entries = []

    for gid in range(num_groups):
        start_idx = gid * block_size
        end_idx = min((gid + 1) * block_size, total_source_lines)
        glines = source_lines[start_idx:end_idx]
        group_text = "\n".join(glines)
        
        payload = f"{seed}:{group_text}".encode("utf-8")
        h_bytes = hashlib.sha256(payload).digest()
        h_hex = hashlib.sha256(payload).hexdigest()
        val = int.from_bytes(h_bytes, "big")
        
        is_heldout = (val % 5 == 0)
        split_assignment = "heldout" if is_heldout else "train"
        
        if is_heldout:
            heldout_group_ids.append(gid)
            heldout_raw_lines.extend(glines)
        else:
            train_group_ids.append(gid)
            train_raw_lines.extend(glines)

        group_manifest_entries.append({
            "group_id": gid,
            "line_start": start_idx,
            "line_end": end_idx,
            "line_count": len(glines),
            "sha256": h_hex,
            "assignment": split_assignment,
        })

    # 4. Process train output
    # Train output: every line in non-heldout groups, minus old evaluation overlaps
    train_lines = []
    train_omitted_eval_overlap = 0
    for line in train_raw_lines:
        norm = normalize_text(line)
        if norm in norm_eval_exclusions:
            train_omitted_eval_overlap += 1
            continue
        train_lines.append(line)

    # 5. Process heldout validation output
    # Heldout: lines in heldout groups, 2-40 words, no Latin/URLs/markup/emoji/number-only,
    # exclude normalized matches in old bench/data/*.txt, dedup normalized, preserve order.
    heldout_lines = []
    heldout_norm_seen = set()
    heldout_omitted_reasons = {
        "word_count": 0,
        "hygiene_filter": 0,
        "eval_overlap": 0,
        "duplicate_normalized": 0,
    }

    for line in heldout_raw_lines:
        ok, reason = is_valid_heldout_candidate(line)
        if not ok:
            if "word_count" in reason:
                heldout_omitted_reasons["word_count"] += 1
            else:
                heldout_omitted_reasons["hygiene_filter"] += 1
            continue

        norm = normalize_text(line)
        if norm in norm_eval_exclusions:
            heldout_omitted_reasons["eval_overlap"] += 1
            continue

        if norm in heldout_norm_seen:
            heldout_omitted_reasons["duplicate_normalized"] += 1
            continue

        heldout_norm_seen.add(norm)
        heldout_lines.append(line)

    # 6. Integrity verifications
    train_norm_set = {normalize_text(l) for l in train_lines}
    heldout_norm_set = {normalize_text(l) for l in heldout_lines}
    
    # Exact and normalized intersections between train and heldout
    exact_train_set = set(train_lines)
    exact_heldout_set = set(heldout_lines)
    exact_overlap_count = len(exact_train_set.intersection(exact_heldout_set))
    norm_overlap_count = len(train_norm_set.intersection(heldout_norm_set))

    # Overlaps with old evaluation data
    train_eval_overlap_count = len(train_norm_set.intersection(norm_eval_exclusions))
    heldout_eval_overlap_count = len(heldout_norm_set.intersection(norm_eval_exclusions))

    # Internal duplicates
    train_exact_duplicates = len(train_lines) - len(set(train_lines))
    heldout_exact_duplicates = len(heldout_lines) - len(set(heldout_lines))
    heldout_norm_duplicates = len(heldout_lines) - len(heldout_norm_set)

    if exact_overlap_count != 0 or norm_overlap_count != 0:
        raise ValueError(f"Integrity check failed: Disjointness violation between train and heldout! exact={exact_overlap_count}, norm={norm_overlap_count}")
    if train_eval_overlap_count != 0 or heldout_eval_overlap_count != 0:
        raise ValueError(f"Integrity check failed: Overlap with old evaluation data! train={train_eval_overlap_count}, heldout={heldout_eval_overlap_count}")
    if heldout_norm_duplicates != 0:
        raise ValueError(f"Integrity check failed: Normalized duplicates in heldout! count={heldout_norm_duplicates}")

    # 7. Write outputs atomically / cleanly
    os.makedirs(os.path.dirname(args.train_out), exist_ok=True)
    os.makedirs(os.path.dirname(args.heldout_out), exist_ok=True)
    os.makedirs(os.path.dirname(args.sources_doc), exist_ok=True)
    os.makedirs(os.path.dirname(args.manifest), exist_ok=True)

    train_content = "\n".join(train_lines) + "\n"
    heldout_content = "\n".join(heldout_lines) + "\n"
    sources_doc_content = build_provenance_doc_content()

    with open(args.train_out, "w", encoding="utf-8") as f:
        f.write(train_content)

    with open(args.heldout_out, "w", encoding="utf-8") as f:
        f.write(heldout_content)

    with open(args.sources_doc, "w", encoding="utf-8") as f:
        f.write(sources_doc_content)

    train_sha256 = hashlib.sha256(train_content.encode("utf-8")).hexdigest()
    heldout_sha256 = hashlib.sha256(heldout_content.encode("utf-8")).hexdigest()
    sources_doc_sha256 = hashlib.sha256(sources_doc_content.encode("utf-8")).hexdigest()

    # Manifest dictionary (no non-deterministic fields like timestamps)
    manifest = {
        "source": {
            "path": os.path.relpath(args.source, REPO_ROOT),
            "sha256": source_sha256,
            "total_lines": total_source_lines,
        },
        "parameters": {
            "block_size": block_size,
            "seed": seed,
            "selection_rule": "sha256(f'{seed}:{group_text}').digest() % 5 == 0",
        },
        "group_summary": {
            "total_groups": num_groups,
            "train_groups": len(train_group_ids),
            "heldout_groups": len(heldout_group_ids),
            "train_raw_lines": len(train_raw_lines),
            "heldout_raw_lines": len(heldout_raw_lines),
        },
        "outputs": {
            "train": {
                "path": os.path.relpath(args.train_out, REPO_ROOT),
                "row_count": len(train_lines),
                "sha256": train_sha256,
                "omitted_old_eval_overlap": train_omitted_eval_overlap,
            },
            "heldout": {
                "path": os.path.relpath(args.heldout_out, REPO_ROOT),
                "row_count": len(heldout_lines),
                "sha256": heldout_sha256,
                "omitted_breakdown": heldout_omitted_reasons,
                "total_omitted_heldout_lines": len(heldout_raw_lines) - len(heldout_lines),
            },
            "sources_doc": {
                "path": os.path.relpath(args.sources_doc, REPO_ROOT),
                "sha256": sources_doc_sha256,
            },
        },
        "disjointness_verification": {
            "train_heldout_exact_overlap": exact_overlap_count,
            "train_heldout_normalized_overlap": norm_overlap_count,
            "train_old_eval_overlap": train_eval_overlap_count,
            "heldout_old_eval_overlap": heldout_eval_overlap_count,
            "heldout_normalized_duplicates": heldout_norm_duplicates,
            "excluded_old_eval_files": excluded_file_list,
        },
        "groups": group_manifest_entries,
    }

    manifest_bytes = json.dumps(manifest, indent=2, ensure_ascii=False).encode("utf-8") + b"\n"
    with open(args.manifest, "wb") as f:
        f.write(manifest_bytes)

    print("Shami holdout split completed successfully.")
    print(f"Source: {args.source} ({total_source_lines} lines, sha256={source_sha256})")
    print(f"Groups: {num_groups} (Train: {len(train_group_ids)}, Heldout: {len(heldout_group_ids)})")
    print(f"Train output: {args.train_out} ({len(train_lines)} lines, sha256={train_sha256})")
    print(f"Heldout output: {args.heldout_out} ({len(heldout_lines)} lines, sha256={heldout_sha256})")
    print(f"Manifest: {args.manifest}")
    print(f"Sources doc: {args.sources_doc}")
    print("Disjointness check: 0 exact, 0 normalized overlaps.")


if __name__ == "__main__":
    main()
