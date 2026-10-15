#!/usr/bin/env python3
"""Small, fail-closed desktop quality receipts. Python standard library only.

No builds, discovery dispatch, production edits, or device operations. See QUALITY.md.
"""
import argparse
from collections import Counter, defaultdict
import csv
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys

REPO = Path(__file__).resolve().parents[2]
SETTINGS = {"language", "dialect", "autocorrect", "autocap", "learning"}
FIELDS = ("text", "suggestions", "auto", "events", "mode", "word", "context")


def digest(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def load(path):
    return json.loads(Path(path).read_text())


def save(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")


def jsonl(path):
    return [json.loads(line) for line in Path(path).read_text().splitlines() if line.strip()]


def newdir(path):
    path = Path(path).resolve()
    path.mkdir(parents=True, exist_ok=False)
    return path


def tree(root):
    return {str(p.relative_to(root)): digest(p) for p in sorted(root.rglob("*")) if p.is_file()}


def verify(root):
    root = Path(root).resolve()
    manifest = load(root / "manifest.json")
    files = manifest.get("files", manifest)
    assert files and any(p.startswith("lib/") for p in files), "missing engine identity"
    assert any(p.startswith("assets/") for p in files), "missing asset identity"
    for name, sha in files.items():
        p = root / name
        assert p.resolve().is_relative_to(root), "manifest path escape"
        assert p.is_file() and digest(p) == sha, f"manifest mismatch: {name}"
    # Reject injected/unmanifested jars or assets, not just changed listed files.
    for folder in ("lib", "assets", "source"):
        for p in (root / folder).rglob("*"):
            if p.is_file():
                assert str(p.relative_to(root)) in files, f"unmanifested: {p}"
    return digest(root / "manifest.json")


def freeze(args):
    out = newdir(args.out)
    if args.snapshot:
        src = Path(args.snapshot).resolve()
        verify(src)
        for folder in ("lib", "assets", "source"):
            shutil.copytree(src / folder, out / folder)
        origin = {"snapshot": str(src), "manifest_sha256": digest(src / "manifest.json")}
    else:
        shutil.copytree(REPO / "bench/build/install/mboard-bench/lib", out / "lib")
        shutil.copytree(REPO / "app/src/main/assets", out / "assets")
        for directory, dest in (("app/src/main/java", "source/app"),
                                ("bench/src/main/kotlin", "source/bench")):
            shutil.copytree(REPO / directory, out / dest)
        for name in ("bench/build.gradle.kts", "build.gradle.kts", "bench/run.sh",
                     "bench/scripts/summary.py", "gradle/libs.versions.toml",
                     "bench/lm/seeds/words_en.tsv", "bench/lm/seeds/words_ar.tsv"):
            p = REPO / name
            if p.is_file():
                dest = out / "source/build" / name
                dest.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(p, dest)
        origin = {"git_head": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=REPO, text=True).strip(),
                  "working_tree_not_HEAD": True,
                  "build_precondition": "caller must build installDist before freeze"}
        (out / "working-tree.diff").write_bytes(subprocess.check_output(["git", "diff", "--binary"], cwd=REPO))
    save(out / "manifest.json", {"files": tree(out), "origin": origin})
    print(json.dumps({"frozen": str(out), "manifest_sha256": verify(out)}))


def check(args):
    engine = Path(args.engine).resolve()
    identity = verify(engine)
    if args.working_tree:
        for frozen, live in (("source/app", "app/src/main/java"),
                             ("source/bench", "bench/src/main/kotlin"),
                             ("assets", "app/src/main/assets"),
                             ("lib", "bench/build/install/mboard-bench/lib")):
            assert tree(engine / frozen) == tree(REPO / live), f"verification invalidated: {live} changed"
        for p in (engine / "source/build").rglob("*"):
            if p.is_file():
                live = REPO / p.relative_to(engine / "source/build")
                assert live.is_file() and digest(p) == digest(live), f"verification invalidated: {live} changed"
    print(json.dumps({"manifest_sha256": identity, "working_tree_matches": args.working_tree}))


def native(path):
    records = jsonl(path)
    assert records and records[0].get("event") == "ready", "missing ready"
    assert records[-1].get("event") == "end", "missing end"
    assert sum(r.get("event") == "ready" for r in records) == 1, "duplicate ready"
    assert sum(r.get("event") == "end" for r in records) == 1, "duplicate end"
    settings = records[0]["settings"]
    assert set(settings) == SETTINGS, "incomplete settings"
    assert settings["language"] in ("en", "ar"), "invalid language"
    assert all(type(settings[k]) is bool for k in SETTINGS - {"language", "dialect"}), "invalid settings"
    assert settings["dialect"] in (True, False, None), "invalid dialect"
    commands, replies, pending, keys = [], [], None, []
    for r in records[1:-1]:
        event = r.get("event")
        if event == "command":
            assert pending is None, "missing reply"
            assert r["seq"] == len(commands) + 1, "nonconsecutive command"
            pending = json.loads(r["raw"])
            commands.append(pending)
            keys = []
        elif event == "reply":
            assert pending is not None and r["seq"] == len(commands), "unpaired reply"
            assert r.get("ok") is True, f"failed command {r}"
            assert r.get("id") == pending.get("id"), "reply id mismatch"
            assert all(k in r for k in FIELDS), "incomplete reply"
            assert r["keys"] == sum(k["key"] not in ("set", "wait") for k in keys), "missing key records"
            replies.append(r)
            pending = None
        elif event == "key":
            assert pending is not None and r["seq"] == len(commands), "unpaired key"
            assert r["n"] == len(keys) + 1, "nonconsecutive key records"
            keys.append(r)
        else:
            raise AssertionError(f"unknown native event {event}")
    assert pending is None and commands, "incomplete session"
    assert commands[-1].get("quit") is True, "no explicit quit (shutdown end is insufficient)"
    assert records[-1]["commands"] == len(commands) == len(replies), "command count mismatch"
    assert records[-1]["text"] == replies[-1]["text"], "end text mismatch"
    assert records[-1]["keys"] == sum(r["keys"] for r in replies), "end key count mismatch"
    return {"settings": settings, "starting_text": records[0]["text"],
            "commands": commands, "replies": replies}


def chain(session):
    previous = {"text": session["starting_text"], "word": "", "suggestions": []}
    words, empty, segments, current = [], [], [], []
    for command, reply in zip(session["commands"], session["replies"]):
        if "pick" in command:
            assert previous is not None, "pick without prior state"
            token = previous["suggestions"][command["pick"] - 1]
            expected = previous["text"][:-len(previous["word"])] if previous["word"] else previous["text"]
            assert reply["text"] == expected + token.rstrip() + " ", "picked token/text mismatch"
            words.append(token.rstrip())
            current.append(token.rstrip())
            if not reply["suggestions"]:
                empty.append(reply["seq"])
        elif any(k in command for k in ("type", "set", "key")):
            if current:
                segments.append(current)
                current = []
        previous = reply
    if current:
        segments.append(current)
    def grams(tokens):
        c = Counter(tuple(tokens[i:i + 4]) for i in range(len(tokens) - 3))
        return {"fourgrams": sum(c.values()), "unique": len(c),
                "repeated_patterns": sum(n > 1 for n in c.values()), "max_multiplicity": max(c.values(), default=0)}
    longest = max(segments, key=len, default=[])
    return {"picks_excluding_seed": len(words), "picked_tokens": words,
            "metrics_window": "longest uninterrupted pick sequence; seeds excluded",
            "segments": [{"picks": len(s), "first200": grams(s[:200]), "all": grams(s)} for s in segments],
            "first200": grams(longest[:200]), "all": grams(longest), "empty_post_pick_seq": empty}


def consolidate(args):
    root, out = Path(args.engine).resolve(), newdir(args.out)
    identity = verify(root)
    logs = sorted(Path(args.logs).resolve().rglob("*-agent*.jsonl"))
    assert logs, "no native logs found"
    accepted, rejected, seen = [], [], {}
    for path in logs:
        dest = out / "logs" / path.relative_to(Path(args.logs).resolve())
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, dest)
        try:
            session = native(path)
            sidecar = path.parent / "session.json"
            bound = False
            if sidecar.exists():
                binding = load(sidecar)
                assert binding["manifest_sha256"] == identity, "session manifest mismatch"
                assert binding["log_sha256"] == digest(path), "session log mismatch"
                assert binding["settings"] == session["settings"], "session settings mismatch"
                assert binding["exit_code"] == 0, "failed process"
                bound = True
            assert bound or args.legacy, "missing session identity binding (use --legacy for historical attribution only)"
            canonical = [{k: v for k, v in command.items() if k != "id"} for command in session["commands"]]
            key = json.dumps([session["settings"], session["starting_text"], canonical], sort_keys=True)
            duplicate = seen.get(key)
            seen.setdefault(key, str(path))

            row = {"log": str(dest), "log_sha256": digest(dest), "original_log": str(path),
                   "manifest_sha256": identity, "identity_binding": "verified sidecar" if bound else "historical directory attribution only",
                   "settings": session["settings"], "starting_text": session["starting_text"],
                   "commands": session["commands"], "command_seq": [r["seq"] for r in session["replies"]],
                   "duplicate_of": duplicate, "prediction": chain(session)}
            accepted.append(row)
        except (AssertionError, KeyError, ValueError, IndexError) as exc:
            rejected.append({"log": str(dest), "original_log": str(path), "log_sha256": digest(dest), "reason": str(exc)})
    save(out / "consolidated.json", {"manifest_sha256": identity, "logs_examined": len(logs),
         "accepted": accepted, "rejected": rejected, "unique_sessions": len(seen),
         "note": "Equivalent sessions deduplicated by exact settings/start/actions. Mechanism grouping requires investigation."})
    print(json.dumps({"examined": len(logs), "accepted": len(accepted), "rejected": rejected, "out": str(out)}))
    return bool(rejected)


