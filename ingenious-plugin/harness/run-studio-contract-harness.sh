#!/usr/bin/env bash
# The names the panel reflects on, against the jars the Studio actually ships.
#
#   bash ingenious-plugin/harness/run-studio-contract-harness.sh
#
# Exit 0 GREEN · 1 RED (a name the panel needs is gone from the core) · 2 the harness could
# not be built · 4 UNGEPRUEFT — this machine could not read the signatures at all, which is
# a different sentence from "the names are still there" and must never print the same way.
#
# WHY IT IS ITS OWN HARNESS SINCE 2026-07-28
#
# It used to be the last scenario of run-guided-flow-harness.sh, under the id `kontrakt`. That
# welded two different questions to one verdict:
#
#   * thirteen scenarios that need nothing but a desktop, and run anywhere;
#   * one that needs a BUILT INGenious core and an installed Studio's lib/ folder.
#
# The first CI run showed what that costs. A hosted runner has neither, so the contract
# scenario went red and took the whole guided flow down with it — thirteen scenarios that had
# just passed, reported as a failure of the tester flow. Worse, the red said "nineteen classes
# are missing from the core", which is what a rename would look like. Split, each says its own
# true thing: gefuehrter-flow passes on the runner, and this one SKIPS there by name, with the
# reason, declared in ci-gate.sh so the skip is on the job summary instead of inside a count.
#
# WHAT IT GUARDS
#
# ingenious-plugin/harness/studio-double is a hand-written stand-in for four Studio methods.
# Every name in it, and the branch order of record(), is checked here against the real built
# jars — that is what stops the double drifting into a fiction that passes while the real
# Studio has moved on. The guided flow uses the double; this proves the double.
set -u

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
WORK="$REPO/ingenious-plugin/target/harness-kontrakt"

command -v "$JAVA"  >/dev/null 2>&1 || { echo "SKIP — no java (set JAVA_HOME to a JDK 17)"; exit 2; }
command -v "$JAVAC" >/dev/null 2>&1 || { echo "SKIP — no javac; a JRE is not enough"; exit 2; }

rm -rf "$WORK"; mkdir -p "$WORK"
"$JAVAC" -encoding UTF-8 -d "$WORK" "$HERE/StudioContractHarness.java" \
  > "$WORK/build.log" 2>&1 \
  || { echo "the harness did not compile — see $WORK/build.log"; cat "$WORK/build.log"; exit 2; }

# WHICH install is not a detail. Until 2026-07-28 this was
#
#   for candidate in "${INGENIOUS_HOME:-}/lib" "$HOME"/ingenious/*/lib …; do
#     if [ -d "$candidate" ]; then STUDIO_LIB="$candidate"; break; fi
#   done
#
# — the FIRST glob match, i.e. whatever the shell sorts first. On a machine with more than one
# install that is the OLDEST: here ingenious-playwright-3.0.0-preview, whose core still has
# Panel.activate() without ProjectTestDataApi — the exact difference that made the
# customer-profile write impossible and produced a wrong verdict once already. A harness that
# silently resolves to a core nothing else in the workspace uses can pass or fail for reasons
# that have nothing to do with the code under test, and it never said which one it took.
#
# So the choice is explicit, deterministic and printed: ING_STUDIO_LIB names a lib folder
# outright, INGENIOUS_HOME/lib is next, and otherwise the HIGHEST version of the installs
# found — by version sort, not by directory order and not by mtime, so the same machine
# answers the same way tomorrow. Every candidate is listed, chosen or not.
studio_lib_candidates() {
  ls -d "$HOME"/ingenious/*/lib "$HOME"/ingenious*/lib 2>/dev/null | sort -u | sort -V
}

STUDIO_LIB=""
STUDIO_LIB_WHY=""
if [ -n "${ING_STUDIO_LIB:-}" ]; then
  STUDIO_LIB="$ING_STUDIO_LIB"; STUDIO_LIB_WHY="ING_STUDIO_LIB"
elif [ -n "${INGENIOUS_HOME:-}" ] && [ -d "${INGENIOUS_HOME}/lib" ]; then
  STUDIO_LIB="${INGENIOUS_HOME}/lib"; STUDIO_LIB_WHY="INGENIOUS_HOME"
else
  STUDIO_LIB="$(studio_lib_candidates | tail -1)"
  STUDIO_LIB_WHY="hoechste gefundene Version"
fi

echo "Studio-Installationen auf diesem Rechner:"
FOUND_ANY=0
while IFS= read -r candidate; do
  [ -n "$candidate" ] || continue
  FOUND_ANY=1
  COUNT="$(ls "$candidate"/*.jar 2>/dev/null | wc -l | tr -d ' ')"
  if [ "$candidate" = "$STUDIO_LIB" ]; then MARK="  <== benutzt"; else MARK=""; fi
  echo "  $candidate ($COUNT jars)$MARK"
done <<CANDIDATES
$(studio_lib_candidates)
CANDIDATES
[ "$FOUND_ANY" -eq 1 ] || echo "  (keine)"
if [ -n "$STUDIO_LIB" ] && [ -d "$STUDIO_LIB" ]; then
  echo "GEPRUEFT GEGEN: $STUDIO_LIB  ($STUDIO_LIB_WHY, $(ls "$STUDIO_LIB"/*.jar 2>/dev/null | wc -l | tr -d ' ') jars)"
else
  # Not a pass and not a failure: without an install the harness cannot read the signatures
  # that mention a third-party type, and it says so itself with exit 4. Naming the gap here
  # means the gap is in the log next to the verdict rather than inferred from it.
  echo "GEPRUEFT GEGEN: (keine Studio-Installation gefunden — die Signaturen sind hier nicht"
  echo "                lesbar, das Ergebnis ist UNGEPRUEFT; ING_STUDIO_LIB setzen)"
  STUDIO_LIB=""
fi

"$JAVA" -cp "$WORK" StudioContractHarness "$REPO" $STUDIO_LIB
RC=$?
echo "Studio-Bibliothek: ${STUDIO_LIB:-(keine)}"
exit $RC
