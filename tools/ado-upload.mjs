// ado-upload.mjs — THE TRIGGER for the ADO evidence upload.
//
// ado-automark.mjs already does the whole ADO lifecycle (run, outcome, .docx,
// --attach evidence, comment, work-item link) and is proven against the real org.
// What was missing was anything that CALLS it when a tester finishes a run — so a
// tester got nothing unless somebody remembered the CLI. This file is that caller:
// end of run -> resolve the ADO id -> collect the evidence set -> invoke
// ado-automark -> leave a receipt a human can find.
//
// Three deliberate design rules, each of them a lesson from an earlier attempt:
//
//   1. DEFAULT ON. The old path was opt-in behind COMPANION_ADO_MARK=1, which is
//      exactly how a working feature came to look broken. Here the upload runs
//      unless ING_ADO_UPLOAD is explicitly switched off.
//   2. THE STATE IS ALWAYS VISIBLE. Every invocation prints its enabled state and
//      writes it into the receipt, so "it is off" and "it failed" can never be
//      confused with "it silently did nothing".
//   3. A FAILED UPLOAD NEVER FAILS THE RUN. With --hook the exit code is always 0
//      and nothing throws; the outcome travels in the receipt and in the last
//      stdout line instead. Invisible success and invisible failure are equally
//      damaging, so both are reported the same way, loudly.
//
// THE IMAGE DOCUMENT. Attachments are capped per FILE, and a real run produces
// fourteen screenshots against four rationed slots — so ten pictures used to stay on
// the tester's disk no matter how good the rationing got.
// The fix changes the unit rather than the cap: one document holds every
// screenshot and counts as one attachment. Before the evidence is picked, this file
// calls tools/evidence-doc.mjs to build it; the screenshots inside it are then recorded
// as COVERED rather than skipped, because a picture in an attached document is not a
// picture a tester lost. Off with --no-doc or ING_EVIDENCE_DOC=0.
//
// Usage:
//   node ado-upload.mjs --test-case 12345 [--evidence <folder>] [--comment "..."]
//   node ado-upload.mjs --test-case-name "12345 - Beispielfall" --hook
//   node ado-upload.mjs --state          # just print whether it is on, and why
//   node ado-upload.mjs --selftest       # offline, no az, no network
//   node ado-upload.mjs ... --no-doc     # do not build the image document
//
// The last stdout line is always machine-readable, for the Java caller:
//   ADO-UPLOAD OK|FEHLER|AUS|UEBERSPRUNGEN <one-line German message>
//
// Comment policy: by default the comment is the ADO test case id and NOTHING else.
// The execution record lives in a production system; an account number or any other
// customer detail only travels when a caller passes --comment explicitly.
//
// EIN ROTER LAUF LÄDT SEINE BELEGE TROTZDEM HOCH — UND SETZT KEIN ERGEBNIS (#259).
// Bis zum 07.08.2026 wurde ein Lauf mit --outcome failed komplett abgewiesen; Video, Trace und
// Berichte erreichten Azure DevOps nie, obwohl die Person den Testfall von Hand durchgeführt und
// bestanden gemeldet hatte. Jetzt geht ein solcher Lauf auf den Beleg-Pfad: ado-automark
// --nur-belege hängt die Dateien am Arbeitselement an und kommentiert sie, stellt aber keinen
// einzigen Request an /test/runs — es gibt dort also nichts, was ein Ergebnis setzen könnte.
// Der Grundsatz "ado-automark markiert ausschliesslich Bestanden" gilt für ERGEBNISSE
// unverändert; das manuell gemeldete Passed bleibt stehen. Jeder Beleg-Upload nennt dazu im
// Kommentar Zeitstempel und Ausgang seines Laufs (laufNotiz), damit nie wieder offen ist, aus
// welcher Durchführung ein Video stammt.
import { spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, readdirSync, readFileSync, realpathSync, rmSync, statSync, writeFileSync, appendFileSync } from 'node:fs';
import { dirname, join, basename, isAbsolute, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { homedir } from 'node:os';
import { readDocSidecar } from './evidence-doc.mjs';

const __dirname = dirname(fileURLToPath(import.meta.url));
const AUTOMARK = join(__dirname, 'ado-automark.mjs');
const EVIDENCE_DOC = join(__dirname, 'evidence-doc.mjs');

/** Attach at most this many files, so a 200-screenshot run does not flood the result. */
const DEFAULT_MAX_FILES = 12;
/** ado-automark caps each file at 20MB; this caps the whole upload. */
const MAX_TOTAL_BYTES = 40 * 1024 * 1024;
/**
 * Mirror of MAX_ATTACH_BYTES in ado-automark.mjs. Known here so a file that the
 * uploader cannot attach anyway is never given a slot and never charged against the
 * total: a 50 MB trace used to consume the whole budget, be reported as "Gesamtgrenze
 * 40 MB", and then be dropped a second time downstream with a different reason. The
 * skip is the same either way — the REASON a human reads has to be the true one.
 *
 * For the record, since the number is the interesting part: ADO's own ceilings are far
 * higher — 60 MB per work-item attachment and ~100 MB per test-result attachment. 20 MB
 * is OUR limit, and it is the binding one. Raising it is a deliberate decision and not
 * this file's to take, because ado-automark base64-encodes the whole file into a single
 * JSON body (52.8 MB of trace becomes ~70 MB on the wire).
 */
const MAX_ATTACH_BYTES = 20 * 1024 * 1024;
/**
 * How many screenshots may take a slot. The other evidence — reports, log, console,
 * manifest, trace, video — is unique: lose one and that view of the run is simply gone.
 * Screenshots are the opposite; eighteen of them mostly show the same page, so past a
 * handful each further one costs a slot and adds nothing.
 */
const DEFAULT_MAX_SCREENSHOTS = 4;
/**
 * ado-automark allows an interactive `az login` five minutes, so this has to outlast it —
 * but that only ever applies to a human at a command line. Since the guard landed
 * a context that declares
 * itself non-interactive — ADO_NONINTERACTIVE=1, TF_BUILD, CI — refuses the login and
 * answers in about a second with an instruction. Studio sets ADO_NONINTERACTIVE=1 because
 * it opens the sign-in itself, in a window the tester can see (AdoSignIn); a
 * pipeline agent sets ADO_BEARER. So the six minutes are the ceiling for the interactive
 * case and nothing else waits them out.
 */
const AUTOMARK_TIMEOUT_MS = 6 * 60 * 1000;

/* ------------------------------------------------------------------ state ---- */

/**
 * Whether the upload runs, and the sentence explaining it. Default is ON: the
 * whole point of this tool is that an opt-in flag is indistinguishable from a bug.
 */
export function uploadState(env = process.env) {
  const raw = (env.ING_ADO_UPLOAD ?? '').trim().toLowerCase();
  if (raw === '0' || raw === 'off' || raw === 'false' || raw === 'nein') {
    return { enabled: false, reason: 'ING_ADO_UPLOAD=' + raw, text: 'ADO-Upload AUS (ING_ADO_UPLOAD=' + raw + ')' };
  }
  if (raw === '') {
    return { enabled: true, reason: 'default', text: 'ADO-Upload AN (Standard; Abschalten mit ING_ADO_UPLOAD=0)' };
  }
  return { enabled: true, reason: 'ING_ADO_UPLOAD=' + raw, text: 'ADO-Upload AN (ING_ADO_UPLOAD=' + raw + ')' };
}

/* -------------------------------------------------------------- ado id ------- */

/**
 * The JS twin of AdoNaming.adoIdFromTestCaseName() in the INGenious plugin
 * (de/ing/qa/studio/AdoNaming.java). Same rule, deliberately: the leading digit
 * run is the id, and it must be followed by whitespace or nothing, so "12345Foo"
 * stays a name that happens to start with digits rather than a bogus id.
 */
export function adoIdFromTestCaseName(name) {
  if (typeof name !== 'string') return null;
  const trimmed = name.trim();
  let end = 0;
  while (end < trimmed.length && trimmed[end] >= '0' && trimmed[end] <= '9') end++;
  if (end === 0) return null;
  if (end < trimmed.length && !/\s/.test(trimmed[end])) return null;
  return trimmed.slice(0, end);
}

/** %LOCALAPPDATA%\IngQaAutopilot — same directory AdoCache.java uses. */
export function autopilotDir(env = process.env) {
  const local = env.LOCALAPPDATA;
  return local ? join(local, 'IngQaAutopilot') : join(homedir(), '.IngQaAutopilot');
}

/** The case the tester took on in the Studio panel, or null. Never throws. */
export function readSelectedTestCase(env = process.env) {
  const path = env.ING_TESTCASE_SELECTION || join(autopilotDir(env), 'selected-testcase.json');
  try {
    const o = JSON.parse(readFileSync(path, 'utf8'));
    const id = String(o.adoId ?? '').trim();
    return id ? { adoId: id, title: String(o.title ?? ''), source: path } : null;
  } catch {
    return null;
  }
}

/**
 * The ADO id for this run, and where it came from — the origin is reported, because
 * "we uploaded to the wrong case" must be diagnosable from the receipt alone.
 */
export function resolveAdoId(args, env = process.env) {
  if (args['test-case']) {
    const id = String(args['test-case']).trim();
    if (/^\d+$/.test(id)) return { adoId: id, from: '--test-case' };
  }
  if (args['test-case-name']) {
    const id = adoIdFromTestCaseName(args['test-case-name']);
    if (id) return { adoId: id, from: '--test-case-name "' + args['test-case-name'] + '"' };
  }
  if (args.evidence) {
    // artifacts/TC-12345/run-20260727-201500 — collect-artifacts.mjs writes this shape.
    const m = String(args.evidence).replace(/[\\/]+$/, '').match(/TC-(\d+)/);
    if (m) return { adoId: m[1], from: 'Evidenzordner (' + basename(String(args.evidence)) + ')' };
  }
  const sel = readSelectedTestCase(env);
  if (sel) return { adoId: sel.adoId, from: 'selected-testcase.json' };
  return { adoId: null, from: 'nirgends' };
}

/* ------------------------------------------------------------- evidence ------ */

/** Repo root = the nearest ancestor holding tools/collect-artifacts.mjs. */
export function repoRoot(start = __dirname) {
  let dir = resolve(start);
  for (let i = 0; i < 8; i++) {
    if (existsSync(join(dir, 'tools', 'collect-artifacts.mjs'))) return dir;
    const parent = dirname(dir);
    if (parent === dir) break;
    dir = parent;
  }
  return null;
}

/** Newest artifacts/TC-<id>/run-* folder, or null. The default evidence folder. */
export function newestRunFolder(adoId, root = repoRoot(), env = process.env) {
  const artifactsRoot = env.ING_ARTIFACTS_ROOT || (root ? join(root, 'artifacts') : null);
  if (!artifactsRoot || !adoId) return null;
  const tcDir = join(artifactsRoot, 'TC-' + adoId);
  if (!existsSync(tcDir)) return null;
  const runs = readdirSync(tcDir)
    .filter((n) => n.startsWith('run-'))
    .sort();                       // run-<yyyymmdd-HHMMss> sorts lexically = chronologically
  return runs.length ? join(tcDir, runs[runs.length - 1]) : null;
}

// Report boilerplate. INGenious copies ~2.2 MB of bootstrap/fonts/icons into media/
// on every run; attaching that to a bank's test result would be noise, not evidence.
const SKIP_DIRS = new Set(['media', 'node_modules', '.git']);
const SKIP_EXT = new Set(['.css', '.woff', '.woff2', '.ttf', '.eot', '.svg', '.ico', '.map', '.docx']);

/**
 * Rank of a file as evidence — lower is more important. Everything above the cut
 * still gets listed in the receipt, so nothing disappears without a trace.
 *
 * .docx is deliberately absent: ado-automark finds and attaches it itself
 * (findDocx), and attaching it here too would upload it twice.
 */
function evidenceRank(rel) {
  const p = rel.replace(/\\/g, '/').toLowerCase();
  if (/\.(webm|mp4)$/.test(p)) return 0;              // video of the run
  if (/^summary-v2\.html$/.test(p)) return 1;         // the report a human opens first
  if (/^bild-dokument\.(pdf|html)$/.test(p)) return 2; // ALL screenshots, one attachment
  if (/^detailed-v2\.html$/.test(p)) return 3;
  if (/^[^/]+-v2\.html$/.test(p)) return 4;           // per-test report
  if (/^data\.js$/.test(p)) return 5;                 // the report's data (html is blank without it)
  if (/^traces\/.*\.zip$/.test(p)) return 6;          // Playwright trace = the step-by-step film
  if (/^img\/.*\.(png|jpe?g)$/.test(p)) return 7;     // step screenshots
  if (/^logs\/.*\.txt$/.test(p)) return 8;
  if (/^webservice\/.*\.txt$/.test(p)) return 9;      // API step responses (INGenious 3.1)
  if (/^console\.txt$/.test(p)) return 10;
  if (/^manifest\.json$/.test(p)) return 11;
  return -1;                                          // not evidence
}

/** Indirected so the offline selftest can present a 50 MB trace without writing 50 MB. */
let __sizeOf = (p) => statSync(p).size;

function walk(dir, base = dir, out = []) {
  let entries;
  try { entries = readdirSync(dir, { withFileTypes: true }); } catch { return out; }
  for (const e of entries) {
    const full = join(dir, e.name);
    if (e.isDirectory()) {
      if (!SKIP_DIRS.has(e.name.toLowerCase())) walk(full, base, out);
      continue;
    }
    const dot = e.name.lastIndexOf('.');
    if (dot >= 0 && SKIP_EXT.has(e.name.slice(dot).toLowerCase())) continue;
    const rel = full.slice(base.length + 1);
    const rank = evidenceRank(rel);
    if (rank < 0) continue;
    let size = 0;
    try { size = __sizeOf(full); } catch { continue; }
    out.push({ path: full, rel, rank, size });
  }
  return out;
}

/** Step screenshots — the one evidence class whose members substitute for each other. */
const SCREENSHOT_RANK = 7;

/** "50,4 MB", German decimal comma, for a message a tester reads. */
function mb(bytes) { return (bytes / 1048576).toFixed(1).replace('.', ',') + ' MB'; }

/**
 * Numeric-aware, so Step-9 sorts before Step-11 instead of after it. Plain
 * localeCompare put "Step-11" before "Step-2" and that alone decided which
 * screenshots a tester got: the eleventh through eighteenth, never the last ones.
 */
const byName = (a, b) => a.rel.localeCompare(b.rel, undefined, { numeric: true });

/**
 * The evidence set for one run — chosen by VALUE, not by walk order.
 *
 * The question it answers: if only twelve files may travel, which twelve tell a human
 * what happened? Three rules, in this order:
 *
 *   1. A file the uploader cannot attach at all (over the 20 MB per-file limit) is
 *      reported with its real size and never charged against slot or budget.
 *   2. Unique evidence first, all of it: video, reports, data.js, trace, log, API
 *      responses, console, manifest. Each is the only copy of its view of the run.
 *   3. Screenshots last and rationed (DEFAULT_MAX_SCREENSHOTS), taken from the END of
 *      the run backwards — the last picture before a run stops is the one that shows
 *      why it stopped; the eleventh of eighteen shows a page that eight others show too.
 *
 * Returns what was taken AND what was left behind, each with the reason it was left —
 * a cap that silently drops the log is the same failure mode this whole issue is about.
 */
export function collectEvidence(folder, maxFiles = DEFAULT_MAX_FILES, maxScreenshots = DEFAULT_MAX_SCREENSHOTS) {
  if (!folder || !existsSync(folder)) return { files: [], skipped: [], covered: [], folder: folder || null, exists: false };
  const all = walk(folder);
  const unique = all.filter((f) => f.rank !== SCREENSHOT_RANK).sort((a, b) => a.rank - b.rank || byName(a, b));
  const shots = all.filter((f) => f.rank === SCREENSHOT_RANK).sort((a, b) => byName(b, a)); // last of the run first

  const files = [];
  const skipped = [];
  let total = 0;
  const take = (list, quota, quotaWhy) => {
    let used = 0;
    for (const f of list) {
      const note = (why) => skipped.push({ rel: f.rel, bytes: f.size, rank: f.rank, why });
      if (f.size > MAX_ATTACH_BYTES) { note('größer als das 20-MB-Limit pro Datei (' + mb(f.size) + ')'); continue; }
      if (used >= quota) { note(quotaWhy); continue; }
      if (files.length >= maxFiles) { note('Limit ' + maxFiles + ' Dateien'); continue; }
      if (total + f.size > MAX_TOTAL_BYTES) { note('Gesamtgrenze 40 MB'); continue; }
      files.push(f); total += f.size; used++;
    }
  };
  take(unique, Infinity, null);

  // Rule 3a: a screenshot that is INSIDE the image document does not need a slot of its
  // own — that is the entire point of the document. It is recorded as
  // COVERED, never as skipped: "übersprungen" means a tester lost it, and a picture in
  // the attached document is not lost. Conflating the two would make the summary lie in
  // the opposite direction from the older bug, claiming a loss that did not happen.
  //
  // The condition is deliberately strict. Coverage is only credited when the document
  // ACTUALLY took an attachment slot above — a document that was itself refused (over
  // 20 MB, or squeezed out by the file limit) covers nothing, and treating its contents
  // as safe would drop all fourteen pictures on the strength of a file that never
  // travelled. That is the worst failure this file could have, so it is the one the
  // condition is written around.
  const doc = readDocSidecar(folder);
  const docAttached = doc && files.some((f) => resolve(f.path) === resolve(doc.doc));
  const covered = [];
  let rationed = shots;
  if (docAttached) {
    const inDoc = new Set(doc.covers);
    rationed = [];
    for (const f of shots) {
      if (inDoc.has(f.rel.replace(/\\/g, '/'))) covered.push({ rel: f.rel, bytes: f.size, in: basename(doc.doc) });
      else rationed.push(f);
    }
  }

  const quota = Math.max(0, Math.min(maxScreenshots, maxFiles - files.length));
  take(rationed, quota, 'Screenshot-Kontingent ' + quota + ' von ' + rationed.length + ' (untereinander austauschbar; die letzten zählen)');

  files.sort((a, b) => a.rank - b.rank || byName(a, b)); // attach in reading order
  skipped.sort((a, b) => a.rank - b.rank || byName(a, b));
  covered.sort(byName);
  return { files, skipped, covered, folder, exists: true, totalBytes: total };
}

/**
 * What the summary line must admit. A count of attachments on its own reads as
 * completeness — "12 Datei(en) angehängt" is true and still leaves a tester believing
 * the trace is in there. So the most valuable thing that did NOT travel is named.
 */
export function skippedNote(skipped) {
  if (!skipped || !skipped.length) return ', nichts übersprungen';
  const worst = skipped[0]; // already sorted by evidence rank
  return ', ' + skipped.length + ' übersprungen (wichtigste: ' + worst.rel + ' — ' + worst.why + ')';
}

/**
 * The other half of that sentence: what travelled INSIDE the document rather than as a
 * file of its own. Without it a run that attaches seven files and carries fourteen
 * pictures reads as though it attached seven things — which undersells it as badly as
 * "12 Datei(en) angehängt" oversold the old one.
 */
export function coveredNote(covered) {
  if (!covered || !covered.length) return '';
  return ', ' + covered.length + ' Screenshot(s) im Dokument ' + covered[0].in;
}

/* ------------------------------------------------------- Ausgang des Laufs --- */

/**
 * Wann dieser Lauf fertig war — die Zeit, die im Beleg-Kommentar steht.
 *
 * <p>Genommen aus den Berichtsdateien und nicht aus dem Ordnernamen: die Engine benennt ihre
 * Lauf-Ordner lokalisiert ({@code 28-Juli-2026 02-12-42}), collect-artifacts schreibt
 * {@code run-20260727-201500}, und die Abgabe legt noch eine dritte Form an. Die Berichtsdatei
 * gibt es in allen drei Fällen, und sie ist dieselbe Datei, an der AdoRunWatcher.reportStamp
 * einen Lauf als fertig erkennt.
 *
 * @returns {Date|null} null, wenn hier gar kein Lauf liegt — dann ist der Ordner eine Aufnahme
 *   oder ein leerer Belegordner, und über einen Lauf ist nichts zu sagen
 */
export function runStamp(folder) {
  if (!folder || !existsSync(folder)) return null;
  let newest = 0;
  let entries;
  try { entries = readdirSync(folder, { withFileTypes: true }); } catch { return null; }
  for (const e of entries) {
    if (!e.isFile()) continue;
    const name = e.name.toLowerCase();
    if (name !== 'data.js' && !name.endsWith('-v2.html')) continue;
    try {
      const st = statSync(join(folder, e.name));
      if (st.size === 0) continue;               // der Null-Byte-Platzhalter ist kein Bericht
      newest = Math.max(newest, st.mtimeMs);
    } catch { /* weiter */ }
  }
  return newest > 0 ? new Date(newest) : null;
}

/** "21.07.2026 01:06" — dieselbe Schreibweise, die Abgabe.java einem Tester zeigt. */
function whenText(date) {
  const p = (n) => String(n).padStart(2, '0');
  return p(date.getDate()) + '.' + p(date.getMonth() + 1) + '.' + date.getFullYear()
    + ' ' + p(date.getHours()) + ':' + p(date.getMinutes());
}

/**
 * Ob dieser Lauf rot war, gemessen am Bericht des Laufs selbst.
 *
 * <p>{@code data.js} zählt pro Ausführung {@code nofailTests}; im echten roten Fixture steht
 * dort "16", im grünen "0". Gelesen wird der Bericht und nicht das {@code --outcome} des
 * Aufrufers, weil die beiden verschiedene Fragen beantworten: bei der Abgabe ist
 * {@code --outcome passed} die Unterschrift der PERSON, während der beigelegte Lauf sehr wohl
 * rot gewesen sein kann. Ein Kommentar, der dann "Automatischer Lauf GRÜN" behauptet, wäre
 * genau die Verwechslung, die #259 abstellt.
 *
 * @returns {{red: boolean, failed: number}|null} null, wenn es keinen lesbaren Bericht gibt
 */
export function runVerdict(folder) {
  if (!folder) return null;
  const dataJs = join(folder, 'data.js');
  if (!existsSync(dataJs)) return null;
  try {
    const text = readFileSync(dataJs, 'utf8');
    const start = text.indexOf('{');
    if (start < 0) return null;
    const doc = JSON.parse(text.slice(start).replace(/;\s*$/, ''));
    const runs = Array.isArray(doc.EXECUTIONS) ? doc.EXECUTIONS : [];
    if (!runs.length) return null;
    let failed = 0;
    for (const r of runs) failed += Number(r.nofailTests) || 0;
    return { red: failed > 0, failed };
  } catch {
    return null;
  }
}

/** Kein Anführungszeichen, keine Zeilenumbrüche — der Text reist als ein argv-Element. */
function oneLine(s, max = 160) {
  const flat = String(s).replace(/[\r\n]+/g, ' ').replace(/"/g, "'").replace(/\s{2,}/g, ' ').trim();
  return flat.length > max ? flat.slice(0, max - 1) + '…' : flat;
}

/**
 * Der ERSTE rote Schritt eines Laufs, als ein Satz — nicht der ganze Stacktrace.
 *
 * <p>Die Lauf-Protokolle unter {@code logs/<Testfall>.txt} schreiben pro Schritt einen Kopf und
 * darunter im Fehlerfall den Fehlerblock:
 *
 * <pre>
 * Step:2  |  Object:Username  |  Action:Click  |  Input:  |  Condition:  | @21-Juli-2026 01:06:25
 * [FAIL]   | Error: Error {
 *   message='Timeout 30000ms exceeded.
 *   name='TimeoutError
 * </pre>
 *
 * <p>Daraus wird {@code TimeoutError am Schritt 2 (Username/Click): Timeout 30000ms exceeded.}
 * Der erste Fehler, weil alles danach meist Folgefehler desselben Problems ist — im echten
 * roten Fixture sind es sechzehn Zeitüberschreitungen hinter einer abgelehnten Verbindung.
 *
 * @returns {string|null} der Satz, oder null wenn in den Protokollen kein Fehler steht
 */
export function firstFailure(folder) {
  if (!folder || !existsSync(folder)) return null;
  const files = [];
  const logs = join(folder, 'logs');
  if (existsSync(logs)) {
    try {
      for (const n of readdirSync(logs).sort()) {
        if (n.toLowerCase().endsWith('.txt')) files.push(join(logs, n));
      }
    } catch { /* weiter */ }
  }
  const console_ = join(folder, 'console.txt');
  if (existsSync(console_)) files.push(console_);

  for (const file of files) {
    let lines;
    try { lines = readFileSync(file, 'utf8').split(/\r?\n/); } catch { continue; }
    let step = null;
    for (let i = 0; i < lines.length; i++) {
      const line = lines[i];
      const head = line.match(/^Step:(\S+)\s*\|\s*Object:(.*?)\s*\|\s*Action:(.*?)\s*\|/);
      if (head) { step = { no: head[1], object: head[2].trim(), action: head[3].trim() }; continue; }
      if (!/^\[FAIL\]/.test(line.trim())) continue;
      let message = '';
      let name = '';
      for (let j = i; j < Math.min(i + 12, lines.length); j++) {
        const m = lines[j].match(/^\s*message='(.*)$/);
        if (m && !message) message = m[1];
        const n = lines[j].match(/^\s*name='(.*)$/);
        if (n && !name) name = n[1];
      }
      const wo = step
        ? ' am Schritt ' + step.no
          + (step.object || step.action ? ' (' + [step.object, step.action].filter(Boolean).join('/') + ')' : '')
        : '';
      const was = (name || 'Fehler') + wo;
      return oneLine(message ? was + ': ' + message : was);
    }
  }
  return null;
}

/**
 * Der Satz, der an jedem Beleg-Upload hängt: WANN der Lauf war und WIE er ausging.
 *
 * <p>Der zweite Teil von #259 — "es wird immer das falsche Video hochgeladen" war in Wahrheit
 * "man sieht dem Video nicht an, aus welcher Durchführung es stammt". Ein Beleg ohne Zeitstempel
 * und ohne Ausgang lässt die Frage offen; mit beiden ist sie beantwortet.
 *
 * <p>Kein Lauf im Ordner (die Abgabe hängt die Aufnahme selbst an) heisst: nichts behaupten.
 * Der Kommentar der Abgabe sagt dort ohnehin, dass gar kein Automat gelaufen ist.
 *
 * @returns {string} der Satz, oder '' wenn im Ordner kein Lauf liegt
 */
export function laufNotiz(folder) {
  const stamp = runStamp(folder);
  if (!stamp) return '';
  const verdict = runVerdict(folder);
  const failure = firstFailure(folder);
  const red = (verdict && verdict.red) || (!verdict && !!failure);
  const wann = 'Beleg-Lauf vom ' + whenText(stamp) + '.';
  if (red) {
    return 'Automatischer Lauf ROT — ' + (failure || 'der Bericht zählt '
      + ((verdict && verdict.failed) || 0) + ' fehlgeschlagene(n) Schritt(e); der erste Fehler '
      + 'steht in den beiliegenden Protokollen') + '. ' + wann
      + ' Das Testfall-Ergebnis wurde dadurch NICHT verändert — hochgeladen wurden nur Belege.';
  }
  if (verdict) return 'Automatischer Lauf GRÜN. ' + wann;
  return 'Automatischer Lauf: Ausgang aus dem Bericht nicht lesbar. ' + wann;
}

/* -------------------------------------------------------------- receipt ------ */

export function logsDir(env = process.env) {
  const explicit = env.ING_ADO_UPLOAD_LOGS || env.COMPANION_LOGS_DIR;
  if (explicit && explicit.trim()) return explicit.trim();
  return join(homedir(), 'ingenious', 'companion-logs');
}

/**
 * Writes the receipt: one JSON file per attempt plus a line in an append-only
 * ledger. The ledger is the thing a tester or René can point at and say "it did /
 * did not upload", without reading a GUI that has already moved on.
 */
function writeReceipt(receipt, env = process.env) {
  const dir = logsDir(env);
  const stamp = receipt.at.replace(/[:.]/g, '-');
  const file = join(dir, 'ado-upload-TC' + (receipt.adoId || 'unbekannt') + '-' + stamp + '.json');
  const ledger = join(dir, 'ado-upload.log');
  try {
    mkdirSync(dir, { recursive: true });
    writeFileSync(file, JSON.stringify(receipt, null, 2), 'utf8');
    // BOM on creation: without it Windows PowerShell's Get-Content reads the ledger
    // as ANSI and every German umlaut in it turns to mojibake — an evidence trail
    // nobody can read is barely better than no evidence trail.
    if (!existsSync(ledger)) writeFileSync(ledger, '﻿', 'utf8');
    appendFileSync(ledger,
      [receipt.at, receipt.status, 'TC-' + (receipt.adoId || '?'), receipt.runId || '-', receipt.message].join('\t') + '\n', 'utf8');
    return file;
  } catch (e) {
    // A receipt we cannot write is itself news — say it, do not swallow it.
    process.stderr.write('[ado-upload] Beleg konnte nicht geschrieben werden: ' + e.message + '\n');
    return null;
  }
}

/* --------------------------------------------------------------- upload ------ */

/**
 * Runs ado-automark as a CHILD PROCESS rather than importing markPassed(). Two
 * reasons, both about rule 3: a crash or a hang in the ADO lifecycle cannot take
 * the caller down with it, and the child's exit code + output land verbatim in the
 * receipt instead of being reconstructed from an exception.
 */
function runAutomark(argv, env) {
  const r = spawnSync(process.execPath, [AUTOMARK, ...argv], {
    encoding: 'utf8', timeout: AUTOMARK_TIMEOUT_MS, windowsHide: true, env,
  });
  const stdout = r.stdout || '';
  const stderr = r.stderr || '';
  let result = null;
  for (const line of stdout.split(/\r?\n/)) {
    const t = line.trim();
    if (t.startsWith('{') && t.endsWith('}')) { try { result = JSON.parse(t); } catch { /* keep looking */ } }
  }
  return {
    exit: typeof r.status === 'number' ? r.status : -1,
    timedOut: r.error && r.error.code === 'ETIMEDOUT',
    spawnError: r.error && r.error.code !== 'ETIMEDOUT' ? String(r.error.message) : null,
    result, stdout, stderr,
  };
}

/**
 * How long the image document may take to build. Chromium prints the real 14-shot run
 * in a few seconds; three minutes is the ceiling for a machine under load, and it is a
 * ceiling rather than a wait because an upload that hangs on picture-making is worse
 * than an upload that arrives without the pictures bundled.
 */
const DOC_TIMEOUT_MS = 3 * 60 * 1000;

/** Whether the image document is built. Default ON, for the reason at the top of this file. */
export function docState(env = process.env, args = {}) {
  if (args['no-doc']) return { enabled: false, reason: '--no-doc' };
  const raw = (env.ING_EVIDENCE_DOC ?? '').trim().toLowerCase();
  if (raw === '0' || raw === 'off' || raw === 'false' || raw === 'nein') {
    return { enabled: false, reason: 'ING_EVIDENCE_DOC=' + raw };
  }
  return { enabled: true, reason: raw ? 'ING_EVIDENCE_DOC=' + raw : 'default' };
}

/**
 * Build the image document for this run, as a child process.
 *
 * Same reasoning as runAutomark: the builder starts a BROWSER, and a browser that hangs
 * or dies must not take a tester's upload with it. spawnSync with a timeout gives that a
 * hard bound, which an in-process import could not.
 *
 * Returns the builder's receipt, or a shaped failure. Never throws.
 */
function runEvidenceDoc(folder, adoId, env) {
  const r = spawnSync(process.execPath, [EVIDENCE_DOC, '--run-dir', folder, '--tc', String(adoId), '--json'], {
    encoding: 'utf8', timeout: DOC_TIMEOUT_MS, windowsHide: true, env,
  });
  for (const line of (r.stdout || '').split(/\r?\n/)) {
    const t = line.trim();
    if (t.startsWith('{') && t.endsWith('}')) { try { return JSON.parse(t); } catch { /* keep looking */ } }
  }
  return {
    status: 'FEHLER',
    message: r.error && r.error.code === 'ETIMEDOUT'
      ? 'Bilddokument nach ' + (DOC_TIMEOUT_MS / 60000) + ' Minuten abgebrochen'
      : 'Bilddokument konnte nicht erzeugt werden (Exit ' + r.status + ')'
        + ((r.stderr || '').trim() ? ': ' + (r.stderr || '').trim().split(/\r?\n/).slice(-1)[0] : ''),
  };
}

/** German one-liner for why ado-automark declined, so the tester is not handed a code. */
function reasonText(reason) {
  switch (reason) {
    case 'no-token': return 'kein ADO-Token (az-Anmeldung fehlt oder abgelaufen)';
    case 'no-point': return 'kein Testpunkt für diesen Testfall im Testplan';
    default: return reason ? 'Grund: ' + reason : 'unbekannter Grund';
  }
}

/**
 * The trigger itself. Never throws: every path returns a receipt.
 *
 * @param {object} args parsed CLI arguments
 * @param {object} env  environment (injected so the selftest can vary it)
 */
export async function upload(args, env = process.env) {
  const at = new Date().toISOString();
  const state = uploadState(env);
  const { adoId, from } = resolveAdoId(args, env);
  const receipt = {
    at, status: 'FEHLER', enabled: state.enabled, enabledReason: state.reason,
    adoId, adoIdFrom: from,
    // The requested outcome, recorded FIRST — before the enabled check, before the id
    // check, before the outcome guard itself. It is the single most safety-critical
    // input this tool takes ("ado-automark marks Bestanden and only Bestanden"), and it
    // used to be the one thing the receipt did not keep: a switched-off run of a FAILED
    // test wrote {"status":"AUS"} and was indistinguishable, on disk, from a switched-off
    // run of a passing one. The property "a failed run is never marked Bestanden" is only
    // worth anything if it can be audited from the evidence we keep, including from the
    // paths that return early — and including the caller's choice, which is how the Java
    // side's passed/failed ternary (AdoUpload.upload) becomes observable at all.
    //
    // Seit #259 entscheidet dieses Feld nicht mehr über HOCHLADEN oder NICHT, sondern über
    // MARKIEREN oder NUR BELEGEN — was es umso mehr zum Feld macht, an dem sich im Nachhinein
    // prüfen lässt, dass kein roter Lauf je auf Bestanden gesetzt wurde. Siehe evidenceOnly.
    outcome: args.outcome == null ? null : String(args.outcome),
    evidenceOnly: false,
    evidenceFolder: null, attached: [], skipped: [], covered: [], document: null,
    runId: null, runUrl: null, message: '', automarkExit: null, comment: null,
  };

  if (!state.enabled) {
    receipt.status = 'AUS';
    receipt.message = state.text + ' — es wurde nichts nach ADO hochgeladen.';
    return receipt;
  }
  if (!adoId) {
    receipt.message = 'Kein ADO-Testfall erkennbar (weder --test-case, noch Testfallname, '
      + 'noch Evidenzordner, noch selected-testcase.json) — nichts hochgeladen.';
    return receipt;
  }
  // ado-automark markiert Bestanden und ausschliesslich Bestanden — das gilt für ERGEBNISSE
  // unverändert weiter. Ein roter Lauf wird deshalb nicht mehr komplett übersprungen, sondern
  // auf den Beleg-Pfad geschickt: --nur-belege legt Anhänge und Kommentar am Arbeitselement ab
  // und stellt keinen einzigen Request an die Testlauf-API, kann also weder Bestanden noch
  // Durchgefallen schreiben (#259). Bis zum 07.08.2026 erreichten Video, Trace und Berichte
  // eines roten Nachspiel-Laufs Azure DevOps nie — obwohl die Person den Testfall von Hand
  // durchgeführt und bestanden gemeldet hatte und die Belege genau dorthin gehören.
  const evidenceOnly = !!args.outcome && String(args.outcome).trim().toLowerCase() !== 'passed';
  receipt.evidenceOnly = evidenceOnly;

  const folder = args.evidence
    ? (isAbsolute(args.evidence) ? args.evidence : resolve(process.cwd(), args.evidence))
    : newestRunFolder(adoId, repoRoot(), env);

  // The image document is built BEFORE the evidence is picked, because it is itself a
  // candidate — and because it changes the picking: once it exists and is attachable,
  // the screenshots it contains stop competing for the four rationed slots.
  //
  // This runs on a dry run too. A dry run whose job is to show what WOULD be attached
  // has to build the thing that would be attached, and the builder writes only into the
  // artifacts folder — it never touches Azure DevOps.
  const doc = docState(env, args);
  if (folder && existsSync(folder) && doc.enabled) {
    const built = runEvidenceDoc(folder, adoId, env);
    receipt.document = {
      status: built.status || 'FEHLER',
      file: built.doc || null,
      format: built.format || null,
      bytes: built.bytes || 0,
      images: built.images || 0,
      engine: built.engine ? basename(built.engine) : null,
      message: built.message || '',
    };
  } else {
    // "Not built" is not "not attached". If a document is already lying in the run folder
    // — built by an earlier upload, or by a tester running evidence-doc.mjs by hand — it
    // is still evidence and still travels. Refusing to attach it because this invocation
    // did not create it would throw away belegs for a bookkeeping reason.
    //
    // The state text says so out loud, because the receipt would otherwise read as a
    // contradiction: "Bilddokument: AUS" on one line and "14 Screenshot(s) im Dokument"
    // on the next.
    const existing = readDocSidecar(folder || '');
    receipt.document = {
      status: 'NICHT-GEBAUT', reason: doc.reason, file: existing ? existing.doc : null,
      format: existing ? existing.format : null, bytes: existing ? existing.bytes : 0,
      images: existing ? existing.covers.length : 0,
      message: existing
        ? 'nicht gebaut (' + doc.reason + '), aber ein vorhandenes ' + basename(existing.doc) + ' wird angehängt'
        : 'nicht gebaut (' + doc.reason + '), und es liegt auch keins im Laufordner',
    };
  }

  const maxFiles = args['max-files'] ? parseInt(args['max-files'], 10) : DEFAULT_MAX_FILES;
  const evidence = collectEvidence(folder, maxFiles);
  receipt.evidenceFolder = evidence.folder;
  receipt.skipped = evidence.skipped;
  receipt.covered = evidence.covered;

  // Comment policy: the id and nothing else unless a caller opts into more. Dazu kommt — immer,
  // auch beim grünen Lauf — die Notiz, aus WELCHER Durchführung die Belege stammen: Zeitstempel
  // und Ausgang. Angehängt und nicht ersetzt, weil der Kommentar der Abgabe die Unterschrift der
  // Person trägt und die hier nicht überschrieben werden darf.
  const base = args.comment && String(args.comment).trim() ? String(args.comment).trim() : String(adoId);
  const notiz = laufNotiz(evidence.folder && evidence.exists ? evidence.folder : null);
  const comment = notiz ? base + ' ' + notiz : base;
  receipt.comment = comment;
  receipt.laufNotiz = notiz || null;

  const argv = ['--test-case', String(adoId), '--label', String(adoId), '--comment', comment];
  if (evidenceOnly) argv.push('--nur-belege');
  if (evidence.folder && evidence.exists) argv.push('--evidence', evidence.folder);
  for (const f of evidence.files) argv.push('--attach', f.path);
  if (args['dry-run']) argv.push('--dry-run');
  receipt.attached = evidence.files.map((f) => ({ rel: f.rel, bytes: f.size }));

  const run = runAutomark(argv, env);
  receipt.automarkExit = run.exit;
  receipt.automarkStderrTail = run.stderr.split(/\r?\n/).filter(Boolean).slice(-6).join(' | ');

  if (run.spawnError) {
    receipt.message = 'ado-automark konnte nicht gestartet werden: ' + run.spawnError;
    return receipt;
  }
  if (run.timedOut) {
    receipt.message = 'ado-automark hat nach ' + (AUTOMARK_TIMEOUT_MS / 60000) + ' Minuten nicht geantwortet — abgebrochen.';
    return receipt;
  }
  if (run.result && run.result.ok) {
    // A dry run invents run id 99999. Reporting that as a plain OK would put a
    // convincing fake run id in the ledger — the exact "invisible success" this
    // whole issue exists to kill. It is labelled instead, everywhere.
    if (args['dry-run']) {
      receipt.status = 'PROBELAUF';
      receipt.dryRun = true;
      receipt.message = 'Probelauf: ' + evidence.files.length + ' Datei(en) wären angehängt worden'
        + coveredNote(evidence.covered) + skippedNote(evidence.skipped)
        + (evidenceOnly ? ' (nur Belege, ohne Ergebnis)' : '')
        + ' — es wurde NICHTS nach ADO geschrieben.';
      return receipt;
    }
    if (evidenceOnly) {
      // Kein runId, weil kein Testlauf angelegt wurde — das ist hier die Zusicherung und nicht
      // eine fehlende Angabe. Der Status ist OK, weil die Belege wirklich in Azure DevOps
      // liegen; der Satz sagt im selben Atemzug, dass das Ergebnis unangetastet blieb.
      receipt.status = 'OK';
      receipt.workItemUrl = run.result.workItemUrl || null;
      receipt.message = 'Belege am Testfall ' + adoId + ' abgelegt: ' + (run.result.attached ?? 0)
        + ' Datei(en) angehängt' + coveredNote(evidence.covered) + skippedNote(evidence.skipped)
        + '. Kein Testlauf angelegt, kein Ergebnis geschrieben. ' + (notiz || '')
        + ' ' + (run.result.workItemUrl || '');
      return receipt;
    }
    receipt.status = 'OK';
    receipt.runId = run.result.runId;
    receipt.runUrl = run.result.runUrl;
    receipt.message = 'ADO-Lauf ' + run.result.runId + ' angelegt, ' + evidence.files.length
      + ' Datei(en) angehängt' + coveredNote(evidence.covered) + skippedNote(evidence.skipped)
      + '. ' + (run.result.runUrl || '');
    return receipt;
  }
  receipt.message = 'ADO-Upload fehlgeschlagen (' + reasonText(run.result && run.result.reason)
    + ', Exit ' + run.exit + '). Details im Beleg.';
  return receipt;
}

/* ------------------------------------------------------------------ CLI ------ */

function parseArgs(argv) {
  const o = {};
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--hook' || a === '--dry-run' || a === '--state' || a === '--selftest' || a === '--no-doc') o[a.slice(2)] = true;
    else if (a.startsWith('--')) { o[a.slice(2)] = argv[i + 1]; i++; }
  }
  return o;
}

/** The loud part. Both success and failure get the same shape, so neither hides. */
function announce(receipt, receiptFile) {
  const bar = '─'.repeat(64);
  process.stderr.write('\n' + bar + '\n');
  process.stderr.write('[ado-upload] ' + receipt.status + ' — ' + receipt.message + '\n');
  if (receipt.adoId) process.stderr.write('[ado-upload] Testfall ' + receipt.adoId + ' (Quelle: ' + receipt.adoIdFrom + ')\n');
  // Said out loud as well as written, so the caller's choice is visible in the Java
  // side's studio-upload-*.log — which captures this output — and not only in the receipt.
  process.stderr.write('[ado-upload] Angefordertes Ergebnis: ' + (receipt.outcome ?? '(keins angegeben)')
    + (receipt.evidenceOnly ? ' — NUR BELEGE, es wird kein Ergebnis geschrieben' : '') + '\n');
  if (receipt.laufNotiz) process.stderr.write('[ado-upload] Lauf: ' + receipt.laufNotiz + '\n');
  if (receipt.evidenceFolder) process.stderr.write('[ado-upload] Evidenz: ' + receipt.evidenceFolder + '\n');
  if (receipt.document) {
    process.stderr.write('[ado-upload] Bilddokument: ' + receipt.document.status
      + (receipt.document.message ? ' — ' + receipt.document.message : '')
      + (receipt.document.reason ? ' (' + receipt.document.reason + ')' : '') + '\n');
  }
  for (const f of receipt.attached) process.stderr.write('[ado-upload]   + ' + f.rel + ' (' + f.bytes + ' B)\n');
  // Covered is printed with a different sign than skipped on purpose: "=" travelled
  // inside the document, "-" did not travel at all. A tester reading this list has to
  // tell those apart at a glance, because only one of the two is a loss.
  for (const c of receipt.covered || []) process.stderr.write('[ado-upload]   = ' + c.rel + ' im Dokument ' + c.in + '\n');
  for (const s of receipt.skipped) process.stderr.write('[ado-upload]   - ' + s.rel + ' übersprungen: ' + s.why + '\n');
  if (receiptFile) process.stderr.write('[ado-upload] Beleg: ' + receiptFile + '\n');
  process.stderr.write(bar + '\n');
  console.log(JSON.stringify(receipt));
  console.log('ADO-UPLOAD ' + receipt.status + ' ' + receipt.message.replace(/[\r\n]+/g, ' '));
}

async function main() {
  const args = parseArgs(process.argv.slice(2));

  if (args.state) {
    const s = uploadState();
    console.log('ADO-UPLOAD ' + (s.enabled ? 'AN' : 'AUS') + ' ' + s.text);
    process.exit(0);
  }
  if (args.selftest) return selftest();

  const receipt = await upload(args);
  const file = writeReceipt(receipt);
  receipt.receiptFile = file;
  announce(receipt, file);
  // --hook: the tester's run must survive a broken ADO. Without it, a human at a
  // command line still wants a real exit code.
  const succeeded = receipt.status === 'OK' || receipt.status === 'PROBELAUF';
  process.exit(args.hook ? 0 : (succeeded ? 0 : 1));
}

/* ------------------------------------------------------------- selftest ------ */

async function selftest() {
  const fails = [];
  const check = (name, cond) => { if (!cond) fails.push(name); };
  const tmp = join(process.env.TEMP || process.env.TMPDIR || '/tmp', 'ado-upload-selftest');

  // id recovery — the same cases AdoNaming's javadoc calls out
  check('id from plain name', adoIdFromTestCaseName('12345 - Beispielfall') === '12345');
  check('id survives rename', adoIdFromTestCaseName('12345 - Beispielfall (neu)') === '12345');
  check('id rejects glued digits', adoIdFromTestCaseName('12345Foo') === null);
  check('id rejects no digits', adoIdFromTestCaseName('Partner-Suche') === null);
  check('id from bare id', adoIdFromTestCaseName('12345') === '12345');

  // state — default ON is the whole point of the issue
  check('state default on', uploadState({}).enabled === true);
  check('state off by 0', uploadState({ ING_ADO_UPLOAD: '0' }).enabled === false);
  check('state off by off', uploadState({ ING_ADO_UPLOAD: 'off' }).enabled === false);
  check('state on by 1', uploadState({ ING_ADO_UPLOAD: '1' }).enabled === true);

  // id resolution order
  check('id from --test-case', resolveAdoId({ 'test-case': '111' }, {}).adoId === '111');
  check('id from name', resolveAdoId({ 'test-case-name': '222 - x' }, {}).adoId === '222');
  check('id from folder', resolveAdoId({ evidence: 'C:\\a\\artifacts\\TC-333\\run-1' }, {}).adoId === '333');

  // evidence picking on a realistic INGenious run folder. The fixture is wiped first:
  // this directory survives between runs, and a screenshot left behind by an older
  // version of this selftest would quietly change which files the picker chooses.
  rmSync(tmp, { recursive: true, force: true });
  mkdirSync(join(tmp, 'media', 'css'), { recursive: true });
  mkdirSync(join(tmp, 'img'), { recursive: true });
  mkdirSync(join(tmp, 'logs'), { recursive: true });
  mkdirSync(join(tmp, 'traces', 'Case_10-00-00'), { recursive: true });
  writeFileSync(join(tmp, 'summary-v2.html'), 'S');
  writeFileSync(join(tmp, 'detailed-v2.html'), 'D');
  writeFileSync(join(tmp, 'data.js'), 'x');
  writeFileSync(join(tmp, 'console.txt'), 'c');
  writeFileSync(join(tmp, 'manifest.json'), '{}');
  writeFileSync(join(tmp, 'evidence.docx'), 'DOCX');
  writeFileSync(join(tmp, 'img', 'Case_Step-15_15-30-00.png'), 'P');
  writeFileSync(join(tmp, 'logs', 'Case.txt'), 'L');
  mkdirSync(join(tmp, 'webservice'), { recursive: true });
  writeFileSync(join(tmp, 'webservice', 'Case_Step-3_Response.txt'), 'R');
  writeFileSync(join(tmp, 'traces', 'Case_10-00-00', 'traces.zip'), 'Z');
  writeFileSync(join(tmp, 'media', 'css', 'bootstrap.min.css'), 'CSS');
  const ev = collectEvidence(tmp);
  const rels = ev.files.map((f) => f.rel.replace(/\\/g, '/'));
  check('summary first', rels[0] === 'summary-v2.html');
  check('trace included', rels.includes('traces/Case_10-00-00/traces.zip'));
  check('screenshot included', rels.includes('img/Case_Step-15_15-30-00.png'));
  check('log included', rels.includes('logs/Case.txt'));
  check('api response included', rels.includes('webservice/Case_Step-3_Response.txt'));
  check('media excluded', !rels.some((r) => r.startsWith('media/')));
  check('docx excluded (ado-automark attaches it)', !rels.some((r) => r.endsWith('.docx')));
  const capped = collectEvidence(tmp, 2);
  check('cap honoured', capped.files.length === 2);
  check('cap is not silent', capped.skipped.length > 0 && capped.skipped.every((s) => s.why));

  // A real run, reproduced: 14 step screenshots and a
  // 50,4-MB trace. Under the old order-based pick this folder yielded four reports,
  // eight screenshots (Step-11..Step-18, chosen by nothing but string order) and no
  // log, no console, no manifest, no trace. That is the bug, so it is the fixture.
  for (const n of [3, 5, 6, 7, 8, 9, 11, 12, 13, 14, 16, 17, 18]) {
    writeFileSync(join(tmp, 'img', 'Case_Step-' + n + '_15-30-00.png'), 'P');
  }
  const many = collectEvidence(tmp);
  const rel = (f) => f.rel.replace(/\\/g, '/');
  const picked = many.files.map(rel);
  const shots = picked.filter((r) => r.startsWith('img/'));
  check('unique evidence keeps its slot: log', picked.includes('logs/Case.txt'));
  check('unique evidence keeps its slot: console', picked.includes('console.txt'));
  check('unique evidence keeps its slot: manifest', picked.includes('manifest.json'));
  check('unique evidence keeps its slot: api response', picked.includes('webservice/Case_Step-3_Response.txt'));
  check('screenshots are rationed', shots.length === 4);
  check('the LAST screenshots are the ones taken, numerically not lexically',
    shots.join() === ['img/Case_Step-15_15-30-00.png', 'img/Case_Step-16_15-30-00.png',
      'img/Case_Step-17_15-30-00.png', 'img/Case_Step-18_15-30-00.png'].join());
  check('Step-9 does not outrank Step-18', !shots.some((r) => /Step-9_/.test(r)));
  check('the rationed screenshots say so', many.skipped.some((s) => /Kontingent/.test(s.why)));

  // ---- the image document ---------------------------------------------------------
  //
  // Same folder, now with a bild-dokument.pdf that carries every one of the fourteen
  // screenshots. This block is the regression guard for the whole issue: against the
  // version before the image document every assertion below either fails or throws, because
  // collectEvidence had no notion of a screenshot travelling inside something else.
  const docFile = join(tmp, 'bild-dokument.pdf');
  const allShots = many.files.concat(many.skipped)
    .map(rel).filter((r) => r.startsWith('img/'));
  writeFileSync(docFile, 'PDF');
  writeFileSync(join(tmp, 'bild-dokument.json'), JSON.stringify({
    doc: docFile, format: 'pdf', bytes: 1119435, imagePages: allShots.length, covers: allShots,
  }));

  const withDoc = collectEvidence(tmp);
  const withDocRels = withDoc.files.map(rel);
  check('the document is attached, and early — it is what a human opens',
    withDocRels.includes('bild-dokument.pdf') && withDocRels.indexOf('bild-dokument.pdf') <= 1);
  check('THE POINT: no screenshot is left behind any more',
    withDoc.covered.length === allShots.length
    && !withDoc.skipped.some((s) => rel(s).startsWith('img/')));
  check('a covered screenshot is not attached a second time',
    !withDocRels.some((r) => r.startsWith('img/')));
  check('covered says WHICH document carries it',
    withDoc.covered.every((c) => c.in === 'bild-dokument.pdf'));
  check('covered is not skipped — the summary may not report a loss that did not happen',
    withDoc.skipped.length === 0 && skippedNote(withDoc.skipped) === ', nichts übersprungen');
  check('the summary names the pictures that travelled inside the document',
    /14 Screenshot\(s\) im Dokument bild-dokument\.pdf/.test(coveredNote(withDoc.covered)));
  check('an empty covered list adds nothing to the summary', coveredNote([]) === '');
  // Freed slots are the payoff: fourteen pictures now cost one attachment, not four.
  check('the document frees the slots the rationed screenshots used to take',
    withDoc.files.length === many.files.length - DEFAULT_MAX_SCREENSHOTS + 1);

  // The trap: a document that is itself too big to attach covers NOTHING. Crediting it
  // would drop all fourteen pictures on the strength of a file that never travelled —
  // the most damaging thing this file could do, so it is asserted, not assumed.
  const trueSizeOf = __sizeOf;
  __sizeOf = (p) => (p.endsWith('bild-dokument.pdf') ? 21 * 1024 * 1024 : trueSizeOf(p));
  const hugeDoc = collectEvidence(tmp);
  __sizeOf = trueSizeOf;
  check('an unattachable document covers nothing and the screenshots are rationed again',
    hugeDoc.covered.length === 0
    && hugeDoc.files.map(rel).filter((r) => r.startsWith('img/')).length === DEFAULT_MAX_SCREENSHOTS);
  check('the oversized document is refused for its own size',
    hugeDoc.skipped.some((s) => rel(s) === 'bild-dokument.pdf' && /20-MB-Limit pro Datei/.test(s.why)));

  // A sidecar left behind by a build whose document is gone must not be believed.
  rmSync(docFile, { force: true });
  const staleSidecar = collectEvidence(tmp);
  check('a sidecar naming a document that no longer exists is ignored',
    staleSidecar.covered.length === 0
    && staleSidecar.files.map(rel).filter((r) => r.startsWith('img/')).length === DEFAULT_MAX_SCREENSHOTS);
  rmSync(join(tmp, 'bild-dokument.json'), { force: true });

  // A file above the per-file limit is refused for THAT reason, and — the part that
  // matters — without eating the budget everything else needs.
  const realSizeOf = __sizeOf;
  __sizeOf = (p) => (p.endsWith('traces.zip') ? 52841783 : realSizeOf(p));
  const bigTrace = collectEvidence(tmp);
  __sizeOf = realSizeOf;
  const traceSkip = bigTrace.skipped.find((s) => rel(s).endsWith('traces.zip'));
  check('a 50-MB trace is refused for its own size, not blamed on the total',
    !!traceSkip && /20-MB-Limit pro Datei/.test(traceSkip.why) && /50,4 MB/.test(traceSkip.why));
  check('an unattachable file costs nobody else a slot',
    bigTrace.files.length === many.files.length - 1
    && bigTrace.files.map(rel).includes('console.txt'));

  // The summary may not read as completeness when something was left behind.
  check('a summary with losses names the biggest one', /traces\.zip/.test(skippedNote(bigTrace.skipped)));
  check('a complete summary says so plainly', skippedNote([]) === ', nichts übersprungen');

  // Full path through ado-automark --dry-run: offline, no az, no network.
  const off = await upload({ 'test-case': '12345' }, { ING_ADO_UPLOAD: '0' });
  check('off short-circuits', off.status === 'AUS' && off.runId === null);

  // ---- der rote Lauf: Belege ja, Ergebnis nein (#259) -------------------------------
  //
  // Der Ordner bekommt ein data.js mit einer fehlgeschlagenen Ausführung und ein
  // Lauf-Protokoll mit genau dem Kopf/Fehler-Block, den die echte Engine schreibt. Der Test
  // fragt zwei Dinge getrennt: dass die Belege reisen, und dass auf dem Weg dahin nichts
  // steht, was ein Ergebnis setzen könnte.
  writeFileSync(join(tmp, 'data.js'), 'var DATA=' + JSON.stringify({
    EXECUTIONS: [{ testcaseName: 'Fall', nopassTests: '2', nofailTests: '16' }],
  }) + ';');
  writeFileSync(join(tmp, 'logs', 'Case.txt'),
    'Step:1  |  Object:Browser  |  Action:Open  |  Input:Login:URL  |  Condition:  | @21-Juli-2026 01:05:54\n'
    + '[FAIL]   | Error {\n'
    + "  message='net::ERR_CONNECTION_REFUSED at http://localhost:3000/signin\n"
    + "  name='Error\n}\n"
    + 'Step:2  |  Object:Username  |  Action:Click  |  Input:  |  Condition:  | @21-Juli-2026 01:06:25\n'
    + '[FAIL]   | Error: Error {\n'
    + "  message='Timeout 30000ms exceeded.\n  name='TimeoutError\n}\n");
  check('a red run is read as red out of its own report',
    runVerdict(tmp) && runVerdict(tmp).red === true && runVerdict(tmp).failed === 16);
  check('the FIRST red step is named, not the sixteenth',
    /^Error am Schritt 1 \(Browser\/Open\): net::ERR_CONNECTION_REFUSED/.test(firstFailure(tmp)));
  check('the failure extract carries no quote that would cut the comment dead',
    firstFailure(tmp).indexOf('"') < 0);
  check('and no stacktrace travels with it', !/\n|\tat /.test(firstFailure(tmp)));
  const notiz = laufNotiz(tmp);
  check('the note says ROT, names the step and says the result stayed untouched',
    /^Automatischer Lauf ROT — Error am Schritt 1/.test(notiz)
    && /NICHT verändert/.test(notiz));
  check('the note carries the timestamp of the run the evidence comes from',
    /Beleg-Lauf vom \d\d\.\d\d\.\d{4} \d\d:\d\d\./.test(notiz));
  check('a folder without a report claims nothing about a run',
    laufNotiz(join(tmp, 'logs')) === '' && runStamp(join(tmp, 'logs')) === null);

  const failed = await upload({ 'test-case': '12345', evidence: tmp, outcome: 'failed', 'dry-run': true }, {});
  check('a failed run is no longer refused — its evidence travels',
    failed.status === 'PROBELAUF' && failed.attached.length > 0);
  check('…and it goes on the evidence-only path', failed.evidenceOnly === true);
  check('…whose comment names ROT and the first step',
    /Automatischer Lauf ROT — Error am Schritt 1/.test(failed.comment));
  check('…and the receipt still records WHICH outcome was asked for', failed.outcome === 'failed');

  const green = await upload({ 'test-case': '12345', evidence: tmp, outcome: 'passed', 'dry-run': true }, {});
  check('a passing run is never sent down the evidence-only path', green.evidenceOnly === false);
  check('the comment of a run reported passed still tells the truth about the RUN',
    /Automatischer Lauf ROT/.test(green.comment));

  // Grün von A bis Z: derselbe Ordner ohne die roten Zahlen und ohne Fehlerblock.
  writeFileSync(join(tmp, 'data.js'), 'var DATA=' + JSON.stringify({
    EXECUTIONS: [{ testcaseName: 'Fall', nopassTests: '16', nofailTests: '0' }],
  }) + ';');
  writeFileSync(join(tmp, 'logs', 'Case.txt'), 'Step:1  |  Object:Browser  |  Action:Open  |  Input:  |  Condition:  | @x\n[PASS]\n');
  check('a green run says GRÜN and carries its timestamp',
    /^Automatischer Lauf GRÜN\. Beleg-Lauf vom \d\d\.\d\d\.\d{4} \d\d:\d\d\.$/.test(laufNotiz(tmp)));
  const greenRun = await upload({ 'test-case': '12345', evidence: tmp, outcome: 'passed', 'dry-run': true }, {});
  check('a green run marks as before: no evidence-only, comment says GRÜN',
    greenRun.evidenceOnly === false && /Automatischer Lauf GRÜN/.test(greenRun.comment));

  // The requested outcome survives into the receipt on EVERY path, including the ones
  // that return before anything is attempted. Without this, "a failed run is never
  // marked Bestanden" was a property no receipt could be used to audit: a switched-off
  // run of a failed test and of a passing one wrote the same evidence.
  const offFailed = await upload({ 'test-case': '12345', outcome: 'failed' }, { ING_ADO_UPLOAD: '0' });
  check('a switched-off run still records WHICH outcome was asked for',
    offFailed.status === 'AUS' && offFailed.outcome === 'failed');
  check('a switched-off passing run is distinguishable from a failed one',
    (await upload({ 'test-case': '12345', outcome: 'passed' }, { ING_ADO_UPLOAD: '0' })).outcome === 'passed');
  check('the refused outcome is in the receipt, not only in the message', failed.outcome === 'failed');
  check('no outcome given is recorded as none, never as passed',
    off.outcome === null);
  const none = await upload({}, { ING_TESTCASE_SELECTION: join(tmp, 'no-such-selection.json') });
  check('no id is reported, not guessed', none.status === 'FEHLER' && none.adoId === null);
  const dry = await upload({ 'test-case': '12345', evidence: tmp, 'dry-run': true }, {});
  check('dry run is labelled, not a fake OK', dry.status === 'PROBELAUF' && dry.runId === null);
  check('dry run still reports the evidence set', dry.attached.length > 0);

  if (fails.length) {
    console.error('ado-upload selftest FAILED:', fails.join(', '));
    process.exit(1);
  }
  console.log('ado-upload selftest: GREEN — ' + rels.length + ' evidence files picked, all assertions passed.');
}

/**
 * Whether THIS file is the script node was started with — by path, not by name.
 *
 * The old test was `argv[1].endsWith('ado-upload.mjs')`, which meant a copy under any
 * other name loaded, ran nothing, printed nothing and exited 0. A silent no-op that
 * reads as success is the same disease as an invisible failure, and the population
 * that hits it is exactly the one that can least afford it: anyone testing a scratch
 * copy. Comparing realpaths makes a renamed copy WORK instead of quietly doing
 * nothing; importing the module still runs nothing, which is the point of exporting.
 */
function invokedDirectly() {
  const entry = process.argv[1];
  if (!entry) return false;
  const real = (p) => { try { return realpathSync(p); } catch { return resolve(p); } };
  const started = real(entry);
  const self = real(fileURLToPath(import.meta.url));
  return process.platform === 'win32'
    ? started.toLowerCase() === self.toLowerCase()
    : started === self;
}

if (invokedDirectly()) {
  main().catch((e) => {
    // Even an unexpected throw must not be silent, and must not fail a tester's run.
    process.stderr.write('[ado-upload] unerwarteter Fehler: ' + (e && e.stack ? e.stack : e) + '\n');
    console.log('ADO-UPLOAD FEHLER unerwarteter Fehler: ' + (e && e.message ? e.message : e));
    process.exit(process.argv.includes('--hook') ? 0 : 1);
  });
}
