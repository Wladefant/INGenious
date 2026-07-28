#!/usr/bin/env bash
# Headless proof for the two ADO panels (chooser + overview).
#
#   bash ingenious-plugin/harness/run-ado-harness.sh
#
# Run from the repo root. Needs JAVA_HOME (or `java` on PATH) and the plugin JAR,
# which it builds if missing. Each scenario runs in its own JVM: the panels read
# their paths from the environment, and a running JVM cannot change its own env.
set -u

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
JAVA="${JAVA_HOME:-}"
if [ -n "$JAVA" ]; then JAVA="$JAVA/bin/java"; else JAVA="java"; fi
JAVAC="${JAVA_HOME:-}"
if [ -n "$JAVAC" ]; then JAVAC="$JAVAC/bin/javac"; else JAVAC="javac"; fi

# target/classes is shared with maven AND with the panel harnesses, which delete and
# recompile it. Reading it while something else is writing it yields a verdict about a
# half-built tree — it has already produced one NoClassDefFoundError here. run-all.sh sets
# ING_PLUGIN_CP to a build directory it owns, so the suite neither reads nor writes the
# shared one.
CLASSES="${ING_PLUGIN_CP:-$REPO/ingenious-plugin/target/classes}"
API_JAR="$(ls "$HOME"/.m2/repository/com/ing/ingenious-api/3.0/ingenious-api-3.0.jar 2>/dev/null | head -1)"
WORK="$REPO/ingenious-plugin/target/harness"
rm -rf "$WORK"; mkdir -p "$WORK/tmp"

if [ ! -f "$CLASSES/de/ing/qa/panel/GuidedFlowPanel.class" ]; then
  echo "!! $CLASSES carries no compiled plugin — run: mvn -f ingenious-plugin/pom.xml package"; exit 2
fi

"$JAVAC" -encoding UTF-8 -cp "$CLASSES:$API_JAR" -d "$WORK" \
  "$REPO/ingenious-plugin/harness/AdoHarness.java" || exit 2

# A stub that stands in for a tool run that reaches ADO and fails — the normal
# outcome on a machine without ADO reachability.
mkdir -p "$WORK/fakerepo/tools"
cat > "$WORK/fakerepo/tools/ado-testcases.mjs" <<'STUB'
console.error('ado-testcases error: ADO nicht erreichbar (Conditional Access)');
process.exit(1);
STUB

# A cache from before the tool stored links, generated against an unknown org: no
# per-case url AND no org/project, so NO Azure-DevOps URL can be built at all.
cat > "$WORK/tmp/ohne-url.json" <<'NOURL'
{
  "_note": "Harness fixture: a panel cache with neither a per-case url nor org/project.",
  "generatedAt": "2026-01-01T00:00:00.000Z",
  "testCases": [
    {
      "adoId": "1234567",
      "title": "Testfall ohne jeden ADO-Link",
      "suiteId": "1",
      "suiteName": "Alte Suite",
      "state": "Design",
      "outcome": "",
      "description": "",
      "preconditions": "",
      "preconditionField": null,
      "steps": []
    }
  ]
}
NOURL

run() {
  echo
  echo "################################################################"
  "$JAVA" -Djava.awt.headless=true -Dfile.encoding=UTF-8 -cp "$WORK:$CLASSES:$API_JAR" AdoHarness "$@"
}

FIXTURE="$REPO/ingenious-plugin/sample/ado-testcases-beispiel.json"

ING_ADO_CACHE="$FIXTURE" \
ING_TESTCASE_SELECTION="$WORK/tmp/selected-testcase.json" \
  run cache
RC_CACHE=$?

ING_ADO_CACHE="$WORK/tmp/gibt-es-nicht.json" \
ING_TESTCASE_SELECTION="$WORK/tmp/selected-testcase.json" \
ING_QA_REPO="$WORK/tmp" \
  run empty
RC_EMPTY=$?

