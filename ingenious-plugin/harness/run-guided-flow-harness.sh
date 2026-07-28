#!/usr/bin/env bash
# Proof for the guided tester flow (Testfall → Kunde → Aufnahme).
#
#   bash ingenious-plugin/harness/run-guided-flow-harness.sh
#
# Run from anywhere. Compiles the plugin and the harness, then drives the real Swing
# components through the real clicks and writes a PNG of every step to
# ingenious-plugin/target/harness-guided/.
#
# NOT headless, on purpose: the customer step ends in the system clipboard, and
# java.awt.headless=true makes that throw. The frame is packed but never shown, so
# nothing appears on the screen of whoever is using the machine.
set -u

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
PLUGIN="$REPO/ingenious-plugin"
# This harness DELETES and recompiles $CLASSES. When that is the shared maven output,
# anything else reading it at that moment — another harness, another lane — sees a
# half-built tree. ING_PLUGIN_BUILD_DIR/ING_PLUGIN_API_BUILD_DIR let run-all.sh give the
# suite its own build directory instead.
CLASSES="${ING_PLUGIN_BUILD_DIR:-$PLUGIN/target/classes}"
API_CLASSES="${ING_PLUGIN_API_BUILD_DIR:-$PLUGIN/target/api-classes}"
WORK="$PLUGIN/target/harness-guided"
API_SRC="$REPO/INGenious/ingenious-api/src/main/java/com/ing/ingenious/api/contract/ui"

rm -rf "$WORK"; mkdir -p "$WORK/tmp"

# The Studio API: the installed JAR when it really carries the contract/ui classes,
# otherwise compiled from the INGenious sources in this workspace. That JAR is rebuilt by
# whoever is working on the core and has been seen mid-build without them, and a harness
# that fails for that reason tells you nothing about this panel. Nothing here writes to
# the INGenious tree.
API_JAR="$HOME/.m2/repository/com/ing/ingenious-api/3.0/ingenious-api-3.0.jar"
if [ -f "$API_JAR" ] && unzip -l "$API_JAR" 2>/dev/null | grep -q "contract/ui/StudioPanelApi.class"; then
  API_CP="$API_JAR"
  echo "API: installed jar $API_JAR"