def java(engine, heap, main):
    return ["java", f"-Xmx{heap}m", "-cp", str(engine / "lib/*"), f"com.m57.bench.{main}"]


def replay(args):
    engine, out = Path(args.engine).resolve(), newdir(args.out)
    identity = verify(engine)
    if args.native_log:
        recorded = native(args.native_log)
        scenarios = [{"id": Path(args.native_log).stem, "category": "recorded-session-replay",
                      "settings": recorded["settings"], "starting_text": recorded["starting_text"],
                      "actions": recorded["commands"][:-1], "quit_command": recorded["commands"][-1], "expect": [],
                      "source_log": str(Path(args.native_log).resolve()), "source_log_sha256": digest(args.native_log)}]
    else:
        scenarios = jsonl(args.scenarios)
    assert scenarios and len({s["id"] for s in scenarios}) == len(scenarios), "empty/duplicate scenario IDs"
    (out / "scenarios.jsonl").write_text("".join(json.dumps(s, ensure_ascii=False) + "\n" for s in scenarios))
    results = []
    for number, scenario in enumerate(scenarios):
        settings = scenario["settings"]
        assert set(settings) == SETTINGS and settings["language"] in ("en", "ar"), "explicit settings required"
        dest = out / f"{number:03d}"
        dest.mkdir()
        actions = scenario["actions"]
        assert actions and not any("quit" in a for a in actions), "helper supplies final quit"
        actions = actions + [scenario.get("quit_command", {"quit": True})]
        cmd = java(engine, args.heap, "SuggestKt") + ["--agent", "--assets", str(engine / "assets"),
               "--log-dir", str(dest), "--lang", settings["language"]]
        if settings["dialect"]:
            cmd.append("--dialect")
        for setting, flag in (("autocap", "--no-autocap"), ("learning", "--no-learning"), ("autocorrect", "--no-autocorrect")):
            if not settings[setting]:
                cmd.append(flag)
        cmd += ["--", scenario.get("starting_text", "")]
        payload = "".join(json.dumps(a, ensure_ascii=False) + "\n" for a in actions)
        (dest / "commands.jsonl").write_text(payload)
        proc = subprocess.run(cmd, input=payload, text=True, capture_output=True, timeout=args.timeout)
        (dest / "stdout.jsonl").write_text(proc.stdout)
        (dest / "stderr.txt").write_text(proc.stderr)
        assert proc.returncode == 0, f"JVM exit {proc.returncode}: {dest}"
        paths = list(dest.glob("*-agent*.jsonl"))
        assert len(paths) == 1, f"expected exactly one new native log: {dest}"
        log = paths[0]
        session = native(log)
        assert session["settings"] == settings and session["commands"] == actions, "replay settings/action mismatch"
        stdout = jsonl(dest / "stdout.jsonl")
        assert [r for r in stdout if "ok" in r] == [{k: v for k, v in r.items() if k != "event"} for r in session["replies"]], "stdout/native mismatch"
        checks = []
        for expectation in scenario.get("expect", []):
            reply = session["replies"][expectation["at"] - 1]
            passed = True
            if "target" in expectation:
                passed &= expectation["target"].lower() in [w.lower() for w in reply["suggestions"]]
            for field in ("text", "auto"):
                if field in expectation:
                    passed &= reply[field] == expectation[field]
            checks.append({"expect": expectation, "pass": bool(passed), "observed": reply})
        save(dest / "session.json", {"manifest_sha256": identity, "settings": settings,
             "log_sha256": digest(log), "exit_code": proc.returncode, "argv": cmd})
        results.append({"id": scenario["id"], "category": scenario.get("category"), "log": str(log),
                        "settings": settings, "checks": checks,
                        "observations": [{k: r[k] for k in (*FIELDS, "seq")} for r in session["replies"]]})
    assert verify(engine) == identity, "engine changed during replay"
    save(out / "replay.json", {"manifest_sha256": identity, "scenario_sha256": digest(out / "scenarios.jsonl"), "results": results,
         "failed_checks": sum(not c["pass"] for r in results for c in r["checks"])})
    print(json.dumps({"out": str(out), "scenarios": len(results), "failed_checks": sum(not c["pass"] for r in results for c in r["checks"])}))
    return any(not c["pass"] for r in results for c in r["checks"])


