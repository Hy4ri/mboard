## Tatoeba Sentences
- Sources:
  - English: https://downloads.tatoeba.org/exports/per_language/eng/eng_sentences.tsv.bz2
  - Arabic: https://downloads.tatoeba.org/exports/per_language/ara/ara_sentences.tsv.bz2
- License: Creative Commons Attribution 2.0 France (CC BY 2.0 FR)
- Attribution: Tatoeba.org contributors
- Sampling: Deterministic sample with seed random.Random(57), cleaned to 2-25 words, duplicates removed, subtitles/URLs/HTML removed. Arabic stripped of harakat (U+064B..U+065F, U+0670) and tatweel (U+0640), Latin characters removed.
- Line counts:
  - `en_tatoeba.txt`: 6000 lines
  - `ar_tatoeba.txt`: 6000 lines

## en_chat.txt
- Source: Synthetic, written by an LLM subagent to imitate casual English texting; not a corpus measurement.
- License: CC0 / Public Domain dedication for project benchmark use.
- Content: Casual English chat messages (plans, food, work, school, gaming, complaints, quick replies), natural contractions, informal punctuation. No URLs, emoji, or markup.
- Line counts:
  - `en_chat.txt`: 400 lines


## en_subs.txt
- Source: https://object.pouta.csc.fi/OPUS-OpenSubtitles/v2018/mono/en.txt.gz
- License: OPUS OpenSubtitles / open data (subtitles copyright of respective owners)
- Sampling: Fetched held-out stream slice starting after the first 120 MB compressed data (compressed bytes 125,000,000 to 150,000,000, corresponding to ~80 MB uncompressed text). Cleaned to remove HTML tags, bracket notes, speaker labels, dialogue dashes, URLs, emails, timestamps, numbers-only lines, and restricted to 2-25 words. Deduplicated and sampled deterministically (seed 57).
- Line counts:
  - `en_subs.txt`: 8000 lines

## en_real_typos.tsv
- Sources:
  - Birkbeck Spelling Error Corpus (`missp.dat`): https://www.dcs.bbk.ac.uk/~ROGER/missp.dat
  - Wikipedia Common Misspellings (`wikipedia.dat`): https://www.dcs.bbk.ac.uk/~ROGER/wikipedia.dat
  - Aspell Testing Set (`aspell.dat`): https://www.dcs.bbk.ac.uk/~ROGER/aspell.dat
- Licensing:
  - Birkbeck corpus: CC BY-NC-SA 3.0 / Roger Mitton & Oxford Text Archive
  - Wikipedia: Creative Commons Attribution-ShareAlike (CC BY-SA)
  - Aspell: GNU Free Documentation License / GPL (Kevin Atkinson)
