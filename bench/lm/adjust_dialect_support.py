#!/usr/bin/env python3
"""Reweight follower collisions that come from one weighted Jordanian training line."""

import argparse
from collections import Counter, defaultdict
from pathlib import Path

from build_ngrams import WORD_OR_BOUNDARY, is_subtitle_junk, load_exclusion_lines, normalize_key, surface
from make_assets import old_curated

HERE = Path(__file__).resolve().parent
TRAINING = {
    "jo_shami_train.txt": 6,
    "jo_sister_train.txt": 1,
    **{f"jo_train_{part}.txt": 2 for part in ("1", "2", "3", "4a", "4b", "4c")},
}
BOUNDARIES = frozenset(".!?؟")


def line_evidence(followers):
    excluded = load_exclusion_lines(str(HERE.parents[1]))
    normalized_lines = Counter()
    for source, weight in TRAINING.items():
        with (HERE / source).open(encoding="utf-8") as corpus:
            for raw in corpus:
                line = raw.strip()
                if line in excluded or is_subtitle_junk(line, "jo"):
                    continue
                tokens = tuple(map(surface, WORD_OR_BOUNDARY.findall(line)))
                if tokens:
                    normalized_lines[tokens] += weight

    evidence = defaultdict(Counter)
    for line_id, (tokens, weight) in enumerate(normalized_lines.items()):
        previous = None
        for word in tokens:
            if word in BOUNDARIES:
                previous = None
                continue
            key = normalize_key(word)
            if not key:
                continue
            if word in followers.get(previous, ()):
                evidence[previous, word][line_id] += weight
            previous = key
    return evidence


def adjust(source: Path, output: Path):
    with source.open(encoding="utf-8") as table:
        rows = [line.rstrip("\n").split("\t") for line in table]
    followers = {
        row[0]: {cell.rsplit(":", 1)[0] for cell in row[2:]}
        for row in rows
        if " " not in row[0] and row[0] != "<s>"
    }
    evidence = line_evidence(followers)
    curated = old_curated(HERE / "seeds/jordanian_next.tsv")
    changed_rows = 0
    changed_followers = 0
    for row in rows:
        ctx = row[0]
        if ctx not in followers or not any(len(evidence.get((ctx, w), ())) >= 2 for w in followers[ctx]):
            continue
        singles_by_line = defaultdict(list)
        counts = {word: int(count) for word, count in (cell.rsplit(":", 1) for cell in row[2:])}
        for word, count in counts.items():
            support = evidence.get((ctx, word), {})
            if word in curated.get(ctx, {}) or len(support) != 1:
                continue
            line_id, contribution = next(iter(support.items()))
            if count == contribution:
                singles_by_line[line_id].append(word)
        removed = 0
        for words in singles_by_line.values():
            if len(words) < 2:
                continue
            # When a line supplies competing singletons, keep each observation without its replication weight.
            share = 1
            for word in words:
                if counts[word] > share:
                    removed += counts[word] - share
                    counts[word] = share
                    changed_followers += 1
        if removed:
            changed_rows += 1
            row[1] = str(int(row[1]) - removed)
            row[2:] = [f"{w}:{n}" for w, n in sorted(counts.items(), key=lambda entry: -entry[1])]
    with output.open("w", encoding="utf-8") as table:
        table.writelines("\t".join(row) + "\n" for row in rows)
    print(f"Reweighted {changed_followers} followers in {changed_rows} of {len(followers)} one-word rows")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    if args.baseline.resolve() == args.output.resolve():
        parser.error("baseline and output must be different files")
    adjust(args.baseline, args.output)


if __name__ == "__main__":
    main()
