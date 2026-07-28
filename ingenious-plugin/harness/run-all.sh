#!/usr/bin/env bash
# Every harness in this directory, in one command, with an honest summary.
#
#   bash ingenious-plugin/harness/run-all.sh [install-root]
#
# Run from anywhere. It prints, for each harness, one of three verdicts:
#
#   PASS  it ran and it proved its point
#   FAIL  it ran and it did not — or it crashed, or it hung
#   SKIP  it did not run, AND WHY: what is missing and what to do about it
#
# THE EXIT CODE DOES NOT FOLD A SKIP INTO GREEN.
#
#   0   everything ran and everything passed
#   1   at least one harness failed
#   4   nothing failed, but something was not proved — the suite is UNGEPRUEFT
#
# 4 is not a pass. A suite that skipped the account-number boundary because no install was
# found has proved nothing about the account-number boundary, and the number it prints must
# say so. This is not hypothetical: `ok() { [ $1 -eq 0 ] || [ $1 -eq 4 ]; }` once sat three
# lines below a comment in run-guided-flow-harness.sh promising it would never do that, and
# it made the whole hand-off feature — the account-number refusal included — count as green
# on a machine where it had never run.
#
# Preconditions are checked HERE, before each harness starts, so a skip can name the thing
# that is missing. That also makes an exit 2 from a harness whose preconditions were met a
# genuine FAIL (its build broke) rather than another shrug.
#
# WHAT THIS DOES NOT DO
#   * it never starts a real Studio window. The three studio-*-driver harnesses take over
#     the desktop for minutes at a time, so they are opt-in: ING_HARNESS_STUDIO=1.
#   * it never uploads to Azure DevOps. ING_ADO_UPLOAD=0 is exported for every harness.
#   * it never runs `az`. The sign-in harness puts a fake one first on PATH itself.
set -u

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"

export ING_ADO_UPLOAD=0

TIMEOUT="${ING_HARNESS_TIMEOUT:-600}"
LOGS="$REPO/ingenious-plugin/target/harness-all"
rm -rf "$LOGS"; mkdir -p "$LOGS"

JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
JAR="${JAVA_HOME:+$JAVA_HOME/bin/}jar"

win() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else echo "$1"; fi; }

# ---------------------------------------------------------------------------------------
# FACTS — probed, never assumed, and printed so the skips below can be checked against them.
# ---------------------------------------------------------------------------------------
HAS_JAVA=0;    command -v "$JAVA"  >/dev/null 2>&1 && HAS_JAVA=1
HAS_JAVAC=0;   command -v "$JAVAC" >/dev/null 2>&1 && HAS_JAVAC=1
HAS_NODE=0;    command -v node     >/dev/null 2>&1 && HAS_NODE=1

# A desktop, asked of the JVM itself rather than guessed from an environment variable.
# Six harnesses run with java.awt.headless=false on purpose (the customer step ends in the
# system clipboard, which throws when headless), so "is there a display" is a real question.
# Six, not four: the count is every id guarded by need_display below, and it rotted twice
# while harnesses were added. Re-derive it from need_display rather than trusting this line.
HAS_DISPLAY=0
if [ "$HAS_JAVA" = 1 ]; then
  cat > "$LOGS/HeadlessProbe.java" <<'PROBE'
public class HeadlessProbe {
    public static void main(String[] a) {
        System.out.println(java.awt.GraphicsEnvironment.isHeadless() ? "HEADLESS" : "DISPLAY");
    }
}
PROBE
  [ "$("$JAVA" "$(win "$LOGS/HeadlessProbe.java")" 2>/dev/null | tr -d '\r')" = "DISPLAY" ] && HAS_DISPLAY=1
fi

