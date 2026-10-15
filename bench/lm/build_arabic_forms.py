#!/usr/bin/env python3
"""Generate an Arabic spelling-protection asset from the pinned CAMeL Morph MSA release.

Run with the tiny offline-generator dependency (not an Android dependency):
  uv run --with six==1.17.0 python -I bench/lm/build_arabic_forms.py --cache-dir CACHE

The upstream generator validates morpheme compatibility. Proper names, backoff classes,
and unrestricted clitic cross-products are not imported. The output is ONLY a preservation
lexicon, not frequency evidence or a completion/correction-target list.
Data: CC BY 4.0; upstream Python modules: MIT. See SOURCES.md and packaged notices.
"""
import argparse
import hashlib
import importlib
import json
import os
from pathlib import Path
import re
import sys
import time
import types
import urllib.request

REVISION = "15f5aede4b609db54abd6f87aded1f5ec5d930af"
BASE_URL = f"https://raw.githubusercontent.com/CAMeL-Lab/camel_morph/{REVISION}/official_releases/lrec-coling2024_release/"
MODULE_BASE = "camel_morph/camel_tools/camel_tools/"
SOURCES = {
    "morphology/database.py": (MODULE_BASE + "morphology/database.py", "2f3283042ac706471fde0fb498e438724e96a057ea042de6df8d0f76de16d66f"),
    "morphology/generator.py": (MODULE_BASE + "morphology/generator.py", "41f4ea954de6edf4c2457dcb5552ba49e5ad8efe159a525c6ea0b294edebe460"),
    "morphology/utils.py": (MODULE_BASE + "morphology/utils.py", "a24ece6a94e0e2e7027a39d5dacc83e73e9d0ee5c7d7d8669e0fd940918d0de0"),
    "morphology/errors.py": (MODULE_BASE + "morphology/errors.py", "b00c071977d1d7b3b5f7ca58fbd34e72ef703a7c32b6feaa883d530f9617dd4d"),
    "utils/stringutils.py": (MODULE_BASE + "utils/stringutils.py", "cb354ca45fc49397ae371484c2f43fd45c10bd14d2fda7dfc49f757af3846b7d"),
    "camel_morph_msa_v1.0.db": ("databases/camel-morph-msa/camel_morph_msa_v1.0.db", "110e03baa282874062d076e23d9671e985b6f76520b891d58cba4e55de0b8f60"),
}
ALLOWED_POS = {
    "noun", "adj", "adj_comp", "adj_num", "noun_num", "noun_quant", "verb",
    "verb_nom", "verb_pseudo", "adv", "prep", "part", "conj", "pron", "interj",
}
ARABIC_WORD = re.compile(r"[\u0621-\u064a]{2,32}")


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def fetch_sources(cache):
    for name, (relative_url, expected) in SOURCES.items():
        target = cache / name
        if target.is_file() and sha256(target.read_bytes()) == expected:
            continue
        data = urllib.request.urlopen(BASE_URL + relative_url, timeout=120).read()
        if sha256(data) != expected:
            raise ValueError(f"Source hash mismatch: {name}")
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)


def load_generator(cache):
    try:
        import six  # noqa: F401 -- required by the unmodified upstream stringutils module
    except ImportError as error:
        raise RuntimeError("Run with uv run --with six==1.17.0 python -I") from error
    # Namespace packages expose the pinned upstream modules without executing their unrelated
    # package installers or importing a full NLP/ML dependency tree. These are not API mocks.
    for name, path in [("camel_tools", cache), ("camel_tools.morphology", cache / "morphology"), ("camel_tools.utils", cache / "utils")]:
        package = types.ModuleType(name)
        package.__path__ = [str(path)]
        sys.modules[name] = package
    MorphologyDB = importlib.import_module("camel_tools.morphology.database").MorphologyDB
    Generator = importlib.import_module("camel_tools.morphology.generator").Generator
    db = MorphologyDB(str(cache / "camel_morph_msa_v1.0.db"), flags="g")
    # The release's optional diac_only optimization has an invalid locals() lookup; the
    # ordinary, unmodified generator path applies the correct upstream rewrite functions.
    return db, Generator(db)


def surface_form(diacritized):
    return "".join(c for c in diacritized if not ("\u064b" <= c <= "\u065f" or c in "\u0670\u0640")).replace("\u0671", "ا")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache-dir", type=Path, default=Path(os.environ.get("TMPDIR", str(Path.home() / ".cache"))) / "mboard-camel-morph")
    parser.add_argument("--out", type=Path, default=Path(__file__).resolve().parents[2] / "app/src/main/assets/known_forms_ar.txt")
    args = parser.parse_args()
    started = time.monotonic()
    fetch_sources(args.cache_dir)
    db, generator = load_generator(args.cache_dir)
    forms = set()
    lemmas = 0
    analyses = 0
    for lemma, stems in sorted(db.lemma_hash.items()):
        poses = {stem["pos"] for stem in stems} & ALLOWED_POS
        if not poses:
            continue
        lemmas += 1
        for pos in sorted(poses):
            # Unspecified clitics default to zero in the upstream generator. Person, gender,
            # number, tense and mood inflections are enumerated by its compatibility tables.
            for analysis in generator.generate(lemma, {"pos": pos}):
                analyses += 1
                form = surface_form(analysis["diac"])
                if ARABIC_WORD.fullmatch(form):
                    forms.add(form)
        if lemmas % 2000 == 0:
            print(f"{lemmas} lemmas, {len(forms)} forms", flush=True)
    data = ("\n".join(sorted(forms)) + "\n").encode("utf-8")
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_bytes(data)
    manifest = {
        "source_revision": REVISION,
        "data_license": "CC-BY-4.0",
        "source_hashes": {name: expected for name, (_, expected) in SOURCES.items()},
        "allowed_pos": sorted(ALLOWED_POS),
        "forms": len(forms), "lemmas": lemmas, "analyses": analyses,
        "sha256": sha256(data),
    }
    args.out.with_suffix(".source.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({**manifest, "elapsed_seconds": round(time.monotonic() - started, 1)}), flush=True)


if __name__ == "__main__":
    main()
