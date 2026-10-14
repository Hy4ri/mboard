# Language Model Data Sources

## Shami Corpus (Jordanian Dialect)
- Source: GU-CLASP/shami-corpus (GitHub), file `Data/jordinian.txt`
- URL: https://raw.githubusercontent.com/GU-CLASP/shami-corpus/master/Data/jordinian.txt
- License: Apache License 2.0 (GU-CLASP/shami-corpus)
- Citation:
  Kathrein Abu Kwaik, Motaz Saad, Stergios Chatzikyriakidis, and Simon Dobnik. 2018.
  "Shami: A Corpus of Levantine Arabic Dialects."
  In Proceedings of the Eleventh International Conference on Language Resources and Evaluation (LREC 2018), Miyazaki, Japan.
  European Language Resources Association (ELRA). https://aclanthology.org/L18-1576.pdf
- Processing:
  - Fetched raw `Data/jordinian.txt` to `bench/lm/raw/shami_jordinian.txt` using `bench/lm/fetch_shami.py`.
  - Filtered to Arabic script only per line (dropped lines with Latin letters or non-Arabic characters).
  - Stripped harakat (U+064B..U+065F, U+0670) and tatweel (U+0640).
  - Stripped @mentions, URLs, and emoji.
  - Collapsed character repetitions >= 3 to 2.
  - Normalized whitespace.
  - Retained lines with >= 2 words.
  - Deduplicated lines while preserving order.
  - Exact-line exclusion against `bench/data/jo_chat.txt` (0 overlapping lines found).
- Statistics:
  - Raw lines: 7,018
  - Kept lines: 6,964
  - Word count: 122,070
  - Output file: `bench/lm/jo_shami.txt`

## Training tables and reproducibility

The earlier chunk-boundary leak was found during audit and the affected tables were discarded. The current builder ends compressed reads exactly at each range start, decompresses discarded bytes only to maintain gzip state, and counts only subsequent output. Rebuilt ranges (half-open compressed-byte offsets):
- English: [0,125000000) and [150000000,325000000), 300,000,000 counted compressed bytes.
- Arabic: [0,125000000) and [145000000,400000000), 380,000,000 counted compressed bytes.
- Held-out windows: English [125000000,150000000), Arabic [125000000,145000000). Exact benchmark lines are also excluded.

Upstream: OPUS OpenSubtitles2018 monolingual corpora, https://object.pouta.csc.fi/OPUS-OpenSubtitles/v2018/mono/{en,ar}.txt.gz. Attribution: P. Lison and J. Tiedemann (2016), OPUS/OpenSubtitles contributors and original subtitle authors; the derived assets follow the existing project's CC BY-SA 4.0 data licensing (not the generator-code license).

`build_ngrams.py` tokenizes whole contractions, keeps candidate apostrophes, and strips apostrophes only from context lookup keys to match the runtime. It stores row totals and follower counts, with singleton two-word-context pruning every 5 million accepted lines. Thus counts of pruned/rediscovered rare contexts are not complete-corpus counts. Compressed windows may split a line, and UTF-8 output is decoded per chunk with replacement on incomplete characters; edge fragments are a small remaining data limitation, not evidence that full test sentences leaked.

Generated full tables: 60,000 one-word rows and 300,000 two-word rows per language. `make_assets.py` retains 30,000 one-word rows, 60,000 two-word rows, and eight followers per row by default for the Android bundle. These are reproducible from fixed original `seeds/`, rather than the previous generation's target assets. English retains the original word-list coverage, refreshes frequency estimates onto its old frequency scale and adds observed whole contractions. Arabic's base word-frequency list is unchanged.

## Synthetic Jordanian augmentation

Independent LLM subagents wrote training messages without reading the benchmark sentences or old dialect tables. These are synthetic, not measured human usage. After exact-line test exclusion:
- jo_train_1.txt: 1,496 lines (family/greetings/plans)
- jo_train_2.txt: 1,496 lines (work/university/errands)
- jo_train_3.txt: 1,495 lines (friends/banter/weather)
- jo_train_4a.txt: 500 lines (family)
- jo_train_4b.txt: 500 lines (home/neighbourhood)
- jo_train_4c.txt: 497 lines (health/occasions)

The cleaned Shami text contributes weight 3; synthetic lines contribute weight 1. Rebuild excludes all exact benchmark sentences again. Original hand-curated `jordanian_next.tsv` rows (immutable seed) add synthetic rank-based counts, not measured observations. The resulting dialect asset combines one- and two-word contexts in the same file; vocabulary tiers combine the curated words and corpus occurrences. Synthetic text is project-authored augmentation; Shami-derived material remains separately attributed under its repository's Apache-2.0 license. Benchmark-only Tatoeba/Birkbeck data is never fed into this training pipeline.
