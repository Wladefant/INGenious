#!/usr/bin/env node
/**
 * green-audit — does this run prove anything?
 *
 * Every other post-processing tool in this repo answers "what went wrong". This one
 * answers the question nobody was asking: what does a GREEN run actually establish?
 *
 * The trigger is issue #191: two assertions passed while the application under test
 * had done nothing at all — one table carried both class names, and the result table
 * was already on screen before any search ran. A false green is more dangerous than a
 * red, because it feels like progress.
 *
 * Node stdlib only. No network. Reads runs through parse-report.mjs as a child
 * process, so report-format knowledge stays in exactly one file.
 * See tools/README-green-audit.md.
 */

import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const parseReportPath = path.join(__dirname, 'parse-report.mjs');
const MAX_BUFFER = 64 * 1024 * 1024;

const EXIT_CLEAN = 0;
const EXIT_FINDINGS = 1;
const EXIT_USAGE = 2;
/**
 * "I could not measure this" is not "I measured and found nothing". Collapsing the two
 * into exit 0 is the exact error this tool accuses INGenious of, so it gets its own code.
 */
const EXIT_UNMEASURABLE = 3;

/** A test case with this many interactions and a single assertion is thinly covered. */
const THIN_COVERAGE_INTERACTIONS = 8;

const USAGE = `green-audit.mjs — belegt dieser Lauf überhaupt etwas?

Aufruf:
  node tools/green-audit.mjs --run-dir <lauf> [--runs <eltern>] [--json <datei>] [--strict]

  --run-dir <lauf>   Der zu bewertende Lauf (Pflicht).
  --runs <eltern>    Eltern-Verzeichnis mit der Lauf-Historie, für die Frage
                     "ist diese Zusicherung jemals gescheitert?". Ohne Angabe wird
                     das Eltern-Verzeichnis von --run-dir benutzt, falls es weitere
                     Läufe enthält.
  --json <datei>     Zusätzlich als JSON schreiben (die Textausgabe bleibt).
  --strict           Auch bei "schwacher Beleg" mit Code 1 enden.
  --selftest         Die Gegenbeispiele prüfen und beenden.

Code 1 = mindestens ein bestandener Testfall belegt NICHTS (mit --strict auch
bei schwachem Beleg). Code 3 = mindestens ein Testfall war NICHT BEWERTBAR und
sonst gab es nichts zu beanstanden — das ist kein Freispruch, sondern die
Auskunft, dass hier nicht gemessen werden konnte. Code 2 = Aufruffehler.
Code 0 nur, wenn jeder Testfall bewertet werden konnte und nichts zu melden war.`;

function die(msg, code = EXIT_USAGE) {
  console.error(msg);
  process.exit(code);
}

function parseArgs(argv) {
  const args = { runDir: null, runs: null, json: null, strict: false };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--run-dir') args.runDir = argv[++i];
    else if (a === '--runs') args.runs = argv[++i];
    else if (a === '--json') args.json = argv[++i];
    else if (a === '--strict') args.strict = true;
    else if (a === '--selftest') args.selftest = true;
    else if (a === '--help' || a === '-h') args.help = true;
    else die(`Unbekanntes Argument: ${a}\n\n${USAGE}`);
  }
  return args;
}

/** A directory looks like an INGenious run dir. */
function looksLikeRunDir(dir) {
  let entries;
  try {
    entries = fs.readdirSync(dir);
  } catch {
    return false;
  }
  if (entries.includes('data.js')) return true;
  return entries.some((name) => /_.*-v2\.html$/i.test(name));
}

function parseRun(runDir) {
  const stdout = execFileSync('node', [parseReportPath, '--run-dir', runDir], {
    encoding: 'utf8',
    maxBuffer: MAX_BUFFER,
  });
  return JSON.parse(stdout);
}

