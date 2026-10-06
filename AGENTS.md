# AGENTS.md

## Rules

- **Do not write tests.** No unit, instrumented, or screenshot tests, and no test
  source sets or test dependencies. Verify changes by building
  (`./gradlew assembleDebug`) and running the keyboard on a real device over adb.

## Dev environment

`nix develop` provides JDK 21, the Android SDK (platform 37, build-tools 37.0.0,
platform-tools), Gradle (for bootstrapping the wrapper only), and ktlint.
There is no emulator; test on a physical device.
