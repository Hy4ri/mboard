#!/usr/bin/env python3
"""
Synthetic typo generator for mboard suggestion bench.
Turns sentence sets (.txt) into typo TSVs (.tsv) modeling real phone touch errors.
Stdlib only. Run with `python3 -I`.
"""

import argparse
import math
import random
import re
import sys

# Error model weights
WEIGHT_ADJACENT = 0.45
WEIGHT_OMISSION = 0.20
WEIGHT_INSERTION = 0.10
WEIGHT_TRANSPOSITION = 0.10
WEIGHT_DOUBLED = 0.10
WEIGHT_SCRIPT_ARABIC = 0.05  # For Arabic: hamza, taa, yaa; for English this goes to adjacent

# Gaussian touch model parameters
TOUCH_SIGMA_X = 0.45  # in key widths
TOUCH_SIGMA_Y = 0.35  # in key heights

# Keyboard layouts: key centres (row_y, col_x) in key dimensions
# English layout:
# row 0: 'qwertyuiop', offset = 0.0
# row 1: 'asdfghjkl', offset = 0.5
# row 2: 'zxcvbnm', offset = 1.5 (shift key on left of row 2)
# key width = 1.0, key height = 1.0. Centre of key i in row r at offset + i + 0.5, y = r + 0.5.
EN_ROWS = [
    ("qwertyuiop", 0.0),
    ("asdfghjkl", 0.5),
    ("zxcvbnm", 1.5),
]

# Arabic layout: stretch, all 11 keys per row, no offsets
# row 0: 'ضصثقفغعهخحج' (11 keys)
# row 1: 'شسيبلاتنمكط' (11 keys)
# row 2: 'ذئءؤرىةوزظد' (11 keys)
AR_ROWS = [
    ("ضصثقفغعهخحج", 0.0),
    ("شسيبلاتنمكط", 0.0),
    ("ذئءؤرىةوزظد", 0.0),
]


def build_key_positions(layout_rows):
    """
    Returns a dict: char -> (x, y) key centre coordinates.
    """
    positions = {}
    for r, (keys, offset) in enumerate(layout_rows):
        y = r + 0.5
        for i, char in enumerate(keys):
            x = offset + i + 0.5
            positions[char] = (x, y)
    return positions


EN_POSITIONS = build_key_positions(EN_ROWS)
AR_POSITIONS = build_key_positions(AR_ROWS)


def get_adjacent_key(char, positions, rng, sigma_x=TOUCH_SIGMA_X, sigma_y=TOUCH_SIGMA_Y):
    """
    Gaussian touch model: sample a point around intended key centre,
    pick the nearest key. Resample until key differs.
    """
    if char not in positions:
        return char

    orig_x, orig_y = positions[char]
    all_keys = list(positions.items())

    for _ in range(100):
        sample_x = rng.gauss(orig_x, sigma_x)
        sample_y = rng.gauss(orig_y, sigma_y)

        # Find nearest key
        best_char = None
        best_dist = float("inf")
        for k, (kx, ky) in all_keys:
            dist = (sample_x - kx) ** 2 + (sample_y - ky) ** 2
            if dist < best_dist:
                best_dist = dist
                best_char = k

        if best_char is not None and best_char != char:
            return best_char

    # Fallback to nearest deterministic neighbor
    neighbors = sorted(
        [k for k in positions if k != char],
        key=lambda k: (positions[k][0] - orig_x) ** 2 + (positions[k][1] - orig_y) ** 2,
    )
    return neighbors[0] if neighbors else char


# Punctuation stripping regex for context normalization
# English: punctuation stripped, lowercase
RE_EN_PUNCT = re.compile(r"[^a-zA-Z\s]")
# Arabic: punctuation stripped (keep arabic letters and spaces)
RE_AR_PUNCT = re.compile(r"[^\u0600-\u06FF\s]")

RE_EN_WORD = re.compile(r"^[a-zA-Z]{3,}$")
RE_AR_WORD = re.compile(r"^[\u0621-\u064A]{3,}$")


def clean_context_word(word, lang):
    if lang == "en":
        cleaned = RE_EN_PUNCT.sub("", word).lower().strip()
    else:
        cleaned = RE_AR_PUNCT.sub("", word).strip()
    return cleaned


def make_context(preceding_words, lang):
    cleaned_tokens = [clean_context_word(w, lang) for w in preceding_words]
    filtered = [t for t in cleaned_tokens if t]
    return " ".join(filtered)


def is_eligible_word(word, lang):
    if lang == "en":
        return bool(RE_EN_WORD.match(word))
    else:
        return bool(RE_AR_WORD.match(word))


def apply_adjacent(word, positions, rng):
    idx = rng.randrange(len(word))
    c = word[idx]
    lower_c = c.lower()
    if lower_c not in positions:
        return None
    adj = get_adjacent_key(lower_c, positions, rng)
    if c.isupper():
        adj = adj.upper()
    if adj == c:
        return None
    typed = word[:idx] + adj + word[idx + 1 :]
    return typed, "adjacent"