/** Sibling runs of runDir (excluding it), or the runs under an explicit parent. */
function discoverHistory(runDir, explicitParent) {
  const parent = explicitParent
    ? path.resolve(explicitParent)
    : path.dirname(path.resolve(runDir));
  let entries;
  try {
    entries = fs.readdirSync(parent, { withFileTypes: true });
  } catch {
    return [];
  }
  return entries
    .filter((e) => e.isDirectory())
    // Studio keeps a "Latest" copy of the newest run. Counting it would let the same
    // run vouch for itself twice, and B5's "never failed in N runs" would overstate
    // the evidence base by exactly one. Auf einem Testgerät gemessen: es sagte 3 für 2 Läufe.
    .filter((e) => e.name.toLowerCase() !== 'latest')
    .map((e) => path.resolve(parent, e.name))
    // ... and the same overstatement through the other door: the run under audit is one
    // of its own siblings when its parent is scanned. A run cannot be its own
    // counter-example, and the docstring above always claimed it was excluded.
    .filter((d) => d !== path.resolve(runDir))
    .filter((d) => looksLikeRunDir(d))
    .sort();
}

/**
 * Has this test case ever been observed with a FAILING assertion?
 *
 * An assertion that has only ever passed has not been shown to be capable of
 * failing. That is not an accusation — it is an unmeasured state, and issue #191
 * is what it costs to leave it unmeasured.
 */
function counterEvidence(history, caseName) {
  let runsSeen = 0;
  const runsWithFailingAssertion = [];
  for (const { basename, doc } of history) {
    const tc = (doc.testCases || []).find((c) => c.name === caseName);
    if (!tc || !tc.verification) continue;
    runsSeen += 1;
    if (tc.verification.assertionsFailed > 0) runsWithFailingAssertion.push(basename);
  }
  return { runsSeen, runsWithFailingAssertion };
}

