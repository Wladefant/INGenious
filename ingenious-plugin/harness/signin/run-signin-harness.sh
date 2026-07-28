#!/usr/bin/env bash
# Headless proof for the invisible sign-in (issue #128).
#
#   bash ingenious-plugin/harness/signin/run-signin-harness.sh
#
# Run from anywhere. Needs JAVA_HOME (or java/javac on PATH) and node.
#
# WHAT THIS DOES NOT DO, deliberately:
#   * no real `az` is ever run — a fake one goes first on PATH and records every
#     invocation, the same technique ado-automark.mjs's own selftest uses. So no
#     browser prompt can appear on anybody's screen.
#   * no real ADO is ever reached — the uploader is a stub in a fake repo, and the
#     real ado-testcases.mjs scenario never obtains a token, so it never gets as far
#     as a request.
# Two brief console windows DO open (scenarios signin-window and
# upload-signin-required): that window IS the fix, and asserting on its shape without
# ever opening it would be asserting on a string.
set -u

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
WORK="$REPO/ingenious-plugin/target/signin-harness"
rm -rf "$WORK"; mkdir -p "$WORK/classes" "$WORK/bin" "$WORK/tmp"

API_JAR="$(ls "$HOME"/.m2/repository/com/ing/ingenious-api/3.0/ingenious-api-3.0.jar 2>/dev/null | head -1)"
[ -n "$API_JAR" ] || { echo "!! ingenious-api-3.0.jar not in ~/.m2 — cannot compile the plugin"; exit 2; }

"$JAVAC" -encoding UTF-8 -cp "$API_JAR" -d "$WORK/classes" \
  $(find "$REPO/ingenious-plugin/src/main/java/de/ing/qa/studio" \
         "$REPO/ingenious-plugin/src/main/java/de/ing/qa/ado" -name '*.java') \
  "$REPO/ingenious-plugin/harness/AdoUploadProbe.java" \
  "$REPO/ingenious-plugin/harness/signin/SignInHarness.java" || exit 2

# ---------------------------------------------------------------- the fake az
# LOGGED OUT: answers `account get-access-token` the way a logged-out az does, and
# records what it was asked. `login` succeeds (exit 0) but changes nothing — which is
# what a tester who closes the window looks like from here, and which keeps the
# harness from ever hitting the `|| pause` that holds a FAILED login's window open.
cat > "$WORK/bin/az.cmd" <<'AZOUT'
@echo off
>>"%AZ_SHIM_LOG%" echo %*
echo %* | findstr /C:"login" >nul
if not errorlevel 1 (
  if defined AZ_SHIM_LOGIN_WORKS >"%AZ_SHIM_SESSION%" echo signed-in
  exit /b 0
)
if defined AZ_SHIM_SESSION if exist "%AZ_SHIM_SESSION%" (
  echo SHIM.TOKEN.0123456789abcdefghijklmnopqrstuvwxyz
  exit /b 0
)
echo ERROR: Please run 'az login' to setup account. 1>&2
exit /b 1
AZOUT
cat > "$WORK/bin/az" <<'AZSH'
#!/bin/sh
echo "$@" >> "$AZ_SHIM_LOG"
case " $* " in
  *" login "*)
    [ -n "${AZ_SHIM_LOGIN_WORKS:-}" ] && echo signed-in > "$AZ_SHIM_SESSION"
    exit 0 ;;
esac
if [ -n "${AZ_SHIM_SESSION:-}" ] && [ -f "$AZ_SHIM_SESSION" ]; then
  echo "SHIM.TOKEN.0123456789abcdefghijklmnopqrstuvwxyz"; exit 0
fi
echo "ERROR: Please run 'az login' to setup account." >&2
exit 1
AZSH
chmod +x "$WORK/bin/az" 2>/dev/null || true

# ------------------------------------------------------- the fake repo + stubs
FAKE="$WORK/fakerepo"
mkdir -p "$FAKE/tools" "$FAKE/ing-qa-recorder/mvp"
# Stands in for the tool "Aus ADO aktualisieren" runs. Records the environment it was
# handed and answers the way a successful refresh does. It cannot reach ADO: there is no
# network code in this file. Its existence is also what AdoCache.repoRoot() looks for.
cat > "$FAKE/tools/ado-testcases.mjs" <<'TESTCASES'
import { appendFileSync } from 'node:fs';
appendFileSync(process.env.REFRESH_STUB_LOG,
  'ADO_NONINTERACTIVE=' + (process.env.ADO_NONINTERACTIVE ?? '(nicht gesetzt)') + '\n');
