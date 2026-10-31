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
        'en_fresh.txt', 'ar_fresh.txt', 'jo_human_fresh.txt',
        'jo_tatoeba_fresh.txt', 'levantine_sister_heldout.txt'
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

PREFIXES_ARABIC = [
    "وبال", "فبال", "وكال", "فكال", "ولل", "فلل", "وال", "فال", "بال", "كال", "عال", "لل", "ال",
    "و", "ف", "ب", "ل", "ك", "ع"
]

def normalize_stem_vocab_word(w: str) -> str | None:
    """Normalize Arabic word matching WordDictionary.normalize."""
    chars = []
    for c in w:
        if c in 'أإآٱ':
            chars.append('ا')
        elif c == 'ى':
            chars.append('ي')
        elif c == 'ـ':
            pass
        elif ('\u064b' <= c <= '\u065f') or c == '\u0670' or ('\u06d6' <= c <= '\u06ed'):
            pass
        elif '\u0621' <= c <= '\u064a':
            chars.append(c)
        else:
            return None
    res = ''.join(chars)
    return res if res else None

def load_stem_vocab(vocab_dir: str) -> dict[str, int]:
    """Loads words_ar.tsv + jordanian.tsv merged by max frequency/tier."""
    vocab: dict[str, int] = {}
    for name in ("words_ar.tsv", "jordanian.tsv"):
        if not os.path.isfile(os.path.join(vocab_dir, name)):
            raise FileNotFoundError(f"Missing stem frequency vocabulary: {name}")
    words_ar_path = os.path.join(vocab_dir, "words_ar.tsv")
    if os.path.exists(words_ar_path):
        with open(words_ar_path, "r", encoding="utf-8") as f:
            for line in f:
                parts = line.rstrip("\n").split("\t")
                if len(parts) >= 2:
                    try:
                        freq = int(parts[1])
                    except ValueError:
                        continue
                    norm = normalize_stem_vocab_word(parts[0])
                    if norm:
                        vocab[norm] = max(vocab.get(norm, 0), freq)

    tier_map = {"1": 60000, "2": 15000, "3": 4000}
    jordanian_path = os.path.join(vocab_dir, "jordanian.tsv")
    if os.path.exists(jordanian_path):
        with open(jordanian_path, "r", encoding="utf-8") as f:
            for line in f:
                parts = line.rstrip("\n").split("\t")
                if len(parts) >= 2 and parts[1] in tier_map:
                    freq = tier_map[parts[1]]
                    norm = normalize_stem_vocab_word(parts[0])
                    if norm:
                        vocab[norm] = max(vocab.get(norm, 0), freq)

    return vocab

def arabic_stem_key(word: str, vocab: dict[str, int]) -> str:
    """Identical to ArabicContext.kt key function."""
    word_freq = vocab.get(normalize_stem_vocab_word(word), 0)
    for prefix in PREFIXES_ARABIC:
        if word.startswith(prefix):
            candidate = word[len(prefix):]
            if len(candidate) >= 3:
                candidate_freq = vocab.get(normalize_stem_vocab_word(candidate), 0)
                if candidate_freq >= 300:
                    single_letter = (len(prefix) == 1)
                    if not single_letter or candidate_freq >= 3 * max(1, word_freq):
                        return candidate
    return word