- Filter and criteria:
  - `intended` word must exist in keyboard dictionary `words_en.tsv`
  - `typed` consists only of lowercase letters [a-z] and apostrophe ('), length >= 2
  - `typed` != `intended` and `typed` NOT in `words_en.tsv` (real-word errors excluded)
  - Deduplicated on `(typed, intended)` pairs
  - Sampled up to 4000 rows deterministically with `random.Random(57)`
- Header: `context\ttyped\tintended\tkind` (context empty)
- Row count: 4000
- Breakdown by kind:
  - `phonetic`: 1604 (40.1%)
  - `other`: 1459 (36.5%)
  - `omission`: 427 (10.7%)
  - `insertion`: 230 (5.8%)
  - `transposition`: 121 (3.0%)
  - `adjacent`: 88 (2.2%)
  - `doubled`: 71 (1.8%)
## ar_real_typos.tsv
- Hand-written by an LLM subagent from common Arabic typing errors; not corpus measured.
- Total rows: 800
- Distribution across kinds (80 rows each):
  - hamza: 80
  - taa: 80
  - yaa: 80
  - phonetic: 80
  - adjacent: 80
  - omission: 80
  - insertion: 80
  - doubled: 80
  - transposition: 80
  - other: 80

## en_real_typos.tsv
- Sources:
  - Birkbeck Spelling Error Corpus (`missp.dat`): https://www.dcs.bbk.ac.uk/~ROGER/missp.dat
  - Wikipedia Common Misspellings (`wikipedia.dat`): https://www.dcs.bbk.ac.uk/~ROGER/wikipedia.dat
  - Aspell Testing Set (`aspell.dat`): https://www.dcs.bbk.ac.uk/~ROGER/aspell.dat
- Licensing:
  - Birkbeck corpus: CC BY-NC-SA 3.0 / Roger Mitton & Oxford Text Archive
  - Wikipedia: Creative Commons Attribution-ShareAlike (CC BY-SA)
  - Aspell: GNU Free Documentation License / GPL (Kevin Atkinson)
- Filter and criteria:
  - `intended` word must exist in keyboard dictionary `words_en.tsv`
  - `typed` consists only of lowercase letters [a-z] and apostrophe ('), length >= 2
  - `typed` != `intended` and `typed` NOT in `words_en.tsv` (real-word errors excluded)
  - Deduplicated on `(typed, intended)` pairs
  - Sampled up to 4000 rows deterministically with `random.Random(57)`
- Header: `context\ttyped\tintended\tkind` (context empty)
- Row count: 4000
- Breakdown by kind:
  - `phonetic`: 1604 (40.1%)
  - `other`: 1459 (36.5%)
  - `omission`: 427 (10.7%)
  - `insertion`: 230 (5.8%)
  - `transposition`: 121 (3.0%)
  - `adjacent`: 88 (2.2%)
  - `doubled`: 71 (1.8%)

## jo_chat_b.txt
- Source: Synthetic, written by an LLM subagent to imitate Jordanian WhatsApp chat; not a corpus measurement.
- License: CC0 / Public Domain dedication for project benchmark use.
- Content: Natural Jordanian Arabic chat messages (work/university/exams, shopping & prices, transport/taxi/traffic, weather, complaints & jokes, football, errands) in Arabic script without harakat or tatweel.
- Line counts:
  - `jo_chat_b.txt`: 600 lines

## jo_chat_a.txt
- Source: Synthetic, written by an LLM subagent to imitate Jordanian WhatsApp chat; not a corpus measurement.
- License: CC0 / Public Domain dedication for project benchmark use.
- Content: Natural Jordanian Arabic chat messages (family, friends, plans/meeting up, food, greetings, phone/WhatsApp small talk) in Arabic script without harakat or tatweel.
- Line counts:
  - `jo_chat_a.txt`: 600 lines


## ar_subs.txt
- Source: https://object.pouta.csc.fi/OPUS-OpenSubtitles/v2018/mono/ar.txt.gz
- License: OPUS OpenSubtitles / open data (subtitles copyright of respective owners)
- Sampling: Fetched held-out stream slice starting after the first 120 MB compressed data (compressed bytes 125,000,000 to 145,000,000, corresponding to ~40 MB extracted text). Cleaned to strip harakat (U+064B..U+065F, U+0670) and tatweel (U+0640), normalized Arabic punctuation (، ؟ . !), dropped Latin letters, numbers-only lines, subtitle dialogue dashes/artifacts, and restricted to 2-25 words. Deduplicated and sampled deterministically (seed 57).
- Line counts:
  - `ar_subs.txt`: 8000 lines

## en_subs.txt cleanup (Luna)
Dropped 429 lines with OCR artifacts (capital I for l: Iie, AII, CuIIen; ot/ln), mostly-uppercase lines and '' quotes; refilled to 8000 from the same cached slice with the same filters, random.Random(57).

## ar_real_typos.tsv cleanup (Luna)
388 rows had a context sentence that contained the intended word itself; context was cut to the words before it. Dropped 29 insertion rows that were not single insertions. 771 rows remain. 257 typed forms are themselves dictionary words (mostly accepted hamza/taa spellings); the bench reports these separately.

## ar_subs.txt cleanup (Luna)
Dropped 1151 lines: RTL-reversed word order (bigram log-prob of the reversed line beats the forward line by >4 nats, bigrams counted on the cached slice), lines starting/ending with detached punctuation (another RTL-flip sign), digits, Latin, brackets. Refilled to 8000 from the same slice with the same filter, random.Random(57).
