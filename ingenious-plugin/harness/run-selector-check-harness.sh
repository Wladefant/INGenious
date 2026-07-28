#!/usr/bin/env bash
# What the selector check tells a tester — proved against the real probe and a real page.
#
#   bash ingenious-plugin/harness/run-selector-check-harness.sh
#
# Exit 0 GREEN · 1 RED · 2 the harness could not be built or its preconditions are missing
# · 4 UNGEPRUEFT (it ran, nothing failed, but part of it was never put — e.g. no desktop, so
#   the messages were never measured against a real font).
#
# WHAT IS REAL HERE
#   * tools/selector-uniqueness.mjs is started as a child process and answers for itself. No
#     exit code is faked and no count is stubbed — Chromium opens the fixture page and counts.
#   * the page is served over loopback by the fixture's own server, because the iframe cases
#     need a same-origin frame and file:// would make them untestable rather than tested.
#
# WHAT THE FIXTURE'S EXIT CODE IS NOT
#   The probe project is deliberately ambiguous: it exists to be caught. One of its cases even
#   PASSES under the real engine because the engine is wrong (ambiguous css inside a frame is
#   resolved with .first() — https://github.com/ing-bank/INGenious/issues/320). So the fixture
#   exiting 1 is the fixture working. What this harness asserts is the MAPPING: which sentence
#   a tester reads for each answer, and which of those sentences may look like a pass.
#
# THE HARNESS BUILDS ITS OWN COPY.
#   ingenious-plugin/target/classes is shared — maven writes it and several harnesses recompile
#   it underneath each other. It has produced a stale-binary false verdict more than once. So
#   this compiles into a directory it owns, unless run-all.sh has already exported one.
set -u

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
FIXTURE="$REPO/tools/fixtures/ambiguous-selectors"
PROJECT="$FIXTURE/AmbiguityProbe"
PORT="${ING_HARNESS_FIXTURE_PORT:-8734}"

JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
win() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else echo "$1"; fi; }

skip() { echo "SKIP — $1"; exit 2; }

command -v "$JAVA"  >/dev/null 2>&1 || skip "no java (set JAVA_HOME to a JDK 17)"
command -v "$JAVAC" >/dev/null 2>&1 || skip "no javac — a JRE is not enough"
command -v node     >/dev/null 2>&1 || skip "no node on PATH; the probe is a Node tool"
[ -f "$REPO/tools/selector-uniqueness.mjs" ] || skip "tools/selector-uniqueness.mjs is missing"
[ -d "$PROJECT/ObjectRepository/Web" ]       || skip "the probe project is missing: $PROJECT"

# The probe needs the playwright package. Asked of node rather than guessed from a folder —
# a node_modules directory that exists and does not resolve is the failure this catches.
node -e "import('playwright').then(()=>process.exit(0)).catch(()=>process.exit(1))" 2>/dev/null \
  || skip "the playwright package is not resolvable from $REPO — run: npm i -D playwright"

OUT="${ING_HARNESS_OUT:-$REPO/ingenious-plugin/target/harness-selector-check}"
rm -rf "$OUT"; mkdir -p "$OUT"

# ---------------------------------------------------------------------------------------
# Build. run-all.sh exports a plugin build it owns; alone, this makes its own.
# ---------------------------------------------------------------------------------------
JAR="${JAVA_HOME:+$JAVA_HOME/bin/}jar"
PLUGIN_CLASSES="${ING_PLUGIN_CP:-}"

# The api contract, CHOSEN BY CAPABILITY rather than by an exported variable being set.
#
# The variable this used to read, ING_PLUGIN_API_CLASSES, was exported by run-all.sh
# unconditionally and filled only when the jar in ~/.m2 turned out NOT to carry contract/ui —
# so on a machine where the jar is good it named a directory that was never created. Trusting
# it cost this harness a red run inside the suite while it was green standalone, which is the
# same "believed a name instead of asking" mistake run-all documents for its own install
# probe. run-all.sh no longer offers that shape: ING_PLUGIN_API_CP is exported only when it
# names something verified to carry the class, and the build DIRECTORY has a name that says
# so. The probe below stays anyway — it costs one stat, and it is what makes this harness
# right under a runner that has not been fixed yet.
API_CP=""
api_ok() { [ -f "$1/com/ing/ingenious/api/contract/ui/StudioPanelApi.class" ]; }
M2_JAR="$HOME/.m2/repository/com/ing/ingenious-api/3.0/ingenious-api-3.0.jar"

if [ -n "${ING_PLUGIN_API_CP:-}" ] && { api_ok "${ING_PLUGIN_API_CP}" \
     || { [ -f "${ING_PLUGIN_API_CP}" ] && MSYS_NO_PATHCONV=1 "$JAR" tf "$(win "${ING_PLUGIN_API_CP}")" \
          2>/dev/null | tr -d '\r' | grep -q "contract/ui/StudioPanelApi.class"; }; }; then
  API_CP="$(win "$ING_PLUGIN_API_CP")"
