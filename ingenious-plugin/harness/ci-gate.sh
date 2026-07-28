#!/usr/bin/env bash
# The verdict a pipeline is allowed to draw from run-all.sh.
#
#   bash ingenious-plugin/harness/ci-gate.sh
#
# run-all.sh answers "what did THIS MACHINE prove?" and exits 4 whenever something was not
# put. That is the right answer for a laptop and the wrong shape for a build server, which
# has only two conclusions to offer: green or red. This script is where the second question
# is asked — "is what this machine failed to prove exactly what a build server can never
# prove?" — and it is deliberately a separate file, because that question is about the
# RUNNER, not about the suite. run-all.sh must stay free to be honest about any machine.
#
# WHAT THIS IS NOT
#
# It is not "exit 4 counts as success". A skip is never accepted for being a skip. Each
# entry below names a harness AND the reason run-all.sh must give for skipping it; a
# harness that skips for any other reason — the plugin failed to compile, node vanished,
# the api jar was never installed — is RED, even though it is on this list. That is the
# case the list exists to catch: the difference between "CI cannot open a Studio window"
# and "CI stopped being able to build the thing" is invisible in a skip count and obvious
# in a skip reason.
#
# The list is also EXHAUSTIVE by construction. A new harness that cannot run here turns
# the build red until somebody writes down why. Silence is not available.
#
# And nothing is folded away: every skip is emitted as a GitHub warning annotation and
# written into the job summary, so a green run still SAYS on its own page what it did not
# prove. Green here means "everything CI can host ran and passed" — never "everything is
# proved". The three studio drivers below are UNPROVED by every CI run, permanently, and the
# summary says so every time. Four others used to be on that list on the strength of an
# unmeasured cost; they were measured on 2026-07-28 and they are proved by every run now.
set -u

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ---------------------------------------------------------------------------------------
# WHAT A BUILD SERVER CANNOT HOST — id, then a fragment the skip reason must contain.
#
# Keep the fragment SPECIFIC. "no install" would also swallow a broken checkout; the text
# below is the exact sentence run-all.sh's own need_* helpers produce for the one cause we
# are accepting.
# ---------------------------------------------------------------------------------------
allowed_reason() {
  case "$1" in
    # Drive a real INGenious Studio window for minutes and expect a desktop nobody is
    # sharing. Opt-in even on a developer machine (ING_HARNESS_STUDIO=1).
    studio-kette|studio-aufnahme|studio-wachhund)
      echo "opens a real Studio window" ;;
    # ------------------------------------------------------------------------------------
    # WHAT USED TO BE HERE, AND WHY IT IS NOT ANY MORE  (2026-07-28)
    #
    # profil, kette, studio-kontrakt and selektor-pruefung were declared here as well. The
    # first three "need a BUILT INGenious distribution ... whose cost and reliability on a
    # hosted runner have never been measured"; the fourth "commits every run to a browser
    # download ... a cost nobody has weighed". Every word of that was true and none of it
    # was a fact about the cost. It was a refusal to guess, which is right, standing in for
    # a measurement, which is not the same thing and does not expire on its own.
    #
    # Measured in one run, .github/workflows/harness-cost-probe.yml #30330593244:
    #   the submodule build, clean checkout + warm ~/.m2   120s   (220s cold)
    #   npm i playwright@1.58.0 + a Chromium                28s
    # harness.yml does both now and all four run on every build. Removing them from this list
    # is not a formality — it is what turns the workflow's build step into something with a
    # lock on it. Delete that step and profil, kette and studio-kontrakt skip with reasons
    # nothing here declares, which is RED by the rule above; delete the playwright step and
    # selektor-pruefung does the same. The way to stop CI proving the PII boundary is now to
    # write down, in this file, that you are doing it.
    #
    # One of them did not pass first time, which is the whole argument for running a check on
    # a machine that is not yours: kette failed, and for none of the reasons it had been
    # declared for. Links 1-3 passed against the freshly built core and link 4 lost a fifty-
    # millisecond race with the run watcher's history sweep — a coin flip a laptop had always
    # won. Fixed in ChainHarness link 4, where the whole count is written out.
    # ------------------------------------------------------------------------------------
    *)
      echo "" ;;
  esac
}

