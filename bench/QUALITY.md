# Small quality loop

This is desktop regression tooling, not Android editor/device acceptance. The helper uses only Python's standard library and invokes the existing SuggestKt native protocol, MainKt replay and summary.py. It never builds, edits production, dispatches personas, installs, commits or pushes. No tests or test dependencies are introduced.

## Evidence and commands

Run from the repository root. Outputs must be new directories: the helper refuses to overwrite a run. Exit 0 means the requested operation completed; exit 2 means validation failed, expectations failed, or a regression/tradeoff gate blocked. Inspect the receipt; never hide exit 2 behind a successful final shell command. A comparison's ROW_GATE_PASS is only its measured row gate, not whole-candidate acceptance.

The durable round-7 demonstration lives under bench/reports/quality/round7-demo/. Its report is final/report.txt, with final/receipts.json as the machine-readable index. Existing recorded-results/ are copied historical results, not newly executed full benchmarks. Frozen engines, every imported native log (including rejected logs), scenario snapshots, argv, stderr, stdout and hashes remain under this directory. These are local durable files, not a promise that large jars or logs will be committed.

  python3 -I bench/scripts/quality.py --help
  python3 -I bench/scripts/quality.py check --engine bench/reports/quality/round7-demo/engines/current --working-tree

`freeze --out DIR` copies the current compiled desktop lib/, assets/, production and desktop source, build configuration and classification vocabularies into a new durable directory. Build installDist first; freezing is not compilation. Source must remain unchanged between build and freeze. It records HEAD plus the dirty diff; it does not assume HEAD is the baseline. `freeze --snapshot OLD --out DIR` verifies and imports an older hash manifest. `check` verifies all frozen file hashes and rejects unmanifested engine/asset/source files. `--working-tree` additionally rejects live source, assets, jars, or frozen build-input changes. Old flattened source snapshots cannot be checked against today's worktree.

Native logs do not themselves contain jar/asset hashes. New helper sessions have a session.json sidecar containing manifest hash, log hash, settings, argv and process exit. Consolidation requires that binding by default. `--legacy` explicitly allows historical directory attribution, not retroactive cryptographic proof. It still checks the supplied engine manifest. Do not use --legacy for new discovery sessions.

  python3 -I bench/scripts/quality.py consolidate --engine ENGINE --logs SESSION_ROOT --out RUN/native

Every *-agent*.jsonl below SESSION_ROOT is examined, not just the first glob match. A ready record, all ordered command/reply pairs, successful commands, ordered key records/counts, explicit quit and matching end totals are required. Failed commands invalidate that session even if the rest of it finished. Invalid logs remain copied with reasons. Exact equivalent settings/start/actions deduplicate after dropping transport IDs; this is reproduction equivalence, not proof of a shared mechanism.

## Scenario format

bench/data/quality_sentinels.jsonl is independent of the helper. Each line is one fresh JVM session:

  {"id":"partial","category":"partial-competing","settings":{"language":"en","dialect":null,"autocorrect":true,"autocap":false,"learning":false},"starting_text":"","actions":[{"type":"shif"},{"key":"space"}],"expect":[{"at":1,"target":"shift","auto":null},{"at":2,"text":"shif "}]}

All settings are explicit; English dialect is null, Arabic dialect is Boolean. Actions are unchanged native JSON commands. `at` is the one-based reply/command sequence. Expectations can require exact text, exact auto (including null), and target membership in actual shown tokens (case-insensitive, no Arabic orthographic folding). Expectations describe intent, not a command to silently tune fixtures. The helper appends quit; don't put quit in actions. Saved logs support literal fixed picks; do not substitute a semantic target for a recorded pick index during replay.

  nix develop -c python3 -I bench/scripts/quality.py replay --engine ENGINE --scenarios bench/data/quality_sentinels.jsonl --out RUN/sentinels
  nix develop -c python3 -I bench/scripts/quality.py replay --engine ENGINE --native-log RECORDED-agent.jsonl --out RUN/exact-reproduction