def apply_omission(word, rng):
    # Never the first letter
    if len(word) <= 1:
        return None
    idx = rng.randrange(1, len(word))
    typed = word[:idx] + word[idx + 1 :]
    return typed, "omission"


def apply_insertion(word, positions, rng):
    # Insert a neighbour of an adjacent letter
    # Pick a reference position in the word
    ref_idx = rng.randrange(len(word))
    ref_char = word[ref_idx].lower()
    if ref_char not in positions:
        return None
    neighbor = get_adjacent_key(ref_char, positions, rng)
    # Insert before or after ref_idx
    insert_pos = ref_idx if rng.random() < 0.5 else ref_idx + 1
    # Preserve casing if adjacent to upper? contract doesn't mandate, but lowercase is safe
    typed = word[:insert_pos] + neighbor + word[insert_pos:]
    return typed, "insertion"


def apply_transposition(word, rng):
    # Adjacent letters, not identical
    candidates = [i for i in range(len(word) - 1) if word[i] != word[i + 1]]
    if not candidates:
        return None
    idx = rng.choice(candidates)
    typed = word[:idx] + word[idx + 1] + word[idx] + word[idx + 2 :]
    return typed, "transposition"


def apply_doubled(word, rng):
    idx = rng.randrange(len(word))
    typed = word[:idx] + word[idx] + word[idx:]
    return typed, "doubled"


def apply_script_arabic(word, rng):
    # ة→ه at end, ى↔ي at end, أ/إ→ا
    ops = []

    # Check ة -> ه at end
    if word.endswith("ة"):
        ops.append(("taa", word[:-1] + "ه"))

    # Check ى <-> ي at end
    if word.endswith("ى"):
        ops.append(("yaa", word[:-1] + "ي"))
    elif word.endswith("ي"):
        ops.append(("yaa", word[:-1] + "ى"))

    # Check أ/إ -> ا anywhere (or first occurrence)
    hamza_indices = [i for i, ch in enumerate(word) if ch in ("أ", "إ")]
    if hamza_indices:
        h_idx = rng.choice(hamza_indices)
        ops.append(("hamza", word[:h_idx] + "ا" + word[h_idx + 1 :]))

    if not ops:
        return None

    kind, typed = rng.choice(ops)
    return typed, kind


def generate_typo(word, lang, positions, rng):
    """
    Attempts to apply a typo according to weights.
    Returns (typed, kind) where typed != word, or None if impossible.
    """
    weights = [
        ("adjacent", WEIGHT_ADJACENT if lang == "ar" else (WEIGHT_ADJACENT + WEIGHT_SCRIPT_ARABIC)),
        ("omission", WEIGHT_OMISSION),
        ("insertion", WEIGHT_INSERTION),
        ("transposition", WEIGHT_TRANSPOSITION),
        ("doubled", WEIGHT_DOUBLED),
    ]
    if lang == "ar":
        weights.append(("script", WEIGHT_SCRIPT_ARABIC))

    # Try up to 20 times to pick an operation that succeeds and produces typed != word
    for _ in range(20):
        r = rng.random()
        cumulative = 0.0
        chosen_op = weights[0][0]
        for op, w in weights:
            cumulative += w
            if r <= cumulative:
                chosen_op = op
                break

        res = None
        if chosen_op == "adjacent":
            res = apply_adjacent(word, positions, rng)
        elif chosen_op == "omission":
            res = apply_omission(word, rng)
        elif chosen_op == "insertion":
            res = apply_insertion(word, positions, rng)
        elif chosen_op == "transposition":
            res = apply_transposition(word, rng)
        elif chosen_op == "doubled":
            res = apply_doubled(word, rng)
        elif chosen_op == "script":
            res = apply_script_arabic(word, rng)

        if res is not None:
            typed, kind = res
            if typed != word:
                return typed, kind

    # Ultimate fallback: adjacent substitution
    res = apply_adjacent(word, positions, rng)
    if res and res[0] != word:
        return res
    res = apply_doubled(word, rng)
    if res and res[0] != word:
        return res
    return None


SAMPLE_SENTENCES_EN = [
    "The quick brown fox jumps over the lazy dog.",
    "She walked through the sunny park with her friends.",
    "Please remember to buy fresh apples and oranges tomorrow.",
    "The computer system responded with an unexpected error message.",
    "They planned a wonderful journey across the mountain ranges.",
]

SAMPLE_SENTENCES_AR = [
    "ذهب الولد إلى المدرسة في الصباح الباكر.",
    "كتبت المعلمة رسالة جميلة على السبورة.",
    "تناول الأطفال وجبة الإفطار مع العائلة الكريمة.",
    "أحب قراءة القصص القديمة في المساء.",
    "خرجت الأسرة في نزهة ممتعة قرب الحديقة.",
]


