#!/usr/bin/env node
/**
 * Render a self-contained HTML dashboard from a set of INGenious run directories.
 *
 * The measure is not "looks tidy". It is: somebody who does not know this tooling can
 * say within ten seconds whether there is a problem and which one. Every number on the
 * page therefore carries its explanation on the page.
 *
 * One HTML file, no server, no external request of any kind — the target devices are
 * locked down and load nothing. Node stdlib only; parse-report.mjs, green-audit.mjs and
 * triage-failure.mjs run as child processes so no report knowledge is duplicated here.
 *
 * See tools/README-render-dashboard.md.
 */

import { execFileSync, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const parseReportPath = path.join(__dirname, 'parse-report.mjs');
const greenAuditPath = path.join(__dirname, 'green-audit.mjs');
const triagePath = path.join(__dirname, 'triage-failure.mjs');
const MAX_BUFFER = 64 * 1024 * 1024;

/** Two or more status changes across the history is our flake threshold. */
const FLAKE_TRANSITIONS = 2;

function die(msg) {
  console.error(msg);
  process.exit(1);
}

function parseArgs(argv) {
  const args = { runs: null, out: 'dashboard.html', json: null };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--runs') args.runs = argv[++i];
    else if (a === '--out') args.out = argv[++i];
    else if (a === '--json') args.json = argv[++i];
  }
  return args;
}

function esc(s) {
  return String(s ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

// ---------------------------------------------------------------------------
// Reading the runs
// ---------------------------------------------------------------------------

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

/**
 * Run dirs under --runs, oldest first. Directory names carry the timestamp
 * (run-YYYYMMDD-HHMMSS), so lexical order is chronological order.
 */
function discoverRunDirs(runsPath) {
  if (looksLikeRunDir(runsPath)) return [path.resolve(runsPath)];
  let entries;
  try {
    entries = fs.readdirSync(runsPath, { withFileTypes: true });
  } catch (err) {
    die(`Verzeichnis --runs nicht lesbar: ${err.message}`);
  }
  return entries
    .filter((e) => e.isDirectory())
    .map((e) => e.name)
    .filter((name) => name.toLowerCase() !== 'latest')
    .sort()
    .map((name) => path.resolve(runsPath, name))
    .filter((dir) => looksLikeRunDir(dir));
}

function parseRun(runDir) {
  const stdout = execFileSync('node', [parseReportPath, '--run-dir', runDir], {
    encoding: 'utf8',
    maxBuffer: MAX_BUFFER,
  });
  return JSON.parse(stdout);
}

/**
 * green-audit verdicts for one run. It exits 1 when it finds something, which is not
 * an error here, so spawnSync (which does not throw on a non-zero exit) plus the JSON
 * side file. A failure to run it must not take the whole dashboard down.
 */
function greenAudit(runDir, runsPath) {
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'ing-dash-'));
  const jsonPath = path.join(tmpDir, 'green-audit.json');
  try {
    spawnSync(
      'node',
      [greenAuditPath, '--run-dir', runDir, '--runs', runsPath, '--json', jsonPath],
      { encoding: 'utf8', maxBuffer: MAX_BUFFER },
    );
    if (!fs.existsSync(jsonPath)) return null;
    return JSON.parse(fs.readFileSync(jsonPath, 'utf8'));
  } catch {
    return null;
  } finally {
    try {
      fs.rmSync(tmpDir, { recursive: true, force: true });
    } catch {
      /* a leftover temp dir is not worth failing a report over */
    }
  }
}

/** Triage categories for one run, or null when triage could not answer. */
function triage(runDir) {
  const res = spawnSync('node', [triagePath, '--run-dir', runDir], {
    encoding: 'utf8',
    maxBuffer: MAX_BUFFER,
  });
  if (!res.stdout) return null;
  try {
    return JSON.parse(res.stdout);
  } catch {
    return null;
  }
}

// ---------------------------------------------------------------------------
// Deriving what a human actually wants to know
// ---------------------------------------------------------------------------

/**
 * Per test case: its status in every run, how long the current state has held, and
 * whether it keeps switching.
 *
 * "Since when" is the question the existing tooling never answered: a tester looking at
 * a red run cannot tell whether it broke tonight or has been standing for a week, and
 * those two demand completely different reactions.
 */
