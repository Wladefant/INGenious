#!/usr/bin/env bash
# What the panel says when the tools it shells out to are stale — and what it says when they
# are not, which is the half that decides whether anybody still reads the other one.
#
#   bash ingenious-plugin/harness/run-repo-check-harness.sh
#
# Exit 0 GREEN · 1 RED · 2 the harness could not be built or its preconditions are missing
# · 4 UNGEPRUEFT (it ran, nothing failed, and a question could not be put here — e.g. no git).
#
# WHY THIS EXISTS
#   On 2026-07-28 a machine's checkout of this repository was a month old and five of the Node
#   tools the panels start as child processes did not exist on it. The plugin beside them had
#   been rebuilt many times; nothing said the two had drifted apart. The only symptom was a
#   greyed button whose own honest sentence read as "this was never set up here" rather than
#   "your checkout is a month behind". This harness is the lock on the fix.
#
# WHAT IS REAL HERE
#   * REAL git repositories. Each fixture is `git init` plus a real commit, and the "up to
#     date" scenario names a commit the fixture genuinely contains while the stale one names a
#     40-hex id it genuinely does not. Nothing about git's answer is stubbed.
#   * REAL copies of the five tools — empty files, but at the paths the panels resolve, because
#     the check asks whether the file is there and not what is in it.
#   * The REAL GuidedFlowPanel, rendered: packed, laid out, captured with printAll, and asked
#     whether the line has height rather than only whether it has text.
#
# ONE JVM PER SCENARIO, and that is not a preference: ING_QA_REPO is read with System.getenv,
# which cannot be written to from inside a JVM. The build stamp is a system property precisely
# so a test CAN flip it (-Ding.qa.plugin.commit).
#
# NOT headless: the panel is packed into a real frame. The frame is never shown.
#
# THE HARNESS BUILDS ITS OWN COPY of the plugin, unless run-all.sh has already exported one —
# ingenious-plugin/target/classes is shared and several harnesses recompile it underneath each
# other, which has produced a stale-binary false verdict more than once.
set -u

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
PLUGIN="$REPO/ingenious-plugin"
WORK="$PLUGIN/target/harness-repo-check"
SHOTS="$WORK/shots"

JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
JAR="${JAVA_HOME:+$JAVA_HOME/bin/}jar"

win() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else echo "$1"; fi; }
# Windows javac wants ';' between classpath entries and everything else wants ':'.
CPSEP=':'; command -v cygpath >/dev/null 2>&1 && CPSEP=';'

command -v git >/dev/null 2>&1 || { echo "no git on PATH — this harness is about git"; exit 2; }

rm -rf "$WORK"; mkdir -p "$SHOTS" "$WORK/classes"