def run_selfcheck(lang):
    """
    Generates 10 sample rows per kind from built-in sample sentences.
    Prints TSV output.
    """
    rng = random.Random(57)
    sentences = SAMPLE_SENTENCES_EN if lang == "en" else SAMPLE_SENTENCES_AR
    positions = EN_POSITIONS if lang == "en" else AR_POSITIONS

    kinds = ["adjacent", "omission", "insertion", "transposition", "doubled"]
    if lang == "ar":
        kinds.extend(["hamza", "taa", "yaa"])

    results = {k: [] for k in kinds}

    # Keep trying until we have 10 per kind or reach attempt limit
    attempts = 0
    while any(len(results[k]) < 10 for k in kinds) and attempts < 10000:
        attempts += 1
        s = sentences[attempts % len(sentences)]
        words = s.split()
        eligible = [(i, w) for i, w in enumerate(words) if is_eligible_word(w, lang)]
        if not eligible:
            continue
        idx, word = rng.choice(eligible)
        context = make_context(words[:idx], lang)

        # Force kind generation if target kind needs samples
        target_kinds = [k for k in kinds if len(results[k]) < 10]
        chosen_target = rng.choice(target_kinds)

        res = None
        if chosen_target == "adjacent":
            res = apply_adjacent(word, positions, rng)
        elif chosen_target == "omission":
            res = apply_omission(word, rng)
        elif chosen_target == "insertion":
            res = apply_insertion(word, positions, rng)
        elif chosen_target == "transposition":
            res = apply_transposition(word, rng)
        elif chosen_target == "doubled":
            res = apply_doubled(word, rng)
        elif chosen_target in ("hamza", "taa", "yaa") and lang == "ar":
            # Targeted script arabic
            if chosen_target == "taa" and word.endswith("ة"):
                res = (word[:-1] + "ه", "taa")
            elif chosen_target == "yaa":
                if word.endswith("ى"):
                    res = (word[:-1] + "ي", "yaa")
                elif word.endswith("ي"):
                    res = (word[:-1] + "ى", "yaa")
            elif chosen_target == "hamza":
                hamza_indices = [i for i, ch in enumerate(word) if ch in ("أ", "إ")]
                if hamza_indices:
                    h_idx = rng.choice(hamza_indices)
                    res = (word[:h_idx] + "ا" + word[h_idx + 1 :], "hamza")

        if res:
            typed, kind = res
            if typed != word and kind in results and len(results[kind]) < 10:
                results[kind].append((context, typed, word, kind))

    lines = ["context\ttyped\tintended\tkind"]
    for k in kinds:
        for row in results[k]:
            lines.append("\t".join(row))

    output = "\n".join(lines)
    print(output)
    return output


def process_file(lang, in_path, out_path, per_sentence=1, seed=57, max_sentences=None):
    rng = random.Random(seed)
    positions = EN_POSITIONS if lang == "en" else AR_POSITIONS

    with open(in_path, "r", encoding="utf-8") as f:
        raw_lines = [line.strip() for line in f if line.strip()]

    if max_sentences is not None and max_sentences < len(raw_lines):
        raw_lines = raw_lines[:max_sentences]

    out_rows = ["context\ttyped\tintended\tkind"]

    for line in raw_lines:
        words = line.split()
        eligible = [(i, w) for i, w in enumerate(words) if is_eligible_word(w, lang)]
        if not eligible:
            continue

        for _ in range(per_sentence):
            idx, word = rng.choice(eligible)
            context = make_context(words[:idx], lang)
            typo_res = generate_typo(word, lang, positions, rng)
            if typo_res:
                typed, kind = typo_res
                out_rows.append(f"{context}\t{typed}\t{word}\t{kind}")

    with open(out_path, "w", encoding="utf-8") as f:
        f.write("\n".join(out_rows) + "\n")


def main():
    parser = argparse.ArgumentParser(description="Synthetic typo generator for mboard bench")
    parser.add_argument("--lang", choices=["en", "ar"], help="Language (en or ar)")
    parser.add_argument("--in", dest="in_path", help="Input sentence file (.txt)")
    parser.add_argument("--out", dest="out_path", help="Output typo TSV file (.tsv)")
    parser.add_argument("--per-sentence", type=int, default=1, help="Number of typos per sentence")
    parser.add_argument("--seed", type=int, default=57, help="Random seed")
    parser.add_argument("--max", type=int, default=None, help="Max sentences to process")
    parser.add_argument("--selfcheck", action="store_true", help="Run selfcheck sample mode")

    args = parser.parse_args()

    if args.selfcheck:
        lang = args.lang or "en"
        run_selfcheck(lang)
        return

    if not args.lang or not args.in_path or not args.out_path:
        parser.error("--lang, --in, and --out are required unless --selfcheck is specified")

    process_file(
        lang=args.lang,
        in_path=args.in_path,
        out_path=args.out_path,
        per_sentence=args.per_sentence,
        seed=args.seed,
        max_sentences=args.max,
    )


if __name__ == "__main__":
    main()