function buildTimelines(runs) {
  const names = [];
  for (const r of runs) {
    for (const tc of r.doc.testCases || []) {
      if (!names.includes(tc.name)) names.push(tc.name);
    }
  }

  return names.map((name) => {
    const points = runs.map((r) => {
      const tc = (r.doc.testCases || []).find((c) => c.name === name);
      return {
        run: r.basename,
        status: tc ? tc.status : 'absent',
        durationSeconds: tc ? tc.durationSeconds : null,
        verification: tc ? tc.verification : null,
      };
    });

    const seen = points.filter((p) => p.status === 'PASS' || p.status === 'FAIL');
    let transitions = 0;
    for (let i = 1; i < seen.length; i++) {
      if (seen[i].status !== seen[i - 1].status) transitions += 1;
    }

    const current = points[points.length - 1];

    // Walk back while the status stays the same: that is the current streak.
    let streak = 0;
    let streakStart = current.run;
    for (let i = points.length - 1; i >= 0; i--) {
      if (points[i].status !== current.status) break;
      streak += 1;
      streakStart = points[i].run;
    }

    return {
      name,
      points,
      transitions,
      flaky: transitions >= FLAKE_TRANSITIONS,
      current: current.status,
      streak,
      streakStart,
      isNew: streak === 1 && points.length > 1,
    };
  });
}

function sinceLabel(t) {
  if (t.current !== 'FAIL') return '—';
  if (t.points.length === 1) return 'erster Lauf';
  if (t.isNew) return 'neu in diesem Lauf';
  return `seit ${t.streak} Läufen (${t.streakStart})`;
}

// ---------------------------------------------------------------------------
// Rendering
// ---------------------------------------------------------------------------

const STATUS_ICON = { PASS: '✓', FAIL: '✗', unknown: '?', absent: '·' };
const STATUS_WORD = { PASS: 'bestanden', FAIL: 'gescheitert', unknown: 'unbekannt', absent: 'nicht gelaufen' };

const VERDICT_TEXT = {
  'kein-beleg': ['kein Beleg', 'critical'],
  'schwacher-beleg': ['schwacher Beleg', 'serious'],
  'ohne-gegenbeweis': ['ohne Gegenbeweis', 'warning'],
  belastbar: ['belastbar', 'good'],
  'nicht-bewertet': ['nicht bewertet', 'muted'],
  'nicht-bewertbar': ['nicht bewertbar', 'muted'],
};

function statusChip(status) {
  const tone = status === 'PASS' ? 'good' : status === 'FAIL' ? 'critical' : 'muted';
  return `<span class="chip chip-${tone}"><span class="chip-icon">${STATUS_ICON[status] ?? '?'}</span>${esc(
    STATUS_WORD[status] ?? status,
  )}</span>`;
}

function verdictChip(verdict) {
  const [label, tone] = VERDICT_TEXT[verdict] ?? [verdict, 'muted'];
  const icon = tone === 'good' ? '✓' : tone === 'muted' ? '·' : '!';
  return `<span class="chip chip-${tone}"><span class="chip-icon">${icon}</span>${esc(label)}</span>`;
}

/** One square per run: colour AND glyph, so colour never carries the meaning alone. */
function timelineStrip(t) {
  return t.points
    .map((p) => {
      const tone = p.status === 'PASS' ? 'good' : p.status === 'FAIL' ? 'critical' : 'muted';
      return `<span class="sq sq-${tone}" title="${esc(p.run)}: ${esc(
        STATUS_WORD[p.status] ?? p.status,
      )}">${STATUS_ICON[p.status] ?? '?'}</span>`;
    })
    .join('');
}

function trendChart(runs) {
  const max = Math.max(1, ...runs.map((r) => (r.doc.totals || {}).cases || 0));
  const bars = runs
    .map((r) => {
      const t = r.doc.totals || { cases: 0, passed: 0, failed: 0 };
      const h = (n) => `${(n / max) * 100}%`;
      const label = r.basename.replace(/^run-/, '');
      return `<div class="bar-col">
      <div class="bar-stack" role="img" aria-label="${esc(label)}: ${t.passed} bestanden, ${t.failed} gescheitert">
        ${t.failed > 0 ? `<div class="bar bar-critical" style="height:${h(t.failed)}"><span class="bar-num">${t.failed}</span></div>` : ''}
        ${t.passed > 0 ? `<div class="bar bar-good" style="height:${h(t.passed)}"><span class="bar-num">${t.passed}</span></div>` : ''}
      </div>
      <div class="bar-label">${esc(label)}</div>
    </div>`;
    })
    .join('');
  return `<div class="bars">${bars}</div>`;
}

