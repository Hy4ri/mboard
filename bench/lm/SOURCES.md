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

Generated full tables: 60,000 one-word rows and 300,000 two-word rows per language. `make_assets.py` retains 30,000 one-word rows, 60,000 two-word rows, and eight followers per row by default for the Android bundle. These are reproducible from fixed original `seeds/`, rather than the previous generation's target assets. English retains the original word-list coverage, refreshes frequency estimates onto its old frequency scale, adds observed whole contractions, and adds the separately validated SCOWL vocabulary described below. Arabic's base word-frequency list is unchanged.

## English spelling coverage

`seeds/scowl_en.txt` is a sorted, deduplicated union of SCOWL 2020.12.07 `final/{english,american,british,british_z}-words.*` through size70 plus `-upper.*` and `-abbreviations.*` through size60. Proper-name and variant lists are excluded. Lowercase then retain only `[a-z]{2,32}`. This produces121,701 spellings; seed SHA-256 `90557f784f1aaa1dce0c4243793590ca6169096d43fc8ce14ce83388ee6aaa35`. Source archive: https://downloads.sourceforge.net/wordlist/scowl-2020.12.07.tar.gz, SHA-256 `5587667caa20c4891390c2d42dbb4d5c4c3f41bee77af1457ece3ba23fb859cc`. Full upstream component notices are shipped in `app/src/main/assets/licenses/scowl-Copyright.txt`. Rebuild the seed with `python3 -I bench/lm/build_scowl_seed.py --source-dir <extracted-scowl>/final`.

`english_words()` merges this immutable spelling seed with the original vocabulary and contractions. New words observed in the training-only unigram table receive the same scaled corpus frequency as existing words; unobserved words receive frequency 1. This protects valid but rare words from forced replacement without claiming they are frequent completions. No benchmark corpus is used to select or score spellings. Run `python3 -I bench/lm/make_assets.py --only-english-words` to update only English vocabulary; repeated runs must be byte-identical. Current merged size is125,674. This is a spelling-coverage change, not evidence of a measured noise rate or universal name coverage.

## Arabic spelling protection

The ranked25,000-entry Arabic frequency list is unchanged. A separate `app/src/main/assets/known_forms_ar.txt` guards valid morphological surface forms from destructive replacement, without becoming completion/edit targets or frequency evidence. Source: CAMeL-Lab/camel_morph MSA LREC-COLING2024 release at revision `15f5aede4b609db54abd6f87aded1f5ec5d930af`, database `official_releases/lrec-coling2024_release/databases/camel-morph-msa/camel_morph_msa_v1.0.db`, SHA-256 `110e03baa282874062d076e23d9671e985b6f76520b891d58cba4e55de0b8f60`.

Data is CC BY4.0, confirmed in the pinned upstream and release READMEs; upstream code is MIT, not the data license. Authors: Christian Khairallah, Salam Khalifa, Reham Marzouk, Mayar Mohamadein Nassar and Nizar Habash (CAMeL Lab, NYU Abu Dhabi). Citation: Camel Morph MSA: A Large-Scale Open-Source Morphological Analyzer for Modern Standard Arabic, LREC-COLING2024, https://aclanthology.org/2024.lrec-main.240.pdf. Full attribution and license ship in `NOTICE.txt` and `licenses/camel-morph-CC-BY-4.0.txt`. No endorsement is implied.

`build_arabic_forms.py` fetches hash-pinned, unmodified upstream parser/generator modules and the official database. It asks the generator for compatible inflections of attested noun/adjective/verb and functional lexemes, with unspecified clitics defaulting to zero. It omits proper names/backoff POS classes, removes U+064B..U+065F/U+0670/U+0640, maps U+0671 to alef, and retains only U+0621..U+064A forms of length2..32. It does not synthesize unrestricted affix products or use benchmark examples to select forms. Runtime supports the existing limited clitic decomposition against these forms. The ordinary upstream generator path is used because its optional diac_only path has a broken locals() lookup.

Rebuild: `uv run --with six==1.17.0 python -I bench/lm/build_arabic_forms.py --cache-dir <scratch-cache>`. The85MB source database and Python dependency are generator-only and are not shipped. Output:429,720 sorted unique forms (5,548,066 UTF-8 bytes) from33,592 lemmas/3,134,967 analyses; SHA-256 `c6b97dfdb60cf1ca210fb930ee69c6fd13552d75e484a56ba0c6b1b5e3328dd1`. Runtime stores one compact sorted string, not hundreds of thousands of separate objects. Source-backed preservation is not proof of every dialect spelling, prediction coherence or complete Arabic lexical coverage; replay and device gates remain separate.

## Small runtime spelling supplements

The unchanged TSV asset counts above exclude `Language.supplementalWords`: a small source-honest runtime overlay with lexical score1 (not an invented corpus frequency). English respawn is independently curated from the observed gaming usage; it was absent from the downloaded SCOWL lists and training unigram table, so is not falsely attributed to them. Arabic آراء and مرفقات are directly attested in the pinned CAMeL surface-form output; المرفقات is the productive definite form of that noun. CAMeL-derived excerpts retain the CC BY4.0 attribution/license above. Supplements already present in an asset never replace its observed frequency.