# ---------------------------------------------------------------------------------------
# EVERY HARNESS MUST HAVE SAID SOMETHING — the hole the skip list above does not cover.
#
# allowed_reason() is exhaustive over SKIPS. It is not exhaustive over HARNESSES, and until
# 2026-07-28 nothing was. Delete a `run_one` line from run-all.sh, comment it out, misspell
# its id, or lose it to a bad merge, and that harness neither passes nor skips: it simply
# stops existing. NP drops by one, NF/NU/NS do not move, every declaration still matches, and
# this gate reports GREEN. A check that silently does not run is the exact disease the suite
# was built to cure, and the cure had no lock on its own front door.
#
# So: the roster below, and every id on it has to appear in run-all.sh's output with SOME
# verdict. Removing a harness now turns the build red until somebody removes it from here
# too — the same discipline the skip list applies, applied one level up. An id that appears
# and is NOT on the roster is equally red: a new harness must be declared before it counts.
#
# `plugin-build` is the one legitimate extra row: run-all.sh emits it as its own FAIL when
# the plugin will not compile, so that a syntax error reads as a broken build rather than as
# six absences.
EXPECTED_HARNESSES="profil ado-panels aufnahmeziel ziel-identitaet run-wachhund kette anmeldung auswahl-vertrag \
studio-kontrakt gefuehrter-flow unlesbar abgabe-unlesbar einstiegsadresse anmeldung-anzeige \
testdaten-filter selektor-pruefung herkunft ado-discover ado-mark distill-trace mvp-einheit \
studio-kette studio-aufnahme studio-wachhund"

LOG="${ING_CI_GATE_LOG:-$(mktemp -t harness-XXXXXX.log)}"
SUMMARY="${GITHUB_STEP_SUMMARY:-/dev/null}"

# ING_HARNESS_FROM_HEAD=1: build the plugin from `git archive HEAD` rather than the working
# tree. On a runner the two are identical, so this costs nothing and removes the question.
ING_HARNESS_FROM_HEAD=1 bash "$HERE/run-all.sh" 2>&1 | tee "$LOG"
RC=${PIPESTATUS[0]}

# ---------------------------------------------------------------------------------------
# What the run said about itself. Read from run-all.sh's own output rather than recomputed:
# there is one place that decides what passed, and it is not this file.
# ---------------------------------------------------------------------------------------
counts="$(grep -m1 -E '^# [0-9]+ passed' "$LOG" || true)"
NP="$(printf '%s' "$counts" | sed -n 's/^# \([0-9]*\) passed.*/\1/p')"
NF="$(printf '%s' "$counts" | sed -n 's/.*· \([0-9]*\) failed.*/\1/p')"
NU="$(printf '%s' "$counts" | sed -n 's/.*· \([0-9]*\) unproved.*/\1/p')"
NS="$(printf '%s' "$counts" | sed -n 's/.*· \([0-9]*\) skipped.*/\1/p')"

{
  echo "## Harness suite"
  echo
  echo '```'
  sed -n '/^repo /,/^logs /p' "$LOG"
  echo '```'
  echo
  echo "**$NP passed · $NF failed · $NU unproved · $NS skipped**"
  echo
} >> "$SUMMARY"

VERDICT=0

if [ -z "$counts" ]; then
  echo "::error::run-all.sh printed no summary line — it did not finish. Exit was $RC."
  { echo "### RED"; echo "run-all.sh did not finish (exit $RC)."; } >> "$SUMMARY"
  exit 1
fi