console.log('ado-testcases: 0 unique test case(s) from plan 1234567 -> Stub');
TESTCASES
cat > "$FAKE/tools/parse-report.mjs" <<'PARSE'
// Stub: one test case whose name carries the ADO id. PARSE_STATUS decides whether the
// run passed, so the "a failed run is never marked Bestanden" path can be exercised.
console.log(JSON.stringify({ testCases: [
  { name: 'Beispielanwendung:3951650 - Probe', status: process.env.PARSE_STATUS || 'PASS' },
] }));
PARSE
cat > "$FAKE/ing-qa-recorder/mvp/ado-upload.mjs" <<'UPLOAD'
// Stub for the uploader. --state answers the on/off contract; a real invocation
// records the environment it was handed and reports the OK line verbatim in the
// hook's shape. It cannot reach ADO: there is no network code in this file.
import { appendFileSync } from 'node:fs';
const args = process.argv.slice(2);
if (args.includes('--state')) {
  console.log('ADO-UPLOAD AN (Stub; Abschalten mit ING_ADO_UPLOAD=0)');
  process.exit(0);
}
appendFileSync(process.env.UPLOAD_STUB_LOG,
  'ADO_NONINTERACTIVE=' + (process.env.ADO_NONINTERACTIVE ?? '(nicht gesetzt)')
  + ' args=' + args.join(' ') + '\n');
// The real tool refuses anything but "passed" — ado-automark marks Bestanden and only
// Bestanden — and says so in the hook's shape. The stub keeps that contract, so the
// argument the Java side chose decides what the tester is shown.
const i = args.indexOf('--outcome');
if (i >= 0 && args[i + 1] !== 'passed') {
  console.log('ADO-UPLOAD UEBERSPRUNGEN Ergebnis "' + args[i + 1]
    + '" — ADO-Upload übersprungen; ado-automark markiert ausschließlich Bestanden.');
  process.exit(0);
}
console.log('ADO-UPLOAD OK Stub-Lauf 99999 angelegt, 0 Datei(en) angehängt.');
process.exit(0);
UPLOAD

RUN_DIR="$FAKE/Results/TestDesign/Beispielanwendung/3951650 - Probe/2026-07-28 10-00-00"
mkdir -p "$RUN_DIR"

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) SEP=';' ; WIN=1 ;;
  *) SEP=':' ; WIN=0 ;;
esac
tow() { if [ "$WIN" = 1 ]; then cygpath -w "$1"; else printf '%s' "$1"; fi; }
CP="$(tow "$WORK/classes")$SEP$(tow "$API_JAR")"

# Deliberately NOT headless: AdoSignIn refuses to open a window in a screenless JVM —
# which is what protects every other harness in this repo from putting a real Azure
# DevOps prompt on a developer's laptop — so the scenarios that exercise the window
# have to run in a JVM that has one. The `signin-headless` scenario asserts the guard
# itself and is the only one started with -Djava.awt.headless=true.
# The site configuration the panel reads (AdoConfig). Invented values, exported for every
# scenario: the harness must pass on a machine that has never been set up, and must never be
# able to name a real organisation or tenant.
export ADO_TENANT_ID=00000000-0000-0000-0000-000000000000
export ADO_ORG=harness-org
export ADO_PROJECT=harness-project
export ADO_TEST_PLAN_ID=1

run() {                       # run <scenario> ; env comes from the caller
  echo
  echo "################################################################"
  PATH="$WORK/bin:$PATH" \
  timeout 240 "$JAVA" -Dfile.encoding=UTF-8 -cp "$CP" de.ing.qa.studio.SignInHarness "$@"
}

run_headless() {
  echo
  echo "################################################################"
  PATH="$WORK/bin:$PATH" \
  timeout 240 "$JAVA" -Djava.awt.headless=true -Dfile.encoding=UTF-8 \
    -cp "$CP" de.ing.qa.studio.SignInHarness "$@"
}

# 1. an injected bearer answers, and az is never spawned
AZ_SHIM_LOG="$WORK/tmp/az-bearer.log" AZ_SHIM_SESSION="$WORK/tmp/no-session-1" \
LOCALAPPDATA="$(tow "$WORK/tmp/appdata-empty")" ADO_BEARER=HARNESS-INJECTED-BEARER \
  run probe-bearer
RC_BEARER=$?

# 2. a live token cache answers, and az is never spawned
mkdir -p "$WORK/tmp/appdata-live/IngQaAutopilot"
node -e "require('fs').writeFileSync(process.argv[1], JSON.stringify({access_token:'HARNESS.CACHED.TOKEN.0123456789abcdef', expires_at: Math.floor(Date.now()/1000)+3000}))" \
  "$WORK/tmp/appdata-live/IngQaAutopilot/token.json"