/** Findings for one test case. Ordered most-damning first. */
function auditCase(tc, history) {
  const v = tc.verification;
  const findings = [];

  if (!v) {
    return {
      name: tc.name,
      status: tc.status,
      verdict: 'nicht-bewertbar',
      // Not zero — unknown. Nothing about this case's history was established either.
      historieLaeufe: null,
      gegenbeweisLaeufe: null,
      findings: [
        {
          id: 'B0-keine-schritte',
          schwere: 'unbekannt',
          text: 'Der Report enthält für diesen Testfall keine Schrittdaten. Ohne Schritte lässt sich nicht sagen, ob etwas geprüft wurde.',
          massnahme: 'Report-Format prüfen — erwartet wird data.js oder ein Bericht je Testfall.',
        },
      ],
    };
  }

  const passedAssertions = v.assertionSteps.filter((a) => a.status === 'PASS');
  const isGreen = tc.status === 'PASS';

  // B1 — nothing was verified at all.
  if (isGreen && v.assertions === 0) {
    findings.push({
      id: 'B1-ohne-zusicherung',
      schwere: 'kein-beleg',
      text: `${v.stepsTotal} Schritte gelaufen, davon 0 Zusicherungen. Der Testfall hat die Anwendung bedient, aber nie gefragt, ob dabei das Richtige herauskam.`,
      massnahme: 'Mindestens eine Zusicherung ergänzen, die den fachlichen Endzustand prüft.',
    });
  }

  // B2 — every passing assertion sits downstream of a broken step (issue #191 §1).
  if (
    v.firstFailedStepIndex !== null &&
    passedAssertions.length > 0 &&
    passedAssertions.every((a) => a.index > v.firstFailedStepIndex)
  ) {
    findings.push({
      id: 'B2-zusicherung-nach-fehler',
      schwere: 'kein-beleg',
      text: `Alle ${passedAssertions.length} bestandene(n) Zusicherung(en) liegen NACH dem ersten fehlgeschlagenen Schritt (#${v.firstFailedStepIndex}). Was nach einem Abbruch noch grün wird, prüft einen Zustand, der so nie erreicht werden sollte.`,
      massnahme: 'Die fehlgeschlagenen Schritte reparieren — nicht löschen. Wer sie löscht, behält genau diese grünen Zusicherungen und hat einen Testfall, der nichts mehr beweist.',
    });
  }

  // B3 — every assertion could already have been true before anything happened (#191 §2).
  if (isGreen && v.assertions > 0 && v.assertionSteps.every((a) => a.interactionsBefore === 0)) {
    findings.push({
      id: 'B3-nur-anfangszustand',
      schwere: 'schwacher-beleg',
      text: `Alle ${v.assertions} Zusicherung(en) laufen, bevor der Testfall eine einzige Eingabe oder einen Klick gemacht hat. Sie können bereits im Ausgangszustand der Seite wahr gewesen sein.`,
      massnahme: 'Prüfen, ob die Zusicherung auch OHNE den Testfall wahr ist. Falls ja: sie hinter die Handlung verschieben, die den Zustand erzeugt.',
    });
  }

  // B4 — one assertion carrying a long workflow.
  if (isGreen && v.assertions === 1 && v.interactions >= THIN_COVERAGE_INTERACTIONS) {
    findings.push({
      id: 'B4-duenne-abdeckung',
      schwere: 'schwacher-beleg',
      text: `${v.interactions} Handlungen, aber nur 1 Zusicherung. Alles zwischen Start und dieser einen Prüfung ist ungeprüft — ein Zwischenschritt kann falsch laufen, ohne dass es auffällt.`,
      massnahme: 'Zwischenzustände zusichern, nicht nur den Schlusszustand.',
    });
  }

  // B5/B7 — the counter-evidence question, and the honest admission when it could not
  // be asked at all. One observed failure is proof enough that the assertion CAN fail;
  // what must never happen is that a history too thin to answer the question reads as
  // an answer. "Nobody looked" and "we looked and it never failed" are different states
  // and neither of them is "proven".
  const ce = counterEvidence(history, tc.name);
  if (isGreen && v.assertions > 0) {
    if (ce.runsWithFailingAssertion.length === 0) {
      if (ce.runsSeen >= 2) {
        findings.push({
          id: 'B5-ohne-gegenbeweis',
          schwere: 'ungemessen',
          text: `In ${ce.runsSeen} weiteren ausgewerteten Läufen ist keine Zusicherung dieses Testfalls jemals gescheitert. Damit ist nicht gezeigt, dass sie überhaupt scheitern KANN.`,
          massnahme: 'Einen Gegenbeweis führen: einmal absichtlich brechen (falsche Erwartung, falsche Testdaten) und beobachten, dass der Testfall rot wird.',
        });
      } else {
        findings.push({
          id: 'B7-historie-zu-duenn',
          schwere: 'ungemessen',
          text: `Die Frage „kann diese Zusicherung überhaupt scheitern?" wurde hier NICHT beantwortet: außer diesem Lauf ${ce.runsSeen === 0 ? 'liegt kein weiterer Lauf dieses Testfalls vor' : 'liegt nur 1 weiterer Lauf dieses Testfalls vor'}. Das ist kein Freispruch, sondern eine fehlende Messung.`,
          massnahme: 'Mehr Läufe desselben Testsets über --runs bereitstellen — oder den Gegenbeweis direkt führen: einmal absichtlich brechen und beobachten, dass der Testfall rot wird.',
        });
      }
    }
  }

  // A failed test case never earns a reassuring word here. Its evidence is the
  // failure itself and triage-failure.mjs owns the cause; the only thing this tool
  // has to say about it is the trap in B2 — the green that appears once somebody
  // deletes the broken steps.
  let verdict;
  if (findings.some((f) => f.schwere === 'kein-beleg')) verdict = 'kein-beleg';
  else if (!isGreen) verdict = 'nicht-bewertet';
  else if (findings.some((f) => f.schwere === 'schwacher-beleg')) verdict = 'schwacher-beleg';
  else if (findings.some((f) => f.schwere === 'ungemessen')) verdict = 'ohne-gegenbeweis';
  else verdict = 'belastbar';

  if (!isGreen && findings.length === 0) {
    findings.push({
      id: 'B6-gescheitert',
      schwere: 'nicht-bewertet',
      text: 'Gescheiterter Testfall. Die Frage „was belegt das Grün" stellt sich hier nicht.',
      massnahme: 'Die Ursache steht in der Fehlereinordnung, nicht in dieser Bewertung.',
    });
  }

  return {
    name: tc.name,
    status: tc.status,
    verdict,
    findings,
    historieLaeufe: ce.runsSeen,
    gegenbeweisLaeufe: ce.runsWithFailingAssertion.length,
  };
}

