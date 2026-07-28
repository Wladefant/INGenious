#!/usr/bin/env bash
# Headless proof that a finished INGenious run reaches Azure DevOps from Studio —
# the trigger that replaces the retiring companion (#124, #93).
#
#   bash ingenious-plugin/harness/run-run-watcher-harness.sh
#
# Run from the repo root. Needs JAVA_HOME (or `java` on PATH), `node`, and the plugin
# classes: mvn -f ingenious-plugin/pom.xml package
#
# The fixtures are REAL INGenious run output from artifacts/, and the report reader and
# the uploader are the real Node tools. ING_ADO_UPLOAD=0 stops the chain exactly at the
# ADO boundary, so a harness never writes to a live banking system.
set -u

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
JAVA="${JAVA_HOME:-}"
if [ -n "$JAVA" ]; then JAVA="$JAVA/bin/java"; else JAVA="java"; fi
JAVAC="${JAVA_HOME:-}"
if [ -n "$JAVAC" ]; then JAVAC="$JAVAC/bin/javac"; else JAVAC="javac"; fi

API_JAR="$(ls "$HOME"/.m2/repository/com/ing/ingenious-api/3.0/ingenious-api-3.0.jar 2>/dev/null | head -1)"
WORK="$REPO/ingenious-plugin/target/harness-runwatcher"
CLASSES="$WORK/classes"
rm -rf "$WORK"; mkdir -p "$WORK/logs" "$CLASSES"

# Compiled here rather than taken from target/classes, because the trigger depends on
# de.ing.qa.studio and de.ing.qa.ado and on nothing in de.ing.qa.panel — so this proof
# stands up even while the panels are being edited in another branch.
SRC="$REPO/ingenious-plugin/src/main/java/de/ing/qa"
"$JAVAC" -encoding UTF-8 -cp "$API_JAR" -d "$CLASSES" "$SRC"/ado/*.java "$SRC"/studio/*.java \
  || exit 2

"$JAVAC" -encoding UTF-8 -cp "$CLASSES:$API_JAR" -d "$WORK" \
  "$REPO/ingenious-plugin/harness/RunWatcherHarness.java" || exit 2

export ING_QA_REPO="$REPO"
export ING_HARNESS_WORK="$WORK"
export ING_INGENIOUS_PROJECT="$WORK/projekt"
export ING_ADO_UPLOAD_LOGS="$WORK/logs"

run() {
  echo
  echo "################################################################"
  "$JAVA" -Djava.awt.headless=true -Dfile.encoding=UTF-8 -cp "$WORK:$CLASSES:$API_JAR" \
    RunWatcherHarness "$@"
}

run zeile;         RC_ZEILE=$?
run entdeckt;      RC_ENTDECKT=$?
# Its own project directory: this scenario needs a Results tree nothing else has written to,
# and resultsRoot() reads ING_INGENIOUS_PROJECT.
ING_INGENIOUS_PROJECT="$WORK/projekt-kopie" run kopie; RC_KOPIE=$?
ING_ADO_UPLOAD=0 run kette;         RC_KETTE=$?
ING_ADO_UPLOAD=0 run durchgefallen; RC_FAIL=$?

echo
echo "################################################################"
echo "Belege in $WORK/logs:"
ls -1 "$WORK/logs" 2>/dev/null | sed 's/^/  /'
echo
echo "zeile=$RC_ZEILE entdeckt=$RC_ENTDECKT kopie=$RC_KOPIE kette=$RC_KETTE durchgefallen=$RC_FAIL  (0 = green)"
[ $RC_ZEILE -eq 0 ] && [ $RC_ENTDECKT -eq 0 ] && [ $RC_KOPIE -eq 0 ] \
  && [ $RC_KETTE -eq 0 ] && [ $RC_FAIL -eq 0 ]