AZ_SHIM_LOG="$WORK/tmp/az-cached.log" AZ_SHIM_SESSION="$WORK/tmp/no-session-2" \
LOCALAPPDATA="$(tow "$WORK/tmp/appdata-live")" \
  run probe-cached
RC_CACHED=$?

# 3. logged out: answered in seconds, and the probe itself opens nothing
AZ_SHIM_LOG="$WORK/tmp/az-out.log" AZ_SHIM_SESSION="$WORK/tmp/no-session-3" \
LOCALAPPDATA="$(tow "$WORK/tmp/appdata-empty")" \
  run probe-signed-out
RC_OUT=$?

# 4. the visible sign-in: the window runs the login and the verdict is re-checked
AZ_SHIM_LOG="$WORK/tmp/az-login.log" AZ_SHIM_SESSION="$(tow "$WORK/tmp/session-4")" \
AZ_SHIM_LOGIN_WORKS=1 LOCALAPPDATA="$(tow "$WORK/tmp/appdata-empty")" \
  run signin-window
RC_LOGIN=$?

# 4b. no screen, no window — the guard that keeps every other harness safe
AZ_SHIM_LOG="$WORK/tmp/az-headless.log" AZ_SHIM_SESSION="$WORK/tmp/no-session-4b" \
AZ_SHIM_LOGIN_WORKS=1 LOCALAPPDATA="$(tow "$WORK/tmp/appdata-empty")" \
  run_headless signin-headless
RC_HEADLESS=$?

# 5. the whole Studio path, logged out and the login coming to nothing
AZ_SHIM_LOG="$WORK/tmp/az-upload-out.log" AZ_SHIM_SESSION="$WORK/tmp/no-session-5" \
LOCALAPPDATA="$(tow "$WORK/tmp/appdata-empty")" \
ING_QA_REPO="$(tow "$FAKE")" SIGNIN_RUN_DIR="$(tow "$RUN_DIR")" \
UPLOAD_STUB_LOG="$(tow "$WORK/tmp/stub-signin.log")" \
ING_ADO_UPLOAD_LOGS="$(tow "$WORK/tmp/logs")" \
  run upload-signin-required
RC_UPLOAD_OUT=$?

# 6. the ordinary path with a live token: nobody is asked anything
AZ_SHIM_LOG="$WORK/tmp/az-upload-ok.log" AZ_SHIM_SESSION="$WORK/tmp/no-session-6" \
LOCALAPPDATA="$(tow "$WORK/tmp/appdata-live")" \
ING_QA_REPO="$(tow "$FAKE")" SIGNIN_RUN_DIR="$(tow "$RUN_DIR")" \
UPLOAD_STUB_LOG="$(tow "$WORK/tmp/stub-ok.log")" \
ING_ADO_UPLOAD_LOGS="$(tow "$WORK/tmp/logs")" \
  run upload-token-ok
RC_UPLOAD_OK=$?

# 6b. a run that did NOT pass reaches the uploader as such, and two uploads in the same
#     second leave two logs. Signed in (live cache), so nothing is asked of anybody.
mkdir -p "$WORK/tmp/logs-failed"
AZ_SHIM_LOG="$WORK/tmp/az-upload-failed.log" AZ_SHIM_SESSION="$WORK/tmp/no-session-6b" \
LOCALAPPDATA="$(tow "$WORK/tmp/appdata-live")" PARSE_STATUS=FAIL \
ING_QA_REPO="$(tow "$FAKE")" SIGNIN_RUN_DIR="$(tow "$RUN_DIR")" \
UPLOAD_STUB_LOG="$(tow "$WORK/tmp/stub-failed.log")" \
ING_ADO_UPLOAD_LOGS="$(tow "$WORK/tmp/logs-failed")" \
  run upload-outcome-failed
RC_FAILED=$?

# 6c/6d. "Aus ADO aktualisieren" — the same defect, the other button.
AZ_SHIM_LOG="$WORK/tmp/az-refresh-out.log" AZ_SHIM_SESSION="$WORK/tmp/no-session-6c" \
LOCALAPPDATA="$(tow "$WORK/tmp/appdata-empty")" ING_QA_REPO="$(tow "$FAKE")" \
ING_ADO_CACHE="$(tow "$WORK/tmp/refresh-cache.json")" \
REFRESH_STUB_LOG="$(tow "$WORK/tmp/refresh-out.log")" \
  run refresh-signin-required