function causeChart(categories) {
  const entries = Object.entries(categories).sort((a, b) => b[1] - a[1]);
  if (entries.length === 0) return null;
  const max = Math.max(...entries.map(([, n]) => n));
  const rows = entries
    .map(
      ([name, n]) => `<div class="hbar-row">
      <div class="hbar-label">${esc(name)}</div>
      <div class="hbar-track"><div class="hbar" style="width:${(n / max) * 100}%"></div></div>
      <div class="hbar-num">${n}</div>
    </div>`,
    )
    .join('');
  return `<div class="hbars">${rows}</div>`;
}

/**
 * Everything the page's headline is made of, computed once.
 *
 * Extracted out of renderHtml because a second reader appeared: the Studio button
 * "Testlauf ansehen" writes this out with --json and repeats the banner verbatim on the
 * panel. A caller who re-derived the sentence from the numbers would eventually disagree
 * with the page it just produced, and a tester would be looking at two answers.
 */
function summarise(model) {
  const { timelines, latest, audit } = model;

  const latestTotals = latest ? latest.doc.totals || {} : { cases: 0, passed: 0, failed: 0 };
  const failing = timelines.filter((t) => t.current === 'FAIL');
  const newFailing = failing.filter((t) => t.isNew);
  const flaky = timelines.filter((t) => t.flaky);

  const auditCases = audit ? audit.testCases || [] : [];
  const green = auditCases.filter((c) => c.status === 'PASS');
  const solid = green.filter((c) => c.verdict === 'belastbar');
  const worthless = green.filter((c) => c.verdict === 'kein-beleg');
  const weak = green.filter(
    (c) => c.verdict === 'schwacher-beleg' || c.verdict === 'ohne-gegenbeweis',
  );

  // The headline: the single sentence the page exists to deliver.
  let bannerTone = 'good';
  let bannerTitle = 'Kein Handlungsbedarf';
  let bannerText = 'Alle Testfälle sind bestanden, und jedes Grün ist belegt.';
  if (failing.length > 0) {
    bannerTone = 'critical';
    bannerTitle = `${failing.length} von ${latestTotals.cases} Testfällen sind rot`;
    bannerText =
      newFailing.length > 0
        ? `${newFailing.length} davon ${newFailing.length === 1 ? 'ist' : 'sind'} in diesem Lauf neu dazugekommen — dort zuerst hinsehen.`
        : 'Keiner davon ist neu; sie stehen schon länger. Das ist kein frischer Einbruch, sondern eine offene Baustelle.';
  } else if (worthless.length > 0) {
    bannerTone = 'critical';
    bannerTitle = `Alles grün — aber ${worthless.length} Testfall/Testfälle belegen nichts`;
    bannerText =
      'Diese Testfälle sind bestanden, ohne dass geprüft wurde, ob die Anwendung das Richtige getan hat. Ein falsches Grün ist gefährlicher als ein Rot.';
  } else if (weak.length > 0) {
    bannerTone = 'warning';
    bannerTitle = `Alles grün — ${weak.length} von ${green.length} Testfällen belegen aber weniger, als sie aussehen`;
    bannerText =
      'Nichts ist kaputt. Die Aussagekraft dieser grünen Haken ist jedoch geringer, als die Zahl im Report vermuten lässt.';
  } else if (!audit) {
    bannerTone = 'warning';
    bannerTitle = 'Alle Testfälle bestanden';
    bannerText = 'Der Beweiswert konnte nicht ermittelt werden — green-audit.mjs hat nicht geantwortet.';
  }

  return {
    latestTotals, failing, newFailing, flaky,
    auditCases, green, solid, worthless, weak,
    bannerTone, bannerTitle, bannerText,
  };
}

