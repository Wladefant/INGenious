#!/usr/bin/env bash
# What the "Aufnahme abgeben" button does when Studio will not say what the recorder is doing.
#
#   bash ingenious-plugin/harness/unreadable/run-handoff-unreadable-harness.sh
#
# One run, one Studio double (studio-double-renamed, shared with RecorderUnreadableHarness):
# isRecording() is gone from the toolbar, record() is still the toggle, and a recording is
# live. The panel therefore reads UNREADABLE — the state in which pressing "abgeben" used to
# package a project a recorder may still be writing into, and report "✔ Fertig".
#
# The real tools/handoff-pack.mjs is invoked, exactly as in run-guided-flow-harness.sh's
# "abgabe" scenario: the first press happens before any Studio exists and really does write a
# zip, so the empty folder after every later press is evidence about the refusal and not about
# a fixture that could never have packed anything.
#
# Everything in the fixture is invented. No real customer data goes near this harness.
#
# Shots 44-46 land in ingenious-plugin/target/harness-guided/ beside the other panel proofs.
# Run this AFTER harness/run-guided-flow-harness.sh, not before: that script empties the shot
# directory when it starts.
#
# NOT headless, on purpose: reaching the recording step goes through the system clipboard,
# which throws under headless. The frame is packed but never shown.
set -u

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
PLUGIN="$REPO/ingenious-plugin"
# This harness DELETES and recompiles $CLASSES. When that is the shared maven output,
# anything else reading it at that moment — another harness, another lane — sees a
# half-built tree. ING_PLUGIN_BUILD_DIR/ING_PLUGIN_API_BUILD_DIR let run-all.sh give the
# suite its own build directory instead.
CLASSES="${ING_PLUGIN_BUILD_DIR:-$PLUGIN/target/classes}"
API_CLASSES="${ING_PLUGIN_API_BUILD_DIR:-$PLUGIN/target/api-classes}"
HERE="$PLUGIN/harness/unreadable"
WORK="$PLUGIN/target/harness-handoff-unreadable"
SHOTS="$PLUGIN/target/harness-guided"
API_SRC="$REPO/INGenious/ingenious-api/src/main/java/com/ing/ingenious/api/contract/ui"

rm -rf "$WORK"; mkdir -p "$WORK/classes" "$WORK/abgabe-out" "$SHOTS"

API_JAR="$HOME/.m2/repository/com/ing/ingenious-api/3.0/ingenious-api-3.0.jar"
if [ -f "$API_JAR" ] && unzip -l "$API_JAR" 2>/dev/null | grep -q "contract/ui/StudioPanelApi.class"; then
  API_CP="$API_JAR"
else
  rm -rf "$API_CLASSES"; mkdir -p "$API_CLASSES"
  "$JAVAC" -encoding UTF-8 -d "$API_CLASSES" "$API_SRC"/*.java || exit 2
  API_CP="$API_CLASSES"
fi

# The panel itself, exactly as the guided-flow harness builds it.
rm -rf "$CLASSES"; mkdir -p "$CLASSES"
"$JAVAC" -encoding UTF-8 -cp "$API_CP" -d "$CLASSES" \
  $(find "$PLUGIN/src/main/java" -name '*.java') || exit 2
cp -r "$PLUGIN/src/main/resources/." "$CLASSES/" 2>/dev/null || true

"$JAVAC" -encoding UTF-8 -d "$WORK/classes" \
  $(find "$HERE/studio-double-renamed" -name '*.java') || exit 2
# RecorderProbe belongs to the guided-flow harness and is used here unchanged: it is how the
# state the panel READ is told apart from the state the button happened to be left in.
"$JAVAC" -encoding UTF-8 -cp "$CLASSES:$API_CP:$WORK/classes" -d "$WORK/classes" \
  "$PLUGIN/harness/RecorderProbe.java" \
  "$HERE/PanelHarnessKit.java" "$HERE/HandoffUnreadableHarness.java" || exit 2

# A recorded project to hand over: a test case, a test lab entry, settings, run output and a
# saved browser session (the last two must stay behind), and a customer PROFILE with no number
# in it — a number would make handoff-pack refuse for its own, unrelated, reason.
PROJECT="$WORK/Uebergabe-Probe"
mkdir -p "$PROJECT/TestPlan/Partner" "$PROJECT/TestLab/Release1" "$PROJECT/TestData" \
  "$PROJECT/Settings" \
  "$PROJECT/Results/TestDesign/Partner/3951650_Partner-Suche/28-Juli-2026 02-12-42"
cat > "$PROJECT/TestPlan/Partner/3951650_Partner-Suche.csv" <<'CSV'
Step,Object,Action,Input,Condition,Comments
1,Browser,Open,,,
2,Suchfeld,Set,Mustermann,,
3,Suchen,Click,,,
CSV
cat > "$PROJECT/TestLab/Release1/Regression.csv" <<'CSV'
Execute,TestScenario,TestCase,Browser
Y,Partner,3951650_Partner-Suche,Chromium
CSV
cat > "$PROJECT/Settings/ApplicationConfig.properties" <<'PROPS'
appName=Beispielanwendung
PROPS
cat > "$PROJECT/TestData/Testkunde.csv" <<'CSV'
Scenario,Flow,Iteration,SubIteration,Partnertyp,Produktvariante,Bonitaet
Partner,3951650_Partner-Suche,1,1,P,Giro Direkt,12
CSV
echo 'var data = {};' > "$PROJECT/Results/TestDesign/Partner/3951650_Partner-Suche/28-Juli-2026 02-12-42/data.js"
echo '{"cookies":[]}' > "$PROJECT/login.json"

FIXTURE="$PLUGIN/sample/ado-testcases-beispiel.json"

echo
echo "################################################################"
ING_ADO_CACHE="$FIXTURE" \
ING_TESTCASE_SELECTION="$WORK/selected-testcase.json" \
ING_TESTDATA_CSV="$PLUGIN/sample/testdaten-beispiel.csv" \
ING_TESTDATA_LABELS="$PLUGIN/sample/labels.properties" \
ING_QA_REPO="$REPO" \
ING_INGENIOUS_PROJECT="$PROJECT" \
ING_HANDOFF_OUT="$WORK/abgabe-out" \
"$JAVA" -Djava.awt.headless=false -Dfile.encoding=UTF-8 \
  -cp "$WORK/classes:$CLASSES:$API_CP" unreadable.HandoffUnreadableHarness "$SHOTS"
RC=$?

echo
echo "################################################################"
echo "abgabe-unlesbar=$RC  (0 = green, 4 = ungeprueft, 1 = red)"
echo "screenshots: $SHOTS"
# VERBATIM, and it must stay verbatim. The harness answers 4 when it could not ask its question
# at all — an ING_HANDOFF_OUT that is not there, a fixture the setup above failed to write — and
# run-all.sh reports that as UNPROVED rather than FAILED. Rewriting this as `[ $RC -eq 0 ]`, the
# shape its sibling run-unreadable-harness.sh carried until 2026-07-28, would turn every one of
# those into a claim that the hand-off button is broken.
exit $RC
