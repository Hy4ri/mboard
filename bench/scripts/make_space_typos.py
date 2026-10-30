#!/usr/bin/env python3
"""Build data/en_space_typos.tsv: missed-space typos made from clean en_fresh sentences.

Each row takes two adjacent words and either joins them ("fix it" -> "fixit", kind omit) or replaces the
space with a key that sits above the space bar ("fix it" -> "fixxit", kind stray). The target is the pair
"fix it"; the context is the words before it. Deterministic for a given seed.
Usage: python3 -I scripts/make_space_typos.py [--seed 333] [--per-kind 500]
"""
import argparse
import os
import random
import re

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(HERE, "..", "data")
STRAY = "xcvbnm"
WORD = re.compile(r"^[a-z]+$")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--seed", type=int, default=333)
    ap.add_argument("--per-kind", type=int, default=500)
    args = ap.parse_args()
    rng = random.Random(args.seed)
    pairs = []
    with open(os.path.join(DATA, "en_fresh.txt"), encoding="utf-8") as f:
        for line in f:
            words = line.strip().split()
            for i in range(len(words) - 1):
                left, right = words[i].lower(), words[i + 1].lower()
                if not (WORD.match(left) and WORD.match(right)):
                    continue
                if len(right) < 2 or (len(left) < 2 and left not in ("a", "i")):
                    continue
                context = " ".join(w.lower() for w in words[max(0, i - 4):i])
                pairs.append((context, left, right))
    rng.shuffle(pairs)
    rows = []
    for n, (context, left, right) in enumerate(pairs[: args.per_kind * 2]):
        if n < args.per_kind:
            rows.append((context, left + right, f"{left} {right}", "omit"))
        else:
            rows.append((context, left + rng.choice(STRAY) + right, f"{left} {right}", "stray"))
    with open(os.path.join(DATA, "en_space_typos.tsv"), "w", encoding="utf-8") as out:
        out.write("context\ttyped\tintended\tkind\n")
        for r in rows:
            out.write("\t".join(r) + "\n")
    print(len(rows), "rows")


if __name__ == "__main__":
    main()