/** The receipt beside the page — the same answer, for a caller that is not a pair of eyes. */
function receipt(model, s) {
  return {
    generatedAt: model.generatedAt,
    runsPath: model.runsPath,
    latestRun: model.latest ? model.latest.basename : null,
    banner: { tone: s.bannerTone, title: s.bannerTitle, text: s.bannerText },
    totals: {
      runs: model.runs.length,
      testCases: model.timelines.length,
      cases: s.latestTotals.cases ?? 0,
      passed: s.latestTotals.passed ?? 0,
      failed: s.latestTotals.failed ?? 0,
      failing: s.failing.length,
      newFailing: s.newFailing.length,
      flaky: s.flaky.length,
      green: s.green.length,
      solid: s.solid.length,
      weak: s.weak.length,
      worthless: s.worthless.length,
    },
    // Named, not counted: "3 rot" sends nobody anywhere, "Fall X, seit 4 Läufen" does.
    failing: s.failing.map((t) => ({ name: t.name, since: sinceLabel(t) })),
    causes: model.causes,
    auditAnswered: Boolean(model.audit),
  };
}

function renderHtml(model) {
  const {
    runs, timelines, latest, audit, triageDoc, causes, generatedAt, runsPath,
  } = model;

  const {
    latestTotals, failing, newFailing, flaky,
    auditCases, green, solid, worthless, weak,
    bannerTone, bannerTitle, bannerText,
  } = summarise(model);

  const tiles = [
    {
      label: 'Letzter Lauf',
      value: `${latestTotals.passed ?? 0} / ${latestTotals.cases ?? 0}`,
      unit: 'bestanden',
      tone: (latestTotals.failed ?? 0) > 0 ? 'critical' : 'good',
      hint: latest ? `Lauf ${latest.basename}` : 'kein Lauf gefunden',
    },
    {
      label: 'Beweiswert der grünen',
      value: !audit || green.length === 0 ? '—' : `${solid.length} / ${green.length}`,
      unit: green.length === 0 ? 'kein grüner Testfall' : 'belastbar',
      tone:
        green.length === 0
          ? 'muted'
          : worthless.length > 0
            ? 'critical'
            : weak.length > 0
              ? 'warning'
              : 'good',
      hint:
        green.length === 0
          ? 'Es ist nichts grün geworden, also gibt es hier nichts zu bewerten.'
          : 'Ein Testfall ist belastbar, wenn er nach der Handlung prüft und in der Historie schon einmal gescheitert ist.',
    },
    {
      label: 'Instabil',
      value: String(flaky.length),
      unit: flaky.length === 1 ? 'Testfall' : 'Testfälle',
      tone: flaky.length > 0 ? 'warning' : 'good',
      hint: `Der Status wechselt über die Läufe hin und her (ab ${FLAKE_TRANSITIONS} Wechseln).`,
    },
    {
      label: 'Blockiert gerade',
      value: String(failing.length),
      unit: failing.length === 1 ? 'Testfall' : 'Testfälle',
      tone: failing.length > 0 ? 'critical' : 'good',
      hint:
        newFailing.length > 0
          ? `${newFailing.length} davon neu in diesem Lauf.`
          : 'Nichts davon ist in diesem Lauf neu dazugekommen.',
    },
  ];

  const tileHtml = tiles
    .map(
      (t) => `<div class="tile">
      <div class="tile-label">${esc(t.label)}</div>
      <div class="tile-value tone-${t.tone}">${esc(t.value)} <span class="tile-unit">${esc(t.unit)}</span></div>
      <div class="tile-hint">${esc(t.hint)}</div>
    </div>`,
    )
    .join('');

  const categoryByCase = new Map();
  if (triageDoc && Array.isArray(triageDoc.findings)) {
    for (const f of triageDoc.findings) categoryByCase.set(f.testCase, f);
  }

  const auditByCase = new Map(auditCases.map((c) => [c.name, c]));

  const rows = timelines
    .map((t) => {
      const finding = categoryByCase.get(t.name);
      const a = auditByCase.get(t.name);
      const cause = finding
        ? `<span class="cause">${esc(finding.category)}</span><div class="cause-note">${esc(
            (finding.erklaerung || '').split('.')[0],
          )}.</div>`
        : t.current === 'FAIL'
          ? '<span class="muted">nicht ermittelt</span>'
          : '<span class="muted">—</span>';
      // The correction that carries the whole page: the engine's step count next to
      // the number of steps that actually verified something.
      const v = t.points[t.points.length - 1].verification;
      const checked = v
        ? `<span class="checked-num">${v.assertionsPassed}</span> von ${v.stepsTotal} Schritten`
        : '<span class="muted">—</span>';
      return `<tr>
      <td class="name">${esc(t.name)}</td>
      <td class="strip">${timelineStrip(t)}</td>
      <td>${statusChip(t.current)}</td>
      <td class="checked">${checked}</td>
      <td class="since">${esc(sinceLabel(t))}</td>
      <td>${cause}</td>
      <td>${a ? verdictChip(a.verdict) : '<span class="muted">—</span>'}</td>
    </tr>`;
    })
    .join('');

  const causeHtml = causeChart(causes);

  // Everything green-audit found, in full, under the table — a verdict word without
  // its reason is exactly the "category without a next step" this page must not produce.
  const findingsHtml = auditCases
    .filter((c) => (c.findings || []).length > 0 && c.verdict !== 'nicht-bewertet')
    .map(
      (c) => `<div class="finding">
      <div class="finding-head">${esc(c.name)} ${verdictChip(c.verdict)}</div>
      ${(c.findings || [])
        .map(
          (f) => `<div class="finding-body">
        <div class="finding-text">${esc(f.text)}</div>
        <div class="finding-action"><span class="arrow">→</span> ${esc(f.massnahme)}</div>
      </div>`,
        )
        .join('')}
    </div>`,
    )
    .join('');

  return `<!DOCTYPE html>
<html lang="de">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Testlauf-Übersicht</title>
<style>
  :root {
    color-scheme: light;
    --surface: #fcfcfb;
    --plane: #f9f9f7;
    --ink: #0b0b0b;
    --ink-2: #52514e;
    --muted: #898781;
    --grid: #e1e0d9;
    --ring: rgba(11,11,11,0.10);
    --good: #0ca30c;
    --warning: #fab219;
    --serious: #ec835a;
    --critical: #d03b3b;
    --bar: #2a78d6;
    --wash-good: rgba(12,163,12,0.10);
    --wash-warning: rgba(250,178,25,0.16);
    --wash-serious: rgba(236,131,90,0.16);
    --wash-critical: rgba(208,59,59,0.10);
  }
  @media (prefers-color-scheme: dark) {
    :root:where(:not([data-theme="light"])) {
      color-scheme: dark;
      --surface: #1a1a19;
      --plane: #0d0d0d;
      --ink: #ffffff;
      --ink-2: #c3c2b7;
      --muted: #898781;
      --grid: #2c2c2a;
      --ring: rgba(255,255,255,0.10);
      --bar: #3987e5;
    }
  }
  :root[data-theme="dark"] {
    color-scheme: dark;
    --surface: #1a1a19;
    --plane: #0d0d0d;
    --ink: #ffffff;
    --ink-2: #c3c2b7;
    --grid: #2c2c2a;
    --ring: rgba(255,255,255,0.10);
    --bar: #3987e5;
  }
  * { box-sizing: border-box; }
  body {
    margin: 0;
    padding: 1.5rem 1.25rem 3rem;
    background: var(--plane);
    color: var(--ink);
    font-family: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
    font-size: 15px;
    line-height: 1.5;
  }
  .wrap { max-width: 1080px; margin: 0 auto; }
  h1 { font-size: 1.15rem; margin: 0 0 0.15rem; font-weight: 600; }
  h2 { font-size: 0.95rem; margin: 2rem 0 0.15rem; font-weight: 600; }
  .sub { color: var(--ink-2); font-size: 0.85rem; margin: 0 0 1.25rem; }
  .section-note { color: var(--ink-2); font-size: 0.82rem; margin: 0 0 0.75rem; }

  .banner {
    border: 1px solid var(--ring);
    border-left: 5px solid var(--muted);
    background: var(--surface);
    border-radius: 10px;
    padding: 1rem 1.15rem;
    margin-bottom: 1.25rem;
  }
  .banner-good { border-left-color: var(--good); background: var(--wash-good); }
  .banner-warning { border-left-color: var(--warning); background: var(--wash-warning); }
  .banner-critical { border-left-color: var(--critical); background: var(--wash-critical); }
  .banner-title { font-size: 1.3rem; font-weight: 600; line-height: 1.25; }
  .banner-text { color: var(--ink-2); margin-top: 0.3rem; }

  .tiles { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 0.85rem; }
  .tile { background: var(--surface); border: 1px solid var(--ring); border-radius: 10px; padding: 0.85rem 1rem; }
  .tile-label { font-size: 0.75rem; text-transform: uppercase; letter-spacing: 0.04em; color: var(--muted); font-weight: 600; }
  .tile-value { font-size: 1.85rem; font-weight: 600; line-height: 1.2; margin-top: 0.15rem; }
  .tile-unit { font-size: 0.8rem; font-weight: 500; color: var(--ink-2); }
  .tile-hint { font-size: 0.78rem; color: var(--ink-2); margin-top: 0.3rem; }
  .tone-muted { color: var(--muted); }
  .tone-good { color: var(--good); }
  .tone-warning { color: var(--warning); }
  .tone-critical { color: var(--critical); }

  .panel { background: var(--surface); border: 1px solid var(--ring); border-radius: 10px; padding: 1rem 1.15rem; overflow-x: auto; }

  .bars { display: flex; align-items: flex-end; justify-content: flex-start; gap: 10px; height: 150px; padding-top: 0.5rem; }
  .bar-col { display: flex; flex-direction: column; justify-content: flex-end; height: 100%; width: 92px; flex: 0 0 auto; }
  .bar-stack { display: flex; flex-direction: column-reverse; justify-content: flex-start; height: 118px; gap: 2px; }
  .bar { border-radius: 0 0 2px 2px; position: relative; min-height: 20px; display: flex; align-items: center; justify-content: center; }
  .bar:last-child { border-radius: 4px 4px 2px 2px; }
  .bar-good { background: var(--good); }
  .bar-critical { background: var(--critical); }
  .bar-num { color: #fff; font-size: 0.8rem; font-weight: 600; font-variant-numeric: tabular-nums; }
  .bar-label { font-size: 0.7rem; color: var(--muted); text-align: center; margin-top: 0.4rem; font-variant-numeric: tabular-nums; }

  .hbars { display: grid; gap: 0.4rem; }
  .hbar-row { display: grid; grid-template-columns: 150px 1fr 40px; align-items: center; gap: 0.6rem; }
  .hbar-label { font-size: 0.85rem; color: var(--ink-2); }
  .hbar-track { background: var(--grid); border-radius: 4px; height: 18px; }
  .hbar { background: var(--bar); height: 100%; border-radius: 2px 4px 4px 2px; min-width: 3px; }
  .hbar-num { font-size: 0.85rem; font-variant-numeric: tabular-nums; text-align: right; color: var(--ink-2); }

  table { width: 100%; border-collapse: collapse; font-size: 0.88rem; }
  th, td { text-align: left; padding: 0.55rem 0.6rem; border-bottom: 1px solid var(--grid); vertical-align: top; }
  th { font-size: 0.7rem; text-transform: uppercase; letter-spacing: 0.04em; color: var(--muted); font-weight: 600; }
  tr:last-child td { border-bottom: none; }
  td.name { font-weight: 500; max-width: 260px; }
  td.since { font-variant-numeric: tabular-nums; color: var(--ink-2); white-space: nowrap; }
  td.checked { font-variant-numeric: tabular-nums; color: var(--ink-2); white-space: nowrap; }
  .checked-num { font-weight: 700; color: var(--ink); font-size: 1.05rem; }
  .muted { color: var(--muted); }
  .cause { font-weight: 600; }
  .cause-note { color: var(--ink-2); font-size: 0.8rem; margin-top: 0.1rem; max-width: 320px; }

  .chip { display: inline-flex; align-items: center; gap: 0.3rem; padding: 0.1rem 0.5rem 0.1rem 0.35rem; border-radius: 999px; font-size: 0.78rem; font-weight: 600; white-space: nowrap; }
  .chip-icon { font-weight: 700; }
  .chip-good { color: var(--good); background: var(--wash-good); }
  .chip-warning { color: #8a6100; background: var(--wash-warning); }
  .chip-serious { color: #a24a22; background: var(--wash-serious); }
  .chip-critical { color: var(--critical); background: var(--wash-critical); }
  .chip-muted { color: var(--muted); background: var(--grid); }
  @media (prefers-color-scheme: dark) {
    :root:where(:not([data-theme="light"])) .chip-warning { color: var(--warning); }
    :root:where(:not([data-theme="light"])) .chip-serious { color: var(--serious); }
  }

  .strip { white-space: nowrap; }
  .sq { display: inline-flex; align-items: center; justify-content: center; width: 20px; height: 20px; border-radius: 4px; margin-right: 2px; font-size: 0.72rem; font-weight: 700; color: #fff; }
  .sq-good { background: var(--good); }
  .sq-critical { background: var(--critical); }
  .sq-muted { background: var(--muted); }

  .finding { border: 1px solid var(--ring); border-radius: 8px; padding: 0.7rem 0.9rem; margin-bottom: 0.6rem; background: var(--surface); }
  .finding-head { font-weight: 600; display: flex; gap: 0.5rem; align-items: center; flex-wrap: wrap; margin-bottom: 0.35rem; }
  .finding-body { margin-top: 0.35rem; }
  .finding-text { color: var(--ink-2); font-size: 0.86rem; }
  .finding-action { font-size: 0.86rem; margin-top: 0.15rem; }
  .arrow { color: var(--muted); }

  .legend { font-size: 0.82rem; color: var(--ink-2); }
  .legend dt { font-weight: 600; color: var(--ink); margin-top: 0.5rem; }
  .legend dd { margin: 0.1rem 0 0; }
  footer { margin-top: 2rem; font-size: 0.76rem; color: var(--muted); }
</style>
</head>
<body>
<div class="wrap">

  <h1>Testlauf-Übersicht</h1>
  <p class="sub">${esc(runsPath)} · ${runs.length} ${runs.length === 1 ? 'Lauf' : 'Läufe'} · erstellt ${esc(generatedAt)}</p>

  <div class="banner banner-${bannerTone}">
    <div class="banner-title">${esc(bannerTitle)}</div>
    <div class="banner-text">${esc(bannerText)}</div>
  </div>

  <div class="tiles">${tileHtml}</div>

  <h2>Verlauf über die Läufe</h2>
  <p class="section-note">Je Lauf ein Balken: unten grün die bestandenen, oben rot die gescheiterten Testfälle. Ältester Lauf links.</p>
  <div class="panel">${trendChart(runs)}</div>

  <h2>Testfälle im Einzelnen</h2>
  <p class="section-note">Ein Kästchen je Lauf, ältester links. <strong>Wirklich geprüft</strong> zählt nur die Schritte, die etwas zugesichert haben — INGenious zählt auch bloß ausgeführte Schritte als „bestanden". <strong>Seit wann</strong> entscheidet über die Dringlichkeit: frisch eingebrochen oder alte Baustelle.</p>
  <div class="panel">
    <table>
      <thead><tr>
        <th>Testfall</th><th>Verlauf</th><th>Jetzt</th><th>Wirklich geprüft</th><th>Seit wann rot</th><th>Ursache</th><th>Beweiswert</th>
      </tr></thead>
      <tbody>${rows || '<tr><td colspan="7">Keine Testfälle gefunden.</td></tr>'}</tbody>
    </table>
  </div>

${
  causeHtml
    ? `  <h2>Ursachenverteilung</h2>
  <p class="section-note">Wie oft welche Ursache im letzten Lauf gefunden wurde — regelbasiert aus Report und Trace, ohne Raten.</p>
  <div class="panel">${causeHtml}</div>
`
    : ''
}
${
  findingsHtml
    ? `  <h2>Was die grünen Haken wert sind</h2>
  <p class="section-note">INGenious zählt jeden ausgeführten Schritt als „bestanden" — auch einen, der nichts geprüft hat. Hier steht, was tatsächlich geprüft wurde und was daraus folgt.</p>
${findingsHtml}
`
    : ''
}

  <h2>Was die Begriffe bedeuten</h2>
  <div class="panel legend">
    <dl>
      <dt>bestanden / gescheitert</dt>
      <dd>Das Urteil der Engine über den ganzen Testfall.</dd>
      <dt>Beweiswert</dt>
      <dd><strong>belastbar</strong> = geprüft wird nach der Handlung, und in der Historie ist belegt, dass die Prüfung scheitern kann.
      <strong>schwacher Beleg</strong> = geprüft wird, aber zu wenig oder an der falschen Stelle.
      <strong>ohne Gegenbeweis</strong> = die Prüfung ist nie gescheitert; dass sie überhaupt scheitern kann, ist damit nicht gezeigt.
      <strong>kein Beleg</strong> = das Grün sagt nichts über die Anwendung aus.</dd>
      <dt>instabil</dt>
      <dd>Der Testfall wechselt über die Läufe zwischen bestanden und gescheitert. Das kann Timing, Testdaten oder ein echter sporadischer Fehler sein — die Ursache sagt diese Zahl nicht.</dd>
      <dt>Wirklich geprüft</dt>
      <dd>Wie viele Schritte des Testfalls eine Zusicherung waren, die bestanden hat — gegenüber allen ausgeführten Schritten. Ein Schritt, der nur geklickt oder getippt hat, gilt im Report als „bestanden", hat aber nichts geprüft. „1 von 16 Schritten" heißt: fünfzehn Handlungen, eine Prüfung.</dd>
      <dt>Seit wann rot</dt>
      <dd>Wie viele aufeinanderfolgende Läufe dieser Testfall schon rot ist. „Neu in diesem Lauf" heißt: davor war er grün.</dd>
      <dt>Ursache</dt>
      <dd>Kategorie aus der regelbasierten Fehlereinordnung des letzten Laufs. Ohne Eintrag konnte keine Regel greifen — dann ist es kein „unbekannt", sondern ein „nicht ermittelt".</dd>
    </dl>
  </div>

  <footer>
    Eine einzelne HTML-Datei. Kein Server, keine Nachladung, kein Netzzugriff.
    Erzeugt von tools/render-dashboard.mjs aus den Berichten der oben genannten Läufe.
  </footer>
</div>
</body>
</html>
`;
}