# ingenious-api in the local repository, which the older harnesses take their contract from
API_JAR="$HOME/.m2/repository/com/ing/ingenious-api/3.0/ingenious-api-3.0.jar"
HAS_API_JAR=0; [ -f "$API_JAR" ] && HAS_API_JAR=1
# ingenious-api SOURCE, which the panel harnesses compile themselves
API_SRC="$REPO/INGenious/ingenious-api/src/main/java/com/ing/ingenious/api/contract/ui"
HAS_API_SRC=0; [ -d "$API_SRC" ] && HAS_API_SRC=1
# Studio's own recording code, which ziel-identitaet reads to see whether the plugin target
# is still resolved rather than duplicated. A different question from the two above: not the
# contract the plugin compiles against, but the implementation behind it.
STUDIO_SRC_FILE="$REPO/INGenious/IDE/src/main/java/com/ing/ide/main/mainui/components/testdesign/testcase/TestCaseComponent.java"

# ---------------------------------------------------------------------------------------
# The suite builds its OWN copy of the plugin and works from that.
#
# ingenious-plugin/target/classes is shared: maven writes it, and four of the panel
# harnesses `rm -rf` and recompile it while two others read it. That is a false-verdict
# generator in both directions — a harness can fail because a sibling was mid-recompile
# (seen: NoClassDefFoundError on SelectedTestCase, from a tree that was perfectly fine
# seconds later), and it can PASS against classes older than the fix they are supposed to
# be proving. That second one is the stale-Harness.class bug wearing a different hat.
#
# So: one javac into a directory this script owns, exported to every harness. Nothing here
# reads or writes the shared build.
#
# TWO VARIABLES, TWO JOBS, AND NEITHER MAY BE READ AS THE OTHER  (2026-07-28)
#
# Until today there was one name for both jobs, exported unconditionally and filled only
# sometimes:
#
#     export ING_PLUGIN_API_CLASSES="$LOGS/api-classes"      # always
#     …
#     mkdir -p "$ING_PLUGIN_API_CLASSES"; javac -d …          # only when the ~/.m2 jar
#                                                             # turns out to LACK contract/ui
#
# A harness that recompiles the contract reads that as "build into here" and is right. A
# harness that puts it on -cp reads it as "the contract is here" and is wrong on every
# machine whose jar is good: the directory was never created, javac gets an empty classpath
# entry, and the failure surfaces as the PLUGIN not compiling. That cost the selector-check
# harness a red run inside the suite while it was green standalone, and it is the same shape
# as every other false verdict in this suite's history — a name believed instead of a
# question asked. An exported-but-empty variable reads as "configured".
#
#   *_BUILD_DIR   always exported, always a directory a harness MAY create and write into.
#                 Says nothing whatsoever about its contents. Existence is not evidence.
#   *_CP          exported ONLY when it names something that really carries the classes.
#                 Unset means "the suite has none for you" — never "here is an empty one".
#
# So the name a harness reads decides what it is allowed to conclude, and neither name can
# be misread into the other's meaning.
# ---------------------------------------------------------------------------------------
export ING_PLUGIN_BUILD_DIR="$LOGS/plugin-classes"
export ING_PLUGIN_API_BUILD_DIR="$LOGS/api-classes"
# Nothing inherited from the caller may look like an answer this run produced.
unset ING_PLUGIN_CP ING_PLUGIN_API_CP ING_PLUGIN_CLASSES ING_PLUGIN_API_CLASSES
HAS_CLASSES=0
BUILT_FROM=""
if [ "$HAS_JAVAC" = 1 ]; then
  if [ "${ING_HARNESS_FROM_HEAD:-0}" = "1" ]; then
    SRC_TREE="$LOGS/head"; mkdir -p "$SRC_TREE"
    ( cd "$REPO" && git archive HEAD ingenious-plugin ) | tar -x -C "$SRC_TREE" 2>/dev/null \
      && BUILT_FROM="committed HEAD $(cd "$REPO" && git rev-parse --short HEAD)"
  else
    SRC_TREE="$REPO"; BUILT_FROM="working tree"
  fi
  PSRC="$SRC_TREE/ingenious-plugin/src/main/java"
  if [ -n "$BUILT_FROM" ] && [ -d "$PSRC" ]; then
    mkdir -p "$ING_PLUGIN_BUILD_DIR"
    # The api contract, from the installed jar when it really carries contract/ui, else
    # from source — the same choice run-guided-flow-harness.sh makes, and for its reason.
    API_CP=""
    if [ -f "$HOME/.m2/repository/com/ing/ingenious-api/3.0/ingenious-api-3.0.jar" ] \
      && MSYS_NO_PATHCONV=1 "$JAR" tf "$(win "$HOME/.m2/repository/com/ing/ingenious-api/3.0/ingenious-api-3.0.jar")" 2>/dev/null \
         | tr -d '\r' | grep -q "contract/ui/StudioPanelApi.class"; then
      API_CP="$(win "$HOME/.m2/repository/com/ing/ingenious-api/3.0/ingenious-api-3.0.jar")"
      # The condition above IS the capability check — it looked inside the jar for the class.
      API_HOME="$HOME/.m2/repository/com/ing/ingenious-api/3.0/ingenious-api-3.0.jar"
    elif [ -d "$API_SRC" ]; then
      mkdir -p "$ING_PLUGIN_API_BUILD_DIR"
      ASRCS=""; for f in "$API_SRC"/*.java; do ASRCS="$ASRCS $(win "$f")"; done
      if MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' "$JAVAC" -encoding UTF-8 \
        -d "$(win "$ING_PLUGIN_API_BUILD_DIR")" $ASRCS >/dev/null 2>&1 \
        && [ -f "$ING_PLUGIN_API_BUILD_DIR/com/ing/ingenious/api/contract/ui/StudioPanelApi.class" ]
      then
        API_CP="$(win "$ING_PLUGIN_API_BUILD_DIR")"
        API_HOME="$ING_PLUGIN_API_BUILD_DIR"
      fi
    fi
    if [ -n "$API_CP" ]; then
      SRCS=""
      for f in $(find "$PSRC" -name '*.java'); do SRCS="$SRCS $(win "$f")"; done
      if MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' "$JAVAC" -encoding UTF-8 \
           -cp "$API_CP" -d "$(win "$ING_PLUGIN_BUILD_DIR")" $SRCS > "$LOGS/plugin-build.log" 2>&1; then
        # The bundled label map ships inside the JAR, so the panels need it on the classpath
        # too — without it they fall back to raw codes and prove the wrong thing.
        cp -r "$SRC_TREE/ingenious-plugin/src/main/resources/." "$ING_PLUGIN_BUILD_DIR/" 2>/dev/null || true
        HAS_CLASSES=1
      fi
    fi
  fi
fi

# …and only now, when they name something that was really produced and was verified to carry
# the class it is wanted for. Both are ordinary paths, the same shape the old variables had;
# every harness that uses them converts for the JVM itself.
if [ -n "${API_HOME:-}" ]; then
  export ING_PLUGIN_API_CP="$API_HOME"
fi
if [ "$HAS_CLASSES" = 1 ]; then
  export ING_PLUGIN_CP="$ING_PLUGIN_BUILD_DIR"
fi

# An install whose core can actually answer — the same capability probe run-profile-harness.sh
# uses, and for the same reason: this machine carries several installs under identical file
# names and only some of them have ProjectTestDataApi. Chosen by capability, never by
# position in a glob.
INSTALL=""
install_capable() {
  [ -f "$1/lib/ingenious-api-3.0.jar" ] || return 1
  [ -f "$1/lib/ingenious-datalib-3.0.0.jar" ] || return 1
  [ -f "$1/lib/ingenious-testdata-csv-3.0.0.jar" ] || return 1
  MSYS_NO_PATHCONV=1 "$JAR" tf "$(win "$1/lib/ingenious-api-3.0.jar")" 2>/dev/null \
    | tr -d '\r' | grep -qx "com/ing/ingenious/api/contract/data/ProjectTestDataApi.class"
}
if [ "$HAS_JAVA" = 1 ]; then
  for candidate in "${1:-}" "${ING_INGENIOUS_HOME:-}" "$REPO/INGenious/Dist/release" \
                   $(ls -d "$HOME"/ingenious/ingenious-playwright-* 2>/dev/null); do
    [ -n "$candidate" ] && [ -d "$candidate" ] || continue
    if install_capable "$candidate"; then INSTALL="$candidate"; break; fi
  done
fi
# A core with the Studio jar, which the chain harness borrows a project and jars from. Not
# the same question as above: the chain harness needs ingenious-ide-3.0.0.jar to exist.
CHAIN_ROOT=""
for candidate in "${1:-}" "${ING_INGENIOUS_HOME:-}" "$REPO/INGenious/Dist/release" \
                 $(ls -d "$HOME"/ingenious/ingenious-playwright-* 2>/dev/null); do
  [ -n "$candidate" ] && [ -f "$candidate/ingenious-ide-3.0.0.jar" ] || continue
  CHAIN_ROOT="$candidate"; break
done

# What studio-kontrakt needs, and a THIRD different question again: not an install to run,
# but a core whose SIGNATURES can be read. Two halves, both required, and reading one method
# needs both — Class.getDeclaredMethod loads every type the signature mentions, so javafx and
# jackson have to be somewhere too, and a Studio install's lib/ is the honest place for them.
CORE_JARS=0
[ -n "$(ls "$REPO/INGenious/IDE/target"/*.jar 2>/dev/null)" ] \
  && [ -n "$(ls "$REPO/INGenious/Datalib/target"/*.jar 2>/dev/null)" ] && CORE_JARS=1
# Same order of preference run-studio-contract-harness.sh applies; here only to PREDICT the
# skip, so that a skip can name what is missing before anything is started.
STUDIO_LIB="${ING_STUDIO_LIB:-}"
if [ -z "$STUDIO_LIB" ] && [ -n "${INGENIOUS_HOME:-}" ] && [ -d "${INGENIOUS_HOME}/lib" ]; then
  STUDIO_LIB="${INGENIOUS_HOME}/lib"
fi
if [ -z "$STUDIO_LIB" ]; then
  STUDIO_LIB="$(ls -d "$HOME"/ingenious/*/lib "$HOME"/ingenious*/lib 2>/dev/null \
    | sort -u | sort -V | tail -1)"