/**
 * Totals and the closing line, derived from the audited cases and nothing else.
 *
 * This is the function the tool's own bug lived in. A summary must never reach a
 * reassuring sentence by running out of complaints: a test case that could not be
 * evaluated produces no finding, and the old chain read that silence as proof. Every
 * branch below therefore states only what was actually established, and the
 * unevaluable cases are counted, named, and given the first word.
 */
function summarize(audited) {
  const count = (v) => audited.filter((c) => c.verdict === v).length;
  const totals = {
    cases: audited.length,
    passed: audited.filter((c) => c.status === 'PASS').length,
    failed: audited.filter((c) => c.status === 'FAIL').length,
    keinBeleg: count('kein-beleg'),
    schwacherBeleg: count('schwacher-beleg'),
    ohneGegenbeweis: count('ohne-gegenbeweis'),
    belastbar: count('belastbar'),
    nichtBewertbar: count('nicht-bewertbar'),
  };

  // The buckets must account for every passed case. If they ever stop adding up, the
  // totals line is hiding somebody — exactly the failure being fixed here.
  totals.bewertet =
    totals.keinBeleg + totals.schwacherBeleg + totals.ohneGegenbeweis + totals.belastbar;

  let headline;
  if (totals.nichtBewertbar > 0 && totals.bewertet === 0) {
    headline =
      `Dieser Lauf belegt nichts und widerlegt nichts: ${totals.nichtBewertbar} von ${totals.cases} Testfällen konnten NICHT BEWERTET werden. ` +
      'Das ist keine Freigabe — es ist die Auskunft, dass hier nicht gemessen werden konnte.';
  } else if (totals.keinBeleg > 0) {
    headline = `${totals.keinBeleg} Testfall/Testfälle belegen NICHTS. Ein grünes Ergebnis von ihnen ist keine Aussage über die Anwendung.`;
  } else if (totals.passed === 0 && totals.nichtBewertbar === 0) {
    headline =
      'Kein Testfall ist grün geworden — es gibt hier nichts zu belegen. Die Ursache klärt triage-failure.mjs.';
  } else if (totals.schwacherBeleg > 0) {
    headline = `Kein Testfall ist wertlos, aber ${totals.schwacherBeleg} belegen weniger, als ihr grüner Haken vermuten lässt.`;
  } else if (totals.ohneGegenbeweis > 0) {
    headline = `Alle bewerteten Zusicherungen sitzen richtig. Offen bleibt für ${totals.ohneGegenbeweis} Testfall/Testfälle, ob sie überhaupt scheitern können — das zeigt nur ein absichtlich gebrochener Lauf.`;
  } else {
    headline =
      'Jeder bewertete Testfall prüft nach der Handlung, und für jeden ist ein Scheitern in der Historie belegt.';
  }

  // Whatever the headline says, an unevaluable case is never swallowed by it.
  if (totals.nichtBewertbar > 0 && totals.bewertet > 0) {
    headline += ` Für ${totals.nichtBewertbar} weitere(n) Testfall/Testfälle gilt das ausdrücklich NICHT — sie waren nicht bewertbar.`;
  }

  return { totals, headline };
}

const VERDICT_LABEL = {
  'kein-beleg': 'KEIN BELEG',
  'schwacher-beleg': 'schwacher Beleg',
  'ohne-gegenbeweis': 'ohne Gegenbeweis',
  belastbar: 'belastbar',
  'nicht-bewertbar': 'nicht bewertbar',
};

