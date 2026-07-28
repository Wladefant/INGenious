#!/usr/bin/env bash
# Setting the recorder's start address from the panel, and proving it reached the file.
#
#   bash ingenious-plugin/harness/unreadable/run-start-address-harness.sh
#
# One JVM, one Studio double. The double's RecorderSettings is file-backed — same
# getStartUrl/setStartUrl/save/getLocation shape as the product's, whose save() and
# getLocation() come from AbstractPropSettings — because the question is not whether a label
# changed but whether the typed value is in <project>/Settings/RecorderSettings.Properties,
# which is the only place TestCaseComponent.resolveRecordingStartUrl looks.
#
# ING_START_ADDRESS_MEMORY points the panel's own copy of the address at this run's work
# directory. Without it the harness would read and write the real ~/.IngQaAutopilot copy, and
# a developer's leftover address would decide what the run proves.
#
# The double carries the same class names as harness/studio-double and as the doubles beside
# it, so it cannot share their classpath; hence its own directory and its own run.
#
# Writes PNGs into ingenious-plugin/target/harness-guided/ beside the other panel proofs,
# numbered from 50 so they cannot collide. Run AFTER harness/run-guided-flow-harness.sh:
# that script empties the shot directory when it starts.
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
WORK="$PLUGIN/target/harness-start-address"
SHOTS="$PLUGIN/target/harness-guided"
API_SRC="$REPO/INGenious/ingenious-api/src/main/java/com/ing/ingenious/api/contract/ui"

rm -rf "$WORK"; mkdir -p "$WORK/classes" "$WORK/project/Settings" "$SHOTS"

API_JAR="$HOME/.m2/repository/com/ing/ingenious-api/3.0/ingenious-api-3.0.jar"
if [ -f "$API_JAR" ] && unzip -l "$API_JAR" 2>/dev/null | grep -q "contract/ui/StudioPanelApi.class"; then
  API_CP="$API_JAR"
else
  rm -rf "$API_CLASSES"; mkdir -p "$API_CLASSES"
  "$JAVAC" -encoding UTF-8 -d "$API_CLASSES" "$API_SRC"/*.java || exit 2
  API_CP="$API_CLASSES"
fi

# The panel itself, exactly as the other panel harnesses build it.
rm -rf "$CLASSES"; mkdir -p "$CLASSES"
"$JAVAC" -encoding UTF-8 -cp "$API_CP" -d "$CLASSES" \
  $(find "$PLUGIN/src/main/java" -name '*.java') || exit 2
cp -r "$PLUGIN/src/main/resources/." "$CLASSES/" 2>/dev/null || true

"$JAVAC" -encoding UTF-8 -d "$WORK/classes" \
  $(find "$HERE/studio-double-starturl" -name '*.java') || exit 2
# RecorderProbe belongs to the guided-flow harness and is compiled here unchanged, because
# PanelHarnessKit is shared and reaches the recorder through it.
"$JAVAC" -encoding UTF-8 -cp "$CLASSES:$API_CP:$WORK/classes" -d "$WORK/classes" \
  "$PLUGIN/harness/RecorderProbe.java" \
  "$HERE/PanelHarnessKit.java" "$HERE/StartAddressHarness.java" || exit 2

FIXTURE="$PLUGIN/sample/ado-testcases-beispiel.json"

echo
echo "################################################################"
ING_ADO_CACHE="$FIXTURE" \
ING_TESTCASE_SELECTION="$WORK/selected-testcase.json" \
ING_START_ADDRESS_MEMORY="$WORK/start-address.properties" \
ING_TESTDATA_CSV="$PLUGIN/sample/testdaten-beispiel.csv" \
ING_TESTDATA_LABELS="$PLUGIN/sample/labels.properties" \
"$JAVA" -Djava.awt.headless=false -Dfile.encoding=UTF-8 \
  -Dharness.project.settings="$WORK/project/Settings" \
  -cp "$WORK/classes:$CLASSES:$API_CP" unreadable.StartAddressHarness "$SHOTS"
RC=$?

echo
echo "################################################################"
# $RC is propagated VERBATIM below — see the note at the end of
# run-handoff-unreadable-harness.sh. 0/4/1 are three different sentences and a boolean has
# room for two.
echo "start-address=$RC  (0 = green, 4 = ungeprueft, 1 = red)"
echo "settings file: $WORK/project/Settings/RecorderSettings.Properties"
echo "screenshots:   $SHOTS"
exit $RC