RC_REFRESH_OUT=$?

AZ_SHIM_LOG="$WORK/tmp/az-refresh-ok.log" AZ_SHIM_SESSION="$WORK/tmp/no-session-6d" \
LOCALAPPDATA="$(tow "$WORK/tmp/appdata-live")" ING_QA_REPO="$(tow "$FAKE")" \
ING_ADO_CACHE="$(tow "$WORK/tmp/refresh-cache.json")" \
REFRESH_STUB_LOG="$(tow "$WORK/tmp/refresh-ok.log")" \
  run refresh-token-ok
RC_REFRESH_OK=$?

# 7. the OTHER marker, for real: `Aus ADO aktualisieren` runs tools/ado-testcases.mjs,
#    which imports the same token function. With stdin closed — which is what Studio's
#    pipes look like — it must refuse the login and say so in seconds, not sit out five
#    minutes. The REAL tool runs here; it never obtains a token, so it never reaches ADO.
echo
echo "################################################################"
echo "== ado-testcases (das echte Werkzeug, ohne Konsole) =="
TC_LOG="$WORK/tmp/az-testcases.log"
TC_OUT="$WORK/tmp/ado-testcases.out"
START=$(date +%s)
PATH="$WORK/bin:$PATH" AZ_SHIM_LOG="$TC_LOG" AZ_SHIM_SESSION="$WORK/tmp/no-session-7" \
LOCALAPPDATA="$(tow "$WORK/tmp/appdata-empty")" \
  timeout 120 node "$REPO/tools/ado-testcases.mjs" --cache "$WORK/tmp/unused-cache.json" \
  > "$TC_OUT" 2>&1 < /dev/null
TC_RC=$?
ELAPSED=$(( $(date +%s) - START ))
RC_TC=0
# "1" passes and EVERYTHING ELSE fails — not "0 fails and everything else passes". The
# arguments below are command substitutions, and a command substitution that goes wrong
# yields the empty string. Under the old polarity that empty string was a pass: the error
# path led to the passing answer, which is the exact shape this suite keeps removing from
# other people's code.
check_tc() { if [ "$2" = "1" ]; then echo "  ok   $1"; else echo "  FAIL $1 [$2]"; RC_TC=1; fi; }
check_tc "it fails instead of hanging (exit $TC_RC)" "$([ "$TC_RC" != 0 ] && [ "$TC_RC" != 124 ] && echo 1 || echo 0)"
check_tc "it answers in seconds, not minutes ($ELAPSED s)" "$([ "$ELAPSED" -lt 60 ] && echo 1 || echo 0)"
check_tc "the last line tells the tester what to do" \
  "$(tail -n 1 "$TC_OUT" | grep -qi 'Anmeldung bei Azure DevOps noetig' && echo 1 || echo 0)"
check_tc "no invisible az login was opened" \
  "$(grep -q ' login' "$TC_LOG" 2>/dev/null && echo 0 || echo 1)"
check_tc "a token WAS attempted (so the refusal is real, not a skipped step)" \
  "$(grep -q 'get-access-token' "$TC_LOG" 2>/dev/null && echo 1 || echo 0)"
echo "  --- letzte Zeilen ---"; tail -n 3 "$TC_OUT" | sed 's/^/  /'

echo
echo "################################################################"
echo "bearer=$RC_BEARER cached=$RC_CACHED signedout=$RC_OUT window=$RC_LOGIN headless=$RC_HEADLESS"
echo "upload-signin=$RC_UPLOAD_OUT upload-ok=$RC_UPLOAD_OK outcome-failed=$RC_FAILED"
echo "refresh-signin=$RC_REFRESH_OUT refresh-ok=$RC_REFRESH_OK ado-testcases=$RC_TC"
if [ $RC_HEADLESS -eq 0 ] && [ $RC_BEARER -eq 0 ] && [ $RC_CACHED -eq 0 ] && [ $RC_OUT -eq 0 ] && [ $RC_LOGIN -eq 0 ] \
  && [ $RC_UPLOAD_OUT -eq 0 ] && [ $RC_UPLOAD_OK -eq 0 ] && [ $RC_FAILED -eq 0 ] \
  && [ $RC_REFRESH_OUT -eq 0 ] && [ $RC_REFRESH_OK -eq 0 ] && [ $RC_TC -eq 0 ]; then
  echo "RESULT: GREEN — every scenario was put and answered."
  exit 0
fi
echo "RESULT: RED — at least one scenario failed; see the codes above."
exit 1