function renderText(doc) {
  const L = [];
  L.push('');
  L.push('Beleg-Prüfung — was dieser Lauf tatsächlich belegt');
  L.push('='.repeat(66));
  L.push(`Lauf              : ${doc.runDir}`);
  L.push(`Historie          : ${doc.historyRuns} Lauf/Läufe ausgewertet`);
  L.push('');

  // The correction that matters most: the engine's own "passed steps" number.
  L.push('Die Zahl, die der Report meldet — und die Zahl, die zählt');
  L.push('-'.repeat(66));
  L.push('  INGenious zählt einen Schritt, der nur AUSGEFÜHRT wurde (DONE), als');
  L.push('  „bestanden". Geprüft hat er damit nichts. Gegenüberstellung:');
  L.push('');
  L.push(
    `  ${'Testfall'.padEnd(44)} ${'Report sagt'.padStart(11)} ${'wirklich geprüft'.padStart(17)}`,
  );
  for (const c of doc.testCases) {
    const label = c.name.length > 43 ? `${c.name.slice(0, 42)}…` : c.name;
    const reported = c.reportPassedSteps == null ? '—' : `${c.reportPassedSteps} Schritte`;
    // A test case without step data has an UNKNOWN number of assertions, not zero.
    // Printing "0 Zusicherungen" there is a default dressed up as a measurement, and it
    // reads as the strongest possible accusation when nothing at all was observed.
    const real =
      c.assertionsPassed == null
        ? '—'
        : `${c.assertionsPassed} Zusicherung${c.assertionsPassed === 1 ? '' : 'en'}`;
    L.push(`  ${label.padEnd(44)} ${reported.padStart(11)} ${real.padStart(17)}`);
  }
  L.push('');

  L.push('Bewertung je Testfall');
  L.push('-'.repeat(66));
  for (const c of doc.testCases) {
    L.push(`  ${c.name}`);
    L.push(`    Status ${c.status} · Urteil: ${VERDICT_LABEL[c.verdict] ?? c.verdict}`);
    if (c.findings.length === 0) {
      // Say what was measured, with the numbers that were measured. The old wording
      // promised "mehrfach" and "in der Historie belegt" without consulting either
      // number, and said it just as readily for a single assertion and an empty history.
      L.push(
        `    Keine Auffälligkeit: ${c.assertions} Zusicherung(en), davon mindestens eine nach`,
      );
      L.push(
        `    einer Handlung, und in der Historie ist ein Scheitern belegt (${c.gegenbeweisLaeufe} Lauf/Läufe).`,
      );
    }
    for (const f of c.findings) {
      L.push(`    [${f.id}] ${f.text}`);
      L.push(`      → ${f.massnahme}`);
    }
    L.push('');
  }

  L.push('Urteil des Laufs');
  L.push('-'.repeat(66));
  const t = doc.totals;
  L.push(`  ${t.cases} Testfälle · ${t.passed} bestanden · ${t.failed} gescheitert`);
  L.push(
    `  Bewertet (${t.bewertet}): ohne Beleg ${t.keinBeleg} · schwacher Beleg ${t.schwacherBeleg} · ohne Gegenbeweis ${t.ohneGegenbeweis} · belastbar ${t.belastbar}`,
  );
  // The line that was missing. A totals row that quietly adds up to less than the number
  // of cases above it is how "nothing to report" becomes "nothing wrong".
  if (t.nichtBewertbar > 0) {
    L.push(
      `  NICHT BEWERTBAR (${t.nichtBewertbar}): über diese Testfälle sagt diese Prüfung nichts — weder gut noch schlecht.`,
    );
  }
  L.push('');
  L.push(`  ${doc.headline}`);
  L.push('');
  L.push('  Dieses Werkzeug ändert nichts. Es bewertet Belege, es verhängt nichts.');
  L.push('');
  return L.join('\n');
}

/**
 * Counter-examples. Every one of these was a live wrong answer from this file before
 * the fix, and each asserts on the sentence a reader actually sees — not on an internal
 * flag. A summary is only as honest as its last line, so the last line is what is pinned.
 */