fi
HAS_STUDIO_LIB=0; [ -n "$STUDIO_LIB" ] && [ -d "$STUDIO_LIB" ] && HAS_STUDIO_LIB=1

echo "################################################################"
echo "# harness suite — $(date '+%Y-%m-%d %H:%M:%S')"
echo "################################################################"
echo "repo             : $REPO"
echo "java             : $( [ "$HAS_JAVA" = 1 ] && "$JAVA" -version 2>&1 | head -1 || echo 'MISSING' )"
echo "javac            : $( [ "$HAS_JAVAC" = 1 ] && echo yes || echo 'MISSING (a JRE is not enough)' )"
echo "node             : $( [ "$HAS_NODE" = 1 ] && node --version || echo 'MISSING' )"
echo "desktop          : $( [ "$HAS_DISPLAY" = 1 ] && echo 'yes' || echo 'none — headless=false harnesses cannot run' )"
echo "plugin build     : $( [ "$HAS_CLASSES" = 1 ] && echo "$BUILT_FROM -> $ING_PLUGIN_BUILD_DIR" || echo "FAILED — see $LOGS/plugin-build.log" )"
echo "ingenious-api jar: $( [ "$HAS_API_JAR" = 1 ] && echo "$API_JAR" || echo 'MISSING from ~/.m2' )"
echo "ingenious-api src: $( [ "$HAS_API_SRC" = 1 ] && echo "$API_SRC" || echo 'MISSING' )"
echo "core under test  : ${INSTALL:-none capable}"
echo "core signatures  : $( [ "$CORE_JARS" = 1 ] && echo -n 'INGenious/{IDE,Datalib}/target jars' || echo -n 'NOT BUILT' ); lib/ = ${STUDIO_LIB:-none}"
[ -n "$INSTALL" ] && [ -f "$INSTALL/INSTALL-VERSION.txt" ] \
  && sed -n '2,3p' "$INSTALL/INSTALL-VERSION.txt" | tr -d '\r' | sed 's/^/                   /'
