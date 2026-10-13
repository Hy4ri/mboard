#!/usr/bin/env python3
"""
Fetch and clean Tatoeba sentence sets for English and Arabic.
Stdlib only. Run with `python3 -I`.
"""

import bz2
import os
import random
import re
import sys
import urllib.request

ENG_URL = "https://downloads.tatoeba.org/exports/per_language/eng/eng_sentences.tsv.bz2"
ARA_URL = "https://downloads.tatoeba.org/exports/per_language/ara/ara_sentences.tsv.bz2"

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATA_DIR = os.path.join(BASE_DIR, "data")
RAW_DIR = os.path.join(DATA_DIR, "raw")

ENG_RAW = os.path.join(RAW_DIR, "eng_sentences.tsv.bz2")
ARA_RAW = os.path.join(RAW_DIR, "ara_sentences.tsv.bz2")

ENG_OUT = os.path.join(DATA_DIR, "en_tatoeba.txt")
ARA_OUT = os.path.join(DATA_DIR, "ar_tatoeba.txt")
SOURCES_MD = os.path.join(DATA_DIR, "SOURCES.md")

TARGET_EN_LINES = 6000
TARGET_AR_LINES = 6000

# Regexes for cleaning
RE_URL = re.compile(r"https?://\S+|www\.\S+", re.IGNORECASE)
RE_EMAIL = re.compile(r"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Z|a-z]{2,}\b")
RE_HTML = re.compile(r"<[^>]+>")
RE_SUB_ARTIFACTS = re.compile(r"^[-\u2013\u2014]\s+|\[.*?\]|\(.*?\)|♪|♫")
RE_HARAKAT = re.compile(r"[\u064B-\u065F\u0670\u0640]")  # harakat and tatweel
RE_LATIN = re.compile(r"[a-zA-Z]")
RE_ARABIC_CHAR = re.compile(r"[\u0600-\u06FF]")

def download_file(url, dest_path):
    if os.path.exists(dest_path) and os.path.getsize(dest_path) > 0:
        print(f"Already downloaded: {dest_path}")
        return
    print(f"Downloading {url} -> {dest_path}...")
    headers = {"User-Agent": "mboard-bench-fetcher/1.0"}
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req) as response, open(dest_path, "wb") as out_file:
        while chunk := response.read(1024 * 1024):
            out_file.write(chunk)
    print(f"Downloaded: {dest_path} ({os.path.getsize(dest_path)} bytes)")

def clean_sentence(text, lang):
    text = text.strip()
    if not text:
        return None

    # Strip html
    text = RE_HTML.sub("", text)
    # Check URLs / Emails
    if RE_URL.search(text) or RE_EMAIL.search(text):
        return None

    # Strip subtitle/dialogue artifacts
    text = RE_SUB_ARTIFACTS.sub("", text).strip()
    if not text:
        return None

    # No tabs, replace multiple whitespace with single space
    text = re.sub(r"\s+", " ", text).strip()

    # Numbers-only check (ignoring punctuation/spaces)
    stripped_punc = re.sub(r"[\s\d.,!?:;'\-—_()[\]\"«»؟،/]", "", text)
    if not stripped_punc:
        return None

    if lang == "ara":
        # Arabic cleaning rules:
        # 1. Drop lines with Latin letters
        if RE_LATIN.search(text):
            return None
        # 2. Must contain Arabic letters
        if not RE_ARABIC_CHAR.search(text):
            return None
        # 3. Strip harakat and tatweel
        text = RE_HARAKAT.sub("", text)
        # Normalize spaces again if any harakat left dangling spaces
        text = re.sub(r"\s+", " ", text).strip()

    words = text.split()
    if not (2 <= len(words) <= 25):
        return None

    return text

def process_file(raw_path, lang, target_lines):
    print(f"Processing {raw_path} for {lang}...")
    cleaned_sentences = []
    seen = set()

    with bz2.BZ2File(raw_path, mode="r") as raw_f:
        import io
        with io.TextIOWrapper(raw_f, encoding="utf-8", errors="replace") as f:
            for line in f:
                parts = line.rstrip("\r\n").split("\t")
                if len(parts) < 3:
                    continue
                # format: id \t lang \t text
                sent_text = parts[2]
                cleaned = clean_sentence(sent_text, lang)
                if cleaned and cleaned not in seen:
                    seen.add(cleaned)
                    cleaned_sentences.append(cleaned)

    print(f"Total unique usable sentences for {lang}: {len(cleaned_sentences)}")

    # Deterministic sampling
    rng = random.Random(57)
    if len(cleaned_sentences) > target_lines:
        sampled = rng.sample(cleaned_sentences, target_lines)
    else:
        sampled = cleaned_sentences
        # Shuffle deterministically
        rng.shuffle(sampled)

    return sampled

def append_sources(en_count, ar_count):
    entry = f"""
## Tatoeba Sentences
- Sources:
  - English: {ENG_URL}
  - Arabic: {ARA_URL}
- License: Creative Commons Attribution 2.0 France (CC BY 2.0 FR)
- Attribution: Tatoeba.org contributors
- Sampling: Deterministic sample with seed random.Random(57), cleaned to 2-25 words, duplicates removed, subtitles/URLs/HTML removed. Arabic stripped of harakat (U+064B..U+065F, U+0670) and tatweel (U+0640), Latin characters removed.
- Line counts:
  - `en_tatoeba.txt`: {en_count} lines
  - `ar_tatoeba.txt`: {ar_count} lines
"""
    mode = "a" if os.path.exists(SOURCES_MD) else "w"
    with open(SOURCES_MD, mode, encoding="utf-8") as f:
        f.write(entry.lstrip("\n"))

def main():
    os.makedirs(RAW_DIR, exist_ok=True)
    os.makedirs(DATA_DIR, exist_ok=True)

    download_file(ENG_URL, ENG_RAW)
    download_file(ARA_URL, ARA_RAW)

    en_sampled = process_file(ENG_RAW, "eng", TARGET_EN_LINES)
    with open(ENG_OUT, "w", encoding="utf-8", newline="\n") as f:
        for s in en_sampled:
            f.write(s + "\n")
    print(f"Wrote {len(en_sampled)} lines to {ENG_OUT}")

    ar_sampled = process_file(ARA_RAW, "ara", TARGET_AR_LINES)
    with open(ARA_OUT, "w", encoding="utf-8", newline="\n") as f:
        for s in ar_sampled:
            f.write(s + "\n")
    print(f"Wrote {len(ar_sampled)} lines to {ARA_OUT}")

    append_sources(len(en_sampled), len(ar_sampled))
    print("Updated SOURCES.md")

if __name__ == "__main__":
    main()