const SELFTEST_CASES = [
  {
    name: 'PASS ohne Schrittdaten darf nicht in die Freispruch-Zeile laufen',
    // The reported contradiction: per case "nicht bewertbar", closing line "for every
    // one a failure is on record in the history".
    audited: [{ name: 'Kampagne:Anlegen', status: 'PASS', verdict: 'nicht-bewertbar' }],
    expectTotals: { nichtBewertbar: 1, bewertet: 0, passed: 1 },
    forbidHeadlineContains: 'für jeden ist ein Scheitern in der Historie belegt',
    expectHeadlineContains: 'NICHT BEWERTET',
    expectExit: EXIT_UNMEASURABLE,
  },
  {
    name: 'ein nicht bewertbarer Testfall neben einem belastbaren wird nicht verschluckt',
    audited: [
      { name: 'A', status: 'PASS', verdict: 'belastbar' },
      { name: 'B', status: 'PASS', verdict: 'nicht-bewertbar' },
    ],
    expectTotals: { nichtBewertbar: 1, bewertet: 1, passed: 2 },
    expectHeadlineContains: 'gilt das ausdrücklich NICHT',
    expectExit: EXIT_UNMEASURABLE,
  },
  {
    name: 'ein reiner Freispruch bleibt möglich, wenn wirklich alles bewertet wurde',
    // The other direction, and it is not a formality: a tool that finds fault with every
    // run is a tool nobody reads. "belastbar" must stay reachable.
    audited: [{ name: 'A', status: 'PASS', verdict: 'belastbar' }],
    expectTotals: { nichtBewertbar: 0, bewertet: 1, passed: 1 },
    expectHeadlineContains: 'Jeder bewertete Testfall',
    expectExit: EXIT_CLEAN,
  },
  {
    name: 'ohne Historie ist der Gegenbeweis offen, nicht erbracht',
    // Before the fix a single run with a single assertion was declared "belastbar" and
    // the page read "ein Scheitern ist in der Historie belegt" over an empty history.
    audit: () =>
      auditCase(
        {
          name: 'Kampagne:Anlegen',
          status: 'PASS',
          verification: {
            stepsTotal: 2,
            assertions: 1,
            assertionsPassed: 1,
            assertionsFailed: 0,
            interactions: 1,
            firstFailedStepIndex: null,
            assertionSteps: [{ index: 1, status: 'PASS', interactionsBefore: 1 }],
          },
        },
        [],
      ),
    expectVerdict: 'ohne-gegenbeweis',
    expectFindingId: 'B7-historie-zu-duenn',
  },
  {
    name: 'ein einziger gescheiterter Lauf in der Historie genügt als Gegenbeweis',
    audit: () =>
      auditCase(
        {
          name: 'Kampagne:Anlegen',
          status: 'PASS',
          verification: {
            stepsTotal: 2,
            assertions: 1,
            assertionsPassed: 1,
            assertionsFailed: 0,
            interactions: 1,
            firstFailedStepIndex: null,
            assertionSteps: [{ index: 1, status: 'PASS', interactionsBefore: 1 }],
          },
        },
        [
          {
            basename: 'lauf-rot',
            doc: {
              testCases: [
                {
                  name: 'Kampagne:Anlegen',
                  verification: { assertionsFailed: 1 },
                },
              ],
            },
          },
        ],
      ),
    expectVerdict: 'belastbar',
    expectFindingId: null,
  },
];