def aggregate_stems(input_file: str, vocab: dict[str, int], lang: str, out_dir: str, min_next_total: int):
    # Groups: stem_key -> {
    #     'source_contexts': set(),
    #     'has_stripped': False,
    #     'original_row_totals_sum': 0,
    #     'followers': {follower: count}
    # }
    groups: dict[str, dict] = {}

    with open(input_file, 'r', encoding='utf-8') as f:
        for line in f:
            line = line.rstrip('\n')
            if not line:
                continue
            parts = line.split('\t')
            if len(parts) < 3:
                continue
            ctx = parts[0]
            if ctx == '<s>' or ' ' in ctx:
                continue
            try:
                row_total = int(parts[1])
            except ValueError:
                continue

            follower_counts = {}
            for item in parts[2:]:
                if not item:
                    continue
                w, _, c = item.rpartition(':')
                try:
                    follower_counts[w] = int(c)
                except ValueError:
                    continue

            k = arabic_stem_key(ctx, vocab)
            grp = groups.setdefault(k, {
                'source_contexts': set(),
                'has_stripped': False,
                'original_row_totals_sum': 0,
                'followers': {}
            })
            grp['source_contexts'].add(ctx)
            if k != ctx:
                grp['has_stripped'] = True
            grp['original_row_totals_sum'] += row_total
            for w, c in follower_counts.items():
                grp['followers'][w] = grp['followers'].get(w, 0) + c

    filtered_groups = {}
    for k, grp in groups.items():
        if len(grp['source_contexts']) >= 2 and grp['has_stripped']:
            filtered_groups[k] = grp

    counts_dict = {k: grp['followers'] for k, grp in filtered_groups.items()}
    # format_table prunes min_word_count=2, min_total=min_next_total, max_rows=60000
    rows = []
    for ctx, word_map in counts_dict.items():
        # Total in output row is original full row totals sum
        total = filtered_groups[ctx]['original_row_totals_sum']
        if total < min_next_total:
            continue
        valid_words = [(w, c) for w, c in word_map.items() if c >= 2]
        if not valid_words:
            continue
        valid_words.sort(key=lambda x: (-x[1], x[0]))
        top_words = valid_words[:12]
        words_str = '\t'.join(f'{w}:{c}' for w, c in top_words)
        rows.append((total, f'{ctx}\t{total}\t{words_str}\n'))

    rows.sort(key=lambda x: -x[0])
    if len(rows) > 60000:
        rows = rows[:60000]

    os.makedirs(out_dir, exist_ok=True)
    out_path = os.path.join(out_dir, f"stem_next_{lang}.tsv")
    with open(out_path, 'w', encoding='utf-8', newline='\n') as f:
        f.writelines(r[1] for r in rows)
    print(f"Wrote {len(rows)} stem rows to {out_path} ({os.path.getsize(out_path)} bytes)", flush=True)

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
    parser.add_argument("--aggregate-stems-from", type=str, default="", help="TSV file with 1-word contexts to aggregate by Arabic stem")
    parser.add_argument("--stem-vocab-dir", type=str, default="", help="Directory containing words_ar.tsv and jordanian.tsv")
    args = parser.parse_args()

    if args.aggregate_stems_from or args.stem_vocab_dir:
        if not (args.aggregate_stems_from and args.stem_vocab_dir):
            sys.exit("Error: --aggregate-stems-from and --stem-vocab-dir must be provided together.")
        if args.lang not in ("ar", "jo"):
            sys.exit(f"Error: --lang must be 'ar' or 'jo' for stem aggregation, got '{args.lang}'.")
        if os.path.basename(args.aggregate_stems_from) != f"next_{args.lang}.tsv":
            sys.exit("Error: stem aggregation requires the language's training next_<lang>.tsv, never evaluation TSVs.")
        if not os.path.isfile(args.aggregate_stems_from):
            sys.exit(f"Error: input file {args.aggregate_stems_from} does not exist.")
        if not os.path.isdir(args.stem_vocab_dir):
            sys.exit(f"Error: vocab dir {args.stem_vocab_dir} does not exist.")
        vocab = load_stem_vocab(args.stem_vocab_dir)
        print(f"Loaded {len(vocab)} vocabulary entries for Arabic stem aggregation.")
        aggregate_stems(args.aggregate_stems_from, vocab, args.lang, args.out_dir, args.min_next_total)
        return

    repo_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
    exclude_set = load_exclusion_lines(repo_dir)
    print(f"Loaded {len(exclude_set)} exclusion lines from bench test files.")

    builder = NGramBuilder(args.lang, exclude_set)

    # Process extra-text files if any
    for spec in args.extra_text:
        extra_file, _, w = spec.partition(':')
        if os.path.basename(extra_file) in {'jo_tatoeba_fresh.txt', 'levantine_sister_heldout.txt'}:
            sys.exit(f'Error: {extra_file} is evaluation-only, never LM training')
        if extra_file.endswith('_touch_typos.tsv'):
            sys.exit(f"Error: {extra_file} is a benchmark touch typo dataset and must never be used for LM training")
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
