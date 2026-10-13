#!/usr/bin/env python3
"""
Fetch, stream-decompress, clean, and sample Arabic subtitle sentences from
OPUS OpenSubtitles2018 mono corpus (https://object.pouta.csc.fi/OPUS-OpenSubtitles/v2018/mono/ar.txt.gz).

Stdlib only.
"""

import argparse
import os
import random
import re
import sys
import urllib.request
import zlib

URL = "https://object.pouta.csc.fi/OPUS-OpenSubtitles/v2018/mono/ar.txt.gz"

# Cleaning regexes
HTML_TAG_RE = re.compile(r"<[^>]+>")
BRACKET_NOTE_RE = re.compile(r"\[.*?\]|\(.*?\)|<.*?>|\{.*?\}")
NOTE_SYMBOLS_RE = re.compile(r"[♪♫#\*_~=\\/]")
URL_RE = re.compile(r"https?://\S+|www\.\S+", re.IGNORECASE)
EMAIL_RE = re.compile(r"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Z|a-z]{2,}\b")
TIMESTAMP_RE = re.compile(r"\d{1,2}:\d{2}(?::\d{2})?")
SPEAKER_PREFIX_RE = re.compile(r"^[^\s:]{2,20}:\s*")
LEADING_DASHES_RE = re.compile(r"^[-–—\s]+")

# Arabic normalization and constraints
HARAKAT_TATWEEL_RE = re.compile(r"[\u064B-\u065F\u0670\u0640]")
LATIN_RE = re.compile(r"[A-Za-z]")
ARABIC_CHAR_RE = re.compile(r"[\u0600-\u06FF]")
BIDI_CHARS_RE = re.compile(r"[\u200E\u200F\u202A-\u202E]")
DIGITS_AND_PUNCT_ONLY_RE = re.compile(r"^[\s\.\،\؟\!0-9\u0660-\u0669]+$")


def clean_line_ar(line: str) -> str | None:
    # 1. Strip markup and bracket notes
    line = HTML_TAG_RE.sub(" ", line)
    line = BRACKET_NOTE_RE.sub(" ", line)
    line = NOTE_SYMBOLS_RE.sub(" ", line)
    line = BIDI_CHARS_RE.sub("", line)

    # 2. Reject URLs, emails, timestamps
    if URL_RE.search(line) or EMAIL_RE.search(line) or TIMESTAMP_RE.search(line):
        return None

    # 3. Strip harakat (U+064B..U+065F, U+0670) and tatweel (U+0640)
    line = HARAKAT_TATWEEL_RE.sub("", line)

    # 4. Reject lines containing Latin letters
    if LATIN_RE.search(line):
        return None

    # 5. Remove speaker prefixes and leading dashes / dialogue dashes
    line = SPEAKER_PREFIX_RE.sub("", line)
    line = LEADING_DASHES_RE.sub("", line)

    # 6. Normalize punctuation to Arabic punctuation: ، ؟ . !
    line = line.replace(",", "،")
    line = line.replace("?", "؟")

    # Re-strip leading dashes/whitespace that might have been revealed
    line = LEADING_DASHES_RE.sub("", line)
    line = line.strip()
    if not line:
        return None

    # 7. Subtitle artifact checks: e.g. starting with dash
    if line.startswith("-") or line.startswith("–") or line.startswith("—"):
        return None

    # 8. Must contain Arabic letters
    if not ARABIC_CHAR_RE.search(line):
        return None

    # 9. Words count constraint: between 2 and 25 words
    words = line.split()
    if not (2 <= len(words) <= 25):
        return None

    # 10. Reject digits-only or numbers-only lines
    if DIGITS_AND_PUNCT_ONLY_RE.match(line):
        return None

    return " ".join(words)


def fetch_and_extract(offset: int, length: int, raw_cache_path: str) -> str:
    # Check if raw cache already exists
    if os.path.exists(raw_cache_path) and os.path.getsize(raw_cache_path) > 0:
        print(f"Loading raw cached slice from {raw_cache_path}...")
        with open(raw_cache_path, "r", encoding="utf-8", errors="replace") as f:
            return f.read()

    end_byte = offset + length - 1
    print(f"Fetching from {URL} (streaming from byte 0 through {end_byte}, extracting bytes {offset}..{end_byte})...")
    req = urllib.request.Request(URL, headers={"Range": f"bytes=0-{end_byte}"})

    decompressor = zlib.decompressobj(16 + 15)
    bytes_streamed = 0
    extracted_text_chunks = []
    chunk_size = 1024 * 1024

    with urllib.request.urlopen(req) as resp:
        while True:
            chunk = resp.read(chunk_size)
            if not chunk:
                break
            bytes_streamed += len(chunk)
            decompressed = decompressor.decompress(chunk)

            # If we are past offset, keep extracted text
            if bytes_streamed > offset and decompressed:
                text = decompressed.decode("utf-8", errors="replace")
                extracted_text_chunks.append(text)

            if bytes_streamed % (20 * 1024 * 1024) < chunk_size:
                print(f"Streamed {bytes_streamed / (1024*1024):.1f} MB compressed...")

    full_extracted = "".join(extracted_text_chunks)
    print(f"Stream complete. Extracted {len(full_extracted)} chars past compressed offset {offset}.")

    # Cache raw extracted text
    os.makedirs(os.path.dirname(os.path.abspath(raw_cache_path)), exist_ok=True)
    with open(raw_cache_path, "w", encoding="utf-8") as f:
        f.write(full_extracted)
    print(f"Cached raw extracted slice to {raw_cache_path}")

    return full_extracted


def main():
    parser = argparse.ArgumentParser(description="Fetch, clean, and sample Arabic subtitle sentences from OPUS mono.")
    parser.add_argument("--offset", type=int, default=125_000_000, help="Compressed byte offset start (default: 125 MB)")
    parser.add_argument("--length", type=int, default=20_000_000, help="Compressed byte length to read past offset (default: 20 MB)")
    parser.add_argument("--lines", type=int, default=8000, help="Number of sampled lines to output (default: 8000)")
    parser.add_argument("--out", type=str, required=True, help="Output path for cleaned sentences file")
    args = parser.parse_args()

    raw_cache_name = f"ar_slice_{args.offset}_{args.length}.txt"
    raw_cache_path = os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "data", "raw", raw_cache_name
    )

    raw_text = fetch_and_extract(args.offset, args.length, raw_cache_path)

    seen = set()
    cleaned_lines = []

    for raw_line in raw_text.splitlines():
        cleaned = clean_line_ar(raw_line)
        if cleaned and cleaned not in seen:
            seen.add(cleaned)
            cleaned_lines.append(cleaned)

    print(f"Total cleaned and deduplicated candidate lines: {len(cleaned_lines)}")

    if len(cleaned_lines) < args.lines:
        print(f"Warning: requested {args.lines} lines but only {len(cleaned_lines)} available.", file=sys.stderr)
        sampled = cleaned_lines
    else:
        # Deterministic sampling with seed 57
        rng = random.Random(57)
        sampled = rng.sample(cleaned_lines, args.lines)

    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, "w", encoding="utf-8", newline="\n") as f:
        for line in sampled:
            f.write(line + "\n")

    print(f"Successfully wrote {len(sampled)} lines to {args.out}")


if __name__ == "__main__":
    main()