# EVERY number has to have been read, and this file must not be the reason a red run looks
# green. The four sed expressions above each yield the empty string when they do not match,
# and every test below defaults an empty count to 0 — so a change to run-all.sh's summary
# line that still starts "# N passed" would leave NF, NU and NS empty, and this script would
# conclude: nothing failed, nothing unproved, nothing skipped. GREEN. The failing parse leads
# straight to the passing answer, in the one file whose whole job is refusing to do that.
for pair in "passed:$NP" "failed:$NF" "unproved:$NU" "skipped:$NS"; do
  case "${pair#*:}" in
    ''|*[!0-9]*)
      echo "::error::ci-gate.sh could not read the '${pair%%:*}' count out of run-all.sh's"
      echo "::error::summary line, so it cannot say what this run proved. The line was:"
      echo "::error::  $counts"
      { echo "### RED — the gate could not read the result"
        echo
        echo "\`ci-gate.sh\` parses run-all.sh's summary line and got no **${pair%%:*}** count"
        echo "out of it. Refusing to draw a verdict from numbers it does not have."
        echo
        echo '```'; echo "$counts"; echo '```'; } >> "$SUMMARY"
      exit 1 ;;
  esac
done

if [ "${NF:-0}" -gt 0 ]; then
  echo "::error::$NF harness(es) FAILED."
  VERDICT=1
fi

# An UNPROVED harness ran and reported that part of itself was never put — ado-panels does
# this when `badpath` cannot be exercised on the filesystem. On CI that is not acceptable
# quietly: it is the same "we did not test that" that exit 4 exists to keep visible.
if [ "${NU:-0}" -gt 0 ]; then
  echo "::error::$NU harness(es) ran but left part of themselves unproved. CI does not accept UNGEPRUEFT."
  VERDICT=1
fi

# ---------------------------------------------------------------------------------------
# Every skip, against the declaration.
# ---------------------------------------------------------------------------------------
NOT_PROVED=""
UNDECLARED=""
SEEN=0
while IFS= read -r line; do
  [ -n "$line" ] || continue
  SEEN=$((SEEN + 1))
  id="${line%% *}"
  reason="$(printf '%s' "${line#"$id"}" | sed 's/^ *//')"
  expected="$(allowed_reason "$id")"
  if [ -z "$expected" ]; then
    echo "::error::$id was not run, and nothing in ci-gate.sh says CI cannot run it: $reason"
    UNDECLARED="$UNDECLARED  * \`$id\` — $reason"$'\n'
    VERDICT=1
  elif ! printf '%s' "$reason" | grep -qF "$expected"; then
    # Right harness, WRONG reason. This is the stale-build / broken-toolchain case wearing
    # a skip's clothing, and it is exactly what a bare skip count would hide.
    echo "::error::$id skipped for an undeclared reason. Expected \"$expected\", got: $reason"
    UNDECLARED="$UNDECLARED  * \`$id\` — UNEXPECTED REASON: $reason"$'\n'
    VERDICT=1
  else
    echo "::warning::NOT PROVED BY THIS RUN — $id: $reason"
    NOT_PROVED="$NOT_PROVED  * \`$id\` — $reason"$'\n'
  fi
done <<EOF
$(sed -n 's/^  SKIP  //p' "$LOG" | sed 's/[[:space:]]\{2,\}/ /g')
EOF

# The count run-all.sh reported, against the lines this file actually read. They are produced
# by two different mechanisms — a counter in run-all.sh and a `sed` on its output here — and
# nothing but this line makes them agree. If the SKIP line ever changes shape, the sed matches
# nothing, the loop above never runs, and the gate emits no warnings and finds nothing
# undeclared: a run where every harness was skipped would pass, silently, with the exhaustive
# list exhaustively empty.
if [ "$SEEN" -ne "${NS:-0}" ]; then
  echo "::error::run-all.sh reported $NS skip(s) and this gate could read $SEEN of them."
  echo "::error::The declarations below are checked against the lines it CAN read, so a"
  echo "::error::verdict now would be about the wrong set. Fix the SKIP line or this parse."
  { echo "### RED — the gate read $SEEN of $NS skips"
    echo
    echo "Its exhaustive list is only exhaustive over the skips it can parse."; } >> "$SUMMARY"
  VERDICT=1
fi

# ---------------------------------------------------------------------------------------
# The roster, against what the log actually reported.
#
# NOTE THE `tr '\r' '\n'`, and do not remove it. run-all.sh writes a progress line with
# `printf '  ....  %-18s %s\r' … >&2`, and a carriage return does NOT start a new line in a
# file: the PASS/FAIL/UNPR line that follows is glued onto the end of it, so `sed -n
# 's/^  PASS  …'` matches NOTHING. (SKIP lines are the exception — run_one returns before
# printing any progress — which is why the skip parse below has always worked and why this
# trap was invisible.) Translating CR to LF puts every verdict back at column 0.
SAID="$(tr '\r' '\n' < "$LOG" \
  | sed -n 's/^  \(PASS\|FAIL\|UNPR\|SKIP\)  \([^ ]*\).*/\2/p' | sort -u)"

VANISHED=""
for id in $EXPECTED_HARNESSES; do
  printf '%s\n' "$SAID" | grep -qx -- "$id" || VANISHED="$VANISHED  * \`$id\`"$'\n'
done
NEWCOMER=""
for id in $SAID; do
  case " $EXPECTED_HARNESSES plugin-build " in
    *" $id "*) ;;
    *) NEWCOMER="$NEWCOMER  * \`$id\`"$'\n' ;;
  esac
