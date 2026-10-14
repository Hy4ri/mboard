#!/usr/bin/env python3
"""Turn the n-gram builds in out_{en,ar,jo}/ into the keyboard's assets.

  next_<code>.tsv, context_<code>.tsv  `<context>\t<total>\t<word>:<count>...` (counts kept for probabilities)
  jordanian_next.tsv                   Jordanian one- and two-word contexts in the same format, plus the
                                       hand-curated rows of the old file
  jordanian.tsv                        `word\ttier` dialect vocabulary: old curated list + corpus words
  words_en.tsv                         frequencies refreshed from the corpus; contractions kept whole

Usage: python3 -I bench/lm/make_assets.py [--assets DIR] [--next-rows N] [--context-rows N] [--width N]
"""
import argparse
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
SEEDS = os.path.join(HERE, "seeds")


def read_rows(path, limit, width):
    out = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 3:
                continue
            out.append((parts[0], int(parts[1]), parts[2 : 2 + width]))
            if len(out) >= limit:
                break
    return out


def write_rows(path, rows):
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        for ctx, total, words in rows:
            f.write(ctx + "\t" + str(total) + "\t" + "\t".join(words) + "\n")


def key(word):
    w = word.lower()
    return "".join(c for c in w if not ("\u064b" <= c <= "\u065f" or c in "\u0670\u0640'\u2019"))


def old_curated(path):
    """Old jordanian_next.tsv rows (`ctx\tw1\tw2...`, no counts) as synthetic counts: rank 1 = 30, then fewer."""
    rows = {}
    if not os.path.exists(path):
        return rows
    with open(path, encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 2 or parts[1].isdigit():
                continue
            rows[key(parts[0])] = {w: max(30 - 4 * i, 6) for i, w in enumerate(parts[1:]) if w}
    return rows


def jordanian_next(assets, width, source_dir=None):
    rows = {}
    for name, limit in (("next_jo.tsv", 30000), ("context_jo.tsv", 60000)):
        for ctx, total, words in read_rows(os.path.join(source_dir or os.path.join(HERE, "out_jo"), name), limit, width):
            counts = {}
            for item in words:
                w, _, c = item.rpartition(":")
                counts[w] = int(c)
            rows[ctx] = [total, counts]
    for ctx, counts in old_curated(os.path.join(SEEDS, "jordanian_next.tsv")).items():
        row = rows.setdefault(ctx, [0, {}])
        for w, c in counts.items():
            row[1][w] = row[1].get(w, 0) + c
            row[0] += c
    out = []
    for ctx, (total, counts) in rows.items():
        best = sorted(counts.items(), key=lambda x: -x[1])[:width]
        out.append((ctx, total, [f"{w}:{c}" for w, c in best]))
    out.sort(key=lambda r: -r[1])
    return out


def jordanian_vocabulary(assets):
    tiers = {}
    path = os.path.join(SEEDS, "jordanian.tsv")
    with open(path, encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) == 2 and parts[1] in ("1", "2", "3"):
                tiers[parts[0]] = int(parts[1])
    curated = len(tiers)
    with open(os.path.join(HERE, "out_jo", "unigram_jo.tsv"), encoding="utf-8") as f:
        for line in f:
            w, c = line.rstrip("\n").split("\t")
            c = int(c)
            # Shami counts are weighted x3; 12 means at least four real uses.
            tier = 1 if c >= 300 else 2 if c >= 60 else 3 if c >= 12 else 0
            if tier and len(w) >= 1 and re.fullmatch(r"[\u0621-\u064a]+", w):
                tiers[w] = min(tiers.get(w, 9), tier)
    return curated, sorted(tiers.items(), key=lambda x: (x[1], x[0]))


def english_words(assets):
    """Old 25k list with corpus frequencies; split contraction halves drop, whole contractions join."""
    old = []
    with open(os.path.join(SEEDS, "words_en.tsv"), encoding="utf-8") as f:
        for line in f:
            w, c = line.rstrip("\n").split("\t")
            old.append((w, int(c)))
    uni = {}
    order = []
    with open(os.path.join(HERE, "out_en", "unigram_en.tsv"), encoding="utf-8") as f:
        for line in f:
            w, c = line.rstrip("\n").split("\t")
            uni[w.lower()] = int(c)
            order.append(w)
    # Keep the dictionary's frequency scale so absolute thresholds in WordDictionary still mean the same.
    shared = [w for w, _ in old if w in uni]
    scale = sum(c for w, c in old if w in uni) / max(1, sum(uni[w] for w in shared))
    out = {}
    for w, c in old:
        out[w] = round(uni[w] * scale) if w in uni else max(1, c // 4)
    added = 0
    for w in order:
        lw = w.lower()
        if "'" in lw and lw not in out and uni[lw] * scale >= 2000 and re.fullmatch(r"[a-z]+'[a-z]+", lw):
            # I'm, I'll keep their capital; other contractions are lowercase like the rest of the list.
            out[w if w.lower().startswith("i'") else lw] = round(uni[lw] * scale)
            added += 1
    rows = sorted(out.items(), key=lambda x: -x[1])
    return added, scale, rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--assets", default=os.path.join(REPO, "app", "src", "main", "assets"))
    ap.add_argument("--next-rows", type=int, default=30000)
    ap.add_argument("--context-rows", type=int, default=60000)
    ap.add_argument("--width", type=int, default=8)
    ap.add_argument("--dialect-source", default=os.path.join(HERE, "out_jo"))
    ap.add_argument("--only-jordanian-next", action="store_true", help="Refresh continuation counts only; preserve lexical spelling guards")
    a = ap.parse_args()
    if a.only_jordanian_next:
        jo = jordanian_next(a.assets, a.width, a.dialect_source)
        write_rows(os.path.join(a.assets, "jordanian_next.tsv"), jo)
        print(f"jordanian_next rows {len(jo)}; lexical assets unchanged")
        return
    for code in ("en", "ar"):
        write_rows(os.path.join(a.assets, f"next_{code}.tsv"), read_rows(os.path.join(HERE, f"out_{code}", f"next_{code}.tsv"), a.next_rows, a.width))
        write_rows(
            os.path.join(a.assets, f"context_{code}.tsv"),
            read_rows(os.path.join(HERE, f"out_{code}", f"context_{code}.tsv"), a.context_rows, a.width),
        )
    jo = jordanian_next(a.assets, a.width, a.dialect_source)
    curated, vocab = jordanian_vocabulary(a.assets)
    write_rows(os.path.join(a.assets, "jordanian_next.tsv"), jo)
    with open(os.path.join(a.assets, "jordanian.tsv"), "w", encoding="utf-8", newline="\n") as f:
        for w, t in vocab:
            f.write(f"{w}\t{t}\n")
    added, scale, words = english_words(a.assets)
    with open(os.path.join(a.assets, "words_en.tsv"), "w", encoding="utf-8", newline="\n") as f:
        for w, c in words:
            f.write(f"{w}\t{c}\n")
    print(f"jordanian_next rows {len(jo)}, jordanian vocab {len(vocab)} (curated {curated})")
    print(f"words_en {len(words)} (+{added} contractions, scale {scale:.4f})")


if __name__ == "__main__":
    main()
