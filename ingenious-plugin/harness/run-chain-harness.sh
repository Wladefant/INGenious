#!/usr/bin/env bash
# The whole Studio -> Azure DevOps chain, end to end, in one run — the regression guard that
# stops a change in one lane quietly breaking the seam with another.
#
#   bash ingenious-plugin/harness/run-chain-harness.sh [install-root]
#
# Run from the repo root. Needs JAVA_HOME (or `java` on PATH), `node`, and an INGenious
# install to borrow a real project and the engine/Datalib jars from.
#
#   1 a test case is chosen           5 evidence is assembled
#   2 a customer profile persisted    6 the upload is invoked
#   3 a recording target resolved     7 the outcome is announced
#   4 a finished run is detected
#
# Nothing is mocked. The project is a real INGenious project (copied, never written to in
# place) loaded with the engine's own Project; the test-data handle is Studio's own
# ProjectTestData; the run is real INGenious output from artifacts/; the report reader and
# uploader are the real Node tools. ING_ADO_UPLOAD=0 stops ado-upload.mjs at the network
# boundary and nowhere earlier — the only stub, and the last link.
#
# Exit 0 = the chain is intact. Non-zero = a link is broken, and it says which.
set -u

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ROOT="${1:-$REPO/INGenious/Dist/release}"

JAVA="${JAVA_HOME:-}"; if [ -n "$JAVA" ]; then JAVA="$JAVA/bin/java"; else JAVA="java"; fi
JAVAC="${JAVA_HOME:-}"; if [ -n "$JAVAC" ]; then JAVAC="$JAVAC/bin/javac"; else JAVAC="javac"; fi

if [ ! -f "$ROOT/ingenious-ide-3.0.0.jar" ]; then
  echo "No INGenious install at $ROOT — pass one as the first argument." >&2
  exit 2
fi
# Said out loud, because this machine has several installs and they are not the same core:
# ~/ingenious/ingenious-playwright-3.0.0-preview's Panel.activate() takes no
# ProjectTestDataApi, so on that one the customer profile cannot be written at all. A verdict
# that does not name the install it was reached against is not reproducible.
echo "studio under test: $ROOT/ingenious-ide-3.0.0.jar"

API_JAR="$(ls "$HOME"/.m2/repository/com/ing/ingenious-api/3.0/ingenious-api-3.0.jar 2>/dev/null | head -1)"
if [ -z "$API_JAR" ]; then
  echo "ingenious-api 3.0 is not in ~/.m2 — build it: mvn -f INGenious/ingenious-api/pom.xml install" >&2
  exit 2
fi

WORK="$REPO/ingenious-plugin/target/harness-chain"
CLASSES="$WORK/classes"
rm -rf "$WORK"; mkdir -p "$WORK/logs" "$CLASSES"

win() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else echo "$1"; fi; }
WORK_W="$(win "$WORK")"; CLASSES_W="$(win "$CLASSES")"; ROOT_W="$(win "$ROOT")"
API_W="$(win "$API_JAR")"

# Compiled from source rather than taken from target/classes, so the guard stands up even
# while another lane is editing the panels.
SRC="$REPO/ingenious-plugin/src/main/java/de/ing/qa"
"$JAVAC" -encoding UTF-8 -cp "$API_W" -d "$CLASSES_W" "$SRC"/ado/*.java "$SRC"/studio/*.java \
  || exit 2

STUDIO_CP="$ROOT_W\\ingenious-ide-3.0.0.jar;$ROOT_W\\lib\\*;$ROOT_W\\lib\\clib\\*"
MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' \
"$JAVAC" -encoding UTF-8 -cp "$CLASSES_W;$API_W;$STUDIO_CP" -d "$WORK_W" \
  "$(win "$REPO/ingenious-plugin/harness/ChainHarness.java")" || exit 2

# Windows paths throughout: java resolves a "/c/..." MSYS path against the current drive and
# silently lands on "C:\c\...", which exists just enough to look like it worked.
export ING_QA_REPO="$(win "$REPO")"
export ING_HARNESS_WORK="$WORK_W"
export ING_INGENIOUS_HOME="$ROOT_W"
export ING_TESTCASE_SELECTION="$(win "$WORK/selected-testcase.json")"
export ING_ADO_UPLOAD_LOGS="$(win "$WORK/logs")"
# Link 4 resolves the Results directory from this. The other way of resolving it — reflecting
# into a live AppMainFrame from the plugin class loader — is proven separately and for real by
# run-studio-watcher-driver.sh, which is where that question belongs.
export ING_INGENIOUS_PROJECT="$(win "$WORK/projekt")"
# The one stub, and the last link.
export ING_ADO_UPLOAD=0

MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' \
"$JAVA" -Djava.awt.headless=true -Dfile.encoding=UTF-8 \
  -cp "$WORK_W;$CLASSES_W;$API_W;$STUDIO_CP" ChainHarness
RC=$?

echo
echo "evidence in $WORK/logs:"
ls -1 "$WORK/logs" 2>/dev/null | sed 's/^/  /'
exit $RC