echo "studio drivers   : $( [ "${ING_HARNESS_STUDIO:-0}" = 1 ] && echo 'enabled' || echo 'off (ING_HARNESS_STUDIO=1 to enable — opens a real Studio window)' )"
echo "logs             : $LOGS"
echo

# ---------------------------------------------------------------------------------------
# THE TABLE. `why_skip <id>` returns the reason this harness cannot run here, or "" when it
# can. One place to read, one place to change.
# ---------------------------------------------------------------------------------------
need_java()    { [ "$HAS_JAVA" = 1 ] && [ "$HAS_JAVAC" = 1 ] || echo "no JDK (set JAVA_HOME to a JDK 17)"; }
need_node()    { [ "$HAS_NODE" = 1 ] || echo "no node on PATH"; }
need_classes() { [ "$HAS_CLASSES" = 1 ] || echo "the plugin did not compile — see $LOGS/plugin-build.log"; }
need_apijar()  { [ "$HAS_API_JAR" = 1 ] || echo "ingenious-api-3.0.jar not in ~/.m2 — run: mvn -f INGenious/ingenious-api/pom.xml install"; }
need_apisrc()  { [ "$HAS_API_SRC" = 1 ] || echo "no ingenious-api source at INGenious/ingenious-api (submodule not checked out?)"; }
# ziel-identitaet reads Studio's OWN source — not the contract, the implementation — because
# the thing it guards is a semantic change upstream can make without touching any signature.
# Deliberately NOT declared in ci-gate.sh: if this file goes missing, the guard on the pin is
# gone and the build should say so rather than skip politely.
need_studio_src() { [ -f "$STUDIO_SRC_FILE" ] || echo "no Studio source at $STUDIO_SRC_FILE (submodule not checked out?)"; }
need_display() { [ "$HAS_DISPLAY" = 1 ] || echo "no desktop session — this harness needs java.awt.headless=false"; }
need_install() { [ -n "$INSTALL" ] || echo "no install with a ProjectTestDataApi core — pass one as the first argument"; }
need_chain()   { [ -n "$CHAIN_ROOT" ] || echo "no install with ingenious-ide-3.0.0.jar — pass one as the first argument"; }
need_studio()  { [ "${ING_HARNESS_STUDIO:-0}" = 1 ] || echo "opens a real Studio window for minutes; opt in with ING_HARNESS_STUDIO=1"; }
# ONE reason for studio-kontrakt, deliberately, although two things can be missing: from a
# runner's point of view they are one fact — there is no built INGenious here to read. The
# leading sentence is what ci-gate.sh declares, so it must stay stable; what is actually
# absent is appended, so the log still says which half. A skip for any OTHER cause (no JDK,
# harness will not compile) keeps its own words and stays red at the gate.
need_core_sigs() {
  [ "$CORE_JARS" = 1 ] && [ "$HAS_STUDIO_LIB" = 1 ] && return
  local missing=""
  [ "$CORE_JARS" = 1 ] || missing="INGenious/{IDE,Datalib}/target/*.jar"
  [ "$HAS_STUDIO_LIB" = 1 ] && : || missing="${missing:+$missing and }a Studio install's lib/"
  echo "no built INGenious core here — the names the panel reflects on can only be read from" \
       "a built core plus an install's lib/; missing: $missing"
}
# The selector check starts a real browser through the probe. Asked of node rather than
# guessed from a node_modules folder: one that exists and does not resolve is the case this
# catches, and it would otherwise surface as the harness reporting a failure of the plugin.
need_playwright() {
  node -e "import('playwright').then(()=>process.exit(0)).catch(()=>process.exit(1))" 2>/dev/null \
    || echo "the playwright package is not resolvable from $REPO — run: npm i -D playwright"
}
need_probe()   { [ -f "$REPO/tools/selector-uniqueness.mjs" ] || echo "tools/selector-uniqueness.mjs is missing"; }
need_git()     { command -v git >/dev/null 2>&1 || echo "no git on PATH"; }