# ADO_BEARER pins the sign-in state, and that is the whole point of setting it.
#
# This scenario asks one question — "when the tool runs and fails, does its reason reach the
# tester?" — and until 2026-07-28 it left the ANSWER TO A DIFFERENT QUESTION, "is this machine
# signed in to Azure DevOps", to whatever the developer's laptop happened to say. Since #128
# AdoCache.refresh() settles the sign-in BEFORE it starts the child, so on a signed-out machine
# the tool is never started at all and the status line reads "Anmeldung bei Azure DevOps
# noetig…" instead of the tool's stderr. Same code, same commit, two verdicts, decided by
# `az account get-access-token` on the machine that happened to run it.
#
# ADO_BEARER is the first branch of AdoSignIn.check() and short-circuits before the token cache
# and before az, so the gate opens deterministically and the tool really runs. It is a fixture
# string, not a credential: nothing here reaches ADO — tools/ado-testcases.mjs is the two-line
# stub above, and it ignores its environment entirely.
#
# What this scenario therefore no longer proves is the gate itself. That is not a loss of
# coverage: signin/run-signin-harness.sh pins the opposite state (a fake az first on PATH, an
# empty LOCALAPPDATA) in `refresh-signin-required` and asserts the refusal, and `refresh-token-ok`
# asserts the pass-through. Two scenarios, two pinned preconditions, no machine in the loop.
ING_ADO_CACHE="$WORK/tmp/gibt-es-nicht.json" \
ING_TESTCASE_SELECTION="$WORK/tmp/selected-testcase.json" \
ING_QA_REPO="$WORK/fakerepo" \
ADO_BEARER="HARNESS-FIXTURE-NOT-A-TOKEN" \
  run toolfail
RC_TOOLFAIL=$?

ING_ADO_CACHE="$WORK/tmp/ohne-url.json" \
ING_TESTCASE_SELECTION="$WORK/tmp/selected-nourl.json" \
  run nourl
RC_NOURL=$?

# The selection path is a NON-EMPTY DIRECTORY, so the atomic move cannot replace it.
# Proves the failure is reported instead of vanishing — the difference between "silent
# success" and "silent failure" is exactly what made the button look dead.
# (An EMPTY directory is not enough: Files.move REPLACE_EXISTING happily replaces one.)
mkdir -p "$WORK/tmp/nicht-schreibbar.json"
echo blockiert > "$WORK/tmp/nicht-schreibbar.json/belegt.txt"
ING_ADO_CACHE="$FIXTURE" \
ING_TESTCASE_SELECTION="$WORK/tmp/nicht-schreibbar.json" \
  run writefail
RC_WRITEFAIL=$?

# An UNCHECKED failure on the way to the file: an illegal path throws
# InvalidPathException out of Paths.get, which the old `catch (IOException)` did not
# cover — it escaped into the EDT and the button looked dead. Windows-only; the
# scenario reports itself as not applicable elsewhere.
ING_ADO_CACHE="$FIXTURE" \
ING_TESTCASE_SELECTION="$WORK/tmp/un<moegliche>|datei.json" \
  run badpath
RC_BADPATH=$?

echo
echo "################################################################"
echo "cache=$RC_CACHE empty=$RC_EMPTY toolfail=$RC_TOOLFAIL nourl=$RC_NOURL writefail=$RC_WRITEFAIL badpath=$RC_BADPATH  (0 = green, 3 = nicht anwendbar)"
# badpath is Windows-only: elsewhere the filesystem accepts the path and there is no rejection
# for the panel to report. It used to print "RESULT: GREEN — 0 checks passed" and exit 0 for
# that, so a scenario that asked nothing counted as a scenario that passed. It now exits 3,
# and 3 is neither green nor red here either — the run is UNGEPRUEFT for that half.
if [ $RC_CACHE -eq 0 ] && [ $RC_EMPTY -eq 0 ] && [ $RC_TOOLFAIL -eq 0 ] \
  && [ $RC_NOURL -eq 0 ] && [ $RC_WRITEFAIL -eq 0 ] && [ $RC_BADPATH -eq 0 ]; then
  echo "RESULT: GREEN — every scenario was put and answered."
  exit 0
fi
if [ $RC_CACHE -eq 0 ] && [ $RC_EMPTY -eq 0 ] && [ $RC_TOOLFAIL -eq 0 ] \
  && [ $RC_NOURL -eq 0 ] && [ $RC_WRITEFAIL -eq 0 ] && [ $RC_BADPATH -eq 3 ]; then
  echo "RESULT: UNGEPRUEFT — badpath is not applicable on this filesystem; nothing failed,"
  echo "        but the invalid-path banner was never exercised. Run it on Windows."
  exit 4
fi
echo "RESULT: RED — at least one scenario failed; see the codes above."
exit 1
