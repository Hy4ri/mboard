# Dialect Corpus Candidates for mboard (Research Gap 4b / Card #298)

This document investigates shippable Levantine/Jordanian Arabic corpora for the mboard keyboard.
The current dialect model (`jordanian_next.tsv`) is trained on:
1. `bench/lm/jo_shami_train.txt`: the Jordanian subset of the **Shami Corpus** (`GU-CLASP/shami-corpus`, Apache-2.0, ~6,964 clean lines, 122k words), split ~79% train / ~21% held-out.
2. Synthetic Jordanian chat augmentation (`jo_train_1.txt` through `jo_train_4c.txt`, CC0, 6,484 lines total).
3. Curated seed rows (`bench/lm/seeds/jordanian_next.tsv`).

**Constraint & Policy:**
All non-commercial or restrictive licenses (**MADAR**, **Curras** [CC BY-NC], **Baladi** [CC BY-NC-SA], **PADIC**) are strictly rejected.
Any corpus intended for shipping derived n-gram counts inside the application requires explicit **m57 sign-off on each license** before any data enters app assets or training tables.

---

## 1. Summary of Evaluated Candidates

| Candidate / Dataset | Exact Primary URL | License & Terms | Derivative / App Compatibility | Dialect Quality (Levantine/Jordanian) | Size & Yield | Recommendation |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Shami Corpus Sister Partitions (Syrian, Palestinian, Lebanese)** | `https://github.com/GU-CLASP/shami-corpus` (under `Data/`) | [Apache-2.0](https://raw.githubusercontent.com/GU-CLASP/shami-corpus/master/LICENSE) | **Permissive (Green)**. Same project/license as current `jordinian.txt`. Apache-2.0 explicitly permits commercial redistribution of derivative works with attribution notice. | **High Levantine**. Authentic web forums/blogs from Syria, Palestine, Lebanon. Very close to Jordanian continuum. | **59,230 lines**, ~4.15 MB text (~800k words total across all 3). 8.5x larger than current `jordinian.txt`. | **Rank 1 (Top Pick)**. Safest, cleanest, and highest quality dialect expansion. Ready to fetch & block-split. |
| **FineWeb-2 Levantine (`apc_Arab`)** | `https://huggingface.co/datasets/HuggingFaceFW/fineweb-2` (`data/apc_Arab/`) | [ODC-By v1.0](https://opendatacommons.org/licenses/by/1-0/) + Common Crawl Terms | **Uncertain / Gray**. Database is ODC-By, but underlying Common Crawl content retains original web authors' copyright. Safe for internal bench/embeddings; shipping derived frequency tables in a production app has mild legal friction. | **Mixed Levantine**. Contains high-quality colloquial Syrian/Lebanese forum posts, but heavily polluted with OCR artifacts, erotica, spam, and book metadata snippets. Requires heavy filtering. | ~50k web docs (~65 MB parquet), ~35M raw tokens. After strict conversational/profanity filtering: ~5-8M words. | **Rank 2 (Conditionally Recommended)**. High potential volume, but needs m57 legal sign-off on CC crawl derivatives + extensive cleaning pipeline. |
| **Tatoeba Sentences (`ajp` South Levantine / `apc` North Levantine)** | `https://downloads.tatoeba.org/exports/per_language/` (`ajp`, `apc`) | [CC BY 2.0 FR](https://creativecommons.org/licenses/by/2.0/fr/) | **Permissive with Attribution**. Compatible with shipping derivative tables provided Tatoeba contributors are credited. | **High Levantine**. Colloquial Jordanian/Palestinian (`ajp`) and Syrian/Lebanese (`apc`). | **Extremely Tiny**: Only **262 lines** for `ajp` and **49 lines** in Arabic script for `apc` (~311 lines total). | **Rank 3 (Held-Out Eval Candidate)**. Far too small to materially boost training n-grams, but valuable as a high-quality human evaluation test set. |
| **Arabic Wikipedia (`arwiki`)** | `https://dumps.wikimedia.org/arwiki/latest/` | [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/) | **ShareAlike Copyleft**. Requires CC BY-SA 4.0 for derived n-grams (mboard already ships CC BY-SA 4.0 word lists). | **Near Zero Dialect**. Almost exclusively Modern Standard Arabic (MSA / فصحى) formal prose. Minimal colloquial Levantine value. | Massive (2 GB compressed, 1.3M+ articles). | **Rejected for Dialect Training**. Excellent for general MSA n-grams, useless for Levantine dialect suggestions. |
| **Leipzig Corpora Collection (Arabic)** | `https://wortschatz.uni-leipzig.de/en/download/Arabic` | [CC BY-NC 4.0](https://huggingface.co/datasets/Texttechnologylab/leipzig-corpora-collection/blob/main/LICENSE) | **Non-Commercial (Red)**. Upstream LCC data is licensed under CC BY-NC 4.0. Shipping in an application or commercial reuse violates NC terms. | Mostly formal MSA news crawls from regional domains (e.g. Jordan, Lebanon). | 100k-1M sentences per year. | **Strictly Rejected**. Non-commercial license fails the shippable requirement. |
| **Common Voice Arabic Transcripts** | `https://datacollective.mozillafoundation.org/` | [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/) (Sentence Collector) | **Permissive (Public Domain)**. CC0 allows any derivation and commercial inclusion without attribution. | **Near Zero Dialect**. Sentences are crowdsourced/extracted from Wikipedia/Wikisource in formal MSA. No separate Levantine dialect transcripts. | Thousands of sentences. | **Rejected for Dialect Training**. Strictly MSA texts. |
| **OPUS Monolingual (`apc`, `ajp`)** | `https://opus.nlpl.eu/` | Varied (CC BY-SA, Open Data) | **Permissive / Open**. OpenSubtitles/TED corpora are already used for English and Arabic. | N/A: OPUS has **no** monolingual subtitle files for `apc` or `ajp` (HTTP 404). Only bilingual sentence alignments from Tatoeba and Translatewiki. | Negligible Levantine text (< 5,000 tokens). | **Rejected (No Data)**. No substantial dialect text exists in OPUS. |

---

## 2. In-Depth Candidate Analysis

### Candidate 1: Shami Corpus Sister Partitions (`syrian.txt`, `Palestinian.txt`, `Lebanees.txt`)
- **Exact URLs:**
  - `https://raw.githubusercontent.com/GU-CLASP/shami-corpus/master/Data/syrian.txt`
  - `https://raw.githubusercontent.com/GU-CLASP/shami-corpus/master/Data/Palestinian.txt`
  - `https://raw.githubusercontent.com/GU-CLASP/shami-corpus/master/Data/Lebanees.txt`
- **License & Legal Text:**
  - License: **Apache License 2.0**
  - Upstream License file: `https://raw.githubusercontent.com/GU-CLASP/shami-corpus/master/LICENSE`
  - Attribution Notice: Kathrein Abu Kwaik, Motaz Saad, Stergios Chatzikyriakidis, and Simon Dobnik (2018), *"Shami: A Corpus of Levantine Arabic Dialects"*, LREC 2018.
- **App Shipping Compatibility:**
  - **100% Compatible**. Section 4 of Apache-2.0 permits redistribution and derivative works (commercial or open-source) as long as attribution is retained in `NOTICE.txt` and a copy of the license is included. mboard already includes `assets/licenses/shami-Apache-2.0.txt` and attributes `jordinian.txt` in `NOTICE.txt`. Extending to the sister partitions requires no legal shifts or new license terms.
- **Size of Dialect Text:**
  - `syrian.txt`: 37,759 raw lines (2.65 MB, ~500,000 words)
  - `Palestinian.txt`: 10,642 raw lines (0.94 MB, ~180,000 words)
  - `Lebanees.txt`: 10,829 raw lines (0.56 MB, ~110,000 words)
  - **Total:** 59,230 lines, ~4.15 MB raw text (~800,000 words). Combined with the existing 7,000 lines of `jordinian.txt`, this expands genuine human-written Levantine training data by **~850%**.
- **Dialect Quality & Linguistic Inspection:**
  - **Sample 10 lines from sister partitions:**
    1. Syrian: `قامت على طاولتها واخدت الشنته وطلعت مع الكفتيريا` (Authentic Levantine vocabulary: `اخدت`, `الشنته`, `طلعت`).
    2. Syrian: `شو بدك تساوي هلق؟ قلي شو صار معك مبارح` (Syrian/Levantine markers: `شو بدك`, `تساوي`, `هلق`, `مبارح`).
    3. Syrian: `والله مالي عرفان شو بدي احكي معك يا زلمة` (Levantine negation and vocabulary: `مالي عرفان`, `شو بدي`, `يا زلمة`).
    4. Palestinian: `هه اصلا حاليا فى وفد قطري موجود بغزه واللعبه هاى صارت محفوظه خخ` (Colloquial Palestinian: `هاى`, `صارت محفوظه`).
    5. Palestinian: `وين رايحين بكرا؟ بدنا نطلع عالقدس ازا بتسمح الظروف` (South Levantine / Palestinian: `وين رايحين`, `بدنا نطلع`, `عالقدس`, `ازا`).
    6. Palestinian: `يسعد مساك يا غالي ان شاء الله امورك تمام وكيف الصحه` (Warm Levantine greeting).
    7. Lebanese: `بقا بالوسايط و المحسوبيات` (Colloquial: `بقا`).
    8. Lebanese: `شو هيدا الشي اللي عم تحكي فيه؟ ماني فهمان شي بنوب` (North Levantine: `شو هيدا`, `عم تحكي`, `ماني فهمان`, `بنوب`).
    9. Lebanese: `كتير حلو البيت وين ساكنين هلق؟` (Levantine: `كتير حلو`, `وين ساكنين`, `هلق`).
    10. Syrian: `رح نلتقي بالسوق بعد الضهر جهز حالك` (Levantine future prefix and idiom: `رح نلتقي`, `جهز حالك`).
  - **Dialect Assessment:** Natural Levantine dialect across the dialect continuum. South Levantine (Palestinian) is almost indistinguishable from Jordanian Urban/Rural; Syrian and Lebanese provide rich, authentic Levantine prefixes (`عم`, `رح`, `بد-`) and vocabulary that directly match Jordanian social media and informal chat.
- **Download Method:**
  - Simple HTTP/HTTPS curl or `urllib.request` from GitHub raw repository URLs.

---

### Candidate 2: FineWeb-2 Levantine Subset (`apc_Arab`)
- **Exact URL:**
  - `https://huggingface.co/datasets/HuggingFaceFW/fineweb-2`
  - Shard files: `data/apc_Arab/train/000_00000.parquet` (65.2 MB) and `data/apc_Arab/test/000_00000.parquet` (0.92 MB).
- **License & Legal Terms:**
  - Dataset License: **Open Data Commons Attribution License (ODC-By) v1.0** ([Legal Code](https://opendatacommons.org/licenses/by/1-0/)).
  - Upstream Terms: Sourced from 96 Common Crawl snapshots. Hugging Face states: *"The dataset is released under ODC-By v1.0. The use of this dataset is also subject to CommonCrawl's Terms of Use."*
- **App Shipping Compatibility:**
  - **Uncertain / Needs Legal Sign-Off**.
    - ODC-By applies to the *database structure and compilation rights*, allowing commercial use, modification, and creation of derivative works with attribution.
    - However, Common Crawl consists of raw crawled websites. While training an ML model or n-gram model on public web crawls is widely accepted under fair use/text-and-data-mining exemptions, shipping raw texts or derived n-grams in proprietary/consumer apps without individual website copyright clearance involves legal ambiguity.
- **Size of Dialect Text:**
  - Train split: 65.2 MB parquet file (~50,000 documents, ~35 million words).
  - Test split: 697 documents (1.3 MB raw text, ~180,000 words).
- **Dialect Quality & Linguistic Inspection:**
  - Inspection of the test shard shows a significant amount of genuine colloquial Levantine forum comments (e.g., Syrian news comments, forums like `syria-news.com`, `lakii.com`, `hawaaworld.com`), but also severe noise:
    - ~30% consists of Google Books OCR garbage, single-letter alphabetic index lines (`ئا اا ار اع...`).
    - Explicit erotic stories / adult fiction forum scrapes (`انا شاب بموت في نيك الطلبه...`).
    - Standard Arabic news excerpts misclassified as `apc_Arab` by FastText.
  - **Sample 10 dialect lines after filtering out OCR & adult content:**
    1. `اشتقنا لشامالرجال الحترم والصادق والحساس والطيب منسميه بها الزمن اشاعه او كذبه` (Colloquial Syrian: `منسميه بها الزمن`).
    2. `عنجد أنا كتير تعاطفت معكن والله ياسورية مصيبتك مو قليلة الله يعينك` (Authentic Levantine: `عنجد`, `كتير`, `معكن`, `مو قليلة`).
    3. `سورية وعايشة بالغربةانا بدي اعطي نصيحة للبنات الي لساماتزوجو` (Colloquial: `بدي اعطي`, `لساماتزوجو`).
    4. `نصيحة لاحدا يحكي من فوق الاساطيحيعني دائما المرأة بتفكر انو الرجال يلي رح ترتبط فيه هوه فارس الاحلام` (Levantine: `لاحدا`, `بتفكر`, `انو`, `يلي رح ترتبط`).
    5. `مسا الخير للكلانا شايف انو كل هالتعليقات راح تنتج بالاخر على انو في البعض راح يتعرفو` (Colloquial: `مسا الخير`, `كل هالتعليقات`, `راح يتعرفو`).
    6. `شو هالحكي؟؟؟ عيب تقولوا يا صبايا ما في رجال اذا مرت معكون حالات` (Levantine: `شو هالحكي`, `يا صبايا`, `معكون`).
    7. `أهم شي يشبه مهند و الباقي مو مهم بدي متع ناظري بجمالو و كمان يكون غني كثير كثير` (Colloquial: `أهم شي`, `بدي متع`, `كتير كثير`).
    8. `نصيحة مني يا بنات اذا بدكن تختارو شريك العمر حاولو تلاقو فيو ما يلي` (Levantine: `بدكن`, `تلاقو فيو`).
    9. `وجعتولي راسي الي ساعة عم اقرا لأني ما شفت الخبر من زمان، اي في كتابة بتكفي شهر` (Levantine: `وجعتولي راسي`, `الي ساعة عم اقرا`, `ما شفت`, `بتكفي`).
    10. `كل كلامك حكي فاضي ... شو حافظة تعاريف الكتب وجاية تكتبيا هون` (Levantine: `حكي فاضي`, `وجاية تكتبيا هون`).
  - **Dialect Assessment:** Contains very rich, natural spoken Levantine text, but requires a heavy, multi-stage cleaning pipeline (profanity blacklist, OCR-filter, word-count and script filters).
- **Download Method:**
  - Download parquet from Hugging Face via standard HTTPS stream, extract `text` column using `pyarrow` or `duckdb`.

---

### Candidate 3: Tatoeba Sentences (`ajp` South Levantine & `apc` North Levantine)
- **Exact URLs:**
  - South Levantine (`ajp`): `https://downloads.tatoeba.org/exports/per_language/ajp/ajp_sentences.tsv.bz2`
  - North Levantine (`apc`): `https://downloads.tatoeba.org/exports/per_language/apc/apc_sentences.tsv.bz2`
- **License & Legal Terms:**
  - License: **Creative Commons Attribution 2.0 France (CC BY 2.0 FR)** ([Legal Code](https://creativecommons.org/licenses/by/2.0/fr/)).
  - Attribution: Tatoeba.org contributors.
- **App Shipping Compatibility:**
  - **Compatible with Attribution**. CC BY 2.0 FR permits commercial reuse and derivative works provided proper credit is given. mboard already uses Tatoeba for English and Arabic held-out benchmarks.
- **Size of Dialect Text:**
  - `ajp` (South Levantine): **262 total lines** (all in Arabic script).
  - `apc` (North Levantine): 160 total lines, of which only **49 lines** are in Arabic script (the remainder are Arabizi/Latin script).
  - **Total Arabic Script Text:** **311 sentences** (~2,500 words).
- **Dialect Quality & Linguistic Inspection:**
  - **Sample 10 lines (`ajp` & `apc`):**
    1. `كيفك؟`
    2. `إيش إسمك؟`
    3. `ما بعرفش.`
    4. `بدنا حل سلمي.`
    5. `مش فاهم.`
    6. `عندي بسة.`
    7. `شو بدّك؟`
    8. `قديش مدارس في ع مدينتك؟`
    9. `توم بيفهم اللي هي ما بتفهم.`
    10. `بدي أسكن بالمدينة.`
  - **Dialect Assessment:** 100% natural, pristine colloquial Jordanian/Palestinian and Syrian.
- **Verdict & Recommendation:**
  - Because the size is minuscule (311 lines), it is not viable as a major training expansion. However, it is an **ideal fresh evaluation benchmark** to test Levantine next-word suggestions without risk of data contamination.
- **Download Method:**
  - Direct HTTPS download of `.tsv.bz2`, extract with stdlib `bz2`.

---

### Candidate 4: Arabic Wikipedia (`arwiki`)
- **Exact URL:** `https://dumps.wikimedia.org/arwiki/latest/arwiki-latest-pages-articles.xml.bz2`
- **License:** **CC BY-SA 4.0** (Attribution-ShareAlike 4.0).
- **App Shipping Compatibility:** Permissive copyleft (compatible with mboard's CC BY-SA 4.0 word frequency assets).
- **Dialect Quality:** **Zero to Negligible Levantine Dialect**. Arabic Wikipedia enforces strict Modern Standard Arabic (الفصحى). Only rare article quotes contain dialect. Egyptian Wikipedia (`arzwiki`) exists as a separate project, but there is no active Levantine Wikipedia (only an abandoned Wikimedia Incubator test wiki `Wp/apc` with < 100 stubs).
- **Recommendation:** **Rejected** for dialect language modeling.

---

### Candidate 5: Leipzig Corpora Collection (Arabic)
- **Exact URL:** `https://wortschatz.uni-leipzig.de/en/download/Arabic` / `https://cls.corpora.uni-leipzig.de/`
- **License:** **CC BY-NC 4.0** (Non-Commercial). Confirmed in Leipzig's repository terms and Hugging Face mirror.
- **App Shipping Compatibility:** **Incompatible (Non-Commercial)**. Shipping derived n-gram models in an Android keyboard violates CC BY-NC 4.0 terms for commercial or general application bundling.
- **Recommendation:** **Strictly Rejected**.

---

### Candidate 6: Common Voice Arabic Transcripts
- **Exact URL:** `https://datacollective.mozillafoundation.org/`
- **License:** **CC0 1.0** (Public Domain dedication for sentence collector submissions).
- **App Shipping Compatibility:** Fully compatible.
- **Dialect Quality:** **Negligible**. Common Voice sentences are gathered from Wikipedia or formal public-domain texts in Modern Standard Arabic. There are no Levantine-specific dialect speech transcripts in Common Voice Arabic.
- **Recommendation:** **Rejected** for dialect modeling.

---

### Candidate 7: OPUS Monolingual (`apc`, `ajp`)
- **Exact URL:** `https://opus.nlpl.eu/`
- **Dialect Availability:** Checked `https://object.pouta.csc.fi/OPUS-OpenSubtitles/v2018/mono/apc.txt.gz` and `ajp.txt.gz` -> **HTTP 404 (Not Found)**. OPUS only contains bilingual sentence pairs from Tatoeba and Translatewiki, totaling fewer than 5,000 words.
- **Recommendation:** **Rejected** due to absence of monolingual data.

---

## 3. Recommended Acquisition Strategy & Ranking

### Top Recommendation:
1. **Primary Immediate Candidate (Rank 1): Shami Corpus Sister Partitions (`syrian.txt`, `Palestinian.txt`, `Lebanees.txt`)**
   - **Why:** Same provenance, authors, and **Apache-2.0** license as the currently deployed `jordinian.txt`.
   - **Yield:** 59,230 new lines of human Levantine text (~800k words), providing an immediate **8.5x expansion** of the human dialect corpus.
   - **Licensing Safety:** Fully compatible with mboard's existing `NOTICE.txt` and Apache-2.0 asset declarations. No legal uncertainty.
2. **Secondary High-Volume Candidate (Rank 2): FineWeb-2 Levantine (`apc_Arab`)**
   - **Why:** Massive scale (up to ~35 million words), but requires **m57 explicit sign-off** on ODC-By / Common Crawl derivative rights, plus an extensive cleaning pipeline to remove web spam, OCR garbage, and adult forum texts.
3. **Evaluation Benchmark Candidate (Rank 3): Tatoeba `ajp` & `apc` Sentences**
   - **Why:** Pristine 311 human dialect sentences under **CC BY 2.0 FR**. Perfect for an immutable, leak-free evaluation benchmark set (`bench/data/jo_tatoeba_fresh.txt`).

---

## 4. Pipeline Script: `bench/lm/fetch_298.py`

To operationalize the top permissive candidate (Shami sister partitions) without writing into app assets or training tables, `bench/lm/fetch_298.py` is provided. It:
1. Downloads `syrian.txt`, `Palestinian.txt`, and `Lebanees.txt` into `bench/lm/raw/` (gitignored).
2. Cleans text using mboard's standard cleaning pipeline (Arabic script filter, strip harakat, tatweel, URLs, mentions, emojis, collapse repeated characters).
3. Strictly excludes lines matching any existing benchmark in `bench/data/*.txt`.
4. Splits lines into **contiguous ~50-line hash blocks** (SHA-256 modulo) to prevent conversational leakage between train and held-out partitions.
5. Saves the clean partitions strictly under `bench/lm/` (training candidate) and `bench/data/` (held-out candidate), never touching `app/src/main/assets/`.
