# Published quality evidence

The repository contains the standard-library workflow helper, unchanged sentinels, fixed quick sample, compact reports, comparison receipts and frozen hash manifests. The large local run directories also contain jars, assets, APKs, native logs and complete benchmark outputs; those payloads are deliberately not committed. A manifest by itself is not a runnable frozen engine. References to omitted files identify retained local evidence, not downloadable repository artifacts.

## Current result

- `round7-demo/final/report.txt`: workflow demonstration and analysis of historical round-7 results, including the 193 correct automatic fixes downgraded to manual relative to round7c. This is an explicit tradeoff, not blanket acceptance.
- `manual-choice-batch1/acceptance-report.txt`: fresh full baseline/candidate replay for the subsequent manual-slot fix; 22,252 aligned typo rows, three targets gained, none lost, unchanged automatic outcomes, passing sentinels and quick gate. Its timing concern is retained as recorded.
- `manual-choice-batch1/latency-followup-1/report.txt`: latest consolidated status after an authorized quieter serial timing follow-up. The several-fold slowdown was not reproduced. A smaller English p95 difference is recorded without claiming statistical equivalence, a speedup or general performance acceptance.

Desktop evidence does not establish Android editor compatibility or typing feel. Device acceptance remains separate and unperformed. Publishing does not silently approve historical tradeoffs.

## Running from a fresh checkout

Follow `bench/QUALITY.md`. Build the existing desktop harness with `nix develop -c ./gradlew -p bench installDist --console=plain`, then use `quality.py freeze` to create a new complete engine directory at a new path. Do not overwrite a published metadata-only engine directory or assume it is runnable. Reuse the committed `round7-demo/quick-sample/` unchanged for an authorized comparison, and replay `bench/data/quality_sentinels.jsonl` against both fully frozen engines. New comparisons need the corresponding baseline source and assets, not just historical summary percentages.

No new discovery/persona wave, tuning batch, phone action or publication is authorized by these documents. Stop at the agreed acceptance or tradeoff report.
