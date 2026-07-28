#!/usr/bin/env bash
# The PII boundary: a customer profile reaches a test case, and the account number does not.
#
#   bash ingenious-plugin/harness/run-profile-harness.sh [install-root]
#
# Run from anywhere. Needs JAVA_HOME (or java/javac on PATH) and an INGenious install to
# borrow Datalib, the CSV test-data provider and ingenious-api from. Headless; writes
# nothing outside a temp directory and the work directory below.
#
# This drives ProfileHarness.java, which proves issue #126 end to end against a REAL
# INGenious project on disk — the engine's own Project, Studio's own ProjectTestData, the
# real CSV provider. Nothing is mocked. Its fifteen checks answer, in order:
#
#   * the KONTONUMMER column is dropped, and no value in the profile is the account number
#   * blank columns are left out and the seven settings columns are kept
#   * the profile is written, the sheet exists, and NO ACCOUNT NUMBER IS IN THE FILE
#   * the settings survive closing and reopening the project the way Studio reopens it
#   * a missing Studio handle is refused rather than shrugged off
#
# WHY THIS SCRIPT EXISTS
# ProfileHarness.java sat in this directory invoked by NOTHING — no runner, no pipeline —
# from the day it was written. Fifteen checks, including the one that says no account number
# reaches the project, had never executed. A check that never runs is a check that cannot
# fail, with better camouflage: it still looks like coverage in a file listing. It is armed
# now, and it is armed for real — with CustomerProfile's account-number guard neutralised,
# four of these checks fail and the account number lands in the CSV on disk.
#
# Exit 0 = the boundary holds.  1 = it does not, and it says which check.  2 = it could not
# be asked here, and it says what is missing. A skip is never reported as a pass.
set -u

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"

JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
JAR="${JAVA_HOME:+$JAVA_HOME/bin/}jar"
command -v "$JAVA" >/dev/null 2>&1 || { echo "!! no java — set JAVA_HOME or put java on PATH" >&2; exit 2; }
command -v "$JAVAC" >/dev/null 2>&1 || { echo "!! no javac — a JDK is required, not a JRE" >&2; exit 2; }

win() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else echo "$1"; fi; }

# ---------------------------------------------------------------------------------------
# Which install — decided by CAPABILITY, never by position in a glob.
#
# This machine carries several installs under the same file names, and they are not the same
# core. ingenious-playwright-3.0.0-preview ships an ingenious-api-3.0.jar of 27 classes with
# no ProjectTestDataApi in it at all; the fork core ships 31 and has it. A runner that took
# the FIRST glob match has already produced a false verdict in this repo. So every candidate
# is probed for the class this harness actually needs, every candidate's verdict is printed,
# and the first one that ANSWERS is used. If none answer, that is an exit 2 naming them all —
# not a green run against a core that could never have proved the point.
# ---------------------------------------------------------------------------------------
NEEDED_CLASS="com/ing/ingenious/api/contract/data/ProjectTestDataApi.class"
API_IN_INSTALL="lib/ingenious-api-3.0.jar"

capable() { # $1 = install root; 0 when it can answer this harness's question
  [ -f "$1/$API_IN_INSTALL" ] || return 1
  [ -f "$1/lib/ingenious-datalib-3.0.0.jar" ] || return 1
  [ -f "$1/lib/ingenious-testdata-csv-3.0.0.jar" ] || return 1
  MSYS_NO_PATHCONV=1 "$JAR" tf "$(win "$1/$API_IN_INSTALL")" 2>/dev/null \
    | tr -d '\r' | grep -qx "$NEEDED_CLASS"
}

why_not() { # the specific missing piece, so the message is actionable
  [ -f "$1/$API_IN_INSTALL" ] || { echo "no $API_IN_INSTALL"; return; }
  [ -f "$1/lib/ingenious-datalib-3.0.0.jar" ] || { echo "no ingenious-datalib-3.0.0.jar"; return; }
  [ -f "$1/lib/ingenious-testdata-csv-3.0.0.jar" ] || { echo "no ingenious-testdata-csv-3.0.0.jar (a project cannot be opened without the CSV provider)"; return; }
  echo "api jar has no ProjectTestDataApi — this is the pre-fork core, which cannot write a profile at all"
}