# ---------------------------------------------------------------------------- the contract
API_JAR="$HOME/.m2/repository/com/ing/ingenious-api/3.0/ingenious-api-3.0.jar"
API_SRC="$REPO/INGenious/ingenious-api/src/main/java/com/ing/ingenious/api/contract/ui"
API_CP="${ING_PLUGIN_API_CP:-}"
if [ -z "$API_CP" ]; then
  if [ -f "$API_JAR" ] && MSYS_NO_PATHCONV=1 "$JAR" tf "$(win "$API_JAR")" 2>/dev/null \
      | tr -d '\r' | grep -q "contract/ui/StudioPanelApi.class"; then
    API_CP="$API_JAR"
  elif [ -d "$API_SRC" ]; then
    mkdir -p "${ING_PLUGIN_API_BUILD_DIR:-$WORK/api-classes}"
    APIOUT="${ING_PLUGIN_API_BUILD_DIR:-$WORK/api-classes}"
    ASRCS=""; for f in "$API_SRC"/*.java; do ASRCS="$ASRCS $(win "$f")"; done
    MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' "$JAVAC" -encoding UTF-8 \
      -d "$(win "$APIOUT")" $ASRCS || exit 2
    API_CP="$APIOUT"
  else
    echo "no ingenious-api jar and no ingenious-api source — cannot compile the panel"; exit 2
  fi
fi

# ---------------------------------------------------------------------------- the plugin
CLASSES="${ING_PLUGIN_CP:-}"
if [ -z "$CLASSES" ]; then
  CLASSES="$WORK/plugin-classes"; mkdir -p "$CLASSES"
  SRCS=""; for f in $(find "$PLUGIN/src/main/java" -name '*.java'); do SRCS="$SRCS $(win "$f")"; done
  MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' "$JAVAC" -encoding UTF-8 \
    -cp "$(win "$API_CP")" -d "$(win "$CLASSES")" $SRCS || exit 2
  cp -r "$PLUGIN/src/main/resources/." "$CLASSES/" 2>/dev/null || true
fi

MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' "$JAVAC" -encoding UTF-8 \
  -cp "$(win "$CLASSES")$CPSEP$(win "$API_CP")" -d "$(win "$WORK/classes")" \
  "$(win "$HERE/RepoCheckHarness.java")" || exit 2

# ---------------------------------------------------------------------------- the fixtures
#
# A checkout is recognised by shape — a `tools` directory and an `ingenious-plugin` directory —
# so the fixtures carry both. The five tool files are empty on purpose: the product asks
# whether the file EXISTS, and a fixture that filled them would be testing something else.
TOOLS="tools/selector-uniqueness.mjs tools/handoff-pack.mjs tools/ado-testcases.mjs \
tools/parse-report.mjs ing-qa-recorder/mvp/ado-upload.mjs"

mk_checkout() { # dir
  local dir="$1"
  mkdir -p "$dir/ingenious-plugin"
  for t in $TOOLS; do mkdir -p "$dir/$(dirname "$t")"; : > "$dir/$t"; done
  git -C "$dir" init -q .
  git -C "$dir" add -A >/dev/null 2>&1
  git -C "$dir" -c user.email=harness@example.invalid -c user.name=harness \
      commit -q -m "fixture checkout" >/dev/null 2>&1
}

GESUND="$WORK/checkout-gesund"
LUECKE="$WORK/checkout-luecke"
mk_checkout "$GESUND"
mk_checkout "$LUECKE"
# One tool removed, and only one: the incident was five, but a check that only fires on five is
# a check that misses the sixth deployment.
rm -f "$LUECKE/tools/selector-uniqueness.mjs"

# A device that has no checkout at all — the Fachbereich case. It has to sit OUTSIDE this
# repository: with ING_QA_REPO unset the product walks up from the working directory looking
# for a checkout, and anything under ingenious-plugin/target would find the real one and prove
# the opposite of what this scenario is for.
DRAUSSEN="$(mktemp -d 2>/dev/null || echo "${TMPDIR:-/tmp}/ing-repo-check-$$")"
mkdir -p "$DRAUSSEN"

HEAD_SHA="$(git -C "$GESUND" rev-parse HEAD | tr -d '\r')"
LUECKE_SHA="$(git -C "$LUECKE" rev-parse HEAD | tr -d '\r')"
# A 40-hex id no repository on earth is likely to hold. Not random: a fixed value makes a
# failure reproducible, and `git cat-file -e` on it was measured to exit 1 (absent) rather than
# 128 (cannot ask), which is the distinction the product turns on.
ABSENT_SHA="0123456789abcdef0123456789abcdef01234567"
echo "fixture HEAD   : $HEAD_SHA"
echo "absent commit  : $ABSENT_SHA"
git -C "$GESUND" cat-file -e "$ABSENT_SHA" 2>/dev/null
[ $? -eq 1 ] || { echo "PRECONDITION FAILED: git does not answer 1 for an absent object here."; exit 2; }

FIXTURE="$PLUGIN/sample/ado-testcases-beispiel.json"

# ---------------------------------------------------------------------------- the scenarios
#
# Each scenario is one JVM with one machine's worth of environment. The three arguments are the
# scenario name, what ING_QA_REPO says (empty = unset, the Fachbereich device), and what the
# build stamped into the JAR (empty = nothing, which is what a plain `mvn package` leaves).
CP="$(win "$WORK/classes")$CPSEP$(win "$CLASSES")$CPSEP$(win "$API_CP")"

run() { # scenario | repo-or-empty | stamp-or-empty
  local scenario="$1" repo="$2" stamp="${3:-}"
  echo
  echo "----------------------------------------------------------------"
  local args=(-Djava.awt.headless=false -Dfile.encoding=UTF-8)
  [ -n "$stamp" ] && args+=("-Ding.qa.plugin.commit=$stamp")
  local envs=(ING_ADO_CACHE="$FIXTURE" \
              ING_TESTCASE_SELECTION="$WORK/selected-testcase.json" \
              ING_TESTDATA_CSV="$PLUGIN/sample/testdaten-beispiel.csv" \
              ING_TESTDATA_LABELS="$PLUGIN/sample/labels.properties")
  if [ -n "$repo" ]; then
    envs+=(ING_QA_REPO="$repo")
  else
    # Unset, not empty — and the working directory moved outside this repository, so the
    # product's own walk-up cannot find a checkout and answer the wrong question.
    envs=(-u ING_QA_REPO "${envs[@]}")
    args+=("-Duser.dir=$(win "$DRAUSSEN")")
  fi
  env "${envs[@]}" "$JAVA" "${args[@]}" -cp "$CP" \
    de.ing.qa.panel.RepoCheckHarness "$scenario" "$SHOTS"
}

# The source-level guard: does this check watch exactly the tools the panels really start?
# No panel, no git, no display — it is a fact about the source and runs anywhere.
env ING_QA_REPO="$GESUND" "$JAVA" -Dfile.encoding=UTF-8 -cp "$CP" \
  de.ing.qa.panel.RepoCheckHarness abgleich "$SHOTS"
RC_DRIFT=$?

# A healthy machine: every tool there, and the plugin built from a commit it really has.
run gesund             "$GESUND" "$HEAD_SHA";    RC_HEALTHY=$?
# The incident, minimised to one file.
run werkzeug-fehlt     "$LUECKE" "$LUECKE_SHA";  RC_MISSING=$?
# A checkout that has never seen the commit the plugin was built from.
run veraltet           "$GESUND" "$ABSENT_SHA";  RC_BEHIND=$?
# A Fachbereich device: an install and a launcher, and no checkout anywhere above it.
run nicht-eingerichtet ""        "";             RC_NOREPO=$?
# A build that refused to make a claim about itself — a dirty tree, or an unpublished commit.
run unbeurteilbar      "$GESUND" "dirty";        RC_UNKNOWN=$?
# …and the commonest state of all: a JAR with no stamp at all. Must also be silent.
run unbeurteilbar      "$GESUND" "";             RC_NOSTAMP=$?

echo
echo "################################################################"
echo "abgleich=$RC_DRIFT gesund=$RC_HEALTHY werkzeug-fehlt=$RC_MISSING veraltet=$RC_BEHIND nicht-eingerichtet=$RC_NOREPO unbeurteilbar=$RC_UNKNOWN ohne-stempel=$RC_NOSTAMP  (0 = green)"
echo "screenshots: $SHOTS"
rm -rf "$DRAUSSEN"

UNPROVED=0
for pair in "veraltet:$RC_BEHIND"; do
  if [ "${pair#*:}" -eq 4 ]; then
    echo "!! UNGEPRUEFT: ${pair%%:*} wurde nicht abschliessend geprueft — der Grund steht oben."
    UNPROVED=1
  fi
done

if [ $RC_DRIFT -eq 0 ] && [ $RC_HEALTHY -eq 0 ] && [ $RC_MISSING -eq 0 ] \
  && [ $RC_BEHIND -eq 0 ] && [ $RC_NOREPO -eq 0 ] && [ $RC_UNKNOWN -eq 0 ] \
  && [ $RC_NOSTAMP -eq 0 ]; then
  echo "RESULT: GREEN — it warns on the three broken machines and on none of the three it cannot judge."
  exit 0
fi
if [ $UNPROVED -eq 1 ] && [ $RC_DRIFT -eq 0 ] && [ $RC_HEALTHY -eq 0 ] && [ $RC_MISSING -eq 0 ] \
  && [ $RC_NOREPO -eq 0 ] && [ $RC_UNKNOWN -eq 0 ] && [ $RC_NOSTAMP -eq 0 ]; then
  echo "RESULT: UNGEPRUEFT — nothing failed, but the stale-checkout case was never put."
  exit 4
fi
echo "RESULT: RED — see the codes above."
exit 1