first_reason() { for r in "$@"; do [ -n "$r" ] && { echo "$r"; return; }; done; }

why_skip() {
  case "$1" in
    profil)            first_reason "$(need_java)" "$(need_install)" ;;
    ado-panels)        first_reason "$(need_java)" "$(need_classes)" "$(need_apijar)" ;;
    aufnahmeziel)      first_reason "$(need_java)" "$(need_classes)" "$(need_apijar)" ;;
    # No plugin build and no api jar: the doubles are plain Java and the source check is text.
    ziel-identitaet)   first_reason "$(need_java)" "$(need_studio_src)" ;;
    run-wachhund)      first_reason "$(need_java)" "$(need_classes)" "$(need_apijar)" "$(need_node)" ;;
    kette)             first_reason "$(need_java)" "$(need_apijar)" "$(need_node)" "$(need_chain)" ;;
    studio-kontrakt)   first_reason "$(need_java)" "$(need_core_sigs)" ;;
    anmeldung)         first_reason "$(need_java)" "$(need_apijar)" "$(need_node)" ;;
    gefuehrter-flow)   first_reason "$(need_java)" "$(need_apisrc)" "$(need_display)" "$(need_node)" ;;
    unlesbar)          first_reason "$(need_java)" "$(need_apisrc)" "$(need_display)" ;;
    abgabe-unlesbar)   first_reason "$(need_java)" "$(need_apisrc)" "$(need_display)" "$(need_node)" ;;
    einstiegsadresse)  first_reason "$(need_java)" "$(need_apisrc)" "$(need_display)" ;;
    # Takes the suite's own plugin build (ING_PLUGIN_CP) and its api classpath, so it needs
    # neither the api SOURCE nor an install — only a desktop to lay a frame out in.
    testdaten-filter)  first_reason "$(need_java)" "$(need_classes)" "$(need_display)" ;;
    auswahl-vertrag)   first_reason "$(need_java)" "$(need_classes)" "$(need_apijar)" ;;
    # NOT the id `anmeldung`, which is already the headless sign-in harness above. The two
    # prove different halves of the same defect — that one names the state the publisher
    # chose, this one what that state looks like on screen — and they have different
    # preconditions, so they cannot share a row: run_one would then run the second under the
    # first's why_skip, and ci-gate.sh's roster (which sorts ids unique) would see one name
    # for two harnesses and never notice if one of them stopped running.
    anmeldung-anzeige) first_reason "$(need_java)" "$(need_apisrc)" "$(need_display)" ;;
    selektor-pruefung) first_reason "$(need_java)" "$(need_apisrc)" "$(need_node)" \
                                    "$(need_probe)" "$(need_playwright)" "$(need_display)" ;;
    # Builds real git repositories as fixtures and renders the panel against them. No node:
    # the tool files it plants are empty, because the product asks whether they EXIST.
    herkunft)          first_reason "$(need_java)" "$(need_classes)" "$(need_display)" \
                                    "$(need_git)" ;;
    ado-discover|ado-mark|distill-trace) first_reason "$(need_node)" ;;
    # Bare node scripts over node built-ins. No pnpm, no turbo, no lockfile, no browser —
    # see the header of run-mvp-tests.sh for why the lockfile that blocks ing-qa-recorder/
    # never blocked these. git is wanted too, for the one generated-artifact check.
    mvp-einheit)       first_reason "$(need_node)" "$(need_git)" ;;
    studio-kette|studio-aufnahme|studio-wachhund)
                       first_reason "$(need_studio)" "$(need_java)" "$(need_classes)" "$(need_chain)" "$(need_display)" ;;
  esac
}

