#!/usr/bin/env python3
"""Prune an immutable generated dialect continuation TSV, never vocabulary."""
import argparse
import subprocess
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--input', required=True)
parser.add_argument('--output', required=True)
parser.add_argument('--min-total', type=int, required=True)
parser.add_argument('--min-follower', type=int, required=True)
parser.add_argument('--width', type=int, required=True)
parser.add_argument('--baseline-revision', default='')
parser.add_argument('--max-updates', type=int, default=0)
a = parser.parse_args()
assert Path(a.input).resolve() != Path(a.output).resolve(), 'Input must be immutable'
assert a.min_total > 0 and a.min_follower > 0 and a.width > 0
kept = []
for line in Path(a.input).read_text().splitlines():
    parts = line.split('\t')
    context, total = parts[:2]
    if int(total) < a.min_total and context != '<s>':
        continue
    followers = [x for x in parts[2:] if int(x.rsplit(':', 1)[1]) >= a.min_follower][:a.width]
    if followers:
        # Preserve observed context totals and source order, not a fabricated renormalized total.
        kept.append('\t'.join([context, total, *followers]))
if a.baseline_revision:
    assert a.max_updates > 0
    # An immutable Git source is the low-count fallback, never the previous generated output.
    baseline = subprocess.check_output([
        'git', 'show', f'{a.baseline_revision}:app/src/main/assets/jordanian_next.tsv'
    ], cwd=Path(__file__).resolve().parents[2]).decode().splitlines()
    rows = {line.split('\t', 1)[0]: line for line in baseline}
    for line in kept[:a.max_updates]:
        rows[line.split('\t', 1)[0]] = line
    kept = list(rows.values())
Path(a.output).write_text('\n'.join(kept)+'\n')
print(f'{len(kept)} rows; {Path(a.output).stat().st_size} bytes')