def rows(path):
    with Path(path).open(newline="") as stream:
        return list(csv.DictReader(stream, delimiter="\t"))


def target(row):
    return row["intended"].lower() in [word.lower() for word in row["shown"].split("|")]


def compare(args):
    baseline, candidate, out = Path(args.baseline), Path(args.candidate), newdir(args.out)
    if (baseline / "identity/run.json").exists() or (candidate / "identity/run.json").exists():
        bi, ci = load(baseline / "identity/run.json"), load(candidate / "identity/run.json")
        assert bi["sample_sha256"] == ci["sample_sha256"], "different quick samples"
        assert bi["classification_sha256"] == ci["classification_sha256"], "different classification vocabulary"
    names = {p.name for p in baseline.glob("*.typos.tsv")}
    assert names and names == {p.name for p in candidate.glob("*.typos.tsv")}, "missing/mismatched typo sets"
    reports = {}
    counts = Counter()
    for name in sorted(names):
        old, new = rows(baseline / name), rows(candidate / name)
        assert old and len(old) == len(new), f"row count mismatch: {name}"
        changes = defaultdict(list)
        for index, (b, c) in enumerate(zip(old, new), 2):
            assert all(b[k] == c[k] for k in ("context", "typed", "intended", "kind")), f"unaligned {name}:{index}"
            assert b["outcome"] in {"fixed", "offered", "wrong_fix", "missed"} and c["outcome"] in {"fixed", "offered", "wrong_fix", "missed"}
            receipt = {"line": index, "before": b, "after": c}
            if target(b) and not target(c):
                changes["targets_lost"].append(receipt)
            if target(c) and not target(b):
                changes["targets_gained"].append(receipt)
            if c["outcome"] == "wrong_fix" and (b["outcome"] != "wrong_fix" or b["shown"].split("|")[0] != c["shown"].split("|")[0]):
                changes["new_wrong"].append(receipt)
            if b["outcome"] == "wrong_fix" and c["outcome"] != "wrong_fix":
                changes["wrong_removed"].append(receipt)
            if b["outcome"] == "fixed" and c["outcome"] != "fixed":
                changes["correct_auto_lost"].append(receipt)
                if c["outcome"] == "offered" and target(c):
                    changes["auto_to_manual"].append(receipt)
        for key in ("targets_lost", "targets_gained", "new_wrong", "wrong_removed", "correct_auto_lost", "auto_to_manual"):
            counts[key] += len(changes[key])
        reports[name] = {"rows": len(old), "before_outcomes": dict(Counter(r["outcome"] for r in old)),
                         "after_outcomes": dict(Counter(r["outcome"] for r in new)), **changes}
    metrics = {}
    json_names = {p.name for p in baseline.glob("*.json")}
    assert json_names and json_names == {p.name for p in candidate.glob("*.json")}, "missing/mismatched metric sets"
    corruption = {}
    for name in sorted(json_names):
        b, c = load(baseline / name), load(candidate / name)
        assert all(b[k] == c[k] for k in ("lang", "dialect", "learning")), f"settings mismatch {name}"
        if "typos" in b:
            tsv = name[:-5] + ".typos.tsv"
            assert tsv in reports, f"missing rows {tsv}"
            assert b["typos"]["rows"] == c["typos"]["rows"] == reports[tsv]["rows"], "metric/TSV count mismatch"
        if "sentences" in b:
            assert b["sentences"]["words"] == c["sentences"]["words"], "sentence population mismatch"
            fields = ("found_before_end_pct", "keystroke_savings_pct", "false_autocorrect_count", "next_word_top1_pct", "next_word_top3_pct")
            metrics[name] = {k: {"baseline": b["sentences"][k], "candidate": c["sentences"][k]} for k in fields}
            metrics[name]["p95_us"] = {"baseline": b["sentences"]["latency_desktop_jvm"]["p95_us"], "candidate": c["sentences"]["latency_desktop_jvm"]["p95_us"]}
            for field, gate in (("found_before_end_pct", "clean_completion_declines"),
                                ("keystroke_savings_pct", "clean_savings_declines"),
                                ("next_word_top1_pct", "prediction_declines"),
                                ("next_word_top3_pct", "prediction_declines")):
                if c["sentences"][field] < b["sentences"][field]:
                    counts[gate] += 1
            miss = name[:-5] + ".misses.tsv"
            def bad(directory):
                return Counter((r["context"], r["target"], r["false_correction"]) for r in rows(directory / miss) if r["false_correction"])
            added = bad(candidate) - bad(baseline)
            assert sum(bad(baseline).values()) == b["sentences"]["false_autocorrect_count"], "baseline clean diagnostic count mismatch"
            assert sum(bad(candidate).values()) == c["sentences"]["false_autocorrect_count"], "candidate clean diagnostic count mismatch"
            corruption[name] = [{"context": k[0], "target": k[1], "replacement": k[2], "count": v} for k, v in added.items()]
    counts["new_valid_corruption"] = sum(r["count"] for rs in corruption.values() for r in rs)
    summary = subprocess.run([sys.executable, "-I", str(REPO / "bench/scripts/summary.py"), str(candidate), str(baseline)], capture_output=True, text=True, check=True)
    (out / "summary.txt").write_text(summary.stdout)
    blocked = any(counts[k] for k in ("targets_lost", "new_wrong", "correct_auto_lost", "new_valid_corruption",
                                     "clean_completion_declines", "clean_savings_declines", "prediction_declines"))
    receipt = {"baseline": str(baseline.resolve()), "candidate": str(candidate.resolve()),
               "input_hashes": {"baseline": tree(baseline), "candidate": tree(candidate)},
               "counts": dict(counts), "default_gate": "TRADEOFF_APPROVAL_REQUIRED" if blocked else "ROW_GATE_PASS",
               "sets": reports, "clean_metrics": metrics, "new_valid_corruption": corruption,
               "limitations": "Historical metrics do not bind results to jars. Row gate is not full acceptance; lint/build/state/device gates remain separate. Exact case-insensitive membership, no Arabic folding."}
    save(out / "comparison.json", receipt)
    text = ["mboard quality: aligned recorded baseline/candidate comparison", receipt["default_gate"],
            f"Aligned {sum(r['rows'] for r in reports.values())} rows in {len(reports)} sets, including real-word rows."]
    text += [f"{k}: {v}" for k, v in counts.items()]
    for name, data in reports.items():
        for loss in data["targets_lost"]:
            text.append(f"LOST {name}:{loss['line']} {loss['before']['typed']} -> {loss['before']['intended']}: {loss['before']['shown']} => {loss['after']['shown']}")
    text.append("Clean sets: found%, saved%, badAC count, next3%, desktop p95 us (baseline -> candidate)")
    for name, m in metrics.items():
        cells = [f"{m[k]['baseline']}->{m[k]['candidate']}" for k in ("found_before_end_pct", "keystroke_savings_pct", "false_autocorrect_count", "next_word_top3_pct", "p95_us")]
        text.append(name + ": " + ", ".join(cells))
    text += ["Per-language clean completion, savings, prediction and same-baseline p95: comparison.json / summary.txt.",
             "No general correctness, fresh blind evidence, or Android/editor acceptance is implied."]
    (out / "report.txt").write_text("\n".join(text) + "\n")
    print("\n".join(text))
    return blocked


