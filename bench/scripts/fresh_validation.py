#!/usr/bin/env python3
"""
Generate reproducible fresh English and Arabic evaluation sets (2000 sentences each)
from Tatoeba sentence exports, strictly disjoint from all previous evaluation
and LM training datasets.

Stdlib only.
"""

import bz2
import glob
import io
import os
import random
import re
import sys
import unicodedata
import urllib.request

ENG_URL = "https://downloads.tatoeba.org/exports/per_language/eng/eng_sentences.tsv.bz2"
ARA_URL = "https://downloads.tatoeba.org/exports/per_language/ara/ara_sentences.tsv.bz2"

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATA_DIR = os.path.join(BASE_DIR, "data")
RAW_DIR = os.path.join(DATA_DIR, "raw")
LM_DIR = os.path.join(BASE_DIR, "lm")

ENG_RAW = os.path.join(RAW_DIR, "eng_sentences.tsv.bz2")
ARA_RAW = os.path.join(RAW_DIR, "ara_sentences.tsv.bz2")

EN_OUT = os.path.join(DATA_DIR, "en_fresh.txt")
AR_OUT = os.path.join(DATA_DIR, "ar_fresh.txt")
SOURCES_MD = os.path.join(DATA_DIR, "fresh_validation_sources.md")

RANDOM_SEED = 57485
SAMPLE_COUNT = 2000

# Cleaning regexes
RE_URL = re.compile(r"https?://\S+|www\.\S+", re.IGNORECASE)
RE_EMAIL = re.compile(r"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Z|a-z]{2,}\b")
RE_HTML = re.compile(r"<[^>]+>")
RE_SUB_ARTIFACTS = re.compile(r"^[-\u2013\u2014]\s+|\[.*?\]|\(.*?\)|♪|♫")
RE_HARAKAT = re.compile(r"[\u064B-\u065F\u0670\u0640]")  # harakat and tatweel
RE_LATIN = re.compile(r"[a-zA-Z]")
RE_ARABIC_CHAR = re.compile(r"[\u0600-\u06FF]")
RE_EMOJI = re.compile(r"[\U00010000-\U0010ffff]|[\u2600-\u26FF]|[\u2700-\u27BF]")
RE_BIDI = re.compile(r"[\u200e\u200f\u202a-\u202e\ufeff]")


def download_file(url, dest_path):
    if os.path.exists(dest_path) and os.path.getsize(dest_path) > 0:
        return
    print(f"Downloading {url} -> {dest_path}...")
    headers = {"User-Agent": "mboard-bench-fetcher/1.0"}
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req) as response, open(dest_path, "wb") as out_file:
        while chunk := response.read(1024 * 1024):
            out_file.write(chunk)
    print(f"Downloaded: {dest_path} ({os.path.getsize(dest_path)} bytes)")


def normalize_text(text):
    t = text.strip()
    t = unicodedata.normalize("NFKC", t)
    t = RE_HARAKAT.sub("", t)
    t = t.lower()
    t = RE_BIDI.sub("", t)
    t = re.sub(r"\s+", " ", t)
    return t


def build_exclusion_sets():
    """
    Collect all lines from existing bench/data/*.txt and bench/lm/*.txt.
    Do NOT include en_fresh.txt or ar_fresh.txt so reruns don't exclude themselves.
    """
    existing_files = glob.glob(os.path.join(DATA_DIR, "*.txt")) + glob.glob(
        os.path.join(LM_DIR, "*.txt")
    )

    verbatim_set = set()
    norm_set = set()

    for fpath in existing_files:
        fname = os.path.basename(fpath)
        if fname in ("en_fresh.txt", "ar_fresh.txt"):
            continue
        with open(fpath, "r", encoding="utf-8", errors="replace") as f:
            for line in f:
                line_str = line.strip()
                if line_str:
                    verbatim_set.add(line_str)
                    norm_set.add(normalize_text(line_str))

    return verbatim_set, norm_set


def clean_sentence(text, lang):
    text = RE_BIDI.sub("", text).strip()
    if not text:
        return None
    # Strip HTML
    text = RE_HTML.sub("", text)
    if RE_URL.search(text) or RE_EMAIL.search(text):
        return None
    if RE_EMOJI.search(text):
        return None
    text = RE_SUB_ARTIFACTS.sub("", text).strip()
    if not text:
        return None
    # Normalize spaces
    text = re.sub(r"\s+", " ", text).strip()
    # Numeric-only / OCR junk check: must have lexical characters
    stripped_punc = re.sub(
        r"[\s\d.,!?:;'\-—_()[\]\"«»؟،/`~@#$%^&*+=<>|\\{}]", "", text
    )
    if not stripped_punc:
        return None

    if lang == "ara":
        if RE_LATIN.search(text):
            return None
        if not RE_ARABIC_CHAR.search(text):
            return None
        # Strip harakat and tatweel
        text = RE_HARAKAT.sub("", text)
        text = re.sub(r"\s+", " ", text).strip()
    elif lang == "eng":
        if not RE_LATIN.search(text):
            return None

    words = text.split()
    if not (2 <= len(words) <= 15):
        return None
    return text