done

if [ -n "$VANISHED" ]; then
  echo "::error::harness(es) on ci-gate.sh's roster reported no verdict at all — they did"
  echo "::error::not pass, did not fail and did not even skip. They are simply not being run."
  printf '%s' "$VANISHED" | sed 's/^/::error::/'
  { echo "### RED — a harness stopped reporting"
    echo
    echo "These are on the roster in \`ci-gate.sh\` and produced no PASS, FAIL, UNPR or SKIP"
    echo "line. A harness that is not run is not a harness; it is a name in a list."
    echo
    printf '%s' "$VANISHED"; echo; } >> "$SUMMARY"
  VERDICT=1
fi

if [ -n "$NEWCOMER" ]; then
  echo "::error::run-all.sh reported harness(es) ci-gate.sh does not know about. Add them to"
  echo "::error::EXPECTED_HARNESSES so their disappearance would be noticed later."
  printf '%s' "$NEWCOMER" | sed 's/^/::error::/'
  { echo "### RED — an undeclared harness"
    echo
    echo "Add these to \`EXPECTED_HARNESSES\` in \`ci-gate.sh\`, so that the day one of them"
    echo "stops running, this gate says so."
    echo
    printf '%s' "$NEWCOMER"; echo; } >> "$SUMMARY"
  VERDICT=1
fi

if [ -n "$UNDECLARED" ]; then
  {
    echo "### RED — a check went missing"
    echo
    echo "These did not run, and this workflow does not say CI is unable to run them."
    echo "Either fix the runner or declare the reason in \`ingenious-plugin/harness/ci-gate.sh\`."
    echo
    printf '%s' "$UNDECLARED"
    echo
  } >> "$SUMMARY"
fi

if [ -n "$NOT_PROVED" ]; then
  {
    echo "### NOT PROVED BY CI"
    echo
    echo "This run is green for what it could run. It is **not** a statement about these:"
    echo
    printf '%s' "$NOT_PROVED"
    echo
    echo "They are proved on a developer machine with a built INGenious install:"
    echo
    echo '```'
    echo 'JAVA_HOME="/c/Program Files/Java/jdk-17" \'
    echo '  ING_HARNESS_STUDIO=1 bash ingenious-plugin/harness/run-all.sh'
    echo '```'
    echo
  } >> "$SUMMARY"
fi

if [ "$VERDICT" -ne 0 ]; then
  echo
  echo "CI GATE: RED"
  { echo "### Result: RED"; } >> "$SUMMARY"
  exit 1
fi

echo
echo "CI GATE: GREEN for what CI can host — $NP passed, $NS not provable here (listed above)."
{ echo "### Result: green for what CI can host — $NP passed, $NS never proved here."; } >> "$SUMMARY"
exit 0
