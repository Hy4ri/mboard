# Shami Human Conversational Validation Provenance & Attribution

## Summary
- Target validation file: `bench/data/jo_human_fresh.txt`
- Split training file: `bench/lm/jo_shami_train.txt`
- Split manifest: `bench/lm/shami_split_manifest.json`
- Generation script: `bench/lm/prepare_shami_holdout.py`
- Upstream source: GU-CLASP/shami-corpus `Data/jordinian.txt` (via cleaned `bench/lm/jo_shami.txt`)
- Source license: Apache License 2.0 (GU-CLASP/shami-corpus)
- Citation:
  Kathrein Abu Kwaik, Motaz Saad, Stergios Chatzikyriakidis, and Simon Dobnik. 2018.
  "Shami: A Corpus of Levantine Arabic Dialects."
  In Proceedings of the Eleventh International Conference on Language Resources and Evaluation (LREC 2018), Miyazaki, Japan.
  European Language Resources Association (ELRA). https://aclanthology.org/L18-1576.pdf

## Group Partitioning Methodology
- Contiguous block grouping: The original cleaned `bench/lm/jo_shami.txt` (6,964 lines) was segmented into contiguous blocks of 50 original cleaned lines.
  *Note*: This contiguous grouping approximates conversational thread / discourse locality; these blocks are not guaranteed to be exact document boundaries.
- Deterministic assignment: Each block was assigned to held-out validation when:
  `int(sha256(f"{seed}:{group_text}".encode("utf-8")).hexdigest(), 16) % 5 == 0` (seed=57485).
- Block integrity: All groups assigned to held-out are strictly excluded from the training split `jo_shami_train.txt`. Even lines within held-out groups that are filtered out of the validation file (e.g. >40 words) remain excluded from training. Training groups are never used to replenish validation counts.

## Filtering & Disjointness Criteria
1. Train set (`bench/lm/jo_shami_train.txt`):
   - Contains all lines from non-heldout groups.
   - Strictly excludes any normalized overlaps against existing evaluation sets (`bench/data/*.txt` excluding fresh sets).
2. Held-out validation set (`bench/data/jo_human_fresh.txt`):
   - Contains candidate lines from held-out groups only.
   - Filtered to natural sentence lengths: 2 to 40 words.
   - Content hygiene: Verified free of Latin characters, URLs, HTML markup, emoji, and numeric/punctuation-only content.
   - Strictly excludes exact or normalized matches against old evaluation benchmarks in `bench/data/*.txt`.
   - Deduplicated on normalized form, preserving original order.
   - Disjointness from training set: Verified 0 verbatim and 0 normalized overlaps with `jo_shami_train.txt`.

## Critical Limitations & Non-Claims
1. **Prior Exposure in Installed Model**: The full `bench/lm/jo_shami.txt` corpus was ALREADY used to train the previous installed model assets. Therefore, evaluating the old installed model against `jo_human_fresh.txt` does **NOT** represent an unseen benchmark. Future model comparisons must train both baseline control and candidate models using `jo_shami_train.txt` (or explicitly note prior exposure).
2. **Lexical vs Continual Evaluation**: Existing general word lexicons and dictionaries may already contain vocabulary words present in this validation set. This benchmark evaluates held-out conversational message continuations and next-word prediction quality, not out-of-vocabulary generalization.
3. **Approximated Thread Independence**: Contiguous blocks of 50 lines only approximate conversation-thread independence, as explicit metadata or conversation IDs are not present in the upstream flat text file.
4. **Sealed Benchmark**: Benchmark harnesses and developers must evaluate against this file without inspecting per-sentence engine prediction failures for ad-hoc fine-tuning.
