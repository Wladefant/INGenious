#!/usr/bin/env bash
# The guard on selected-testcase.json: the REAL writer and the REAL reader, nothing between.
#
#   bash ingenious-plugin/harness/run-selection-contract-harness.sh
#
# Run from anywhere. Needs JAVA_HOME (or java/javac on PATH) and the compiled plugin, which
# comes from: mvn -f ingenious-plugin/pom.xml package
#
# WHY IT EXISTS: SelectedTestCase.read() reads a "startUrl" key that AdoCache.writeSelection()
# has never written, so the per-test-case recorder address was unreachable in production for
# its whole life while its javadoc said otherwise. Three harnesses touch that file and none of
# them caught it, because all three hand-roll the JSON instead of calling the writer. This one
# calls the writer.
#
# NOT YET REGISTERED IN run-all.sh / ci-gate.sh — that edit belongs to whoever owns the suite.
# The two lines it needs, verbatim:
#
#   run-all.sh, in why_skip():
#     auswahl-vertrag)   first_reason "$(need_java)" "$(need_classes)" "$(need_apijar)" ;;
#   run-all.sh, in the "headless — safe to run anywhere" block:
#     run_one auswahl-vertrag  "the selection file's writer and reader agree, key by key" bash "$HERE/run-selection-contract-harness.sh"
#
# It is headless, needs no desktop, no node, no install, and finishes in well under a second.
set -u

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
JAVA="${JAVA_HOME:-}"
if [ -n "$JAVA" ]; then JAVA="$JAVA/bin/java"; else JAVA="java"; fi
JAVAC="${JAVA_HOME:-}"
if [ -n "$JAVAC" ]; then JAVAC="$JAVAC/bin/javac"; else JAVAC="javac"; fi

# See run-recording-target-harness.sh: target/classes is shared with maven and with the panel
# harnesses, which delete and recompile it. ING_PLUGIN_CP lets run-all.sh point this at a
# build nobody else is rewriting.
CLASSES="${ING_PLUGIN_CP:-$REPO/ingenious-plugin/target/classes}"
API_JAR="$(ls "$HOME"/.m2/repository/com/ing/ingenious-api/3.0/ingenious-api-3.0.jar 2>/dev/null | head -1)"
WORK="$REPO/ingenious-plugin/target/harness-selection-contract"
rm -rf "$WORK"; mkdir -p "$WORK/tmp"

# Windows paths carry a colon, so ":" cannot separate classpath entries there. run-chain-
# harness.sh already learned this; run-recording-target-harness.sh has not (it joins with ":"
# and can only ever have worked off Windows). Asked of the platform rather than assumed.
if command -v cygpath >/dev/null 2>&1; then
  win() { cygpath -w "$1"; }; SEP=';'
else
  win() { echo "$1"; }; SEP=':'
fi
CLASSES_W="$(win "$CLASSES")"
API_W="$(win "$API_JAR")"
WORK_W="$(win "$WORK")"

if [ ! -f "$CLASSES/de/ing/qa/ado/AdoCache.class" ]; then
  echo "!! $CLASSES carries no compiled plugin — run: mvn -f ingenious-plugin/pom.xml package"
  exit 2
fi

"$JAVAC" -encoding UTF-8 -cp "$CLASSES_W$SEP$API_W" -d "$WORK_W" \
  "$(win "$REPO/ingenious-plugin/harness/SelectionContractHarness.java")" || exit 2

# A path of its own. This harness WRITES a selection file, so it must never be pointed at a
# tester's real one — the harness refuses to run without this variable for the same reason.
export ING_TESTCASE_SELECTION="$WORK/tmp/selected-testcase.json"
# ING_ADO_CACHE only decides the _note line's text here, but an unset one would make the
# writer name a tester's real cache path in a file this script owns. Give it one of ours.
export ING_ADO_CACHE="$WORK/tmp/ado-testcases.json"

echo
echo "################################################################"
"$JAVA" -Djava.awt.headless=true -Dfile.encoding=UTF-8 \
  -cp "$WORK_W$SEP$CLASSES_W$SEP$API_W" SelectionContractHarness
