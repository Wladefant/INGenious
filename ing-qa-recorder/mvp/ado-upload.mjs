// ado-upload.mjs — THE TRIGGER for the ADO evidence upload (issue #124).
//
// ado-automark.mjs already does the whole ADO lifecycle (run, outcome, .docx,
// --attach evidence, comment, work-item link) and is proven against the real org.
// What was missing was anything that CALLS it when a tester finishes a run — so a
// tester got nothing unless somebody remembered the CLI. This file is that caller:
// end of run -> resolve the ADO id -> collect the evidence set -> invoke
// ado-automark -> leave a receipt a human can find.
//
// Three deliberate design rules, all of them lessons from #82:
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
// Usage:
//   node ado-upload.mjs --test-case 3951650 [--evidence <folder>] [--comment "..."]
//   node ado-upload.mjs --test-case-name "3951650 - Partner-Suche" --hook
//   node ado-upload.mjs --state          # just print whether it is on, and why
//   node ado-upload.mjs --selftest       # offline, no az, no network
//
// The last stdout line is always machine-readable, for the Java caller:
//   ADO-UPLOAD OK|FEHLER|AUS|UEBERSPRUNGEN <one-line German message>
//
// Comment policy: by default the comment is the ADO test case id and NOTHING else.
// The execution record lives in a live banking system; a Kontonummer or any other
// customer detail only travels when a caller passes --comment explicitly.
import { spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, readdirSync, readFileSync, realpathSync, statSync, writeFileSync, appendFileSync } from 'node:fs';
import { dirname, join, basename, isAbsolute, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { homedir } from 'node:os';

const __dirname = dirname(fileURLToPath(import.meta.url));
const AUTOMARK = join(__dirname, 'ado-automark.mjs');

/** Attach at most this many files, so a 200-screenshot run does not flood the result. */
const DEFAULT_MAX_FILES = 12;
/** ado-automark caps each file at 20MB; this caps the whole upload. */
const MAX_TOTAL_BYTES = 40 * 1024 * 1024;
/**
 * ado-automark allows an interactive `az login` five minutes, so this has to outlast it —
 * but that only ever applies to a human at a command line. Since the guard landed
 * (https://github.com/Wladefant/ing-qa-automation/commit/976586c) a context that declares
 * itself non-interactive — ADO_NONINTERACTIVE=1, TF_BUILD, CI — refuses the login and
 * answers in about a second with an instruction. Studio sets ADO_NONINTERACTIVE=1 because
 * it opens the sign-in itself, in a window the tester can see (AdoSignIn, issue #128); a
 * pipeline agent sets ADO_BEARER. So the six minutes are the ceiling for the interactive
 * case and nothing else waits them out.
 */
const AUTOMARK_TIMEOUT_MS = 6 * 60 * 1000;

/* ------------------------------------------------------------------ state ---- */

/**
 * Whether the upload runs, and the sentence explaining it. Default is ON: the
 * whole point of #124 is that an opt-in flag is indistinguishable from a bug.
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
    // artifacts/TC-3951650/run-20260727-201500 — collect-artifacts.mjs writes this shape.
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
  if (/^detailed-v2\.html$/.test(p)) return 2;
  if (/^[^/]+-v2\.html$/.test(p)) return 3;           // per-test report
  if (/^data\.js$/.test(p)) return 4;                 // the report's data (html is blank without it)
  if (/^traces\/.*\.zip$/.test(p)) return 5;          // Playwright trace = the step-by-step film
  if (/^img\/.*\.(png|jpe?g)$/.test(p)) return 6;     // step screenshots
  if (/^logs\/.*\.txt$/.test(p)) return 7;
  if (/^webservice\/.*\.txt$/.test(p)) return 8;      // API step responses (INGenious 3.1)
  if (/^console\.txt$/.test(p)) return 9;
  if (/^manifest\.json$/.test(p)) return 10;
  return -1;                                          // not evidence
}

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
    try { size = statSync(full).size; } catch { continue; }
    out.push({ path: full, rel, rank, size });
  }
  return out;
}

/**
 * The evidence set for one run: the interesting files, best first, capped by count
 * and by total size. Returns what was taken AND what was left behind — a cap that
 * silently drops a video is the same failure mode this whole issue is about.
 */
export function collectEvidence(folder, maxFiles = DEFAULT_MAX_FILES) {
  if (!folder || !existsSync(folder)) return { files: [], skipped: [], folder: folder || null, exists: false };
  const all = walk(folder).sort((a, b) => a.rank - b.rank || a.rel.localeCompare(b.rel));
  const files = [];
  const skipped = [];
  let total = 0;
  for (const f of all) {
    if (files.length >= maxFiles) { skipped.push({ rel: f.rel, why: 'Limit ' + maxFiles + ' Dateien' }); continue; }
    if (total + f.size > MAX_TOTAL_BYTES) { skipped.push({ rel: f.rel, why: 'Gesamtgrenze 40 MB' }); continue; }
    files.push(f);
    total += f.size;
  }
  return { files, skipped, folder, exists: true, totalBytes: total };
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
    outcome: args.outcome == null ? null : String(args.outcome),
    evidenceFolder: null, attached: [], skipped: [],
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
  // ado-automark marks Passed and only Passed. A failed run must never be dressed
  // up as a pass, so the negative outcome is refused here rather than mis-marked.
  if (args.outcome && String(args.outcome).toLowerCase() !== 'passed') {
    receipt.status = 'UEBERSPRUNGEN';
    receipt.message = 'Ergebnis "' + args.outcome + '" — ADO-Upload übersprungen; '
      + 'ado-automark markiert ausschließlich Bestanden.';
    return receipt;
  }

  const folder = args.evidence
    ? (isAbsolute(args.evidence) ? args.evidence : resolve(process.cwd(), args.evidence))
    : newestRunFolder(adoId, repoRoot(), env);
  const maxFiles = args['max-files'] ? parseInt(args['max-files'], 10) : DEFAULT_MAX_FILES;
  const evidence = collectEvidence(folder, maxFiles);
  receipt.evidenceFolder = evidence.folder;
  receipt.skipped = evidence.skipped;

  // Comment policy: the id and nothing else unless a caller opts into more.
  const comment = args.comment && String(args.comment).trim() ? String(args.comment).trim() : String(adoId);
  receipt.comment = comment;

  const argv = ['--test-case', String(adoId), '--label', String(adoId), '--comment', comment];
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
      receipt.message = 'Probelauf: ' + evidence.files.length + ' Datei(en) wären angehängt worden — '
        + 'es wurde NICHTS nach ADO geschrieben.';
      return receipt;
    }
    receipt.status = 'OK';
    receipt.runId = run.result.runId;
    receipt.runUrl = run.result.runUrl;
    receipt.message = 'ADO-Lauf ' + run.result.runId + ' angelegt, ' + evidence.files.length
      + ' Datei(en) angehängt. ' + (run.result.runUrl || '');
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
    if (a === '--hook' || a === '--dry-run' || a === '--state' || a === '--selftest') o[a.slice(2)] = true;
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
  process.stderr.write('[ado-upload] Angefordertes Ergebnis: ' + (receipt.outcome ?? '(keins angegeben)') + '\n');
  if (receipt.evidenceFolder) process.stderr.write('[ado-upload] Evidenz: ' + receipt.evidenceFolder + '\n');
  for (const f of receipt.attached) process.stderr.write('[ado-upload]   + ' + f.rel + ' (' + f.bytes + ' B)\n');
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
  check('id from plain name', adoIdFromTestCaseName('3951650 - Partner-Suche') === '3951650');
  check('id survives rename', adoIdFromTestCaseName('3951650 - Partner-Suche (neu)') === '3951650');
  check('id rejects glued digits', adoIdFromTestCaseName('12345Foo') === null);
  check('id rejects no digits', adoIdFromTestCaseName('Partner-Suche') === null);
  check('id from bare id', adoIdFromTestCaseName('3951650') === '3951650');

  // state — default ON is the whole point of the issue
  check('state default on', uploadState({}).enabled === true);
  check('state off by 0', uploadState({ ING_ADO_UPLOAD: '0' }).enabled === false);
  check('state off by off', uploadState({ ING_ADO_UPLOAD: 'off' }).enabled === false);
  check('state on by 1', uploadState({ ING_ADO_UPLOAD: '1' }).enabled === true);

  // id resolution order
  check('id from --test-case', resolveAdoId({ 'test-case': '111' }, {}).adoId === '111');
  check('id from name', resolveAdoId({ 'test-case-name': '222 - x' }, {}).adoId === '222');
  check('id from folder', resolveAdoId({ evidence: 'C:\\a\\artifacts\\TC-333\\run-1' }, {}).adoId === '333');

  // evidence picking on a realistic INGenious run folder
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
  writeFileSync(join(tmp, 'img', 'Step-15.png'), 'P');
  writeFileSync(join(tmp, 'logs', 'Case.txt'), 'L');
  mkdirSync(join(tmp, 'webservice'), { recursive: true });
  writeFileSync(join(tmp, 'webservice', 'Case_Step-3_Response.txt'), 'R');
  writeFileSync(join(tmp, 'traces', 'Case_10-00-00', 'traces.zip'), 'Z');
  writeFileSync(join(tmp, 'media', 'css', 'bootstrap.min.css'), 'CSS');
  const ev = collectEvidence(tmp);
  const rels = ev.files.map((f) => f.rel.replace(/\\/g, '/'));
  check('summary first', rels[0] === 'summary-v2.html');
  check('trace included', rels.includes('traces/Case_10-00-00/traces.zip'));
  check('screenshot included', rels.includes('img/Step-15.png'));
  check('log included', rels.includes('logs/Case.txt'));
  check('api response included', rels.includes('webservice/Case_Step-3_Response.txt'));
  check('media excluded', !rels.some((r) => r.startsWith('media/')));
  check('docx excluded (ado-automark attaches it)', !rels.some((r) => r.endsWith('.docx')));
  const capped = collectEvidence(tmp, 2);
  check('cap honoured', capped.files.length === 2);
  check('cap is not silent', capped.skipped.length > 0 && capped.skipped.every((s) => s.why));

  // Full path through ado-automark --dry-run: offline, no az, no network.
  const off = await upload({ 'test-case': '3951650' }, { ING_ADO_UPLOAD: '0' });
  check('off short-circuits', off.status === 'AUS' && off.runId === null);
  const failed = await upload({ 'test-case': '3951650', outcome: 'failed' }, {});
  check('failed outcome never marks Passed', failed.status === 'UEBERSPRUNGEN');

  // The requested outcome survives into the receipt on EVERY path, including the ones
  // that return before anything is attempted. Without this, "a failed run is never
  // marked Bestanden" was a property no receipt could be used to audit: a switched-off
  // run of a failed test and of a passing one wrote the same evidence.
  const offFailed = await upload({ 'test-case': '3951650', outcome: 'failed' }, { ING_ADO_UPLOAD: '0' });
  check('a switched-off run still records WHICH outcome was asked for',
    offFailed.status === 'AUS' && offFailed.outcome === 'failed');
  check('a switched-off passing run is distinguishable from a failed one',
    (await upload({ 'test-case': '3951650', outcome: 'passed' }, { ING_ADO_UPLOAD: '0' })).outcome === 'passed');
  check('the refused outcome is in the receipt, not only in the message', failed.outcome === 'failed');
  check('no outcome given is recorded as none, never as passed',
    off.outcome === null);
  const none = await upload({}, { ING_TESTCASE_SELECTION: join(tmp, 'no-such-selection.json') });
  check('no id is reported, not guessed', none.status === 'FEHLER' && none.adoId === null);
  const dry = await upload({ 'test-case': '3951650', evidence: tmp, 'dry-run': true }, {});
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
