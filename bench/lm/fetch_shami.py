#!/usr/bin/env python3
"""
Fetch and clean the Jordanian dialect partition of the Shami Dialect Corpus (SDC).

Source:
    GU-CLASP/shami-corpus (GitHub)
    File: Data/jordinian.txt
    URL: https://raw.githubusercontent.com/GU-CLASP/shami-corpus/master/Data/jordinian.txt

License:
    Apache License 2.0 (GU-CLASP/shami-corpus)
"""

import os
import re
import urllib.request

RAW_URL = "https://raw.githubusercontent.com/GU-CLASP/shami-corpus/master/Data/jordinian.txt"
PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
RAW_PATH = os.path.join(PROJECT_ROOT, "bench", "lm", "raw", "shami_jordinian.txt")
OUTPUT_PATH = os.path.join(PROJECT_ROOT, "bench", "lm", "jo_shami.txt")
JO_CHAT_PATH = os.path.join(PROJECT_ROOT, "bench", "data", "jo_chat.txt")

URL_RE = re.compile(r"https?://\S+|www\.\S+")
MENTION_RE = re.compile(r"@\w+")
EMOJI_RE = re.compile(r"[\U00010000-\U0010ffff]")
HARAKAT_TATWEEL_RE = re.compile(r"[\u064B-\u065F\u0670\u0640]")
REPEAT_3_RE = re.compile(r"(.)\1{2,}")
LATIN_RE = re.compile(r"[A-Za-z]")


def is_arabic_script(s: str) -> bool:
    """Check if all non-whitespace characters belong to Arabic script blocks."""
    for c in s:
        if c.isspace():
            continue
        # Arabic, Arabic Supplement, Arabic Extended-A, Arabic Presentation Forms A & B
        if not (
            "\u0600" <= c <= "\u06FF"
            or "\u0750" <= c <= "\u077F"
            or "\u08A0" <= c <= "\u08FF"
            or "\uFB50" <= c <= "\uFDFF"
            or "\uFE70" <= c <= "\uFEFF"
        ):
            return False
    return True


def fetch_raw(url: str, dest_path: str) -> None:
    os.makedirs(os.path.dirname(dest_path), exist_ok=True)
    if os.path.exists(dest_path) and os.path.getsize(dest_path) > 0:
        print(f"Raw file already exists at {dest_path}")
        return
    print(f"Downloading {url} to {dest_path}...")
    req = urllib.request.Request(url, headers={"User-Agent": "mboard-lm-cleaner/1.0"})
    with urllib.request.urlopen(req) as resp, open(dest_path, "wb") as out:
        out.write(resp.read())
    print(f"Downloaded {os.path.getsize(dest_path)} bytes.")


def clean_line(line: str) -> str | None:
    line = line.rstrip("\r\n")

    # Strip URLs, @mentions, emoji
    line = URL_RE.sub("", line)
    line = MENTION_RE.sub("", line)
    line = EMOJI_RE.sub("", line)

    # Drop lines with Latin letters
    if LATIN_RE.search(line):
        return None

    # Strip harakat U+064B..U+065F, U+0670 and tatweel U+0640
    line = HARAKAT_TATWEEL_RE.sub("", line)

    # Collapse letters repeated 3+ times to 2
    line = REPEAT_3_RE.sub(r"\1\1", line)

    # Normalize whitespace
    line = " ".join(line.split())
    if not line:
        return None

    # Arabic script only per line
    if not is_arabic_script(line):
        return None

    # Keep lines with >= 2 words
    words = line.split()
    if len(words) < 2:
        return None

    return line


def main() -> None:
    fetch_raw(RAW_URL, RAW_PATH)

    with open(RAW_PATH, "r", encoding="utf-8") as f:
        raw_lines = f.readlines()
    raw_count = len(raw_lines)

    # Load jo_chat exact lines for exclusion
    jo_chat_exact = set()
    if os.path.exists(JO_CHAT_PATH):
        with open(JO_CHAT_PATH, "r", encoding="utf-8") as f:
            jo_chat_exact = {l.strip() for l in f if l.strip()}

    kept = []
    seen = set()
    exact_excluded_count = 0

    for line in raw_lines:
        cleaned = clean_line(line)
        if cleaned is None:
            continue

        if cleaned in jo_chat_exact:
            exact_excluded_count += 1
            continue

        if cleaned not in seen:
            seen.add(cleaned)
            kept.append(cleaned)

    os.makedirs(os.path.dirname(OUTPUT_PATH), exist_ok=True)
    with open(OUTPUT_PATH, "w", encoding="utf-8", newline="\n") as f:
        for l in kept:
            f.write(l + "\n")

    total_words = sum(len(l.split()) for l in kept)

    print(f"Raw lines: {raw_count}")
    print(f"Kept lines: {len(kept)}")
    print(f"Total words: {total_words}")
    print(f"Verbatim excluded from jo_chat.txt: {exact_excluded_count}")
    print(f"Output saved to: {OUTPUT_PATH}")


if __name__ == "__main__":
    main()
