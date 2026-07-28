#!/usr/bin/env bash
# Headless proof for the ADO half of "Aufnahme abgeben" — and for the property that half
# may never lose: ONE press, at most ONE Azure DevOps run.
#
#   bash ingenious-plugin/harness/run-abgabe-harness.sh
#
# Run from anywhere. Needs JAVA_HOME (or java/javac on PATH), `node`, and ingenious-api
# in ~/.m2: mvn -f INGenious/ingenious-api/pom.xml install
#
# REGISTRATION — run-all.sh and ci-gate.sh are shared and are edited by whoever owns them.
# These are the lines this harness needs, verbatim:
#
#   run-all.sh, next to the other headless rows:
#     run_one abgabe           "abgeben reports what is in ADO and never uploads twice" bash "$HERE/run-abgabe-harness.sh"
#   run-all.sh, in why_skip():
#     abgabe)            first_reason "$(need_java)" "$(need_classes)" "$(need_apijar)" "$(need_node)" ;;
#   ci-gate.sh, in EXPECTED_HARNESSES:
#     abgabe
#
# WHAT THIS DOES NOT DO, deliberately:
#   * no Azure DevOps run is ever created. Three scenarios run with ING_ADO_UPLOAD=0; the
#     one call made with uploading ON passes --dry-run, which makes ado-automark.mjs answer
#     from canned data without ever spawning `az` or reaching the network.
#   * no `az` is ever started: ADO_NONINTERACTIVE=1 on every child that could want one.
#   * the tester's real selection file is never touched — ING_TESTCASE_SELECTION points
#     inside this harness's own work directory for every scenario.
set -u

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"

API_JAR="$(ls "$HOME"/.m2/repository/com/ing/ingenious-api/3.0/ingenious-api-3.0.jar 2>/dev/null | head -1)"
[ -n "$API_JAR" ] || { echo "!! ingenious-api-3.0.jar not in ~/.m2 — cannot compile the plugin"; exit 2; }

WORK="$REPO/ingenious-plugin/target/harness-abgabe"
CLASSES="$WORK/classes"
rm -rf "$WORK"; mkdir -p "$CLASSES"

# Compiled here rather than taken from target/classes: this proof depends on de.ing.qa.studio
# and de.ing.qa.ado and on nothing in de.ing.qa.panel, so it stands up while the panels are
# being edited in another lane.
SRC="$REPO/ingenious-plugin/src/main/java/de/ing/qa"
"$JAVAC" -encoding UTF-8 -cp "$API_JAR" -d "$CLASSES" "$SRC"/ado/*.java "$SRC"/studio/*.java \
  || exit 2
"$JAVAC" -encoding UTF-8 -cp "$CLASSES:$API_JAR" -d "$WORK" \
  "$REPO/ingenious-plugin/harness/AbgabeHarness.java" || exit 2

# ------------------------------------------------------------------- the stub repo
# Only the `doppelt` scenario uses it. Its uploader announces that it has started and then
# waits for a file the harness creates, so "an upload is in flight" is a fact rather than a
# race the scheduler might lose. Everything else runs against the REAL tools.
FAKE="$WORK/doppelt/fakerepo"
mkdir -p "$FAKE/tools" "$FAKE/ing-qa-recorder/mvp"
# AdoCache.repoRoot() accepts a directory by the presence of this file.
echo "// Stub. AdoCache.repoRoot() only checks that this file exists." > "$FAKE/tools/ado-testcases.mjs"
cat > "$FAKE/tools/parse-report.mjs" <<'PARSE'
// Stub: one test case whose name carries the ADO id, exactly as the guided flow names it.
console.log(JSON.stringify({ testCases: [
  { name: 'Payment Operations:3951650 - Partner-Suche pruefen', status: 'PASS' },
] }));
PARSE
cat > "$FAKE/ing-qa-recorder/mvp/ado-upload.mjs" <<'UPLOAD'
// Stub uploader: records every invocation, and holds the upload open until released, so the
// claim in AdoUpload.forRun can be tested while an upload is genuinely in flight. It cannot
// reach Azure DevOps: there is no network code in this file.
import { appendFileSync, existsSync, writeFileSync } from 'node:fs';
const args = process.argv.slice(2);
if (args.includes('--state')) {
  // Answered AUS so the sign-in check is never armed and no window can open.
  console.log('ADO-UPLOAD AUS (Stub: der Schalter wird hier nicht gefragt)');
  process.exit(0);
}
appendFileSync(process.env.STUB_LOG, 'upload ' + args.join(' ') + '\n');
writeFileSync(process.env.STUB_STARTED, 'laeuft');
const sleep = (ms) => Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
for (let waited = 0; waited < 60000 && !existsSync(process.env.STUB_GATE); waited += 50) sleep(50);
console.log('ADO-UPLOAD AUS Stub — es wurde nichts hochgeladen.');
UPLOAD

# ---------------------------------------------------------------------- scenarios
export ING_REAL_REPO="$REPO"

run() { # scenario
  local s="$1"
  local dir="$WORK/$s"
  mkdir -p "$dir/logs" "$dir/projekt"
  echo
  echo "################################################################"
  ING_HARNESS_WORK="$dir" \
  ING_INGENIOUS_PROJECT="$dir/projekt" \
  ING_TESTCASE_SELECTION="$dir/selected-testcase.json" \
  ING_ADO_UPLOAD_LOGS="$dir/logs" \
  ING_QA_REPO="${ING_QA_REPO_OVERRIDE:-$REPO}" \
  ADO_NONINTERACTIVE=1 \
  STUB_LOG="$dir/stub-calls.log" \
  STUB_STARTED="$dir/stub-started" \
  STUB_GATE="$dir/stub-gate" \
  "$JAVA" -Djava.awt.headless=true -Dfile.encoding=UTF-8 \
    -cp "$WORK:$CLASSES:$API_JAR" AbgabeHarness "$s"
}

ING_ADO_UPLOAD=0 run kein-lauf;  RC_KEIN=$?
ING_ADO_UPLOAD=0 run nachholen;  RC_NACH=$?
# `bereits` writes its receipt with uploading ON and --dry-run, from inside the harness.
ING_ADO_UPLOAD=0 run bereits;    RC_BEREITS=$?
ING_QA_REPO_OVERRIDE="$FAKE" ING_ADO_UPLOAD=0 run doppelt; RC_DOPPELT=$?

echo
echo "################################################################"
echo "Belege in $WORK/*/logs:"
ls -1 "$WORK"/*/logs 2>/dev/null | sed 's/^/  /'
echo
echo "kein-lauf=$RC_KEIN nachholen=$RC_NACH bereits=$RC_BEREITS doppelt=$RC_DOPPELT  (0 = green)"
[ $RC_KEIN -eq 0 ] && [ $RC_NACH -eq 0 ] && [ $RC_BEREITS -eq 0 ] && [ $RC_DOPPELT -eq 0 ]