def sample(args):
    out = newdir(args.out)
    sources, strata = {}, defaultdict(list)
    for p in sorted(Path(args.data).glob("*_typos.tsv")):
        sources[str(p)] = digest(p)
        language = p.name.split("_")[0]
        for line, row in enumerate(rows(p), 2):
            key = (language, row["kind"])
            stable = json.dumps([str(p), line, row], sort_keys=True, ensure_ascii=False)
            strata[key].append((hashlib.sha256(stable.encode()).hexdigest(), p, line, row))
    assert strata, "no dataset rows"
    selected = []
    for (lang, kind), values in sorted(strata.items()):
        for _, path, line, row in sorted(values, key=lambda v: v[0])[:args.per_kind]:
            selected.append({"source": str(path), "line": line, "language": "ar" if lang == "jo" else lang,
                             "dialect": lang == "jo", "kind": kind, "row": row})
    for lang, dialect in sorted({(r["language"], r["dialect"]) for r in selected}):
        label = lang + ("_dialect" if dialect else "")
        with (out / f"{label}.tsv").open("w", newline="") as stream:
            writer = csv.DictWriter(stream, fieldnames=("context", "typed", "intended", "kind"), delimiter="\t", lineterminator="\n")
            writer.writeheader()
            writer.writerows(r["row"] for r in selected if (r["language"], r["dialect"]) == (lang, dialect))
    for lang in ("en", "ar"):
        shutil.copy2(REPO / f"bench/lm/seeds/words_{lang}.tsv", out / f"classification_{lang}.tsv")
    save(out / "sample.json", {"provenance": "fixed hash-selected tuning regression sample, NOT blind evidence", "source_sha256": sources, "selected": selected, "files": tree(out)})
    print(json.dumps({"rows": len(selected), "strata": len(strata), "out": str(out)}))