else
  rm -rf "$API_CLASSES"; mkdir -p "$API_CLASSES"
  "$JAVAC" -encoding UTF-8 -d "$API_CLASSES" "$API_SRC"/*.java || exit 2
  API_CP="$API_CLASSES"
  echo "API: compiled from $API_SRC (installed jar lacks contract/ui)"
fi

rm -rf "$CLASSES"; mkdir -p "$CLASSES"
"$JAVAC" -encoding UTF-8 -cp "$API_CP" -d "$CLASSES" \
  $(find "$PLUGIN/src/main/java" -name '*.java') || exit 2
# The bundled label map ships inside the JAR, so it has to be on the classpath here too —
# without it the panel falls back to raw codes and the render proves the wrong thing.
cp -r "$PLUGIN/src/main/resources/." "$CLASSES/" 2>/dev/null || true

# A Studio whose Record method is the real toggle, so the button can be pressed twice.
# It is a double of exactly four methods; every name in it, and the branch order of
# record(), is checked against the BUILT ingenious jars by
#
#   bash ingenious-plugin/harness/run-studio-contract-harness.sh      (id: studio-kontrakt)
#
# — so this cannot drift into a fiction that passes while the real Studio has moved on. That
# check used to run here as a fourteenth scenario, and it needs things this one does not: a
# built INGenious core and an installed Studio's lib/ folder. On a hosted runner it had
# neither, went red, and took thirteen passing scenarios down with it — reporting "nineteen
# classes are missing from the core", which is what a rename would look like. It is its own
# harness now, so each says its own true thing.
"$JAVAC" -encoding UTF-8 -d "$WORK" \
  $(find "$PLUGIN/harness/studio-double" -name '*.java') || exit 2

"$JAVAC" -encoding UTF-8 -cp "$CLASSES:$API_CP:$WORK" -d "$WORK" \
  "$PLUGIN/harness/RecorderProbe.java" \
  "$PLUGIN/harness/AdoUploadProbe.java" \
  "$PLUGIN/harness/PanelAnchor.java" \
  "$PLUGIN/harness/GuidedFlowHarness.java" || exit 2

# A test-data file the way the real export actually arrives: two clean rows, one row that
# lost a separator, one whose Kontonummer column holds a word, one blocklisted account.
cat > "$WORK/tmp/kaputte-testdaten.csv" <<'CSV'
KONTONUMMER;Partnertyp;Produktvariante;Legitimiert;Boni;MDJ
1000000001;P;Giro Direkt;ja;12;nein
1000000002;G;Giro Direkt;ja;21;nein
1000000003;P;Extra-Konto;nein;12
kein Konto;P;Giro Direkt;ja;12;nein
;G;Extra-Konto;ja;21;ja
9999999999;P;Giro Direkt;ja;12;nein
CSV

run() {
  echo
  echo "################################################################"
  "$JAVA" -Djava.awt.headless=false -Dfile.encoding=UTF-8 \
    -cp "$WORK:$CLASSES:$API_CP" GuidedFlowHarness "$@"
}

FIXTURE="$PLUGIN/sample/ado-testcases-beispiel.json"

ING_ADO_CACHE="$FIXTURE" \
ING_TESTCASE_SELECTION="$WORK/tmp/selected-testcase.json" \
ING_TESTDATA_CSV="$PLUGIN/sample/testdaten-beispiel.csv" \
ING_TESTDATA_LABELS="$PLUGIN/sample/labels.properties" \
  run flow "$WORK"
RC_FLOW=$?

ING_ADO_CACHE="$FIXTURE" \
ING_TESTCASE_SELECTION="$WORK/tmp/selected-testcase.json" \
ING_TESTDATA_CSV="$WORK/tmp/kaputte-testdaten.csv" \
ING_TESTDATA_LABELS="$PLUGIN/sample/labels.properties" \
  run dirty "$WORK"
RC_DIRTY=$?

ING_ADO_CACHE="$FIXTURE" \
ING_TESTCASE_SELECTION="$WORK/tmp/selected-testcase.json" \
ING_TESTDATA_CSV="$PLUGIN/sample/testdaten-beispiel.csv" \
ING_TESTDATA_LABELS="$PLUGIN/sample/labels.properties" \
  run nocustomer "$WORK"
RC_NOCUSTOMER=$?

# The shipped label map against the REAL column names, with ING_TESTDATA_LABELS
# deliberately UNSET: a tester who configures nothing must still read German.
ING_ADO_CACHE="$FIXTURE" \
ING_TESTCASE_SELECTION="$WORK/tmp/selected-testcase.json" \
ING_TESTDATA_CSV="$PLUGIN/sample/testdaten-echte-spalten.csv" \
  run labels "$WORK"
RC_LABELS=$?

# The customer written onto the test case, chosen from a FILTERED table — the one case
# where the obvious implementation records the wrong customer, in translated German.
ING_ADO_CACHE="$FIXTURE" \
ING_TESTCASE_SELECTION="$WORK/tmp/selected-testcase.json" \
ING_TESTDATA_CSV="$PLUGIN/sample/testdaten-echte-spalten.csv" \
  run persist "$WORK"
RC_PERSIST=$?

# The recording button against a Studio that really toggles: the second press must never
# claim to have started something.
ING_ADO_CACHE="$FIXTURE" \
ING_TESTCASE_SELECTION="$WORK/tmp/selected-testcase.json" \
ING_TESTDATA_CSV="$PLUGIN/sample/testdaten-beispiel.csv" \
ING_TESTDATA_LABELS="$PLUGIN/sample/labels.properties" \
  run aufnahme "$WORK"
RC_RECORD=$?

# One toolbar button for the tester, all four screens for the engineer who asks.
ING_ADO_CACHE="$FIXTURE" \
ING_TESTCASE_SELECTION="$WORK/tmp/selected-testcase.json" \
ING_TESTDATA_CSV="$PLUGIN/sample/testdaten-beispiel.csv" \
ING_TESTDATA_LABELS="$PLUGIN/sample/labels.properties" \
  run einstieg "$WORK"
RC_ENTRY=$?

# The last step of the tester's job, on screen: an upload that finished before this panel
# existed must still be readable on it, and success, failure, skipped and off must not look
# alike. Uses ado-upload.mjs's real stdout through the real statusLine reader.
ING_ADO_CACHE="$FIXTURE" \
ING_TESTCASE_SELECTION="$WORK/tmp/selected-testcase.json" \
ING_TESTDATA_CSV="$PLUGIN/sample/testdaten-beispiel.csv" \
ING_TESTDATA_LABELS="$PLUGIN/sample/labels.properties" \
  run adostatus "$WORK"
RC_ADO=$?

# The run watcher, armed by the panel CONSTRUCTOR — the F6 case, where the tester re-runs an
# existing test case and never opens this screen at all. Its own JVM on purpose: every other
# scenario builds a panel, so the "not yet armed" half is only observable in a fresh one.
ING_ADO_CACHE="$FIXTURE" \
ING_TESTCASE_SELECTION="$WORK/tmp/selected-testcase.json" \
ING_TESTDATA_CSV="$PLUGIN/sample/testdaten-beispiel.csv" \
ING_TESTDATA_LABELS="$PLUGIN/sample/labels.properties" \
  run wachhund "$WORK"
RC_ARMED=$?

# The hand-off (#127): a tester turns their own recording into one file, without a command
# line.
#
# ONE fixture, since 52ce917. There used to be a second — a project carrying a made-up account
# number, which handoff-pack.mjs was expected to refuse to pack — and a `kontonummer` scenario
# asserting the refusal wording. The scan is gone: the hand-off travels tester -> automation
# engineer inside the organisation and the destination test-management system is internal too,
# so it crosses no boundary, and the scan fired on ADO test-case ids and on INGenious' own
# shipped sample data, which made the hand-off impossible on every project we create. `pack`
# no longer exits 2 at all, so a scenario asserting the refusal could only ever fail. Deleted
# rather than inverted: a test whose only job is to prove a removed feature used to work is
# clutter. What is left below is the behaviour that remains — a package IS produced.
mk_project() {
  local dir="$1"
  mkdir -p "$dir/TestPlan/Partner" "$dir/TestLab/Release1" "$dir/TestData" "$dir/Settings" \
    "$dir/Results/TestDesign/Partner/3951650_Partner-Suche/28-Juli-2026 02-12-42"
  cat > "$dir/TestPlan/Partner/3951650_Partner-Suche.csv" <<'CSV'
Step,Object,Action,Input,Condition,Comments
1,Browser,Open,,,
2,Suchfeld,Set,Mustermann,,
3,Suchen,Click,,,
CSV
  cat > "$dir/TestLab/Release1/Regression.csv" <<'CSV'
Execute,TestScenario,TestCase,Browser
Y,Partner,3951650_Partner-Suche,Chromium
CSV
  cat > "$dir/Settings/ApplicationConfig.properties" <<'PROPS'
appName=Beispielanwendung
PROPS
  # Run output and the saved browser session: both must be left behind.
  echo 'var data = {};' > "$dir/Results/TestDesign/Partner/3951650_Partner-Suche/28-Juli-2026 02-12-42/data.js"
  echo '{"cookies":[]}' > "$dir/login.json"
}

CLEAN="$WORK/tmp/Uebergabe-Probe"
rm -rf "$CLEAN"
mk_project "$CLEAN"
# It carries the customer PROFILE and no number at all — which is what handoff-pack is for:
# the engineer learns which kind of customer is needed, not which one. That property is still
# enforced, by CustomerProfile, and proved by run-profile-harness.sh; it is a different
# question from the deleted packaging scan.
cat > "$CLEAN/TestData/Testkunde.csv" <<'CSV'
Scenario,Flow,Iteration,SubIteration,Partnertyp,Produktvariante,Bonitaet
Partner,3951650_Partner-Suche,1,1,P,Giro Direkt,12
CSV

rm -rf "$WORK/tmp/abgabe-out"
mkdir -p "$WORK/tmp/abgabe-out"

ING_ADO_CACHE="$FIXTURE" \
ING_TESTCASE_SELECTION="$WORK/tmp/selected-testcase.json" \
ING_TESTDATA_CSV="$PLUGIN/sample/testdaten-beispiel.csv" \
ING_TESTDATA_LABELS="$PLUGIN/sample/labels.properties" \
ING_QA_REPO="$REPO" \
ING_INGENIOUS_PROJECT="$CLEAN" \
ING_HANDOFF_OUT="$WORK/tmp/abgabe-out" \
  run abgabe "$WORK"
RC_HANDOFF=$?

# A Fachbereich device as it is documented today: an install folder and ingenious-launch.cmd,
# which sets JAVA_HOME and nothing else — so no repo, so no packaging tool. The button must
# say so instead of looking ready and then failing on the press.
mkdir -p "$WORK/tmp/kein-repo"
ING_ADO_CACHE="$FIXTURE" \
ING_TESTCASE_SELECTION="$WORK/tmp/selected-testcase.json" \
ING_TESTDATA_CSV="$PLUGIN/sample/testdaten-beispiel.csv" \
ING_TESTDATA_LABELS="$PLUGIN/sample/labels.properties" \
ING_QA_REPO="$WORK/tmp/kein-repo" \
ING_INGENIOUS_PROJECT="$CLEAN" \
ING_HANDOFF_OUT="$WORK/tmp/abgabe-out-kein-werkzeug" \
  run ohne-werkzeug "$WORK"
RC_NOTOOL=$?

# …and the other half: the tool is there, node is not. ING_NODE points at an executable that
# does not exist, which is what a machine without node does to ProcessBuilder.start().
mkdir -p "$WORK/tmp/abgabe-out-ohne-node"
ING_ADO_CACHE="$FIXTURE" \
ING_TESTCASE_SELECTION="$WORK/tmp/selected-testcase.json" \
ING_TESTDATA_CSV="$PLUGIN/sample/testdaten-beispiel.csv" \
ING_TESTDATA_LABELS="$PLUGIN/sample/labels.properties" \
ING_QA_REPO="$REPO" \
ING_NODE="node-das-es-hier-nicht-gibt.exe" \
ING_INGENIOUS_PROJECT="$CLEAN" \
ING_HANDOFF_OUT="$WORK/tmp/abgabe-out-ohne-node" \
  run ohne-node "$WORK"
RC_NONODE=$?

# How the LIVE driver finds this screen, asked headlessly. StudioChainDriver climbs from the
# "übernehmen" button to the innermost ancestor carrying the step headline; on 2026-07-28 a
# sentence added to card 2 moved that ancestor from root to cardHost and two links reported
# BROKEN against a screen that was working. It was said this harness could not have caught it
# — it could: build() already puts the real panel into a real, laid-out JFrame, so the tree the
# driver walks is sitting here for free. This scenario points the driver's own locator at it and
# fails on the sentence, not on its consequence.
#
# It reports UNGEPRUEFT (4) until the panel carries a marker of its own — that is a one-line
# change in de/ing/qa/panel and is deliberately not made from here.
ING_ADO_CACHE="$FIXTURE" \
ING_TESTCASE_SELECTION="$WORK/tmp/selected-testcase.json" \
ING_TESTDATA_CSV="$PLUGIN/sample/testdaten-beispiel.csv" \
ING_TESTDATA_LABELS="$PLUGIN/sample/labels.properties" \
  run anker "$WORK"
RC_ANCHOR=$?

echo
echo "################################################################"
echo "flow=$RC_FLOW dirty=$RC_DIRTY nocustomer=$RC_NOCUSTOMER labels=$RC_LABELS persist=$RC_PERSIST aufnahme=$RC_RECORD einstieg=$RC_ENTRY adostatus=$RC_ADO wachhund=$RC_ARMED abgabe=$RC_HANDOFF ohne-werkzeug=$RC_NOTOOL ohne-node=$RC_NONODE anker=$RC_ANCHOR  (0 = green)"
echo "Die Namen, auf die das Panel reflektiert, prueft studio-kontrakt:"
echo "  bash ingenious-plugin/harness/run-studio-contract-harness.sh"
echo "screenshots: $WORK"
# Exit 4 means "could not be tested here" — no node for the hand-off scenarios. It is reported
# as UNGEPRUEFT and never quietly folded into green: nothing was proved.
#
# It used to be folded into green all the same. `ok() { [ $1 -eq 0 ] || [ $1 -eq 4 ]; }` made
# this script exit 0 on a machine where the whole hand-off feature was never exercised, three
# lines under a comment promising the opposite. A caller reading $? got an unqualified pass for
# a run that proved nothing. Now UNGEPRUEFT has an exit code of its own.
UNPROVED=0
for pair in "abgabe:$RC_HANDOFF" "anker:$RC_ANCHOR"; do
  if [ "${pair#*:}" -eq 4 ]; then
    echo "!! UNGEPRUEFT: ${pair%%:*} wurde nicht abschliessend geprueft. Die SKIP-Zeile"
    echo "   dieses Szenarios oben nennt den Grund. Nichts ist fehlgeschlagen — bewiesen"
    echo "   aber auch nichts."
    UNPROVED=1
  fi
done

# Every scenario must be 0. A 4 anywhere leaves the run unproved rather than green.
if [ $RC_FLOW -eq 0 ] && [ $RC_DIRTY -eq 0 ] && [ $RC_NOCUSTOMER -eq 0 ] && [ $RC_LABELS -eq 0 ] \
  && [ $RC_PERSIST -eq 0 ] && [ $RC_RECORD -eq 0 ] && [ $RC_ENTRY -eq 0 ] && [ $RC_ADO -eq 0 ] \
  && [ $RC_ARMED -eq 0 ] && [ $RC_HANDOFF -eq 0 ] \
  && [ $RC_NOTOOL -eq 0 ] && [ $RC_NONODE -eq 0 ] && [ $RC_ANCHOR -eq 0 ]; then
  echo "RESULT: GREEN — every scenario was put and answered."
  exit 0
fi
if [ $UNPROVED -eq 1 ] \
  && [ $RC_FLOW -eq 0 ] && [ $RC_DIRTY -eq 0 ] && [ $RC_NOCUSTOMER -eq 0 ] && [ $RC_LABELS -eq 0 ] \
  && [ $RC_PERSIST -eq 0 ] && [ $RC_RECORD -eq 0 ] && [ $RC_ENTRY -eq 0 ] && [ $RC_ADO -eq 0 ] \
  && [ $RC_ARMED -eq 0 ] && [ $RC_NOTOOL -eq 0 ] && [ $RC_NONODE -eq 0 ] \
  && { [ $RC_HANDOFF -eq 0 ] || [ $RC_HANDOFF -eq 4 ]; } \
  && { [ $RC_ANCHOR -eq 0 ] || [ $RC_ANCHOR -eq 4 ]; }; then
  echo "RESULT: UNGEPRUEFT — nothing failed, but the scenarios named above were never put."
  exit 4
fi
echo "RESULT: RED — at least one scenario failed; see the codes above."
exit 1