PASS=""; FAIL=""; SKIP=""; UNPROVED=""
NP=0; NF=0; NS=0; NU=0

# A plugin that will not compile is a FAILURE, not six skips.
#
# The dependent harnesses genuinely did not run, so reporting each of them as SKIP is
# accurate — but if that were the whole story, a syntax error would come out as "nothing
# failed, some things were unproven", which describes a broken build as an absence of
# information. It is information. The build gets its own row, and it is red.
if [ "$HAS_JAVAC" = 1 ] && [ "$HAS_CLASSES" = 0 ]; then
  printf '  FAIL  %-18s the plugin did not compile — see %s\n' "plugin-build" "$LOGS/plugin-build.log"
  FAIL="${FAIL}plugin-build|the plugin did not compile
"; NF=$((NF + 1))
fi

run_one() { # id | what it proves | command to run...
  local id="$1" what="$2"; shift 2
  local reason; reason="$(why_skip "$id")"
  if [ -n "$reason" ]; then
    printf '  SKIP  %-18s %s\n' "$id" "$reason"
    SKIP="$SKIP$id|$reason
"; NS=$((NS + 1)); return
  fi
  local log="$LOGS/$id.log"
  printf '  ....  %-18s %s\r' "$id" "$what" >&2
  # A harness that hangs must not hang the suite, and must not be mistaken for one that
  # passed. `timeout` returns 124, which is neither 0 nor 4, so it lands in FAIL.
  timeout "$TIMEOUT" "$@" > "$log" 2>&1
  local rc=$?
  case $rc in
    0) printf '  PASS  %-18s %s\n' "$id" "$what"
       PASS="$PASS$id