def quick(args):
    engine, sample_dir, out = Path(args.engine).resolve(), Path(args.sample).resolve(), newdir(args.out)
    identity = verify(engine)
    manifest = load(sample_dir / "sample.json")
    for name, sha in manifest["files"].items():
        assert digest(sample_dir / name) == sha, "sample changed"
    commands = []
    sample_names = {r["language"] + ("_dialect" if r["dialect"] else "") + ".tsv" for r in manifest["selected"]}
    assert {p.name for p in sample_dir.glob("*.tsv")} == sample_names | {"classification_en.tsv", "classification_ar.tsv"}, "unexpected sample files"
    for path in (sample_dir / name for name in sorted(sample_names)):
        lang = path.stem.split("_")[0]
        classification = out / f"classification_{lang}.tsv"
        if not classification.exists():
            frozen = engine / f"source/build/bench/lm/seeds/words_{lang}.tsv"
            # Legacy round-7 snapshots predate classification freezing. Use the immutable
            # supplied sample's classification, never silently read the live repository.
            shutil.copy2(frozen if frozen.exists() else sample_dir / f"classification_{lang}.tsv", classification)
        cmd = java(engine, args.heap, "MainKt") + ["--lang", lang, "--assets", str(engine / "assets"), "--typos", str(path),
              "--classification-words", str(classification), "--label", path.stem, "--out", str(out)]
        if "dialect" in path.stem:
            cmd.append("--dialect")
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=args.timeout)
        (out / f"{path.stem}.stdout.txt").write_text(proc.stdout)
        (out / f"{path.stem}.stderr.txt").write_text(proc.stderr)
        assert proc.returncode == 0, f"quick failed: {path.stem}"
        commands.append(cmd)
    assert verify(engine) == identity, "engine changed"
    # Keep metadata outside the result glob consumed by summary.py.
    save(out / "identity" / "run.json", {"manifest_sha256": identity, "sample_sha256": digest(sample_dir / "sample.json"),
         "classification_sha256": {p.name: digest(p) for p in out.glob("classification_*.tsv")}, "argv": commands})
    print(json.dumps({"quick": str(out), "sets": len(commands)}))


