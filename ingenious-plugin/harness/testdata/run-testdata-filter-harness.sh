#!/usr/bin/env bash
# Every column of the test-data file gets a control — and every control tells the truth.
#
#   bash ingenious-plugin/harness/testdata/run-testdata-filter-harness.sh
#
# WHY THIS EXISTS
# The tester reported: "I liked the previous implementation for the test-data choosing much
# more, where people could choose everything. It seems like Bonitaet Zusatzinfo is missing for
# example." A column got a filter only when it had 2..25 distinct values, so seven of the
# seventeen columns on their machine had no control at all — and nothing on screen said so.
#
# THE FIXTURE IS GENERATED, NOT CHECKED IN, AND ENTIRELY INVENTED
# The real export is customer data and never enters this repository. What this script writes
# below is made up: account numbers counted upwards, "Musterkonto <n>" for the products,
# birth dates that are a counter. Its only fidelity to the real file is its SHAPE — the same
# seventeen column names, the same 50.015 rows, the same distinct-value profile and the same
# columns left empty, because the shape is the only thing the control choice depends on.
#
# THE SHAPE WAS RE-MEASURED ON THE WHOLE FILE (2026-07-28)
# The profile this fixture used to carry came from the first 2,999 rows — 6% of the sheet —
# and it was wrong about exactly the two columns a sample is most likely to be wrong about:
# the two that looked like they said nothing. See the fixture block for the numbers.
#
# BUILDS FROM COMMITTED HEAD, INTO A DIRECTORY OF ITS OWN
# ingenious-plugin/target/classes is shared, and three false verdicts in this repo have come
# from a harness reading a tree another one was mid-rebuild of, or older than the fix under
# test. This never touches it. ING_HARNESS_FROM_WORKTREE=1 builds the working tree instead,
# for the minutes between writing a fix and committing it — and it says which it did.
#
# NOT headless: the panel is packed into a real (never shown) frame and captured with
# printAll, because a control that is laid out to zero height passes every string assertion.
set -u

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
PLUGIN="$REPO/ingenious-plugin"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
JAR="${JAVA_HOME:+$JAVA_HOME/bin/}jar"
HERE="$PLUGIN/harness/testdata"
WORK="$PLUGIN/target/harness-testdata-filter"
SHOTS="$WORK/shots"
CSV="$WORK/testdaten-synthetisch.csv"
SMALL="$WORK/testdaten-klein.csv"

win() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else echo "$1"; fi; }

rm -rf "$WORK"; mkdir -p "$WORK/classes" "$WORK/plugin-classes" "$SHOTS"

# ---------------------------------------------------------------------------------------
# The source tree under test. When run-all.sh has already built the plugin into a directory
# it owns, that build is used — one suite, one tree, and no second answer to "what was
# compiled". ING_PLUGIN_CP is set ONLY when the suite verified the build succeeded.
# ---------------------------------------------------------------------------------------
if [ -n "${ING_PLUGIN_CP:-}" ]; then
  SRC=""
  FROM="the suite's own build: $ING_PLUGIN_CP"
elif [ "${ING_HARNESS_FROM_WORKTREE:-0}" = "1" ]; then
  SRC="$REPO"
  FROM="working tree (UNCOMMITTED)"
else
  mkdir -p "$WORK/head"
  ( cd "$REPO" && git archive HEAD ingenious-plugin ) | tar -x -C "$WORK/head" || {
    echo "FAILED: could not export ingenious-plugin from git HEAD"; exit 2; }
  SRC="$WORK/head"
  FROM="committed HEAD $(cd "$REPO" && git rev-parse --short HEAD)"
fi

