#!/usr/bin/env bash
# One keyboard-strip lookup: prints the 3 suggestions the keyboard would show for TEXT and saves the run
# to bench/logs/suggest/<timestamp>.md. Every run starts clean. See `suggest.sh --help`.
# Usage: bench/suggest.sh [options] TEXT
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"

# The JDK comes from the dev shell; enter it once if the caller is not already in it.
if ! command -v java > /dev/null; then
  [ -z "${MBOARD_IN_NIX:-}" ] || { echo "suggest: no java inside nix develop either" >&2; exit 1; }
  # The dev shell's banner goes to stderr so stdout stays clean for programs.
  # fd 3 carries the real stdout past the shell hook; the script gets it back as stdout.
  exec 3>&1
  exec env MBOARD_IN_NIX=1 nix develop "$here/.." -c bash -c 'exec bash "$0" "$@" 1>&3 3>&-' "$here/suggest.sh" "$@" 1>&2
fi

cd "$here"
jar=build/install/mboard-bench/lib/mboard-bench.jar
if [ ! -f "$jar" ] || [ -n "$(find src ../app/src/main/java build.gradle.kts -newer "$jar" -print -quit)" ]; then
  ../gradlew -p . installDist -q --console=plain >&2
fi

rev="$(git -C .. rev-parse --short HEAD 2> /dev/null || echo unknown)"
[ -z "$(git -C .. status --porcelain -- app/src/main bench/src 2> /dev/null)" ] || rev="$rev+uncommitted"

exec java -Xmx2g -Dsuggest.rev="$rev" -cp "build/install/mboard-bench/lib/*" com.m57.bench.SuggestKt \
  --assets "$here/../app/src/main/assets" --log-dir "$here/logs/suggest" "$@"
