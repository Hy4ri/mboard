#!/usr/bin/env python3
"""
bench/lm/build_ngrams.py

Builds next_<code>.tsv, context_<code>.tsv, and unigram_<code>.tsv
from OPUS OpenSubtitles2018 mono corpora.

Supports --lang en|ar, --url URL, --ranges START:END[,...], --out-dir DIR,
[--max-mb-per-range N], [--extra-text FILE ...].
"""

import argparse
import os
import re
import resource
import sys
import time
import urllib.request
import zlib

HARAKAT_TATWEEL_CHARS = ''.join(
    [chr(c) for c in range(0x064B, 0x065F + 1)] + ['\u0670', '\u0640', "'", '\u2019']
)
TRANSLATE_TABLE = str.maketrans('', '', HARAKAT_TATWEEL_CHARS)

def surface(word: str) -> str:
    """Lowercase written form without harakat/tatweel, apostrophes kept as '."""
    out = word.lower().replace('\u2019', "'")
    return ''.join(c for c in out if not ('\u064b' <= c <= '\u065f' or c in '\u0670\u0640'))

def normalize_key(word: str) -> str:
    """Matches WordSuggestions.matchKey."""
    return word.lower().translate(TRANSLATE_TABLE)

WORD_OR_BOUNDARY = re.compile(r"[^\W\d_]+(?:['’][^\W\d_]+)*|[.!?؟]")
OCR_STARTS = {
    'Iie', 'Iike', 'Iove', 'Iittle', 'Iook', 'Iive', 'Iife', 'Iet',
    'Iot', 'Iong', 'Iate', 'Iast', 'Ieave', 'Iight', 'Iisten', 'Ieft', 'Iearn'
}
LATIN_RE = re.compile(r'[A-Za-z]')

def is_subtitle_junk(s: str, lang: str) -> bool:
    if not s:
        return True
    if s[0] in ('-', '–', '—'):
        return True
    # Brackets, music notes, etc.
    if any(c in s for c in '[]{}<>()♪♫#'):
        return True
    letters = [c for c in s if c.isalpha()]
    if len(letters) >= 2 and all(c.isupper() for c in letters):
        return True
    if lang == 'en':
        if 'AII' in s or 'Iie' in s or 'CuIIen' in s or re.search(r'[a-z]I', s):
            return True
        for w in re.findall(r"[A-Za-z]+(?:'[A-Za-z]+)*", s):
            if w in OCR_STARTS:
                return True
    elif lang in ('ar', 'jo'):
        # Reject Latin letters in Arabic subtitles
        if LATIN_RE.search(s):
            return True
    return False

def load_exclusion_lines(repo_dir: str) -> set:
    exclude = set()
    bench_data_dir = os.path.join(repo_dir, 'bench', 'data')
    test_files = [
        'en_subs.txt', 'ar_subs.txt', 'en_tatoeba.txt',
        'ar_tatoeba.txt', 'en_chat.txt', 'jo_chat.txt',
        'en_fresh.txt', 'ar_fresh.txt', 'jo_human_fresh.txt'
    ]
    for fn in test_files:
        p = os.path.join(bench_data_dir, fn)
        if os.path.exists(p):
            with open(p, 'r', encoding='utf-8') as f:
                for line in f:
                    stripped = line.strip()
                    if stripped:
                        exclude.add(stripped)
    return exclude