// ---------------------------------------------------------------------------

function main() {
  const args = parseArgs(process.argv.slice(2));
  if (!args.runs) {
    die('Aufruf: node tools/render-dashboard.mjs --runs <verzeichnis> [--out dashboard.html] '
      + '[--json uebersicht.json]');
  }

  const runsPath = path.resolve(args.runs);
  if (!fs.existsSync(runsPath) || !fs.statSync(runsPath).isDirectory()) {
    die(`--runs ist kein Verzeichnis: ${runsPath}`);
  }
  const outPath = path.resolve(args.out);

  const runs = [];
  for (const runDir of discoverRunDirs(runsPath)) {
    const basename = path.basename(runDir);
    try {
      runs.push({ basename, runDir, doc: parseRun(runDir) });
    } catch (err) {
      console.error(`Übersprungen: ${basename}: ${err.message || err}`);
    }
  }

  const latest = runs.length > 0 ? runs[runs.length - 1] : null;
  const timelines = buildTimelines(runs);

  const audit = latest ? greenAudit(latest.runDir, runsPath) : null;
  const triageDoc =
    latest && (latest.doc.totals || {}).failed > 0 ? triage(latest.runDir) : null;

  const causes = {};
  if (triageDoc && Array.isArray(triageDoc.findings)) {
    for (const f of triageDoc.findings) {
      causes[f.category] = (causes[f.category] || 0) + 1;
    }
  }

  const model = {
    runs,
    timelines,
    latest,
    audit,
    triageDoc,
    causes,
    runsPath,
    generatedAt: new Date().toLocaleString('de-DE'),
  };
  const html = renderHtml(model);

  fs.mkdirSync(path.dirname(outPath), { recursive: true });
  fs.writeFileSync(outPath, html, 'utf8');

  if (args.json) {
    const jsonPath = path.resolve(args.json);
    fs.mkdirSync(path.dirname(jsonPath), { recursive: true });
    fs.writeFileSync(
      jsonPath,
      `${JSON.stringify(receipt(model, summarise(model)), null, 2)}\n`,
      'utf8',
    );
    console.log(`Geschrieben: ${jsonPath}`);
  }

  const failing = timelines.filter((t) => t.current === 'FAIL');
  console.log(`Geschrieben: ${outPath}`);
  console.log(
    `Läufe: ${runs.length}  Testfälle: ${timelines.length}  jetzt rot: ${failing.length}  instabil: ${
      timelines.filter((t) => t.flaky).length
    }`,
  );
  if (!audit) {
    console.log('Hinweis: green-audit.mjs hat nicht geantwortet — die Beweiswert-Spalte bleibt leer.');
  }
  if (latest && (latest.doc.totals || {}).failed > 0 && !triageDoc) {
    console.log('Hinweis: triage-failure.mjs hat nicht geantwortet — die Ursachen-Spalte bleibt leer.');
  }
}

main();
