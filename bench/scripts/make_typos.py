#!/usr/bin/env python3
"""
Synthetic typo generator for mboard suggestion bench.
Turns sentence sets (.txt) into typo TSVs (.tsv) modeling real phone touch errors.
Stdlib only. Run with `python3 -I`.
"""

import argparse
import json
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

# Actual keyboard layouts from Languages.kt (Languages.ENGLISH and Languages.ARABIC).
# Provenance: Languages.kt commit fadbdb2eb42a8978b1d6fdd925c8069eff45ee0e sha256 e08e06e71306860138310d3464bc583314f7098c96466b3cdf14094ecb2364ae
TOUCH_LAYOUT_SPECS = {
    "en": {
        "rows": [
            list("qwertyuiop"),
            list("asdfghjkl"),
            ["SHIFT"] + list("zxcvbnm") + ["BACKSPACE"],
        ],
        "stretch": False,
    },
    "ar": {
        "rows": [
            list("ضصثقفغعهخحج"),
            list("شسيبلاتنمكط"),
            list("ذئءؤرىةوزظد") + ["BACKSPACE"],
        ],
        "stretch": True,
    },
}


def build_touch_geometry(spec):
    rows = spec["rows"]
    stretch = spec["stretch"]

    def key_weight(k):
        if k in ("SHIFT", "BACKSPACE", "ENTER"):
            return 1.5
        return 1.0

    widths = [[key_weight(k) for k in r] for r in rows]
    row_units = [sum(w) for w in widths]
    max_units = max(row_units)
    cells = []
    char_to_cell = {}
    letter_cells = []
    for r_idx, r in enumerate(rows):
        units = row_units[r_idx]
        scale = (max_units / units) if stretch else 1.0
        x = 0.0 if stretch else (max_units - units) / 2.0
        top = float(r_idx)
        bottom = float(r_idx + 1)
        for i, k in enumerate(r):
            w = widths[r_idx][i] * scale
            left = x
            right = x + w
            cx = (left + right) / 2.0
            cy = (top + bottom) / 2.0
            cell = {
                "key": k,
                "row": r_idx,
                "left": left,
                "top": top,
                "right": right,
                "bottom": bottom,
                "width": w,
                "cx": cx,
                "cy": cy,
            }
            cells.append(cell)
            if len(k) == 1:
                char_to_cell[k] = cell
                letter_cells.append(cell)
            x += w
    return {"cells": cells, "char_to_cell": char_to_cell, "letter_cells": letter_cells, "width": max_units}


TOUCH_GEOMETRIES = {lang: build_touch_geometry(spec) for lang, spec in TOUCH_LAYOUT_SPECS.items()}


def sample_touch_for_char(intended_char, geo, rng):
    c = intended_char.lower()
    if c not in geo["char_to_cell"]:
        return None
    cell = geo["char_to_cell"][c]
    cx = cell["cx"]
    cy = cell["cy"]
    w = cell["width"]
    h = 1.0  # Normalized active row height
    sigma_x = 0.45 * w
    sigma_y = 0.55 * h
    sx = rng.gauss(cx, sigma_x)
    sy = rng.gauss(cy, sigma_y)

    # Condition on actual letter taps. Outside-screen/special-key taps cannot produce
    # letter callbacks in KeyboardView, so discard this word instead of fabricating a letter.
    # Geometry is idealized gap=0 and omits number/bottom rows; no gesture or special-key errors.
    if not (0.0 <= sx < geo["width"] and 0.0 <= sy < 3.0):
        return None
    row_idx = int(sy)

    row_cells = [cell for cell in geo["cells"] if cell["row"] == row_idx]
    picked_cell = None
    for rc in row_cells:
        if rc["left"] <= sx <= rc["right"]:
            picked_cell = rc
            break
    if picked_cell is None:
        picked_cell = min(row_cells, key=lambda rc: min(abs(rc["left"] - sx), abs(rc["right"] - sx)))

    picked_key = picked_cell["key"]
    if len(picked_key) != 1:
        return None

    # KeyboardView likelihood model:
    # Uses the picked cell's width for sigmaX across all candidates in that tap
    picked_w = picked_cell["width"]
    p_sigma_x = 0.45 * picked_w
    p_sigma_y = 0.55 * h
    two_sx2 = 2.0 * p_sigma_x * p_sigma_x
    two_sy2 = 2.0 * p_sigma_y * p_sigma_y

    p_dx = sx - picked_cell["cx"]
    p_dy = sy - picked_cell["cy"]
    p_dist2 = (p_dx * p_dx) / two_sx2 + (p_dy * p_dy) / two_sy2

    likelihoods = {picked_key: 1.0}
    for lc in geo["letter_cells"]:
        k = lc["key"]
        if k == picked_key:
            continue
        dx = sx - lc["cx"]
        dy = sy - lc["cy"]
        dist2 = (dx * dx) / two_sx2 + (dy * dy) / two_sy2
        log_ratio = max(-8.0, min(0.0, p_dist2 - dist2))
        likelihoods[k] = math.exp(log_ratio)

    typed_char = picked_key.upper() if intended_char.isupper() else picked_key
    return typed_char, likelihoods, (round(sx, 4), round(sy, 4))