class NGramBuilder:
    def __init__(self, lang: str, exclude_set: set):
        self.lang = lang
        self.exclude_set = exclude_set
        self.unigram_counts = {}   # word -> count
        self.next_counts = {}      # ctx -> {word: count}
        self.context_counts = {}   # ctx -> {word: count}
        self.lines_processed = 0
        self.case_counts = {}      # surface -> {written form: count}

    def prune_singletons(self, min_context_total: int = 2):
        """Drop trigram contexts seen less than min_context_total times to control RAM."""
        to_del = []
        for ctx, wmap in self.context_counts.items():
            if sum(wmap.values()) < min_context_total:
                to_del.append(ctx)
        for ctx in to_del:
            del self.context_counts[ctx]

    def process_line(self, line: str, weight: int = 1):
        s = line.strip()
        if not s or s in self.exclude_set or is_subtitle_junk(s, self.lang):
            return
        self.lines_processed += 1
        if self.lines_processed % 5_000_000 == 0:
            self.prune_singletons(min_context_total=2)

        prev1 = None
        prev2 = None

        for m in WORD_OR_BOUNDARY.finditer(s):
            tok = m.group()
            if tok in '.!?؟':
                prev1 = None
                prev2 = None
                continue
            k = normalize_key(tok)
            if not k:
                continue
            # Values keep the written form (it's vs its); contexts use the keyboard's match key.
            v = surface(tok)
            if prev1 is not None:
                cases = self.case_counts.setdefault(v, {})
                cases[tok] = cases.get(tok, 0) + weight
            self.unigram_counts[v] = self.unigram_counts.get(v, 0) + weight
            if prev1 is None:
                d0 = self.next_counts.setdefault('<s>', {})
                d0[v] = d0.get(v, 0) + weight
            else:
                d1 = self.next_counts.setdefault(prev1, {})
                d1[v] = d1.get(v, 0) + weight
                if prev2 is not None:
                    ctx = prev2 + ' ' + prev1
                    d2 = self.context_counts.setdefault(ctx, {})
                    d2[v] = d2.get(v, 0) + weight

            prev2 = prev1
            prev1 = k

    def display(self, v: str) -> str:
        """Most common mid-sentence spelling: I'm, its, Amman; sentence-initial capitals don't count."""
        cases = self.case_counts.get(v)
        if not cases:
            return v
        best = max(cases.items(), key=lambda x: x[1])[0]
        return best.replace('\u2019', "'")

    def process_text_stream(self, text_generator):
        remainder = ''
        for chunk in text_generator:
            text = remainder + chunk
            split_lines = text.split('\n')
            remainder = split_lines[-1]
            for line in split_lines[:-1]:
                self.process_line(line)
        if remainder:
            self.process_line(remainder)

def stream_compressed_range(url: str, start: int, end: int, max_mb: int | None = None):
    """
    Streams and decompresses range [start, end) of a gzip file.
    If start == 0, streams directly using HTTP Range.
    If start > 0, streams from byte 0 to end, discarding decompressed text before start.
    Yields decompressed UTF-8 text strings.
    """
    target_end = end
    if max_mb is not None:
        target_end = min(end, start + max_mb * 1024 * 1024)

    req_start = 0 if start > 0 else 0
    print(f"Streaming range {start}..{target_end} from {url} (HTTP Range: {start if start == 0 else 0}-{target_end - 1})...", flush=True)
    req = urllib.request.Request(url, headers={'Range': f'bytes={start if start == 0 else 0}-{target_end - 1}'})
    decompressor = zlib.decompressobj(16 + 15)
    bytes_streamed = start if start == 0 else 0
    chunk_size = 4 * 1024 * 1024

    with urllib.request.urlopen(req) as resp:
        while bytes_streamed < target_end:
            to_read = min(chunk_size, target_end - bytes_streamed)
            # End a read exactly at the range start so no chunk straddles a held-out window.
            if bytes_streamed < start:
                to_read = min(to_read, start - bytes_streamed)
            chunk = resp.read(to_read)
            if not chunk:
                break
            bytes_streamed += len(chunk)
            dec = decompressor.decompress(chunk)
            if bytes_streamed > start and dec:
                yield dec.decode('utf-8', errors='replace')
            if bytes_streamed % (25 * 1024 * 1024) < chunk_size:
                print(f"  Streamed {bytes_streamed / (1024*1024):.1f} MB compressed...", flush=True)

def parse_ranges(ranges_str: str):
    ranges = []
    for part in ranges_str.split(','):
        part = part.strip()
        if not part:
            continue
        s, e = part.split(':')
        ranges.append((int(s), int(e)))
    return ranges

def format_table(counts_dict: dict, min_word_count: int, min_total: int, max_rows: int, display=lambda v: v):
    rows = []
    for ctx, word_map in counts_dict.items():
        total = sum(word_map.values())
        if total < min_total:
            continue
        valid_words = [(w, c) for w, c in word_map.items() if c >= min_word_count]
        if not valid_words:
            continue
        valid_words.sort(key=lambda x: (-x[1], x[0]))
        top_words = valid_words[:12]
        words_str = '\t'.join(f'{display(w)}:{c}' for w, c in top_words)
        rows.append((total, f'{ctx}\t{total}\t{words_str}\n'))

    rows.sort(key=lambda x: -x[0])
    if max_rows and len(rows) > max_rows:
        rows = rows[:max_rows]
    return [r[1] for r in rows]