function selftest() {
  const problems = [];
  for (const c of SELFTEST_CASES) {
    if (c.audit) {
      const res = c.audit();
      const gotId = res.findings.length ? res.findings[0].id : null;
      if (res.verdict !== c.expectVerdict) {
        problems.push(`${c.name}\n      expected verdict ${c.expectVerdict}, got ${res.verdict}`);
      } else if (gotId !== c.expectFindingId) {
        problems.push(`${c.name}\n      expected finding ${c.expectFindingId}, got ${gotId}`);
      } else {
        console.log(`  OK  ${c.name}\n        -> ${res.verdict} (${gotId ?? 'keine Befunde'})`);
      }
      continue;
    }

    const { totals, headline } = summarize(c.audited);
    const wrongTotal = Object.entries(c.expectTotals).find(([k, want]) => totals[k] !== want);
    const exit =
      totals.keinBeleg > 0
        ? EXIT_FINDINGS
        : totals.nichtBewertbar > 0
          ? EXIT_UNMEASURABLE
          : EXIT_CLEAN;

    if (wrongTotal) {
      problems.push(
        `${c.name}\n      totals.${wrongTotal[0]}: expected ${wrongTotal[1]}, got ${totals[wrongTotal[0]]}`,
      );
    } else if (c.forbidHeadlineContains && headline.includes(c.forbidHeadlineContains)) {
      problems.push(
        `${c.name}\n      the closing line must NOT claim "${c.forbidHeadlineContains}", but it did:\n      ${headline}`,
      );
    } else if (c.expectHeadlineContains && !headline.includes(c.expectHeadlineContains)) {
      problems.push(
        `${c.name}\n      the closing line must contain "${c.expectHeadlineContains}", got:\n      ${headline}`,
      );
    } else if (exit !== c.expectExit) {
      problems.push(`${c.name}\n      expected exit ${c.expectExit}, got ${exit}`);
    } else {
      console.log(`  OK  ${c.name}\n        -> Exit ${exit} · ${headline.slice(0, 78)}…`);
    }
  }

  if (problems.length) {
    console.error(`green-audit selftest: ROT\n  ${problems.join('\n  ')}`);
    process.exit(1);
  }
  console.log(
    `green-audit selftest: GRÜN — ${SELFTEST_CASES.length} Gegenbeispiele; "nicht bewertbar" ` +
      'schlägt bis in Schlusszeile und Exit-Code durch, und ein leerer Befund ist kein Freispruch.',
  );
  process.exit(0);
}

function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.selftest) return selftest();
  if (args.help) {
    console.log(USAGE);
    process.exit(EXIT_CLEAN);
  }
  if (!args.runDir) die(USAGE);

  const runDir = path.resolve(args.runDir);
  if (!fs.existsSync(runDir) || !fs.statSync(runDir).isDirectory()) {
    die(`Lauf-Verzeichnis nicht gefunden: ${args.runDir}`);
  }

  let current;
  try {
    current = parseRun(runDir);
  } catch (err) {
    die(`parse-report konnte den Lauf nicht auswerten: ${err.message}`);
  }

  // History for the counter-evidence question. Failure to read a sibling run is not
  // fatal: a missing history weakens the verdict, it must not block it.
  const history = [];
  for (const dir of discoverHistory(runDir, args.runs)) {
    try {
      history.push({ basename: path.basename(dir), doc: parseRun(dir) });
    } catch {
      /* unreadable run: skipped, and the history count below says so */
    }
  }

  const audited = (current.testCases || []).map((tc) => {
    const a = auditCase(tc, history);
    return {
      ...a,
      reportPassedSteps: tc.steps ? tc.steps.passed : null,
      // No step data means these numbers were not measured. `null`, not `0` — a zero
      // here is indistinguishable from "we counted, and it was none".
      assertions: tc.verification ? tc.verification.assertions : null,
      assertionsPassed: tc.verification ? tc.verification.assertionsPassed : null,
      interactions: tc.verification ? tc.verification.interactions : null,
      stepsTotal: tc.verification ? tc.verification.stepsTotal : null,
    };
  });

  const { totals, headline } = summarize(audited);

  const doc = {
    runDir,
    auditedAt: new Date().toISOString(),
    historyRuns: history.length,
    totals,
    headline,
    testCases: audited,
  };

  console.log(renderText(doc));

  if (args.json) {
    const outPath = path.resolve(args.json);
    fs.mkdirSync(path.dirname(outPath), { recursive: true });
    fs.writeFileSync(outPath, `${JSON.stringify(doc, null, 2)}\n`, 'utf8');
    console.log(`JSON geschrieben: ${outPath}\n`);
  }

  const bad = totals.keinBeleg > 0 || (args.strict && totals.schwacherBeleg > 0);
  if (bad) process.exit(EXIT_FINDINGS);
  // No findings is only an all-clear if everything could actually be looked at.
  if (totals.nichtBewertbar > 0) process.exit(EXIT_UNMEASURABLE);
  process.exit(EXIT_CLEAN);
}

main();
