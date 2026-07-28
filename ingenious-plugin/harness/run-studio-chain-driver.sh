#!/usr/bin/env bash
# Walks the WHOLE tester chain through the real guided-flow panel in a real running Studio,
# once, in one sitting, and prints a link-by-link verdict table.
#
#   bash ingenious-plugin/harness/run-studio-chain-driver.sh <install-root> [project]
#
# e.g. bash ingenious-plugin/harness/run-studio-chain-driver.sh \
#          C:/Users/wkiri/OneDrive/Desktop/ing/INGenious/Dist/release
#
# Needs a display: it starts Studio for real, and a Studio window WILL appear for a few
# minutes. Screenshots of every stage are written to
# ingenious-plugin/target/studio-chain/shots/.
#
# WHAT IT DOES NOT TOUCH
#   * the installed plugin — INGENIOUS_PLUGIN_PATH replaces the whole plugin search path
#     (PluginSearchPath.resolve returns early when it is set), so a freshly built jar in a
#     directory of its own is used and whatever the user has installed is left alone;
#   * the shipped sample projects — the project is COPIED into the work directory first, so
#     a run that creates test cases, test data and Results leaves the repo clean and the
#     next run starts from the same state;
#   * Azure DevOps — ING_ADO_UPLOAD=0. A finished run really does invoke ado-upload.mjs, and
#     with the flag off it answers "ADO-UPLOAD AUS" and writes nothing. The driver refuses
#     to start if the flag says anything else.
#   * the real banking application — nothing here logs in anywhere. The recording is
#     exercised only as far as Studio's own set-up: target resolution and test-case
#     creation. The browser half needs an interactive single-sign-on session and is out of
#     scope by design, not by accident.
#
# ING_INGENIOUS_PROJECT is explicitly unset because it short-circuits
# AdoRunWatcher.resultsRoot(), one of the links under test.
set -u

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ROOT="${1:?usage: run-studio-chain-driver.sh <install-root> [project]}"
SOURCE_PROJECT="${2:-$ROOT/Projects/CLIDemo}"

JAVA="${JAVA_HOME:-}"; if [ -n "$JAVA" ]; then JAVA="$JAVA/bin/java"; else JAVA="java"; fi
JAVAC="${JAVA_HOME:-}"; if [ -n "$JAVAC" ]; then JAVAC="$JAVAC/bin/javac"; else JAVAC="javac"; fi

PLUGIN="$REPO/ingenious-plugin"
WORK="$PLUGIN/target/studio-chain"
PLUGINS="$WORK/plugins/ing-tester-panel"
PROJECT="$WORK/project"

rm -rf "$WORK"
mkdir -p "$WORK" "$PLUGINS" "$WORK/logs" "$WORK/shots"

JAR="$PLUGIN/target/ing-tester-panel-0.1.0.jar"
if [ ! -f "$JAR" ]; then
  echo "build the plugin first: mvn -f ingenious-plugin/pom.xml package" >&2
  exit 2
fi
cp "$JAR" "$PLUGINS/"
echo "plugin under test : $JAR"

if [ ! -d "$SOURCE_PROJECT" ]; then
  echo "no such project: $SOURCE_PROJECT" >&2
  exit 2
fi
cp -r "$SOURCE_PROJECT" "$PROJECT"
echo "project (a copy)  : $PROJECT  <- $SOURCE_PROJECT"
"$JAVA" -version 2>&1 | head -1

# Windows java wants Windows paths, and a ';' classpath must not be mangled by MSYS.
win() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else echo "$1"; fi; }
WORK_W="$(win "$WORK")"
ROOT_W="$(win "$ROOT")"