def main():
    parser = argparse.ArgumentParser(description="Build n-gram tables from OpenSubtitles corpus.")
    parser.add_argument("--lang", choices=["en", "ar", "jo"], required=True)
    parser.add_argument("--url", type=str, default="")
    parser.add_argument("--ranges", type=str, default="")
    parser.add_argument("--out-dir", type=str, required=True)
    parser.add_argument("--max-mb-per-range", type=int, default=None)
    parser.add_argument("--extra-text", nargs="*", default=[], help="FILE or FILE:WEIGHT")
    parser.add_argument("--min-next-total", type=int, default=5)
    parser.add_argument("--min-context-total", type=int, default=8)
    args = parser.parse_args()

    repo_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
    exclude_set = load_exclusion_lines(repo_dir)
    print(f"Loaded {len(exclude_set)} exclusion lines from bench test files.")

    builder = NGramBuilder(args.lang, exclude_set)

    # Process extra-text files if any
    for spec in args.extra_text:
        extra_file, _, w = spec.partition(':')
        if os.path.exists(extra_file):
            print(f"Processing extra text file: {extra_file} weight {w or 1}")
            with open(extra_file, 'r', encoding='utf-8') as f:
                for line in f:
                    builder.process_line(line, int(w or 1))

    ranges = parse_ranges(args.ranges)
    total_compressed_bytes = 0
    t0 = time.time()

    for start, end in ranges:
        actual_end = end
        if args.max_mb_per_range is not None:
            actual_end = min(end, start + args.max_mb_per_range * 1024 * 1024)
        bytes_in_range = actual_end - start
        total_compressed_bytes += bytes_in_range

        gen = stream_compressed_range(args.url, start, end, args.max_mb_per_range)
        builder.process_text_stream(gen)
        # Prune after each range to keep memory low
        builder.prune_singletons(min_context_total=2)

    print(f"Processing completed in {time.time() - t0:.2f}s.", flush=True)
    print(f"Total lines processed: {builder.lines_processed}", flush=True)
    print(f"Unigram keys: {len(builder.unigram_counts)}", flush=True)
    print(f"Bigram contexts: {len(builder.next_counts)}", flush=True)
    print(f"Trigram contexts before final pruning: {len(builder.context_counts)}", flush=True)

    os.makedirs(args.out_dir, exist_ok=True)

    # Format next_<lang>.tsv
    # total >= 5, words >= 2, cap at 60,000
    next_rows = format_table(builder.next_counts, min_word_count=2, min_total=args.min_next_total, max_rows=60000, display=builder.display)
    next_path = os.path.join(args.out_dir, f"next_{args.lang}.tsv")
    with open(next_path, 'w', encoding='utf-8', newline='\n') as f:
        f.writelines(next_rows)
    print(f"Wrote {len(next_rows)} rows to {next_path} ({os.path.getsize(next_path)} bytes)", flush=True)

    # Format context_<lang>.tsv
    # total >= 8, words >= 3, cap at 300,000
    context_rows = format_table(builder.context_counts, min_word_count=3, min_total=args.min_context_total, max_rows=300000, display=builder.display)
    context_path = os.path.join(args.out_dir, f"context_{args.lang}.tsv")
    with open(context_path, 'w', encoding='utf-8', newline='\n') as f:
        f.writelines(context_rows)
    print(f"Wrote {len(context_rows)} rows to {context_path} ({os.path.getsize(context_path)} bytes)", flush=True)

    # Format unigram_<lang>.tsv
    # Top 60,000 by count (key-normalized)
    unigrams_sorted = sorted(builder.unigram_counts.items(), key=lambda x: (-x[1], x[0]))[:60000]
    unigram_path = os.path.join(args.out_dir, f"unigram_{args.lang}.tsv")
    with open(unigram_path, 'w', encoding='utf-8', newline='\n') as f:
        for w, c in unigrams_sorted:
            f.write(f"{builder.display(w)}\t{c}\n")
    print(f"Wrote {len(unigrams_sorted)} rows to {unigram_path} ({os.path.getsize(unigram_path)} bytes)", flush=True)

    peak_rss_mb = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024
    print(f"Peak RSS: {peak_rss_mb:.1f} MB", flush=True)

if __name__ == "__main__":
    main()