def report(args):
    out = newdir(args.out)
    comparison = load(Path(args.comparison) / "comparison.json")
    consolidation = load(Path(args.consolidation) / "consolidated.json")
    replays = {str(Path(p).resolve()): load(Path(p) / "replay.json") for p in args.replays}
    findings, prediction = [], []
    for directory, replay_data in replays.items():
        scenarios = {s["id"]: s for s in jsonl(Path(directory) / "scenarios.jsonl")}
        for result in replay_data["results"]:
            if result["category"] == "learning-state":
                prediction.append({"replay": directory, "scenario": result["id"],
                                   "settings": result["settings"], "seed_actions": scenarios[result["id"]]["actions"][:1],
                                   "metrics": chain(native(result["log"]))})
            for check in result["checks"]:
                if check["pass"]:
                    continue
                expectation = check["expect"]
                classification = ("Confirmed missing useful manual choice/completion" if "target" in expectation
                                  else "Confirmed destructive replacement or state bug")
                findings.append({"classification": classification, "grouping": "provisional; no root-cause claim",
                                 "scenario": scenarios[result["id"]], "engine_manifest_sha256": replay_data["manifest_sha256"],
                                 "native_log": result["log"], "command_seq": expectation["at"],
                                 "observed": check["observed"], "expected": expectation})
    receipt = {"comparison": comparison, "consolidation_receipt": str(Path(args.consolidation).resolve() / "consolidated.json"),
               "quick_comparison": load(Path(args.quick_comparison) / "comparison.json") if args.quick_comparison else None,
               "execution": load(args.execution) if args.execution else None,
               "prediction": prediction,
               "historical_prediction": [{"log": r["log"], "settings": r["settings"], "metrics": r["prediction"]}
                                         for r in consolidation["accepted"] if r["prediction"]["picks_excluding_seed"] >= 200],
               "replays": {k: {"manifest_sha256": v["manifest_sha256"], "scenarios": len(v["results"]), "failed_checks": v["failed_checks"]} for k, v in replays.items()},
               "findings": findings,
               "triage": "No fix batch authorized or selected here. At most three investigated mechanism groups per next approved batch; all others queued.",
               "prediction_comparison": "Historical chains differ in seed/dialect/learning and are NOT a learning-only ablation. New paired learning sentinels hold all other settings and seed fixed."}
    if len(replays) >= 2:
        before, after = list(replays.values())[:2]
        assert before["scenario_sha256"] == after["scenario_sha256"], "different replay scenarios"
        deltas = []
        for b, c in zip(before["results"], after["results"]):
            assert b["id"] == c["id"] and b["settings"] == c["settings"], "unaligned replay"
            assert len(b["observations"]) == len(c["observations"]), "unaligned replay actions"
            for bo, co in zip(b["observations"], c["observations"]):
                if any(bo[k] != co[k] for k in FIELDS):
                    deltas.append({"scenario": b["id"], "seq": bo["seq"], "before": bo, "after": co})
        receipt["replay_deltas"] = deltas
    save(out / "receipts.json", receipt)
    text = ["MBOARD QUALITY WORKFLOW — CONSOLIDATED EVIDENCE REPORT", "",
            (Path(args.comparison) / "report.txt").read_text().strip(), "",
            f"Native logs: {consolidation['logs_examined']} examined; {len(consolidation['accepted'])} accepted; {len(consolidation['rejected'])} rejected.",
            "Historical logs have directory attribution, not retroactively proven per-session engine binding."]
    for row in consolidation["rejected"]:
        text.append(f"REJECTED {row['log']}: {row['reason']}")
    for path, data in receipt["replays"].items():
        text.append(f"Replay {path}: {data['scenarios']} scenarios; {data['failed_checks']} failed expectations. Failures retained, never fixture-adjusted.")
    if receipt["quick_comparison"]:
        qc = receipt["quick_comparison"]
        text.append(f"Fixed quick sample: {sum(s['rows'] for s in qc['sets'].values())} aligned rows; {qc['default_gate']}; {qc['counts']}.")
    for p in prediction:
        m = p["metrics"]
        text.append(f"Learning replay {Path(p['replay']).name}/{p['scenario']}: {m['picks_excluding_seed']} exact picks excluding seed; first200={m['first200']}; full={m['all']}; empty pools={len(m['empty_post_pick_seq'])}.")
    for p in receipt["historical_prediction"]:
        m = p["metrics"]
        text.append(f"Historical chain {Path(p['log']).name}: {m['picks_excluding_seed']} exact picks excluding seed; first200={m['first200']}; full={m['all']}; empty pools={len(m['empty_post_pick_seq'])}.")
    if receipt["execution"]:
        text.append("Commands actually run and verification: execution.json (embedded in receipts.json).")
    text += ["", "Triage: failed expectations are evidence of observed behavior versus the stated intent, not proven mechanisms.",
             "Preserved typos with useful manual targets are intentional conservative behavior, not automatically defects.",
             "A wrong manual pick is not an automatic replacement. Vocabulary requests require source investigation.",
             "Unsupported/editor-only claims remain unconfirmed; desktop replay cannot settle device compatibility.",
             receipt["prediction_comparison"], receipt["triage"],
             f"Default gate: {comparison['default_gate']}. Approval is not inferred from an earlier 'accepted' heading.",
             "This report compares supplied result directories; it does not execute a full benchmark. Historical tuning results are regression evidence, not fresh blind evidence.",
             "Full acceptance still requires a source/asset freeze, lint, APK/desktop builds, complete existing run.sh replay, every-row comparison and sentinel state gate after the last behavioral edit.",
             "STOP: no discovery/persona wave, production behavior change, commit, push or phone action."]
    (out / "report.txt").write_text("\n".join(text) + "\n")
    print("\n".join(text))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("freeze")
    p.add_argument("--snapshot")
    p.add_argument("--out", required=True)
    p.set_defaults(func=freeze)
    p = sub.add_parser("check")
    p.add_argument("--engine", required=True)
    p.add_argument("--working-tree", action="store_true")
    p.set_defaults(func=check)
    p = sub.add_parser("consolidate")
    p.add_argument("--engine", required=True)
    p.add_argument("--logs", required=True)
    p.add_argument("--legacy", action="store_true")
    p.add_argument("--out", required=True)
    p.set_defaults(func=consolidate)
    p = sub.add_parser("replay")
    p.add_argument("--engine", required=True)
    inputs = p.add_mutually_exclusive_group(required=True)
    inputs.add_argument("--scenarios")
    inputs.add_argument("--native-log")
    p.add_argument("--out", required=True)
    p.add_argument("--heap", type=int, default=512)
    p.add_argument("--timeout", type=int, default=90)
    p.set_defaults(func=replay)
    p = sub.add_parser("compare")
    p.add_argument("--baseline", required=True)
    p.add_argument("--candidate", required=True)
    p.add_argument("--out", required=True)
    p.set_defaults(func=compare)
    p = sub.add_parser("sample")
    p.add_argument("--data", default=str(REPO / "bench/data"))
    p.add_argument("--per-kind", type=int, default=2)
    p.add_argument("--out", required=True)
    p.set_defaults(func=sample)
    p = sub.add_parser("quick")
    p.add_argument("--engine", required=True)
    p.add_argument("--sample", required=True)
    p.add_argument("--out", required=True)
    p.add_argument("--heap", type=int, default=512)
    p.add_argument("--timeout", type=int, default=180)
    p.set_defaults(func=quick)
    p = sub.add_parser("report")
    p.add_argument("--comparison", required=True)
    p.add_argument("--consolidation", required=True)
    p.add_argument("--replays", nargs="+", required=True)
    p.add_argument("--quick-comparison")
    p.add_argument("--execution")
    p.add_argument("--out", required=True)
    p.set_defaults(func=report)
    args = parser.parse_args()
    try:
        return 2 if args.func(args) else 0
    except (AssertionError, ValueError, KeyError, OSError, subprocess.SubprocessError) as exc:
        print(f"quality: REJECTED: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
