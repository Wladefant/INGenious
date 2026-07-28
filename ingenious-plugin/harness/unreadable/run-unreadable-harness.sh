#!/usr/bin/env bash
# What the recording button does when Studio will not say what the recorder is doing.
#
#   bash ingenious-plugin/harness/unreadable/run-unreadable-harness.sh
#
# Two runs, two Studio doubles, one JVM each — the doubles carry the same class names as
# harness/studio-double and as each other, so they cannot share a classpath:
#
#   1. umbenannt   — isRecording() is gone from the toolbar, record() is still the toggle,
#                    and a recording is live. This is the state the handout's warning is
#                    about, and the one the documentation lane refused to call fixed.
#   2. kein-boolean — isRecording() is there and answers null. Nothing fails; the state
#                    simply cannot be read, which used to come out as "nothing is recording".
#
# Both write PNGs into ingenious-plugin/target/harness-guided/ beside the other panel proofs,
# numbered from 40 so they cannot collide with them. Run this AFTER
# harness/run-guided-flow-harness.sh, not before: that script empties the shot directory when
# it starts, so shots 40-43 would be gone by the time anyone looked.
#
# NOT headless, on purpose: reaching the
# recording step goes through the system clipboard, which throws under headless. The frame is
# packed but never shown.
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
WORK="$PLUGIN/target/harness-unreadable"
SHOTS="$PLUGIN/target/harness-guided"
API_SRC="$REPO/INGenious/ingenious-api/src/main/java/com/ing/ingenious/api/contract/ui"

rm -rf "$WORK"; mkdir -p "$WORK/umbenannt" "$WORK/kein-boolean" "$SHOTS"

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

# RecorderProbe belongs to the guided-flow harness and is used here unchanged: it is the seam
# that can send a start request the button itself refuses to send, which is how a refusal is
# told apart from a button that is merely greyed out.
build_case() {
  local dir="$1"; shift
  local double="$1"; shift
  "$JAVAC" -encoding UTF-8 -d "$dir" $(find "$HERE/$double" -name '*.java') || exit 2
  "$JAVAC" -encoding UTF-8 -cp "$CLASSES:$API_CP:$dir" -d "$dir" \
    "$PLUGIN/harness/RecorderProbe.java" \
    "$HERE/PanelHarnessKit.java" "$HERE/$1" || exit 2
}

build_case "$WORK/umbenannt"    studio-double-renamed    RecorderUnreadableHarness.java
build_case "$WORK/kein-boolean" studio-double-nonboolean RecorderNonBooleanHarness.java

FIXTURE="$PLUGIN/sample/ado-testcases-beispiel.json"

run() {
  local dir="$1"; shift
  echo
  echo "################################################################"
  ING_ADO_CACHE="$FIXTURE" \
  ING_TESTCASE_SELECTION="$WORK/selected-testcase.json" \
  ING_TESTDATA_CSV="$PLUGIN/sample/testdaten-beispiel.csv" \
  ING_TESTDATA_LABELS="$PLUGIN/sample/labels.properties" \
  "$JAVA" -Djava.awt.headless=false -Dfile.encoding=UTF-8 \
    -cp "$dir:$CLASSES:$API_CP" "$@" "$SHOTS"
}

run "$WORK/umbenannt" unreadable.RecorderUnreadableHarness
RC_RENAMED=$?

run "$WORK/kein-boolean" unreadable.RecorderNonBooleanHarness
RC_NONBOOL=$?

echo
echo "################################################################"
echo "umbenannt=$RC_RENAMED kein-boolean=$RC_NONBOOL  (0 = green, 4 = ungeprueft)"
echo "screenshots: $SHOTS"

# TWO JVMs, ONE EXIT CODE — and the mapping is not a boolean.
#
# This line used to be `[ $RC_RENAMED -eq 0 ] && [ $RC_NONBOOL -eq 0 ]`, which has exactly two
# outputs: 0 and 1. Every other code a harness can produce — 4 (nothing failed, a question was
# never asked), 2 (it would not build), 124 (timeout killed it) — arrived at run-all.sh as a
# plain FAIL. That destroys the same information the suite spends the rest of its effort
# protecting, only in the other direction: a run that never got to ask is reported as a run
# that asked and got a bad answer, and somebody goes looking for a defect that was never
# measured. "Not proved" and "proved wrong" are not the same sentence in either direction.
#
# The order below is the ranking the whole suite uses: a real failure outranks a not-asked,
# and a not-asked outranks a pass. The first real failure's own code survives, so `timeout`'s
# 124 still reaches run-all.sh as 124 and is reported as HUNG rather than as a bad verdict.
worst() {
  local rc=0 r
  for r in "$@"; do
    if [ "$r" = 0 ]; then
      continue
    elif [ "$r" = 4 ]; then
      [ "$rc" = 0 ] && rc=4
    else
      if [ "$rc" = 0 ] || [ "$rc" = 4 ]; then rc="$r"; fi
    fi
  done
  printf '%s' "$rc"
}
exit "$(worst "$RC_RENAMED" "$RC_NONBOOL")"