# ---------------------------------------------------------------------------------------
# The api contract: whatever the suite already proved carries it, else the ~/.m2 jar when it
# really holds contract/ui, else the source in the submodule.
# ---------------------------------------------------------------------------------------
API_CP="${ING_PLUGIN_API_CP:-}"
API_JAR="$HOME/.m2/repository/com/ing/ingenious-api/3.0/ingenious-api-3.0.jar"
API_SRC="$REPO/INGenious/ingenious-api/src/main/java/com/ing/ingenious/api/contract/ui"
if [ -z "$API_CP" ] && [ -f "$API_JAR" ] \
   && MSYS_NO_PATHCONV=1 "$JAR" tf "$(win "$API_JAR")" 2>/dev/null | tr -d '\r' \
      | grep -q "contract/ui/StudioPanelApi.class"; then
  API_CP="$API_JAR"
fi
if [ -z "$API_CP" ] && [ -d "$API_SRC" ]; then
  mkdir -p "$WORK/api-classes"
  ASRCS=""; for f in "$API_SRC"/*.java; do ASRCS="$ASRCS $(win "$f")"; done
  MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' "$JAVAC" -encoding UTF-8 \
    -d "$(win "$WORK/api-classes")" $ASRCS || exit 2
  API_CP="$WORK/api-classes"
fi
if [ -z "$API_CP" ]; then
  echo "FAILED: no ingenious-api — neither $API_JAR nor $API_SRC"; exit 2
fi

# ---------------------------------------------------------------------------------------
# The plugin, and the harness on top of it
# ---------------------------------------------------------------------------------------
if [ -n "$SRC" ]; then
  PSRCS=""; for f in $(find "$SRC/ingenious-plugin/src/main/java" -name '*.java'); do
    PSRCS="$PSRCS $(win "$f")"
  done
  MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' "$JAVAC" -encoding UTF-8 \
    -cp "$(win "$API_CP")" -d "$(win "$WORK/plugin-classes")" $PSRCS || exit 2
  # The bundled label map ships inside the JAR. Without it the panel falls back to raw codes
  # and the checks about plain German would prove the opposite of what they say.
  cp -r "$SRC/ingenious-plugin/src/main/resources/." "$WORK/plugin-classes/" 2>/dev/null || true
  PLUGIN_CP="$WORK/plugin-classes"
else
  PLUGIN_CP="$ING_PLUGIN_CP"
fi

MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' "$JAVAC" -encoding UTF-8 \
  -cp "$(win "$PLUGIN_CP");$(win "$API_CP")" -d "$(win "$WORK/classes")" \
  "$(win "$HERE/TestDataFilterHarness.java")" || exit 2

HEADER="Variante_KND;Kbo5 Bonitaet S;Kbo5 Zusatzinfo;Kbo5 Personen Nr;Part Nr;Part Partnertyp Kz;Kontonummer;Produktvariante Pzm;Part Geburtsdatum;MDJ_KND;EZB;Legi Status Kz;Verf Bez;Vest Bez;ZUDA TAN_LISTE;ZUDA APP;ZUDA M_TAN"

# ---------------------------------------------------------------------------------------
# The fixture. Invented values; real shape. See the header of this file.
#
# Every count below was measured on 2026-07-28 against the WHOLE sheet, with the panel's
# own loading rules applied (quoted split, blocklist, usable()). It replaces a profile
# taken from the first 2,999 rows, which was 6% of the file and got two columns wrong:
# "Kbo5 Personen Nr" looked empty (it holds 8,492 different numbers) and
# "ZUDA TAN_LISTE" looked like one value in every row (2,696 rows leave it empty, which
# is a state a tester can pick). A verdict of "this column says nothing" drawn from 6%
# of a file is the same false absence this harness exists to catch.
#
#   Variante_KND        1.804      Kbo5 Bonitaet S       14      Kbo5 Zusatzinfo  14.072
#   Kbo5 Personen Nr    8.492 +leer  Part Nr         21.722      Part Partnertyp Kz    3
#   Kontonummer        25.181      Produktvariante Pzm   30      Part Geburtsdatum 14.603
#   MDJ_KND                 2      EZB                    7      Legi Status Kz  4 +leer
#   Verf Bez                2      Vest Bez               5      ZUDA TAN_LISTE  1 +leer
#   ZUDA APP           2 +leer      ZUDA M_TAN        2 +leer
#
# Widths are part of the shape too — a dropdown is as wide as its widest entry, and that
# is what wraps or clips. So the invented strings are built to the real ones' LENGTH:
# Vest Bez up to 40 characters, Produktvariante up to 30, Variante_KND a long composite.
#
# 50.015 data rows, of which the panel offers 50.014: one row in the real sheet carries a
# five-digit Kontonummer and is withheld by usable(). The fixture carries that row too,
# because "one row was withheld" is a sentence the tester reads on screen.
# ---------------------------------------------------------------------------------------
{
  echo "$HEADER"
  # 21 is the one Bonitaet code with a confirmed German meaning, so it is in the set: the
  # dropdown then really has a translated entry to get wrong. The rest are invented.
  boni=(21 11 13 17 19 23 29 31 37 41 43 47 53 59)
  ptyp=(P G J)
  prod=("Musterkonto" "Muster-Extrakonto" "Musterkredit" "Musterkonto Junior" \
        "Musterdarlehen mit Festzins" "Musterdepot" "Mustertagesgeld" "Musterbaufinanzierung" \
        "Musterrahmenkredit" "Musterautokredit" "Musterwohnkredit" "Musterfestgeld" \
        "Musterbasiskonto" "Musterkonto Studenten" "Musterkonto Geschaeftlich" \
        "Musterkonto Gemeinschaft" "Musterkonto Direkt" "Musterkonto Klassik" \
        "Musterkonto Kompakt" "Musterkonto Plus" "Musterkonto Premium" "Musterkonto Start" \
        "Musterdarlehen kurzfristig" "Musterdarlehen langfristig" "Musterdarlehen variabel" \
        "Musterkredit mit Sicherheit" "Musterkredit ohne Sicherheit" "Mustersparbuch" \
        "Mustersparplan monatlich" "Musterwertpapierdepot Basis")
  ezb=(9 88 145 207 1077 3311 6042)
  legi=(0 1 4 5)
  verf=(INTB PBTN)
  vest=("Musterzugang aktiv (Kunde hat PIN/TAN)" "Musterzugang gesperrt (admin.)" \
        "Musterpostbox-Teilnahme aktiv" "Musterpostbox-Teilnahme inaktiv" \
        "Musterzugang inaktiv (keine Teilnahme)")
  jn=(N J)
  person=0
  legis=0
  for ((i = 0; i < 50014; i++)); do
    # Personennummer: empty in five rows of every eight, as in the sheet (62%). The
    # counter, not i, indexes the pool, so the distinct count is exactly 8.492.
    pn=""
    if [ $(( i % 8 )) -ge 5 ]; then
      printf -v pn '5%09d' $(( person % 8492 )); person=$(( person + 1 ))
    fi
    # Legitimationsstatus: filled in 4 rows of every 100. Same reason for the counter.
    lg=""
    if [ $(( i % 100 )) -ge 96 ]; then
      lg="${legi[$(( legis % 4 ))]}"; legis=$(( legis + 1 ))
    fi
    # The three ZUDA columns are empty on the SAME rows — in the sheet all three are blank
    # on exactly 2.696 rows, which is what a customer without a ZUDA record looks like.
    tanl="N"; app="${jn[$(( i % 2 ))]}"; mtan="N"
    [ $(( i % 150 )) -eq 0 ] && mtan="J"
    if [ $(( i % 1000 )) -lt 54 ]; then tanl=""; app=""; mtan=""; fi
    printf 'VAR%04d_0_M_Musterprodukt lang_ZUGA_Musterzugang aktiv (PIN/TAN)_N;%s;%d;%s;1%09d;%s;5%09d;%s;%d;%s;%s;%s;%s;%s;%s;%s;%s\n' \
      $(( i % 1804 )) "${boni[$(( i % 14 ))]}" $(( (i % 14072) + 100000 )) "$pn" \
      $(( i % 21722 )) "${ptyp[$(( i % 3 ))]}" $(( i % 25181 )) "${prod[$(( i % 30 ))]}" \
      $(( 10000 + i % 14603 )) "${jn[$(( i % 2 ))]}" "${ezb[$(( i % 7 ))]}" "$lg" \
      "${verf[$(( i % 2 ))]}" "${vest[$(( i % 5 ))]}" "$tanl" "$app" "$mtan"
  done
  # The one row the panel must refuse: a five-digit account number. Every other cell
  # repeats values already in the file, so it cannot change any distinct count.
  printf 'VAR0000_0_M_Musterprodukt lang_ZUGA_Musterzugang aktiv (PIN/TAN)_N;21;100000;;1000000000;P;64865;%s;10000;N;9;;INTB;%s;N;N;N\n' \
    "${prod[0]}" "${vest[0]}"
} > "$CSV"

# ---------------------------------------------------------------------------------------
# The second fixture: a tiny file in which two columns really DO say nothing.
#
# On the real sheet no column is inert any more — which would leave the "says nothing in
# this file" control with no test at all, and it is precisely the control that lied about
# ZUDA TAN_LISTE when it was measured on 6% of the rows. So it keeps a file of its own:
# one column empty throughout, one column with the same value in every row. Eight rows
# also put the POPUP_ROWS floor under test — √8 is 3, so a 4-value dropdown can only exist
# because of the floor.
# ---------------------------------------------------------------------------------------
{
  echo "$HEADER"
  for ((i = 0; i < 8; i++)); do
    printf 'VAR%04d;21;%d;;1%09d;%s;5%09d;%s;%d;N;9;5;INTB;%s;N;%s;\n' \
      $i $(( 100000 + i )) $i "$( [ $(( i % 2 )) -eq 0 ] && echo P || echo G )" $i \
      "Musterkonto $(( i % 4 ))" $(( 10000 + i )) "Musterzugang aktiv (Kunde hat PIN/TAN)" \
      "$( [ $(( i % 2 )) -eq 0 ] && echo N || echo J )"
  done
} > "$SMALL"

echo
echo "################################################################"
echo "quelle    : $FROM"
echo "fixture   : $CSV  ($(($(wc -l < "$CSV") - 1)) erfundene Zeilen, 17 Spalten)"
echo "klein     : $SMALL  ($(($(wc -l < "$SMALL") - 1)) erfundene Zeilen, 17 Spalten)"
echo "################################################################"
ING_TESTDATA_CSV="$(win "$CSV")" \
"$JAVA" -Djava.awt.headless=false -Dfile.encoding=UTF-8 \
  -cp "$(win "$WORK/classes");$(win "$PLUGIN_CP");$(win "$API_CP")" \
  testdata.TestDataFilterHarness "$(win "$SHOTS")" voll
RC=$?

# The inert controls, on the only kind of file that still has them. A separate JVM because
# the panel reads its path from the environment, and an environment is not re-read.
echo
ING_TESTDATA_CSV="$(win "$SMALL")" \
"$JAVA" -Djava.awt.headless=false -Dfile.encoding=UTF-8 \
  -cp "$(win "$WORK/classes");$(win "$PLUGIN_CP");$(win "$API_CP")" \
  testdata.TestDataFilterHarness "$(win "$SHOTS")" klein
RC_SMALL=$?
# The worse of the two verdicts, and red beats ungeprueft: a pass in one file cannot cover
# a failure in the other.
if [ "$RC" -eq 1 ] || [ "$RC_SMALL" -eq 1 ]; then RC=1
elif [ "$RC" -eq 4 ] || [ "$RC_SMALL" -eq 4 ]; then RC=4
fi

echo
echo "################################################################"
# Propagated VERBATIM: 0/4/1 are three different sentences and a boolean has room for two.
echo "testdaten-filter=$RC  (0 = green, 4 = ungeprueft, 1 = red)"
echo "screenshots: $SHOTS"
exit $RC
