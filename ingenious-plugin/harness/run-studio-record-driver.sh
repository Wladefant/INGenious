#!/usr/bin/env bash
# Drives a REAL running Studio through both halves of the recorder-target question.
#
#   bash ingenious-plugin/harness/run-studio-record-driver.sh <install-root> [project]
#
# e.g. bash ingenious-plugin/harness/run-studio-record-driver.sh \
#          C:/Users/wkiri/OneDrive/Desktop/ing/INGenious/Dist/release
#
# Needs a display — it starts Studio for real and screenshots it. The plugin JAR must
# already be installed under <install-root>/plugins/ing-tester-panel/.
#
# Two runs, and the pair is the evidence:
#   chosen — a selection file exists  => NO "Choose Recording Target" dialog
#   none   — no selection file        => the dialog appears, exactly as it always did
set -u

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ROOT="${1:?usage: run-studio-record-driver.sh <install-root> [project]}"
PROJECT="${2:-$ROOT/Projects/Tutorial}"

JAVA="${JAVA_HOME:-}"; if [ -n "$JAVA" ]; then JAVA="$JAVA/bin/java"; else JAVA="java"; fi
JAVAC="${JAVA_HOME:-}"; if [ -n "$JAVAC" ]; then JAVAC="$JAVAC/bin/javac"; else JAVAC="javac"; fi

WORK="$REPO/ingenious-plugin/target/studio-driver"
rm -rf "$WORK"; mkdir -p "$WORK"

# Windows java wants Windows paths, and a ';' classpath must not be mangled by MSYS. Without
# this the driver class is simply not on the classpath and java exits 1 having run nothing —
# which this script used to hide, because the failing command was piped into grep and only
# grep's status was ever seen. It printed its screenshots line and exited 0 either way.
win() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else echo "$1"; fi; }
WORK_W="$(win "$WORK")"
ROOT_W="$(win "$ROOT")"
# Named and checked, not assumed. Not every install carries this file — the 3.0.0-preview
# install ships ingenious-ide-3.0.0-PREVIEW.jar — and a classpath entry that does not exist
# is silently ignored by java, so the driver would run against a Studio core that is simply
# not on the classpath and report whatever that produces.
IDE_JAR="$ROOT/ingenious-ide-3.0.0.jar"
if [ ! -f "$IDE_JAR" ]; then
  echo "No Studio core at $IDE_JAR — this install carries: $(ls "$ROOT"/ingenious-ide-*.jar 2>/dev/null | tr '\n' ' ')" >&2
  exit 2
fi
echo "studio under test: $IDE_JAR"
CP="$ROOT_W\\ingenious-ide-3.0.0.jar;$ROOT_W\\lib\\*;$ROOT_W\\lib\\clib\\*"

MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' \
"$JAVAC" -encoding UTF-8 -cp "$CP" -d "$WORK_W" \
  "$(win "$REPO/ingenious-plugin/harness/StudioRecordDriver.java")" || exit 2

cp "$REPO/ingenious-plugin/sample/ado-testcases-beispiel.json" "$WORK/ado-cache.json"
cat > "$WORK/selected-testcase.json" <<'SEL'
{
  "adoId": "3951650",
  "title": "Beispielanwendung SYSTEMTEST: Partner-Suche + Kunde-360 (Set1)",
  "suiteName": "Partner-Suche Suite",
  "source": "TestCaseChooserPanel"
}
SEL

run() { # $1 = label, $2 = selection path
  echo
  echo "################################################################ $1"
  local log="$WORK/$1.log"
  # The whole run is kept, and the interesting lines are shown. Never a pipe: the exit status
  # of a pipeline is the LAST command's, so piping java into grep threw away the one thing
  # this script exists to report — whether the driver ran at all.
  ( cd "$ROOT" && MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' \
      ING_ADO_CACHE="$(win "$WORK/ado-cache.json")" ING_TESTCASE_SELECTION="$(win "$2")" \
      "$JAVA" -Dfile.encoding=UTF-8 -cp "$WORK_W;$CP" \
        StudioRecordDriver "$(win "$PROJECT")" "$1" ) >"$log" 2>&1
  local rc=$?
  grep -E '\[driver\]|Recording into|RecordingTargetPlugins' "$log" || true
  [ -f "$ROOT/driver-screenshot.png" ] && mv "$ROOT/driver-screenshot.png" "$WORK/$1.png"
  if [ $rc -ne 0 ]; then
    echo "FAILED: the driver exited $rc — full output in $log" >&2
    tail -5 "$log" >&2
  fi
  return $rc
}

# The label IS the expectation, and the driver is told it: "chosen" must produce no dialog,
# "none" must produce one. Until 2026-07-28 the driver halted 0 whatever it saw, so both of
# these were green even when the chooser came back — the one regression this script exists
# to catch. Now 0 means the expected answer, 4 the other one, 3 that nothing was decided.
run chosen "$WORK/selected-testcase.json"; RC_CHOSEN=$?
run none   "$WORK/nichts-gewaehlt.json";   RC_NONE=$?

say_rc() { # $1 = label, $2 = rc
  case $2 in
    0) echo "  $1: PROVEN — the expected answer." ;;
    4) echo "  $1: DISPROVEN — the other answer. See $WORK/$1.log." ;;
    3) echo "  $1: INCONCLUSIVE — the question could not be put. Nothing is proved or"
       echo "     disproved; see $WORK/$1.log for why." ;;
    *) echo "  $1: INCONCLUSIVE — the driver exited $2 without answering." ;;
  esac
}

echo
echo "screenshots: $WORK/chosen.png  $WORK/none.png"
echo "logs:        $WORK/chosen.log  $WORK/none.log"
echo
say_rc chosen $RC_CHOSEN
say_rc none   $RC_NONE
# Only a run where BOTH halves came back with their expected answer is a pass. An
# INCONCLUSIVE half is not folded into green: nothing was proved.
[ $RC_CHOSEN -eq 0 ] && [ $RC_NONE -eq 0 ]
