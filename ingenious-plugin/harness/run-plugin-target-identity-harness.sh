#!/usr/bin/env bash
# The guard on plugin target identity — re-recording a plugin-named target must land in the
# SAME test case, not in a suffixed copy.
#
#   bash ingenious-plugin/harness/run-plugin-target-identity-harness.sh
#
# Run from anywhere. Needs a JDK (JAVA_HOME, or javac on PATH) and the INGenious submodule
# checked out. It needs NOTHING else: no plugin build, no install, no desktop, no browser.
#
# WHY THIS SCRIPT IS A MATRIX AND NOT A RUN
#
# Seven squares, and one of them has to be RED. PluginTargetIdentityHarness asserts one thing —
# "record twice, land in one test case" — against two transcribed versions of Studio's
# resolution step and two shapes of our plugin branch. Against upstream head with today's
# plugin branch it MUST fail, because that is the breakage (#302) this guard exists to catch.
#
# So the verdict here is not "did the harness pass". It is "did every square come out the way
# it has to". A green `after-302` is the worst outcome this script can produce: it means the
# double has stopped reproducing the upstream behaviour, the red direction has quietly become
# green, and the guard is decoration. That is treated as red, by name, with the reason.
#
#   base                our pin       + today's plugin path   identity holds       expect 0
#   after-302           upstream head + today's plugin path   identity BREAKS      expect 1
#   after-302-repaired  upstream head + the repair            identity holds       expect 0
#   repaired            our pin       + the repair            holds, nothing lost  expect 0
#   dialog              upstream head, dialog path untouched  still uniquifies     expect 0
#   source              the real submodule source, as pinned  contract is carried  expect 0
#   source-after-302    that same check, on a post-#302 file  it calls it broken   expect 0
#
# `source` is the one that costs nothing and catches everything: it reads the checked-out
# INGenious tree, so it goes red on the commit that moves the pin — on CI, without anybody
# remembering to run a thing. The five doubles prove WHAT it is asserting; `source` is where
# the assertion meets the real dependency.
#
# And `source-after-302` is `source` held to the same standard as everything else here: the
# check that is supposed to fire is shown firing, against the pinned source with upstream
# head's helper spliced into it. It was ALSO run against the genuine three-way merge —
# `git merge-file` of our pin, the merge base 15274331 and up/release/3.1.0, which merged
# without conflict — and `source` returned limb A false, limb B false, exit 1. That merge
# needs an upstream ref a CI checkout does not have, so the splice is what runs every time.
#
# WHERE IT COMPILES TO, and why not target/classes: ingenious-plugin/target/classes is shared
# with maven and with four harnesses that rm -rf and recompile it. This script owns its own
# directory and wipes it first, so it can never read a class older than the source it is
# supposed to be proving. That is the stale-Harness.class bug, and it has been paid for.
set -u

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"

JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"

STUDIO_SRC="${ING_STUDIO_SRC:-$REPO/INGenious/IDE/src/main/java/com/ing/ide/main/mainui/components/testdesign/testcase/TestCaseComponent.java}"
if [ ! -f "$STUDIO_SRC" ]; then
  echo "!! no Studio source at $STUDIO_SRC"
  echo "!! the INGenious submodule is not checked out — this harness CANNOT TEST the thing it"
  echo "!! guards, and will not pretend otherwise. git submodule update --init INGenious"
  exit 2
fi
export ING_STUDIO_SRC="$STUDIO_SRC"

WORK="$REPO/ingenious-plugin/target/harness-plugin-target-identity"
rm -rf "$WORK"; mkdir -p "$WORK"

"$JAVAC" -encoding UTF-8 -d "$WORK" "$HERE/PluginTargetIdentityHarness.java" || exit 2

echo "submodule INGenious at $(git -C "$REPO/INGenious" rev-parse --short HEAD 2>/dev/null || echo '?')"

RC_FAILED=0
NOTES=""

square() { # id | expected exit | what it must show
  local id="$1" want="$2" what="$3"
  echo
  echo "################################################################"
  "$JAVA" -Djava.awt.headless=true -Dfile.encoding=UTF-8 -cp "$WORK" \
    PluginTargetIdentityHarness "$id"
  local rc=$?
  if [ "$rc" -eq 2 ]; then
    echo "-> COULD NOT TEST: $id exited 2 (it did not run)"
    NOTES="$NOTES  $id — COULD NOT TEST, exit 2
"
    RC_FAILED=1
    return
  fi
  if [ "$rc" -eq "$want" ]; then
    echo "-> as it must be: $id exited $rc ($what)"
  else
    echo "-> WRONG: $id exited $rc, expected $want ($what)"
    if [ "$want" -eq 1 ]; then
      NOTES="$NOTES  $id — expected RED and got GREEN. The double no longer reproduces
    upstream #302, so this guard is no longer guarding anything. Re-read
    UpstreamCore in PluginTargetIdentityHarness.java against
    git show up/release/3.1.0:IDE/.../TestCaseComponent.java before trusting any
    other square on this page.
"
    else
      NOTES="$NOTES  $id — expected GREEN and got $rc
"
    fi
    RC_FAILED=1
  fi
}

square base               0 "our pin still lands both recordings in one test case"
square after-302          1 "upstream head forks the case — this is the breakage"
square after-302-repaired 0 "the repair restores identity on upstream head"
square repaired           0 "the repair costs nothing at our pin"
square dialog             0 "upstream's own overwrite fix is left intact"
square source             0 "the real pinned source still carries the contract"
square source-after-302   0 "and that check is shown to go red on the pin move"

echo
echo "################################################################"
if [ "$RC_FAILED" -eq 0 ]; then
  echo "GREEN — all seven squares came out the way they have to, including the red one."
  exit 0
fi
echo "RED — the matrix did not come out:"
printf '%s' "$NOTES"
exit 1
