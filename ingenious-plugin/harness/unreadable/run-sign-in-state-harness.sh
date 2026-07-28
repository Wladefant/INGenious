#!/usr/bin/env bash
# What the panel says when the upload stops to ask the tester to sign in to Azure DevOps.
#
#   bash ingenious-plugin/harness/unreadable/run-sign-in-state-harness.sh
#
# No Studio double at all: this scenario is about one AdoUploadStatus state reaching the
# screen in the right colour, and the panel needs no Studio to show it. SIGN_IN_REQUIRED is
# the one state with no status code — it is decided before ado-upload.mjs is started — so the
# existing upload scenarios, which feed the uploader's own stdout, cannot produce it.
#
# Writes PNGs into ingenious-plugin/target/harness-guided/, numbered from 60 so they cannot
# collide with the other panel proofs. Run AFTER harness/run-guided-flow-harness.sh, which
# empties that directory when it starts.
#
# NOT headless, on purpose: reaching the recording step goes through the system clipboard,
# which throws under headless. The frame is packed but never shown.
set -u

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
PLUGIN="$REPO/ingenious-plugin"
# Same reason as run-start-address-harness.sh: this recompiles $CLASSES, so run-all.sh can
# hand the suite its own build directory instead of the shared maven output.
CLASSES="${ING_PLUGIN_BUILD_DIR:-$PLUGIN/target/classes}"
API_CLASSES="${ING_PLUGIN_API_BUILD_DIR:-$PLUGIN/target/api-classes}"
HERE="$PLUGIN/harness/unreadable"
WORK="$PLUGIN/target/harness-sign-in"
SHOTS="$PLUGIN/target/harness-guided"
API_SRC="$REPO/INGenious/ingenious-api/src/main/java/com/ing/ingenious/api/contract/ui"

rm -rf "$WORK"; mkdir -p "$WORK/classes" "$SHOTS"

API_JAR="$HOME/.m2/repository/com/ing/ingenious-api/3.0/ingenious-api-3.0.jar"
if [ -f "$API_JAR" ] && unzip -l "$API_JAR" 2>/dev/null | grep -q "contract/ui/StudioPanelApi.class"; then
  API_CP="$API_JAR"
else
  rm -rf "$API_CLASSES"; mkdir -p "$API_CLASSES"
  "$JAVAC" -encoding UTF-8 -d "$API_CLASSES" "$API_SRC"/*.java || exit 2
  API_CP="$API_CLASSES"
fi

rm -rf "$CLASSES"; mkdir -p "$CLASSES"
"$JAVAC" -encoding UTF-8 -cp "$API_CP" -d "$CLASSES" \
  $(find "$PLUGIN/src/main/java" -name '*.java') || exit 2
cp -r "$PLUGIN/src/main/resources/." "$CLASSES/" 2>/dev/null || true

# RecorderProbe and AdoUploadProbe belong to the guided-flow harness and are compiled here
# unchanged; SignInStatusProbe adds the one publish those two cannot reach.
"$JAVAC" -encoding UTF-8 -cp "$CLASSES:$API_CP" -d "$WORK/classes" \
  "$PLUGIN/harness/RecorderProbe.java" "$PLUGIN/harness/AdoUploadProbe.java" \
  "$HERE/SignInStatusProbe.java" \
  "$HERE/PanelHarnessKit.java" "$HERE/SignInStateHarness.java" || exit 2

FIXTURE="$PLUGIN/sample/ado-testcases-beispiel.json"

echo
echo "################################################################"
ING_ADO_CACHE="$FIXTURE" \
ING_TESTCASE_SELECTION="$WORK/selected-testcase.json" \
ING_TESTDATA_CSV="$PLUGIN/sample/testdaten-beispiel.csv" \
ING_TESTDATA_LABELS="$PLUGIN/sample/labels.properties" \
"$JAVA" -Djava.awt.headless=false -Dfile.encoding=UTF-8 \
  -cp "$WORK/classes:$CLASSES:$API_CP" unreadable.SignInStateHarness "$SHOTS"
RC=$?

echo
echo "################################################################"
echo "sign-in-state=$RC  (0 = green, 1 = red)"
echo "screenshots:   $SHOTS"
exit $RC