def generate_touch_typo(word, lang, rng):
    geo = TOUCH_GEOMETRIES[lang]
    typed_chars = []
    touches = []
    raw_points = []
    for ch in word:
        sample = sample_touch_for_char(ch, geo, rng)
        if sample is None:
            return None
        t_char, l_map, pt = sample
        typed_chars.append(t_char)
        touches.append(l_map)
        raw_points.append(pt)
    typed = "".join(typed_chars)
    if typed == word:
        return None
    return typed, "touch", touches, raw_points


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


def process_file(lang, in_path, out_path, per_sentence=1, seed=57, max_sentences=None, touch_data=False):
    rng = random.Random(seed)
    positions = EN_POSITIONS if lang == "en" else AR_POSITIONS

    with open(in_path, "r", encoding="utf-8") as f:
        raw_lines = [line.strip() for line in f if line.strip()]

    if max_sentences is not None and max_sentences < len(raw_lines):
        raw_lines = raw_lines[:max_sentences]

    if touch_data:
        out_rows = ["context\ttyped\tintended\tkind\ttouches\traw_points"]
    else:
        out_rows = ["context\ttyped\tintended\tkind"]

    for line in raw_lines:
        words = line.split()
        eligible = [(i, w) for i, w in enumerate(words) if is_eligible_word(w, lang)]
        if not eligible:
            continue

        for _ in range(per_sentence):
            idx, word = rng.choice(eligible)
            context = make_context(words[:idx], lang)
            if touch_data:
                typo_res = generate_touch_typo(word, lang, rng)
                if typo_res:
                    typed, kind, touches, raw_points = typo_res
                    touches_json = json.dumps(touches, separators=(",", ":"), ensure_ascii=False)
                    points_json = json.dumps(raw_points, separators=(",", ":"), ensure_ascii=False)
                    out_rows.append(f"{context}\t{typed}\t{word}\t{kind}\t{touches_json}\t{points_json}")
            else:
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
    parser.add_argument("--touch-data", action="store_true", help="Generate touch-augmented typos with Gaussian sampling and likelihood ratios")

    args = parser.parse_args()

    if args.selfcheck:
        lang = args.lang or "en"
        run_selfcheck(lang)
        return

    if not args.lang or not args.in_path or not args.out_path:
        parser.error("--lang, --in, and --out are required unless --selfcheck is specified")

    if args.touch_data and not args.out_path.endswith("_touch_typos.tsv"):
        parser.error("--touch-data requires --out path ending with _touch_typos.tsv")

    process_file(
        lang=args.lang,
        in_path=args.in_path,
        out_path=args.out_path,
        per_sentence=args.per_sentence,
        seed=args.seed,
        max_sentences=args.max,
        touch_data=args.touch_data,
    )


if __name__ == "__main__":
    main()
