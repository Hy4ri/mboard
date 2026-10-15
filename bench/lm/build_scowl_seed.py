#!/usr/bin/env python3
"""Build reproducible SCOWL English spelling seed.

Selects from SCOWL final/ directory:
- prefixes: english, american, british, british_z
- categories:
    - words through size 70
    - upper and abbreviations through size 60
- excludes:
    - variant and proper-names categories entirely
    - sizes 80 and 95
- filters:
    - ASCII letters only, length 2..32
    - lowercase and deduplicated
    - sorted in deterministic order
"""
import argparse
import os
import re
import sys

DEFAULT_PREFIXES = ("english-", "american-", "british-", "british_z-")
WORD_RE = re.compile(r"^[a-z]{2,32}$")


def select_scowl_files(source_dir):
    """Return sorted list of files matching inclusion criteria."""
    selected = []
    for fname in sorted(os.listdir(source_dir)):
        prefix = None
        for p in DEFAULT_PREFIXES:
            if fname.startswith(p):
                prefix = p
                break
        if not prefix:
            continue

        # Exclude variant and proper-names categories
        if "variant" in fname or "proper-names" in fname:
            continue

        rest = fname[len(prefix):]
        if "." not in rest:
            continue
        cat, sz_str = rest.split(".", 1)
        if not sz_str.isdigit():
            continue
        size = int(sz_str)

        # Exclude sizes 80 and 95
        if size in (80, 95):
            continue

        if cat == "words" and size <= 70:
            selected.append((fname, cat, size))
        elif cat in ("upper", "abbreviations") and size <= 60:
            selected.append((fname, cat, size))

    return selected


def build_seed(source_dir):
    """Collect, validate, deduplicate, and sort words from selected SCOWL files."""
    selected_files = select_scowl_files(source_dir)
    words = set()
    for fname, _, _ in selected_files:
        fpath = os.path.join(source_dir, fname)
        with open(fpath, "r", encoding="iso-8859-1") as f:
            for line in f:
                w = line.strip().lower()
                if WORD_RE.fullmatch(w):
                    words.add(w)
    return sorted(words), selected_files


def main():
    ap = argparse.ArgumentParser(description="Generate scowl_en.txt seed from SCOWL source directory.")
    ap.add_argument("--source-dir", required=True, help="Path to SCOWL final/ directory")
    ap.add_argument(
        "--out",
        default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "seeds", "scowl_en.txt"),
        help="Output seed path (default: bench/lm/seeds/scowl_en.txt)",
    )
    args = ap.parse_args()

    if not os.path.isdir(args.source_dir):
        sys.exit(f"Error: source directory does not exist: {args.source_dir}")

    words, files = build_seed(args.source_dir)
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, "w", encoding="utf-8", newline="\n") as f:
        for w in words:
            f.write(f"{w}\n")

    print(f"Read {len(files)} SCOWL files from {args.source_dir}")
    print(f"Generated {len(words)} distinct words to {args.out}")


if __name__ == "__main__":
    main()