def sample_sentences(raw_path, lang, sample_count, seed, verbatim_excl, norm_excl):
    candidates = []
    seen_verbatim = set()
    seen_norm = set()

    with bz2.BZ2File(raw_path, "r") as f:
        text_f = io.TextIOWrapper(f, encoding="utf-8", errors="replace")
        for line in text_f:
            parts = line.rstrip("\r\n").split("\t")
            if len(parts) < 3:
                continue
            raw_text = parts[2]
            cleaned = clean_sentence(raw_text, lang)
            if not cleaned:
                continue
            norm = normalize_text(cleaned)
            # Check exclusion against existing datasets
            if cleaned in verbatim_excl or norm in norm_excl:
                continue
            # Dedup within the candidate stream
            if cleaned in seen_verbatim or norm in seen_norm:
                continue
            seen_verbatim.add(cleaned)
            seen_norm.add(norm)
            candidates.append(cleaned)

    rng = random.Random(seed)
    sampled = rng.sample(candidates, sample_count)
    return sampled, len(candidates)


def write_provenance(en_count, en_candidates, ar_count, ar_candidates, seed):
    content = f"""# Fresh Validation Dataset Provenance & Attribution

## Summary
- Purpose: Sealed fresh evaluation sets for suggestion benchmarks (evaluating quality without tuning on validation inputs).
- English evaluation set: `{EN_OUT}` ({en_count} sentences)
- Arabic evaluation set: `{AR_OUT}` ({ar_count} sentences)
- Generated by: `bench/scripts/fresh_validation.py`
- Random Seed: `random.Random({seed})`

## Provenance and Attribution
- Primary Source: Tatoeba sentence exports (public downloads per language)
  - English: {ENG_URL}
  - Arabic: {ARA_URL}
- Raw Files Used:
  - `bench/data/raw/eng_sentences.tsv.bz2`
  - `bench/data/raw/ara_sentences.tsv.bz2`
- Attribution: Tatoeba.org contributors
- License: Creative Commons Attribution 2.0 France (CC BY 2.0 FR)
- Role: Fresh evaluation benchmark only (not conversational training, not shipped in IME dictionary assets).

## Selection and Filtering Criteria
1. Sentence length: Natural 2 to 15 words.
2. Content hygiene: No URLs, emails, HTML tags, emoji, or numeric-only/OCR junk.
3. Language purity:
   - English: Must contain Latin letters; apostrophes retained.
   - Arabic: Must contain Arabic letters; no Latin characters allowed.
   - Arabic diacritics: Arabic short vowel marks/harakat (`[\\u064B-\\u065F\\u0670]`) and tatweel (`\\u0640`) stripped.
   - Bidirectional formatting marks (LRO/RLO/LRM/RLM/BOM) stripped.
4. Disjointness & Exclusion:
   - Evaluated against all existing `bench/data/*.txt` and `bench/lm/*.txt` corpora.
   - Excluded both exact verbatim matches and normalized matches (case-folded, whitespace-collapsed, diacritic/tatweel-stripped).
   - Internal deduplication across exact and normalized forms.
5. Pool Statistics:
   - English clean candidates pool: {en_candidates:,} sentences -> sampled {en_count}
   - Arabic clean candidates pool: {ar_candidates:,} sentences -> sampled {ar_count}

## Caveats and Disclaimers
- Disjointness from prior `bench/data/*.txt` and `bench/lm/*.txt` is strictly verified at exact and normalized levels.
- While disjoint from repository evaluation files, open subtitle and web corpora from external domains could theoretically share idiomatic short sentences; no claim of absolute unseenness across all external conversational corpora is made.
- These sets are sealed: benchmark runs must not inspect per-word predictions or tune engine hyperparameters to this validation set.
"""
    with open(SOURCES_MD, "w", encoding="utf-8") as f:
        f.write(content)


def main():
    os.makedirs(RAW_DIR, exist_ok=True)
    os.makedirs(DATA_DIR, exist_ok=True)

    download_file(ENG_URL, ENG_RAW)
    download_file(ARA_URL, ARA_RAW)

    print("Building exclusion sets from existing bench/data/*.txt and bench/lm/*.txt...")
    verbatim_excl, norm_excl = build_exclusion_sets()
    print(f"Exclusion database: {len(verbatim_excl)} verbatim, {len(norm_excl)} normalized entries.")

    print(f"Sampling {SAMPLE_COUNT} EN sentences (seed={RANDOM_SEED})...")
    en_samples, en_pool_size = sample_sentences(
        ENG_RAW, "eng", SAMPLE_COUNT, RANDOM_SEED, verbatim_excl, norm_excl
    )
    with open(EN_OUT, "w", encoding="utf-8", newline="\n") as f:
        for s in en_samples:
            f.write(s + "\n")
    print(f"Wrote {len(en_samples)} sentences to {EN_OUT} (from pool of {en_pool_size}).")

    print(f"Sampling {SAMPLE_COUNT} AR sentences (seed={RANDOM_SEED})...")
    ar_samples, ar_pool_size = sample_sentences(
        ARA_RAW, "ara", SAMPLE_COUNT, RANDOM_SEED, verbatim_excl, norm_excl
    )
    with open(AR_OUT, "w", encoding="utf-8", newline="\n") as f:
        for s in ar_samples:
            f.write(s + "\n")
    print(f"Wrote {len(ar_samples)} sentences to {AR_OUT} (from pool of {ar_pool_size}).")

    write_provenance(len(en_samples), en_pool_size, len(ar_samples), ar_pool_size, RANDOM_SEED)
    print(f"Recorded provenance to {SOURCES_MD}.")


if __name__ == "__main__":
    main()