"; NP=$((NP + 1)) ;;
    4) # The harness ran, nothing failed, but it says itself that something was never put.
       local note; note="$(grep -m1 'UNGEPRUEFT' "$log" | sed 's/^[[:space:]!]*//' | cut -c1-90)"
       printf '  UNPR  %-18s %s\n' "$id" "${note:-the harness reports UNGEPRUEFT — see $id.log}"
       UNPROVED="$UNPROVED$id|${note:-see $id.log}
"; NU=$((NU + 1)) ;;
    124) printf '  FAIL  %-18s HUNG — killed after %ss (see %s.log)\n' "$id" "$TIMEOUT" "$id"
       FAIL="$FAIL$id|hung, killed after ${TIMEOUT}s
"; NF=$((NF + 1)) ;;
    *) # Preconditions were met, so an exit 2 here is a broken build, not a shrug.
       local note; note="$(grep -m1 -E '^(RESULT: RED|!!|FAILED)' "$log" | cut -c1-90)"
       printf '  FAIL  %-18s exit %s — %s\n' "$id" "$rc" "${note:-see $id.log}"
       FAIL="$FAIL$id|exit $rc — ${note:-see $id.log}
"; NF=$((NF + 1)) ;;
  esac
}

echo "headless — safe to run anywhere"
run_one profil           "the PII boundary: a profile is written, the account number is not" bash "$HERE/run-profile-harness.sh" ${INSTALL:+"$INSTALL"}
run_one ado-panels       "the ADO chooser and overview panels" bash "$HERE/run-ado-harness.sh"
run_one aufnahmeziel     "a chosen ADO case becomes the recorder's target" bash "$HERE/run-recording-target-harness.sh"
# …and that re-recording it lands in the SAME test case. aufnahmeziel proves the target is
# CHOSEN; this proves it keeps its identity, which upstream #302 would silently take away on
# a submodule pin move. It reads the pinned Studio source, so it is the check that fires on
# the pin-move commit rather than on the day somebody remembers.
run_one ziel-identitaet  "re-recording a plugin-named target does not fork it" bash "$HERE/run-plugin-target-identity-harness.sh"
run_one run-wachhund     "a finished run reaches ADO from Studio" bash "$HERE/run-run-watcher-harness.sh"
run_one kette            "the whole Studio -> ADO chain, link by link" bash "$HERE/run-chain-harness.sh" ${CHAIN_ROOT:+"$CHAIN_ROOT"}
run_one anmeldung        "the invisible ADO sign-in" bash "$HERE/signin/run-signin-harness.sh"
run_one auswahl-vertrag  "the selection file's writer and reader agree, key by key" bash "$HERE/run-selection-contract-harness.sh"
# The guard on the Studio double: every name the panel reflects on, read off the BUILT core.
# Headless-safe, but it needs a core to read — so it skips where there is none, by name.
run_one studio-kontrakt  "the names the panel reflects on still exist in the core" bash "$HERE/run-studio-contract-harness.sh"
echo
echo "needs a desktop — a frame is packed but never shown"
run_one gefuehrter-flow  "the guided tester flow, click by click" bash "$HERE/run-guided-flow-harness.sh"
run_one unlesbar         "the recording button when Studio will not say" bash "$HERE/unreadable/run-unreadable-harness.sh"
run_one abgabe-unlesbar  "the hand-off button when Studio will not say" bash "$HERE/unreadable/run-handoff-unreadable-harness.sh"
run_one einstiegsadresse "the start address reaches the settings file" bash "$HERE/unreadable/run-start-address-harness.sh"
run_one anmeldung-anzeige "the ADO sign-in is amber to ask, red when it gave up" bash "$HERE/unreadable/run-sign-in-state-harness.sh"
run_one testdaten-filter "every column of the test-data file has a control, and it says what it is" bash "$HERE/testdata/run-testdata-filter-harness.sh"
run_one selektor-pruefung "which recorded steps match more than one element" bash "$HERE/run-selector-check-harness.sh"
# The lock on the 2026-07-28 incident: a machine whose checkout was a month old had lost five
# of the tools the panels shell out to, and the only symptom was a greyed button. This proves
# the panel says so first — and, on three machines it cannot judge, says nothing at all.
run_one herkunft         "the panel says when its tools are older than itself, and stays quiet when they are not" bash "$HERE/run-repo-check-harness.sh"
echo
# These three are contract tests over the Node tools the pipeline actually runs. They were
# reachable only by a human typing them out of a README — 343 assertions with no caller.
# 343, not the 164 written here when they were wired up: distill-trace grew the zip-extractor
# fallback afterwards. A count in prose rots the moment the thing it counts is improved.
echo "node contract tests — no automated caller until now"
run_one ado-discover     "the ADO discovery contract"                         node "$REPO/tools/test-ado-discover.mjs"
run_one ado-mark         "the ADO mark-result contract"                       node "$REPO/tools/test-ado-mark.mjs"
run_one distill-trace    "the trace distiller, and the zip extractor fallback under it" node "$REPO/tools/test-distill-trace.mjs"
# The recorder's own nine unit tests — IR bridge, codegen, selectors, session, test data,
# Katalon import, the ADO-Bug-on-failure planner. Written 2026-06-18 and executed by nothing
# since; wired 2026-07-28. They need node and nothing else, which is why the missing
# pnpm-lock.yaml that stops ing-qa-recorder/ being BUILT never stopped these being RUN.
run_one mvp-einheit      "the recorder's own unit tests — IR, codegen, selectors, test data" \
                         bash "$REPO/ing-qa-recorder/mvp/test/run-mvp-tests.sh"
