"""Standalone row audit for card 298. Not an app test or CI dependency."""
import csv
import json
from pathlib import Path
import sys

base, candidate = map(Path, sys.argv[1:3])
assert base.is_dir() and candidate.is_dir()
assert {p.name for p in base.glob('*.json')} == {p.name for p in candidate.glob('*.json')}
report = {'sets': {}, 'typo_target_changes': []}
for path in sorted(base.glob('*.json')):
    old = json.loads(path.read_text())
    new = json.loads((candidate/path.name).read_text())
    row = {}
    for section in ('sentences', 'typos'):
        if section not in old:
            continue
        a, b = old[section], new[section]
        row[section] = {k: {'base': v, 'candidate': b[k], 'delta': round(b[k]-v, 4)}
                        for k, v in a.items() if isinstance(v, (int, float)) and k in b and (k.endswith('_pct') or k in ('words', 'rows', 'scored_rows', 'false_autocorrect_count'))}
        if section == 'sentences':
            assert a['false_autocorrect_count'] == b['false_autocorrect_count']
            assert a['false_autocorrect_pct'] == b['false_autocorrect_pct']
        else:
            assert a['autofixed_pct'] == b['autofixed_pct']
            assert a['wrong_fix_pct'] == b['wrong_fix_pct']
        if 'words_modified' in a:
            assert a['words_modified'] == b['words_modified']
    report['sets'][path.stem] = row
for path in sorted(base.glob('*.tsv')):
    newpath = candidate/path.name
    assert newpath.exists()
    identical = path.read_bytes() == newpath.read_bytes()
    report['sets'].setdefault(path.name.split('.')[0], {})[path.suffixes[-2]+'.tsv_identical'] = identical
    if path.name.startswith(('en_', 'ar_')) or path.name.startswith('jo_chat_msa'):
        assert identical, path.name
    if not path.name.endswith('.typos.tsv'):
        continue
    old = list(csv.DictReader(path.open(), delimiter='\t'))
    new = list(csv.DictReader(newpath.open(), delimiter='\t'))
    assert len(old) == len(new)
    for number, (a, b) in enumerate(zip(old, new), 2):
        assert all(a[k] == b[k] for k in ('context', 'typed', 'intended', 'kind'))
        oldhit = a['intended'] in a['shown'].split('|')
        newhit = b['intended'] in b['shown'].split('|')
        if oldhit != newhit:
            report['typo_target_changes'].append({'set': path.stem, 'line': number, 'change': 'gained' if newhit else 'lost', 'before': a, 'after': b})
print(json.dumps(report, ensure_ascii=False, indent=2))