The round7 closing additions are likewise spelling-only: إخطار and إفراغ occur in the pinned CAMeL forms and training unigram table (110/202 observations); التجميع is the definite form of listed تجميع and has154 training observations; تعاملك has276 observations, with لتعاملك productively attaching ل. These are selected canonical vocabulary, not typo exceptions or a wholesale import of the429k preservation forms. None receives an invented frequency: runtime score1 remains explicit. The curated dialect seed adds باكيت and الباكيت at tier3 (باكيت appears once in synthetic jo_train_3.txt); tier3 is a policy tier, not three observed uses. The generator's resulting5013-entry vocabulary is semantically checked against the packaged file.

The supplemental set is distinct from keep-as-typed policy: npm/Romex/كويز/سيفتي are preserved without supplying fictional corpus counts. A supplement-only score1 cannot drive Arabic automatic normalization. Manually pickable transposition recovery for a curated spelling (resapwn/respawn), contractions such as shed/she'd, and reserving an observed literal completion such as croch/crochet affect strip choices, not the automatic replacement thresholds. Per-case developer probes are regression evidence, not held-out generalization claims; rerun the full replay against the frozen prior engine to measure collateral effects.

## Synthetic Jordanian augmentation

Independent LLM subagents wrote training messages without reading the benchmark sentences or old dialect tables. These are synthetic, not measured human usage. After exact-line test exclusion:
- jo_train_1.txt: 1,496 lines (family/greetings/plans)
- jo_train_2.txt: 1,496 lines (work/university/errands)
- jo_train_3.txt: 1,495 lines (friends/banter/weather)
- jo_train_4a.txt: 500 lines (family)
- jo_train_4b.txt: 500 lines (home/neighbourhood)
- jo_train_4c.txt: 497 lines (health/occasions)

The cleaned Shami text contributes weight 3; synthetic lines contribute weight 1. Rebuild excludes all exact benchmark sentences again. Original hand-curated `jordanian_next.tsv` rows (immutable seed) add synthetic rank-based counts, not measured observations. The resulting dialect asset combines one- and two-word contexts in the same file; vocabulary tiers combine the curated words and corpus occurrences. Synthetic text is project-authored augmentation; Shami-derived material remains separately attributed under its repository's Apache-2.0 license. Benchmark-only Tatoeba/Birkbeck data is never fed into this training pipeline.

## Arabic stem-context aggregation (experiment, NOT shipped)

Two runtime variants were measured and rejected (see bench/reports/quality/research-gaps/execution.md); the tables are not bundled. The tooling is kept for reproduction.

Deterministic backoff aggregation groups one-word context rows from existing training intermediates (`out_ar/next_ar.tsv` and `out_jo_split/next_jo.tsv`) under a shared stripped stem key. Only contexts with at least two distinct source contexts and at least one stripped source context are retained, preventing redundant single-form duplicates.

Commands:
```bash
# MSA stem aggregation from retained training table:
python3 -I bench/lm/build_ngrams.py --lang ar \
  --aggregate-stems-from bench/lm/out_ar/next_ar.tsv \
  --stem-vocab-dir app/src/main/assets \
  --out-dir bench/lm/out_ar

# Jordanian dialect stem aggregation (split-trained out_jo_split only; never full out_jo):
python3 -I bench/lm/build_ngrams.py --lang jo \
  --aggregate-stems-from bench/lm/out_jo_split/next_jo.tsv \
  --stem-vocab-dir app/src/main/assets \
  --out-dir bench/lm/out_jo_split

# Copy/prune stem tables into keyboard assets:
python3 -I bench/lm/make_assets.py --only-arabic-stem \
  --stem-source-ar bench/lm/out_ar \
  --stem-source-jo bench/lm/out_jo_split
```

Limitations:
- Lexical heuristic, not a full morphological segmenter or CODA analyzer.
- Strips at most one prefix in longest-first order; stem length must be >= 3 and frequency >= 300.
- Single-letter prefixes require candidate frequency >= 3 * max(1, word frequency).
- Retained-count aggregation uses existing training intermediates, not a full corpus retrain. No evaluation sentences or follower counts are ingested. Key-frequency guards use the unchanged bundled lexicon; its Jordanian vocabulary still includes full-Shami spelling coverage. These are tuning replays, not blind unseen-language evidence.


## Tiny neural reranker experiment (card 299, OFF)

`train_reranker.py` and `TrainReranker.java` train tied-embedding, two-word-context
feed-forward scorers from the training-only `unigram_`, `next_`, and `context_`
count intermediates above. English/Arabic use the documented excluded subtitle
windows and evaluation-line exclusions. Jordanian uses `out_jo_split` only, never
full-Shami `out_jo`. No benchmark text is a training input. The model derivations
retain the source attribution and data-license terms above; they have no new
corpus license. No binary model is bundled in the app in this experiment.

See `../reports/neural-reranker-299.md` for fixed-seed training, the input/output
SHA-256 manifest, full quality results, manual-target losses/gains, size and latency.
The feature remains OFF for review. The first candidate lost manual typo targets;
the final candidate limits neural completion scoring to one-letter prefixes and
preserves every measured typo row. Small old-set prediction declines remain.