Each scenario owns a log directory and a fresh JVM with -Xmx512m by default; each JVM has a 90-second timeout. stdout is compared against its entire native log. Nonzero JVM exits, invalid protocols, settings or action mismatches are rejected, not synthesized into plausible replies. State and expected-target failures are retained in replay.json and return exit 2. The sentinels intentionally include the existing glars/glass and exsplain/explained missing targets: a failed current run is expected evidence, not permission to change them to current output. Other failed sentinels also remain visible; investigate rather than auto-accepting.

The sentinels cover preservation, contractions/possessives, partials, competing edits, Arabic orthographic/attached forms, dialect on/off, pick/punctuation, manual spaces, reset, literal picks, undo and earlier collateral cases. Two bounded 210-pick learning sessions use identical seed and settings except learning; these are replay probes, not personas or discovery. Historical 700-pick chains are separately retained. Consolidation counts exact chosen slot tokens excluding the seed, verifies committed text, splits on manual edits, and reports first-200 repetition, full-run recurrence and empty pools independently. Metrics apply to the longest uninterrupted pick segment, not a concatenation across resets. Historical chains with different seeds/dialects are not learning-only comparisons. Neither chain diversity nor nonempty pools proves coherent prose.

## Fixed quick gate

Create a sample once, before candidate editing, and never resample to pass a candidate:

  python3 -I bench/scripts/quality.py sample --out RUN/sample
  nix develop -c python3 -I bench/scripts/quality.py quick --engine BASELINE --sample RUN/sample --out RUN/quick-baseline
  nix develop -c python3 -I bench/scripts/quality.py quick --engine CANDIDATE --sample RUN/sample --out RUN/quick-candidate
  python3 -I bench/scripts/quality.py compare --baseline RUN/quick-baseline --candidate RUN/quick-candidate --out RUN/quick-comparison

The fixed hash-selected sample selects two existing rows per language family/error-kind stratum; it records every source file hash and row reference. The demonstration has 50 rows in 25 strata across English, MSA and Jordanian. This is a tuning regression set, not blind evidence. Classification vocabularies are frozen too. Comparison rejects differing sample or classification identities. Sentinels supply clean/literal/state coverage; the quick typo sample alone does not measure clean-completion savings or Android compatibility.

## Next authorized fix batch: no new discovery required

1. Obtain explicit scope for the batch and decisions on existing tradeoffs. Treat engines/current (verified dirty closing worktree), not HEAD or round7c, as the next baseline. Today's workflow does not authorize fixes or waive the two lost targets/193 auto-to-manual changes relative to round7c.
2. Re-run check --working-tree. Build desktop once if source/build inputs changed, then freeze a new baseline. Replay the exact reproduction before any fix. For the two recorded losses, compare the existing round7c/closing TSV rows and frozen native glars/exsplain sentinels. Investigate finite-slot competing alternatives; do not add typo-specific exceptions. This is a proposed small manual-availability batch, not an asserted root cause.
3. Human-sized triage: confirmed destructive replacement/state bug; confirmed missing useful manual choice/completion; intentional conservative behavior; vocabulary request; unsupported/simulator-only claim. A preserved typo with a useful correction is not automatically broken. A wrong manual pick is not an automatic correction. Log a finding with manifest hashes, ALL settings, starting text, full action sequence, native filename/seq references, observed text/shown/auto/events, intended result or explicit allowed alternatives, and concern (corruption/manual/completion/learning-state). Agent narratives are hypotheses. Deduplicate exact reproductions automatically, then prove mechanisms before grouping. Label uninvestigated groups provisional.
4. Select at most three investigated root-cause groups; preferably one here. Queue all vocabulary expansion, unrelated prediction work and remaining leads. Do not chase each new word.
5. After a meaningful edit build only the changed target (`nix develop -c ./gradlew -p bench installDist --console=plain` for engine/glue; APK only when needed). Freeze to a new directory. Re-run exact affected reproductions, immutable sentinels and the same fixed quick sample. Inspect every newly wrong replacement, lost target and correct-auto downgrade immediately. If the batch repeatedly fails (two unsuccessful fix/gate iterations), preserve baseline, return the concrete blocker and stop; don't expand scope. Do not run the full benchmark after every experiment.
6. Only after quick gates pass, freeze final source/assets and run full acceptance below. If a finite-slot loss or safety/recall tradeoff is needed, present exact rows and metrics for m57's explicit approval. No implicit 'safer wins' policy.

