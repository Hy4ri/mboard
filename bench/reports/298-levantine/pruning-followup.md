# Clean APK size and pruning follow-up

## Decision

Keep the full `crew298-half` continuation candidate. A clean APK grows by only
**208,744 bytes**, below the requested **600 KB** budget. Direct pruning loses
human-set coverage. Bounded updates retain less of the human gains. Neither is
needed to meet the size budget.

**Correction:** the earlier +1,782,278-byte headline was invalid as a content-size
comparison. Incremental APK packaging retained free space. Different pruned tables
produced APKs of exactly the same length even though their packaged binary assets
differed. Clean builds remove this effect. Use `:app:clean assembleDebug` for size
comparisons, not incremental `assembleDebug`.

All clean size measurements use this branch's same code, resources and updated
NOTICE. The baseline changes only the continuation TSV to its pinned original
contents. Each packaged binary was checked byte-for-byte against its frozen replay
asset overlay. This isolates continuation growth; the credit-only baseline APK is
168 bytes larger than the earlier incremental control receipt.

## Size / gain curve

Deltas are percentage points against `crew298-base`. Human and Tatoeba columns
are next3 / found-before-end / idealized savings. Clean baseline: **19,261,637 bytes**.

| Level | Rule | Rows | Clean APK bytes | Growth | Human gains | Tatoeba gains | Decision |
|---|---|---:|---:|---:|---|---|---|
| half | Full 6:1:2, top8, generation min-total6 | 86,020 | 19,470,381 | +208,744 | +0.66 / +0.50 / +0.31 | +1.08 / +0.19 / +0.32 | Selected |
| p8 | min-total8, follower>=2, top6 | 27,922 | 18,451,361 | -810,276 | +0.21 / -0.05 / -0.12 | +1.08 / +0.19 / +0.32 | Reject: human regression |
| p12 | min-total12, follower>=3, top4 | 19,636 | 18,203,069 | -1,058,568 | -0.06 / -0.67 / -0.59 | +1.08 / +0.19 / +0.31 | Reject: human/chat regression |
| p18 | min-total18, follower>=4, top4 | 11,326 | 18,023,249 | -1,238,388 | -0.46 / -1.00 / -0.83 | +0.81 / +0.00 / +0.27 | Reject: human/chat regression |
| u1500 | Top1500 regenerated updates; retain immutable baseline elsewhere | 82,552 | 19,275,649 | +14,012 | +0.32 / +0.20 / +0.06 | +0.95 / +0.19 / +0.27 | Reject: weaker gains/chat regression |
| u6000 | Top6000 regenerated updates; retain immutable baseline elsewhere | 82,938 | 19,343,761 | +82,124 | +0.50 / +0.39 / +0.21 | +1.35 / +0.19 / +0.36 | Not needed: weaker human gains |

The three direct pruning levels completed full replays. Their automatic counts,
autofix/wrong-fix rates, and EN/AR TSVs remain identical (`compare.py` exited 0).
The two bounded-update alternatives used complete rows from four dialect sentence
sets, plus the two existing dialect typo sets. They were screened only; no claim
of a full EN/AR run is made for these rejected alternatives.

The bounded-update mode uses the immutable baseline asset from Git revision
`be17dc98692fbf8f1fafc277b9734c0b925cf799`, not previously generated target assets.
It is not selected and is not part of the shipped generation pipeline.

## Reproduction and receipts

All commands ran under the shared bench lock, exit **0**:

```sh
flock /tmp/crew-mboard/bench.lock nix develop -c bash bench/reports/298-levantine/run-prune-curve.sh
flock /tmp/crew-mboard/bench.lock nix develop -c bash bench/reports/298-levantine/run-update-curve.sh
flock /tmp/crew-mboard/bench.lock nix develop -c bash bench/reports/298-levantine/measure-clean-sizes.sh
```

`run-prune-curve.sh` records the first incremental size pass, which must not be used
for comparison. `measure-clean-sizes.sh` supplies the authoritative clean sizes.
The bounded-update script uses clean builds directly. `prune_298.py` is an optional
research helper; it never edits vocabulary and is not used for the selected asset.
Individual level summaries and comparison JSONs are retained beside this report.

The selected asset, training weights, full-replay metrics and six-lost/six-gained
manual-target tradeoff remain exactly as reported in the original phase.