elif [ -f "$M2_JAR" ] && MSYS_NO_PATHCONV=1 "$JAR" tf "$(win "$M2_JAR")" 2>/dev/null \
     | tr -d '\r' | grep -q "contract/ui/StudioPanelApi.class"; then
  API_CP="$(win "$M2_JAR")"
else
  API_SRC="$REPO/INGenious/ingenious-api/src/main/java"
  [ -d "$API_SRC" ] || skip "no ingenious-api contract: not in ~/.m2 and no source at INGenious/ingenious-api"
  API_CLASSES="$OUT/api-classes"; mkdir -p "$API_CLASSES"
  ASRCS=""; for f in $(find "$API_SRC" -name '*.java'); do ASRCS="$ASRCS $(win "$f")"; done
  MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' "$JAVAC" -encoding UTF-8 \
    -d "$(win "$API_CLASSES")" $ASRCS > "$OUT/api-build.log" 2>&1 \
    || { echo "the ingenious-api contract did not compile — see $OUT/api-build.log"; exit 2; }
  API_CP="$(win "$API_CLASSES")"
fi

# An existing directory is not a compiled plugin: run-all.sh creates its build directory
# before javac runs, so `-d` would be satisfied by an empty one and the harness would report
# a failure of the panel where the truth is that the plugin never compiled.
if [ -z "$PLUGIN_CLASSES" ] || [ ! -f "$PLUGIN_CLASSES/de/ing/qa/panel/GuidedFlowPanel.class" ]; then
  PLUGIN_CLASSES="$OUT/plugin-classes"; mkdir -p "$PLUGIN_CLASSES"
  SRCS=""; for f in $(find "$REPO/ingenious-plugin/src/main/java" -name '*.java'); do
    SRCS="$SRCS $(win "$f")"
  done
  MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' "$JAVAC" -encoding UTF-8 \
    -cp "$API_CP" -d "$(win "$PLUGIN_CLASSES")" $SRCS \
    > "$OUT/plugin-build.log" 2>&1 \
    || { echo "the plugin did not compile — see $OUT/plugin-build.log"; exit 2; }
  cp -r "$REPO/ingenious-plugin/src/main/resources/." "$PLUGIN_CLASSES/" 2>/dev/null || true
fi

# The harness itself declares package de.ing.qa.panel: SelectorCheck is package-private, and
# widening it so a test can reach it would be a production change made for a test's benefit.
HARNESS_CLASSES="$OUT/harness-classes"; mkdir -p "$HARNESS_CLASSES"
MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' "$JAVAC" -encoding UTF-8 \
  -cp "$(win "$PLUGIN_CLASSES");$API_CP" \
  -d "$(win "$HARNESS_CLASSES")" "$(win "$HERE/SelectorCheckHarness.java")" \
  > "$OUT/harness-build.log" 2>&1 \
  || { echo "the harness did not compile — see $OUT/harness-build.log"; cat "$OUT/harness-build.log"; exit 2; }

# ---------------------------------------------------------------------------------------
# The page. A real server on loopback, stopped whatever happens.
# ---------------------------------------------------------------------------------------
node "$FIXTURE/serve.mjs" --port "$PORT" > "$OUT/serve.log" 2>&1 &
SERVER=$!
cleanup() {
  kill "$SERVER" 2>/dev/null || true
  # The receipt belongs to the fixture project and must not be left behind as a committed
  # artifact of a test run — but it is copied out first, because it is the evidence.
  [ -f "$PROJECT/selector-uniqueness.json" ] \
    && cp "$PROJECT/selector-uniqueness.json" "$OUT/receipt.json" 2>/dev/null
  rm -f "$PROJECT/selector-uniqueness.json"
}
trap cleanup EXIT

URL="http://127.0.0.1:$PORT/"
for _ in $(seq 1 40); do
  if node -e "fetch('$URL').then(()=>process.exit(0)).catch(()=>process.exit(1))" 2>/dev/null; then
    break
  fi
  sleep 0.25
done
node -e "fetch('$URL').then(()=>process.exit(0)).catch(()=>process.exit(1))" 2>/dev/null \
  || { echo "the fixture server never answered on $URL — see $OUT/serve.log"; exit 2; }

# ---------------------------------------------------------------------------------------
# Run. headless=false because the rendering step measures a real font at a real size; the
# harness itself reports UNGEPRUEFT rather than passing when there is no desktop.
# ---------------------------------------------------------------------------------------
# Converted, because these are read by the JVM and not by the shell. An MSYS "/c/Users/…"
# reaches Java as "\c\Users\…", which is a path that does not exist — and the first symptom
# is the harness reporting that the probe is not installed on a machine that has it.
export ING_QA_REPO="$(win "$REPO")"
export ING_INGENIOUS_PROJECT="$(win "$PROJECT")"
export ING_HARNESS_FIXTURE_URL="$URL"
export ING_ADO_UPLOAD=0

MSYS_NO_PATHCONV=1 "$JAVA" -Djava.awt.headless=false -Dfile.encoding=UTF-8 \
  -cp "$(win "$HARNESS_CLASSES");$(win "$PLUGIN_CLASSES");$API_CP" \
  de.ing.qa.panel.SelectorCheckHarness
exit $?
