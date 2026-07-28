#!/usr/bin/env bash
# Proves (or disproves) the last unproven link in the Studio -> ADO chain: that
# AdoRunWatcher can reflectively reach the open project from INSIDE the plugin's own
# child-first class loader, in a real running Studio.
#
#   bash ingenious-plugin/harness/run-studio-watcher-driver.sh <install-root> [project]
#
# e.g. bash ingenious-plugin/harness/run-studio-watcher-driver.sh \
#          C:/Users/wkiri/OneDrive/Desktop/ing/INGenious/Dist/release
#
# Needs a display: it starts Studio for real. A Studio window WILL appear for ~40s.
#
# It does NOT touch the installed plugin. INGENIOUS_PLUGIN_PATH replaces the whole plugin
# search path (PluginSearchPath.resolve returns early when it is set), so this runs against
# a freshly built jar in a directory of its own and leaves whatever the user has installed
# — and whatever Studio they have open — completely alone.
#
# ING_ADO_UPLOAD=0 because activating the panel arms a watcher that really uploads, and
# ING_INGENIOUS_PROJECT is explicitly unset because it would short-circuit the very method
# under test. The driver refuses to run if either guard is wrong.
set -u

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ROOT="${1:?usage: run-studio-watcher-driver.sh <install-root> [project]}"
PROJECT="${2:-$ROOT/Projects/Tutorial}"

JAVA="${JAVA_HOME:-}"; if [ -n "$JAVA" ]; then JAVA="$JAVA/bin/java"; else JAVA="java"; fi
JAVAC="${JAVA_HOME:-}"; if [ -n "$JAVAC" ]; then JAVAC="$JAVAC/bin/javac"; else JAVAC="javac"; fi

WORK="$REPO/ingenious-plugin/target/studio-watcher"
PLUGINS="$WORK/plugins/ing-tester-panel"
rm -rf "$WORK"; mkdir -p "$WORK" "$PLUGINS"

JAR="$REPO/ingenious-plugin/target/ing-tester-panel-0.1.0.jar"
if [ ! -f "$JAR" ]; then
  echo "build the plugin first: mvn -f ingenious-plugin/pom.xml package" >&2
  exit 2
fi
cp "$JAR" "$PLUGINS/"
echo "plugin under test: $JAR"
"$JAVA" -version 2>&1 | head -1

# Windows java wants Windows paths, and a ';' classpath must not be mangled by MSYS.
win() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else echo "$1"; fi; }
WORK_W="$(win "$WORK")"
ROOT_W="$(win "$ROOT")"

# Named and checked, not assumed. Not every install carries this file — the 3.0.0-preview
# install ships ingenious-ide-3.0.0-PREVIEW.jar — and a classpath entry that does not exist
# is silently ignored by java, so the driver would run against a Studio core that is simply
# not on the classpath and report whatever that produces.
IDE_JAR="$ROOT/ingenious-ide-3.0.0.jar"
if [ ! -f "$IDE_JAR" ]; then
  echo "No Studio core at $IDE_JAR — this install carries: $(ls "$ROOT"/ingenious-ide-*.jar 2>/dev/null | tr '\n' ' ')" >&2
  exit 2
fi
echo "studio under test: $IDE_JAR"
CP="$ROOT_W\\ingenious-ide-3.0.0.jar;$ROOT_W\\lib\\*;$ROOT_W\\lib\\clib\\*"
MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' \
"$JAVAC" -encoding UTF-8 -cp "$CP" -d "$WORK_W" \
  "$(win "$REPO/ingenious-plugin/harness/StudioWatcherDriver.java")" || exit 2

unset ING_INGENIOUS_PROJECT
export ING_ADO_UPLOAD=0
export INGENIOUS_PLUGIN_PATH="$(win "$WORK/plugins")"

( cd "$ROOT" && MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' \
    "$JAVA" -Dfile.encoding=UTF-8 -cp "$WORK_W;$CP" StudioWatcherDriver "$(win "$PROJECT")" )
RC=$?

[ -f "$ROOT/watcher-driver-screenshot.png" ] \
  && mv "$ROOT/watcher-driver-screenshot.png" "$WORK/verdict.png" \
  && echo "screenshot: $WORK/verdict.png"

echo
case $RC in
  0) echo "VERDICT: PROVEN — the plugin class loader reaches the open project." ;;
  4) echo "VERDICT: DISPROVEN — it does not. The Studio upload chain is broken at this link." ;;
  *) echo "VERDICT: INCONCLUSIVE (exit $RC) — the question was never actually put." ;;
esac
exit $RC
