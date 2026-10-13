"""Print one table of the headline numbers in a results directory; optional second dir shows the change."""
import json
import pathlib
import sys

SENT = [
    ("next_word_top3_pct", "next3"),
    ("next_word_top1_pct", "next1"),
    ("one_letter_top3_pct", "1let3"),
    ("keystroke_savings_pct", "saved"),
    ("found_before_end_pct", "found"),
    ("false_autocorrect_pct", "badAC"),
]
TYPO = [("autofixed_pct", "fixed"), ("in_strip_pct", "inStrip"), ("wrong_fix_pct", "wrongFix")]


def load(d):
    out = {}
    for f in sorted(pathlib.Path(d).glob("*.json")):
        out[f.stem] = json.loads(f.read_text())
    return out


def fmt(v, base):
    if base is None:
        return f"{v:7.2f}"
    return f"{v:7.2f}({v - base:+.2f})"


def main():
    cur = load(sys.argv[1])
    base = load(sys.argv[2]) if len(sys.argv) > 2 else {}
    print("SENTENCES  " + " ".join(f"{n:>8}" for _, n in SENT) + "   words  p95us")
    for label, r in cur.items():
        s = r.get("sentences")
        if not s:
            continue
        b = base.get(label, {}).get("sentences")
        cells = " ".join(fmt(s[k], b and b.get(k)) for k, _ in SENT)
        print(f"{label:<18} {cells} {s['words']:>7} {s['latency_desktop_jvm'].get('p95_us', 0):>6}")
    print()
    print("TYPOS      " + " ".join(f"{n:>9}" for _, n in TYPO) + "   rows")
    for label, r in cur.items():
        t = r.get("typos")
        if not t:
            continue
        b = base.get(label, {}).get("typos")
        cells = " ".join(fmt(t[k], b and b.get(k)) for k, _ in TYPO)
        print(f"{label:<18} {cells} {t['rows']:>6}")
        for kind, kt in t["by_kind"].items():
            kb = b and b["by_kind"].get(kind)
            cells = " ".join(fmt(kt[k], kb and kb.get(k)) for k, _ in TYPO)
            print(f"  {kind:<16} {cells} {kt['rows']:>6}")


main()