# Which Studio to run. The installed one by default — that is what a tester has. Set
# ING_IDE_JAR to a freshly built IDE jar to ask the questions the installed one cannot answer:
# its Panel.activate takes no ProjectTestDataApi, so on that build the plugin is never handed
# the project's test data and the customer profile CANNOT be written. Which jar was used is
# printed, because "the profile did not save" means opposite things on the two of them.
IDE_JAR="${ING_IDE_JAR:-$ROOT/ingenious-ide-3.0.0.jar}"
echo "studio jar        : $IDE_JAR"
CP="$(win "$IDE_JAR");$ROOT_W\\lib\\*;$ROOT_W\\lib\\clib\\*"
MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' \
"$JAVAC" -encoding UTF-8 -cp "$CP" -d "$WORK_W" \
  "$(win "$PLUGIN/harness/PanelAnchor.java")" \
  "$(win "$PLUGIN/harness/StudioChainDriver.java")" || exit 2

# The panel's inputs. The selection file must NOT exist yet: the chooser preselects from it,
# and a step that is already done before the driver presses anything proves nothing.
unset ING_INGENIOUS_PROJECT
export ING_ADO_UPLOAD=0
export INGENIOUS_PLUGIN_PATH="$(win "$WORK/plugins")"
export ING_ADO_CACHE="$(win "$PLUGIN/sample/ado-testcases-beispiel.json")"
export ING_TESTCASE_SELECTION="$(win "$WORK/selected-testcase.json")"
export ING_TESTDATA_CSV="$(win "$PLUGIN/sample/testdaten-echte-spalten.csv")"
export ING_QA_REPO="$(win "$REPO")"
export ING_ADO_UPLOAD_LOGS="$(win "$WORK/logs")"

echo "ado cache         : $ING_ADO_CACHE"
echo "test data         : $ING_TESTDATA_CSV"
echo "upload logs       : $ING_ADO_UPLOAD_LOGS"
echo

( cd "$ROOT" && MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' \
    "$JAVA" -Dfile.encoding=UTF-8 -cp "$WORK_W;$CP" \
      StudioChainDriver "$(win "$PROJECT")" "$WORK_W" chain )
RC=$?

# Second pass, on a project of its own. The one-run-one-upload question cannot be put by the
# first pass: the test case the guided flow records is empty — recording needs an interactive
# single-sign-on session — and an empty run never reaches the engine's report-copying step.
# This pass runs the test case the project opens with, which has steps, so the run really
# finishes and the engine really makes its "Latest" copy.
echo
echo "################################################################"
echo "second pass: a run with steps, to put the one-run-one-upload question"
PROJECT2="$WORK/project-run"
rm -rf "$PROJECT2"; cp -r "$SOURCE_PROJECT" "$PROJECT2"
export ING_TESTCASE_SELECTION="$(win "$WORK/selected-testcase-run.json")"
( cd "$ROOT" && MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' \
    "$JAVA" -Dfile.encoding=UTF-8 -cp "$WORK_W;$CP" \
      StudioChainDriver "$(win "$PROJECT2")" "$WORK_W" runonly )
RC2=$?

echo
echo "screenshots: $WORK/shots"
# 0 = every link was put and none is broken. 4 = a link is BROKEN. 5 = UNGEPRUEFT: nothing
# broke, but at least one link was never put — which the driver used to report as 0, so a walk
# that crashed in its catch(Throwable), or hung until the twenty-minute watchdog fired, read
# here as "no link is broken". A link nobody asked is not a link that passed.
case $RC in
  0) echo "PASS 1 (whole chain): every link was put and none is broken." ;;
  4) echo "PASS 1 (whole chain): at least one link is BROKEN — see the table above." ;;
  5) echo "PASS 1 (whole chain): UNGEPRUEFT — nothing broke, but at least one link was never put." ;;
  *) echo "PASS 1 (whole chain): INCONCLUSIVE (exit $RC) — the walk never got far enough to ask." ;;
esac
case $RC2 in
  0) echo "PASS 2 (run with steps): every link was put and none is broken." ;;
  4) echo "PASS 2 (run with steps): at least one link is BROKEN — see the table above." ;;
  5) echo "PASS 2 (run with steps): UNGEPRUEFT — nothing broke, but at least one link was never put." ;;
  *) echo "PASS 2 (run with steps): INCONCLUSIVE (exit $RC2)." ;;
esac
[ $RC -eq 0 ] && [ $RC2 -eq 0 ]
exit $?