echo
echo "opens a real Studio window — opt-in"
run_one studio-kette     "the whole chain in a real running Studio" bash "$HERE/run-studio-chain-driver.sh" ${CHAIN_ROOT:+"$CHAIN_ROOT"}
run_one studio-aufnahme  "the recorder target in a real running Studio" bash "$HERE/run-studio-record-driver.sh" ${CHAIN_ROOT:+"$CHAIN_ROOT"}
run_one studio-wachhund  "the run watcher in a real running Studio" bash "$HERE/run-studio-watcher-driver.sh" ${CHAIN_ROOT:+"$CHAIN_ROOT"}

echo
echo "################################################################"
echo "# $NP passed · $NF failed · $NU unproved · $NS skipped"
echo "################################################################"
if [ $NF -gt 0 ]; then
  echo
  echo "FAILED — these ran and did not prove their point:"
  printf '%s' "$FAIL" | while IFS='|' read -r id note; do
    [ -n "$id" ] && echo "  $id — $note   ($LOGS/$id.log)"
  done
fi
if [ $NU -gt 0 ]; then
  echo
  echo "UNPROVED — these ran, nothing failed, but part of them was never put:"
  printf '%s' "$UNPROVED" | while IFS='|' read -r id note; do
    [ -n "$id" ] && echo "  $id — $note"
  done
fi
if [ $NS -gt 0 ]; then
  echo
  echo "NOT RUN — and this is what is missing:"
  printf '%s' "$SKIP" | while IFS='|' read -r id reason; do
    [ -n "$id" ] && echo "  $id — $reason"
  done
fi

echo
if [ $NF -gt 0 ]; then
  echo "RESULT: RED — $NF harness(es) failed."
  exit 1
fi
if [ $NS -gt 0 ] || [ $NU -gt 0 ]; then
  echo "RESULT: UNGEPRUEFT — nothing failed, but $((NS + NU)) harness(es) proved nothing here."
  echo "        This is NOT a pass. The list above says what was not proved and why."
  exit 4
fi
echo "RESULT: GREEN — all $NP harnesses ran and passed."
exit 0