candidates() {
  [ $# -gt 0 ] && [ -n "${1:-}" ] && echo "$1"
  [ -n "${ING_INGENIOUS_HOME:-}" ] && echo "$ING_INGENIOUS_HOME"
  echo "$REPO/INGenious/Dist/release"
  ls -d "$HOME"/ingenious/ingenious-playwright-* 2>/dev/null
}

# An install asked for BY NAME is honoured or refused — never quietly swapped for another
# that happens to work. Being handed a verdict about a different core than the one you named
# is the same failure as the glob that picked the first match: the answer is true of
# something, just not of the thing you asked about. Auto-discovery may choose among
# candidates; an explicit request may not be redirected.
REQUESTED="${1:-${ING_INGENIOUS_HOME:-}}"
if [ -n "$REQUESTED" ] && ! capable "$REQUESTED"; then
  echo "RESULT: UNGEPRUEFT — the install you named cannot answer this harness's question." >&2
  echo "        $REQUESTED" >&2
  echo "        $(why_not "$REQUESTED")" >&2
  echo "        Nothing was proved. Name a fork core, or omit the argument to auto-discover." >&2
  exit 2
fi

ROOT=""
echo "Studio-Installationen, gegen die geprueft werden koennte:"
FOUND_ANY=0
while read -r candidate; do
  [ -n "$candidate" ] || continue
  [ -d "$candidate" ] || continue
  FOUND_ANY=1
  if capable "$candidate"; then
    if [ -z "$ROOT" ]; then ROOT="$candidate"; MARK="  <== benutzt"; else MARK="  (auch geeignet)"; fi
    echo "  [ja  ] $candidate$MARK"
  else
    echo "  [nein] $candidate — $(why_not "$candidate")"
  fi
done <<CANDIDATES
$(candidates "${1:-}" | awk 'NF && !seen[$0]++')
CANDIDATES
[ "$FOUND_ANY" -eq 1 ] || echo "  (keine)"

if [ -z "$ROOT" ]; then
  echo
  echo "RESULT: UNGEPRUEFT — no install here carries a core that can write a customer profile." >&2
  echo "        Pass one as the first argument or set ING_INGENIOUS_HOME." >&2
  exit 2
fi
# Said out loud next to the verdict: a verdict that does not name the install it was reached
# against is not reproducible.
echo "GEPRUEFT GEGEN: $ROOT"
[ -f "$ROOT/INSTALL-VERSION.txt" ] && sed -n '1,4p' "$ROOT/INSTALL-VERSION.txt" | tr -d '\r' | sed 's/^/                /'

# ---------------------------------------------------------------------------------------
# Which source — the working tree by default, because that is what you are changing.
# ING_HARNESS_FROM_HEAD=1 builds from committed HEAD instead, which is what you want while
# another lane is mid-edit in the same tree: a harness that fails because a colleague's
# half-finished file is on disk teaches nobody anything.
# ---------------------------------------------------------------------------------------
WORK="$REPO/ingenious-plugin/target/harness-profile"
rm -rf "$WORK"; mkdir -p "$WORK/classes"

if [ "${ING_HARNESS_FROM_HEAD:-0}" = "1" ]; then
  SRC_TREE="$WORK/head"
  mkdir -p "$SRC_TREE"
  ( cd "$REPO" && git archive HEAD ingenious-plugin ) | tar -x -C "$SRC_TREE" \
    || { echo "!! could not export ingenious-plugin from HEAD" >&2; exit 2; }
  echo "QUELLE: committed HEAD ($(cd "$REPO" && git rev-parse --short HEAD))"
else
  SRC_TREE="$REPO"
  echo "QUELLE: working tree (ING_HARNESS_FROM_HEAD=1 fuer committed HEAD)"
fi
PLUGIN_SRC="$SRC_TREE/ingenious-plugin/src/main/java/de/ing/qa"
HARNESS_SRC="$SRC_TREE/ingenious-plugin/harness/ProfileHarness.java"

WORK_W="$(win "$WORK")"; CLASSES_W="$(win "$WORK/classes")"
# The install's own lib/ is the whole classpath: Datalib, the CSV provider and ingenious-api
# all live there. Taking the api jar from ~/.m2 instead would let the harness compile against
# one core and run against another.
STUDIO_CP="$(win "$ROOT")\\lib\\*"

# Windows paths for both -cp AND the source arguments: with MSYS2_ARG_CONV_EXCL='*' set for
# the classpath glob, MSYS stops rewriting every other argument too, and javac then reads a
# "/c/..." source path as the literal "\c\...", which does not exist. That failure prints
# "file not found" and — because an env-prefixed command does not trip `set -e` here — would
# sail straight past a naive `set -e` into a run with no classes. Hence explicit || exit.
SRCS=""
for f in "$PLUGIN_SRC"/ado/*.java "$PLUGIN_SRC"/studio/*.java; do
  [ -f "$f" ] && SRCS="$SRCS $(win "$f")"
done
[ -n "$SRCS" ] || { echo "!! no plugin sources under $PLUGIN_SRC" >&2; exit 2; }

MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' \
  "$JAVAC" -encoding UTF-8 -cp "$STUDIO_CP" -d "$CLASSES_W" $SRCS \
  || { echo "!! the plugin does not compile against $ROOT" >&2; exit 2; }

MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' \
  "$JAVAC" -encoding UTF-8 -cp "$CLASSES_W;$STUDIO_CP" -d "$WORK_W" "$(win "$HARNESS_SRC")" \
  || { echo "!! ProfileHarness does not compile against $ROOT" >&2; exit 2; }

echo
MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' \
  "$JAVA" -Djava.awt.headless=true -Dfile.encoding=UTF-8 \
  -cp "$WORK_W;$CLASSES_W;$STUDIO_CP" ProfileHarness
RC=$?

echo
echo "################################################################"
echo "GEPRUEFT GEGEN: $ROOT"
if [ $RC -eq 0 ]; then
  echo "RESULT: GREEN — the profile is written and no account number went with it."
else
  echo "RESULT: RED — see the FAIL lines above. Exit $RC."
fi
exit $RC
