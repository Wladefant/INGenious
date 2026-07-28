#!/usr/bin/env bash
# Headless proof that a chosen ADO case becomes the recorder's target — and that
# choosing nothing still leaves Studio's own chooser in charge.
#
#   bash ingenious-plugin/harness/run-recording-target-harness.sh
#
# Run from the repo root. Needs JAVA_HOME (or `java` on PATH) and the plugin classes,
# which come from: mvn -f ingenious-plugin/pom.xml package
set -u

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
JAVA="${JAVA_HOME:-}"
if [ -n "$JAVA" ]; then JAVA="$JAVA/bin/java"; else JAVA="java"; fi
JAVAC="${JAVA_HOME:-}"
if [ -n "$JAVAC" ]; then JAVAC="$JAVAC/bin/javac"; else JAVAC="javac"; fi

# See run-ado-harness.sh: target/classes is shared with maven and with the panel harnesses,
# which delete and recompile it. ING_PLUGIN_CP lets run-all.sh point this at a build
# nobody else is rewriting.
CLASSES="${ING_PLUGIN_CP:-$REPO/ingenious-plugin/target/classes}"
API_JAR="$(ls "$HOME"/.m2/repository/com/ing/ingenious-api/3.0/ingenious-api-3.0.jar 2>/dev/null | head -1)"
WORK="$REPO/ingenious-plugin/target/harness-recording"
rm -rf "$WORK"; mkdir -p "$WORK/tmp"

if [ ! -f "$CLASSES/de/ing/qa/panel/GuidedFlowPanel.class" ]; then
  echo "!! $CLASSES carries no compiled plugin — run: mvn -f ingenious-plugin/pom.xml package"; exit 2
fi

"$JAVAC" -encoding UTF-8 -cp "$CLASSES:$API_JAR" -d "$WORK" \
  "$REPO/ingenious-plugin/harness/RecordingTargetHarness.java" || exit 2

run() {
  echo
  echo "################################################################"
  "$JAVA" -Djava.awt.headless=true -Dfile.encoding=UTF-8 -cp "$WORK:$CLASSES:$API_JAR" \
    RecordingTargetHarness "$@"
}

ING_TESTCASE_SELECTION="$WORK/tmp/selected-testcase.json" run chosen
RC_CHOSEN=$?

# A path that deliberately does not exist: the "nothing taken yet" state.
ING_TESTCASE_SELECTION="$WORK/tmp/nichts-gewaehlt.json" run none
RC_NONE=$?

ING_TESTCASE_SELECTION="$WORK/tmp/selected-testcase.json" run naming
RC_NAMING=$?

echo
echo "################################################################"
echo "chosen=$RC_CHOSEN none=$RC_NONE naming=$RC_NAMING  (0 = green)"
[ $RC_CHOSEN -eq 0 ] && [ $RC_NONE -eq 0 ] && [ $RC_NAMING -eq 0 ]