## Full acceptance after the final behavioral edit

Use new result labels. Build/lint exits must be observable separately. No tests, formatting unrelated files, phone actions or publishing are authorized by this document.

  nix develop -c ktlint 'app/src/main/java/**/*.kt' 'bench/src/**/*.kt'
  nix develop -c ./gradlew assembleDebug --console=plain
  nix develop -c ./gradlew -p bench installDist --console=plain
  python3 -I bench/scripts/quality.py freeze --out RUN/final-engine
  nix develop -c bash bench/run.sh UNIQUE_LABEL
  python3 -I bench/scripts/quality.py check --engine RUN/final-engine --working-tree
  python3 -I bench/scripts/quality.py compare --baseline BASELINE_FULL_RESULTS --candidate bench/results/UNIQUE_LABEL --out RUN/full-comparison

The existing run.sh intentionally builds incrementally before running its complete configured sets and propagates JVM failures; it is not replaced by the helper. Serialize builds. Run complete replays as bounded background jobs, collect once finished, and retain command exits. Copy benchmark outputs into the durable run directory. Both engines must use identical datasets and harness semantics; if the harness changes, rerun the baseline. Old historical results lack a complete dataset/command binding; reusing them demonstrates analysis, not a new full acceptance certificate.

The clean benchmark's false_correction diagnostic excludes its folded Arabic orthographic-equivalence cases. Its zero-new-corruption count is therefore bounded by that existing definition; it is not proof of literal preservation for every Arabic form. Exact-text sentinels independently protect selected valid literals such as لدى. Missing all-word clean traces cannot be reconstructed from headline percentages or subset miss files.

compare checks every aligned typo row including real-word buckets and wrong_fix rows, and actual shown-target membership independently of labels. It detects changed wrong targets even when both labels remain wrong_fix. It separately records new wrong replacements, removed wrong replacements, correct autos lost, auto-to-manual transitions, gained/lost targets, newly corrupted clean text, per-language clean completion/key savings, learning/prediction metrics and desktop p95 against the same baseline. Sentence miss files are subset diagnostics rather than aligned all-word traces; new clean false corrections are compared as multisets, with population counts checked. New files/rows or settings mismatches fail closed. The helper also calls existing summary.py after validating both directories, avoiding its missing-baseline fallback.

Default acceptance blocks any new valid-text corruption/state failure, newly wrong replacement, unexplained target loss or silent correct-auto reduction. Inspect each language separately. Latency is desktop-only and environment-sensitive, not Android timing. A row pass doesn't cover a failed state sentinel. Lint/build and exact replay do not establish InputConnection parity or typing feel. Any later source/asset change invalidates affected verification: rebuild and re-run the full gate after behavioral fixes. Device feedback is a separate gate owned by m57.

Generate the concise consolidated report (first two replay directories must use identical scenarios in baseline/candidate order):

  python3 -I bench/scripts/quality.py report --comparison RUN/full-comparison --consolidation RUN/native --replays RUN/sentinels-baseline RUN/sentinels-candidate --quick-comparison RUN/quick-comparison --execution RUN/execution.json --out RUN/final

Then stop after the stable candidate and acceptance/tradeoff report. Never automatically start another wave.

## Discovery only when explicitly authorized later

No discovery was dispatched for this workflow. A future authorized wave uses ten concurrent personas, identical verified frozen jars/assets, one build before dispatch, no agent production edits/builds, one directory per persona/session, -Xmx512m, 40 native interaction commands plus quit per persona and a five-minute wall budget. Use distinct everyday tasks (family scheduling, shopping, repairs, classwork, office messages, etc.), not obscure-name inventories. Set all settings, seed and intended alternatives explicitly. Give the frozen protocol help and paths; invoke SuggestKt directly, not suggest.sh's rebuilding wrapper. Bind each session with manifest/log hashes, settings, argv and exit status. Partial/timeout sessions are rejected, not called passes. Collect the whole batch once it finishes, then consolidate ALL logs and investigate selected leads. Do not narrate or patch after every arrival. Persona text is discovery evidence only after native-log verification, never a verified root-cause explanation.
