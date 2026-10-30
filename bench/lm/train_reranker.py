#!/usr/bin/env python3
"""Dependency-free launcher for the Java SGD trainer. Run in nix develop.

Count-table inputs MUST be training-only outputs of build_ngrams.py. English and
Arabic use the exact compressed-byte ranges from rebuild.sh. Jordanian MUST use
jo_shami_train.txt plus the synthetic augmentation, never full jo_shami.txt.
No evaluation text is read here; build_ngrams.py owns the exclusion list.
Example:
  python3 -I bench/lm/train_reranker.py --en bench/lm/out_en \
    --ar bench/lm/out_ar --jo bench/lm/out_jo_split
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for lang in ('en', 'ar', 'jo'):
        p.add_argument('--' + lang, type=Path, required=True)
    p.add_argument('--out', type=Path, default=Path('bench/lm/out_reranker'))
    p.add_argument('--manifest', type=Path, default=Path('bench/lm/reranker_manifest.json'))
    args = p.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    manifest = {'seed': 57485, 'epochs': 4, 'threads': 4, 'dim': 48,
                'hidden': 128, 'vocab': 10000, 'learning_rate': 0.05,
                'weight_power': 0.5, 'inputs': {}, 'outputs': {}}
    for lang in ('en', 'ar', 'jo'):
        directory = getattr(args, lang)
        if lang == 'jo' and 'split' not in directory.name:
            p.error('Jordanian tables must be the split-trained out_jo_split directory')
        manifest['inputs'][lang] = {}
        for name in ('unigram_', 'next_', 'context_'):
            file = directory / (name + lang + '.tsv')
            manifest['inputs'][lang][file.name] = sha(file)
        output = args.out / ('rerank_' + lang + '.bin')
        subprocess.run(['java', str(Path(__file__).with_name('TrainReranker.java')),
                        '--lang', lang, '--tables', str(directory), '--out', str(output),
                        '--epochs', '4', '--threads', '4', '--seed', '57485'], check=True)
        manifest['outputs'][output.name] = {'sha256': sha(output), 'bytes': output.stat().st_size}
    if sum(x['bytes'] for x in manifest['outputs'].values()) > 2_000_000:
        raise RuntimeError('Combined assets exceed 2 MB')
    args.manifest.write_text(json.dumps(manifest, indent=2, sort_keys=True) + '\n')


if __name__ == '__main__':
    main()
