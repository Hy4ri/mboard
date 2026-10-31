# U2 landscape clipping follow-up

Base: local main `5e2bdba`. Scope: `KeyboardView.kt` only.

## Fix

- Read current WindowMetrics bounds instead of Configuration.screenHeightDp. Detect landscape from the current bounds, not a potentially stale orientation configuration.
- Subtract system-bar and display-cutout insets. Never subtract the IME inset itself (that would make its own sizing recursive).
- Keep public keysHeight as the current-window requested body size. Do not return the previous orientation's measured size to MboardService's fixed-height body layout.
- Lay out and hit-test keys against the actual laid-out body height. This bounds every row within the pixels the parent allocated.
- Include height in the geometry-cache key. Previously only width/layout were checked, allowing stale rectangles after height-only changes.
- Portrait requested size is unchanged: configured row height times 5/4 rows plus the existing gap. Landscape still uses the 60% target and the binding 34 dp minimum-wins rule.

## Final-source gates

Exit 0:

```sh
flock /tmp/crew-mboard/bench.lock nix develop -c bash -c 'set -euo pipefail; ktlint -F "app/src/main/java/**/*.kt" "bench/src/**/*.kt"; ktlint "app/src/main/java/**/*.kt" "bench/src/**/*.kt"; ./gradlew assembleDebug; cd bench; bash run.sh u2-landscape-final --assets /tmp/u2-land-assets'
```

The binary overlay was copied from app/src/main/assets plus app/build/generated/tableAssets and wordAssets after the app build. Final replay: `bench/results/u2-landscape-final`. Baseline: `/home/m57/Projects/mboard/bench/results/main-6959982-bin`.

Python byte comparisons passed for **24/24 TSVs**. No lost/gained rows. All quality metrics remain unchanged on every set. Timing is excluded from this byte-identity claim.

```sh
python3 -I bench/scripts/summary.py bench/results/u2-landscape-final /home/m57/Projects/mboard/bench/results/main-6959982-bin
```

Exit 0. Log: `/tmp/crew-mboard/u2-land-final.log`; summary: `/tmp/crew-mboard/u2-land-final-summary.txt`.

An earlier foreground build attempt timed out waiting for bench.lock; it is not counted as a pass. The final background build/replay above completed with exit 0.

## Device gate: OPEN for final source

An earlier candidate was installed and pulled back byte-identically. The complete script held phone.lock throughout. Screenshots `landscape-number-true.png` and `landscape-number-false.png` were inspected with vision: all five/four rows and complete bottom-key edges were visible above the navigation bar.

Window dumps for that candidate measured:

| Number row | IME window including navigation | Toolbar + strip + keys | Content / physical height | Content / usable height |
|---|---:|---:|---:|---:|
| On | 923 px | 851 px | 70.92% | 85.27% |
| Off | 813 px | 741 px | 61.75% | 74.25% |

Physical height: 1200 px. System status: 130 px. Navigation: 72 px. Usable height: 998 px. Content excludes the 72 px navigation area. Including navigation, the IME occupies 76.92% / 67.75% of the physical height. Both exceed the 60% target because the 34 dp minimum wins.

**These are candidate measurements, NOT final-source acceptance.** That candidate returned measured size publicly; the portrait-number-false screenshot exposed a stale-height feedback issue after rotation. The final source corrects that feedback and adds the height cache key, but needs a fresh device pass.

The successful candidate script restored and checked number_row=true (original value), user_rotation=0 and accelerometer_rotation=0 (original value). See initial-state.json / restored-state.json. It navigated with the in-app Back control because the system Back callback was not enabled on this base.

The final script could not start: `adb: device 'AS7J6R4407003449' not found`. It failed on its initial read-only power check, before changing settings. Log: `/tmp/crew-mboard/u2-land-device-final.log`.

Remaining gate: reconnect the phone, install the final APK, repeat portrait and landscape with number row on/off, inspect all four screenshots, confirm portrait rows stay at the original size, and restore number_row=true and user_rotation=0. The script is `/tmp/crew-mboard/u2-land-device.py` and runs under one phone.lock hold.
