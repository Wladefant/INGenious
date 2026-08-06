// evidence-doc.mjs — ALLE Screenshots eines Laufs als EIN Anhang.
//
// The problem this exists for, stated as a number: the run
// artifacts/TC-12345/run-20260722-133427 has 14 step screenshots. ado-upload may
// attach twelve files in total, of which four may be screenshots — so ten pictures
// stay on the tester's disk. An earlier fix made the choice of those four defensible
// (last-of-the-run, numerically sorted) but it could not make it complete, because
// the cap is per FILE.
//
// The way out is not a bigger cap, it is a different unit: put every screenshot into
// ONE document (a PDF, or an HTML file) and attach that instead of sending each picture
// on its own, which is what the per-file cap keeps refusing.
//
// A document is one attachment no matter how many pictures are inside it. Fourteen
// becomes one, the cap stops binding, and nothing has to be chosen away.
//
// ---------------------------------------------------------------- how it renders --
//
// NO new dependency, and on a locked-down corporate machine that is a constraint rather
// than a preference: nothing may be installed there. This repo has no
// package.json and no node_modules on purpose — every tool here is Node stdlib only.
// So `page.pdf()` from the playwright npm package is out: the package is not present
// and installing it is exactly the thing that is not allowed.
//
// What IS present on every one of these machines is a Chromium-family browser, and
// its own command line can print. Verified on a test device 2026-08-05:
//
//   chrome.exe --headless --disable-gpu --print-to-pdf=out.pdf in.html   -> exit 0
//
// So: build one self-contained HTML, hand it to whichever browser the device already
// has, keep the PDF. Preference order is Edge first — on a corporate Windows device it
// is managed and always installed — then Chrome, then a Playwright-managed Chromium
// (which only exists if somebody ran an install). Whichever answers is NAMED in the
// receipt; "it rendered" and "it rendered with what" are different facts.
//
// If no browser answers, the HTML is the deliverable, not an error. It is a single
// self-contained file with the images embedded, it opens on any machine, and it is the
// honest half of "ein PDF ODER ein Dokument". The receipt says which of the
// two a tester got, because a PDF that silently became an HTML is a surprise at the
// worst moment.
//
// ------------------------------------------------------------- what is in the doc --
//
// A picture nobody can place is not evidence. Every page therefore carries the step
// number, the step name, the action, the status and the timestamp, taken from the
// report's own data.js — the same source the HTML report reads, so the caption cannot
// drift from the report. The cover page lists EVERY step, including the ones that took
// no screenshot, so "step 2 has no picture" is visible instead of merely absent.
//
// Order is run order, by step NUMBER. This is an earlier bug restated: sorted as
// text, "Step-11" comes before "Step-9", and a document built in that order tells the
// story of the run wrong. Images that data.js does not mention are still included, at
// the end, labelled — evidence is never dropped for being unexplained.
//
// Usage:
//   node tools/evidence-doc.mjs --run-dir <ordner> [--out <datei>] [--format pdf|html]
//   node tools/evidence-doc.mjs --run-dir <ordner> --json
//   node tools/evidence-doc.mjs --selftest        # offline, kein Browser nötig
//
// Last stdout line is machine-readable:
//   BILD-DOKUMENT OK|FEHLER|LEER <one-line German message>
import { spawnSync } from 'node:child_process';
import { existsSync, mkdtempSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join, basename, resolve, extname } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { homedir, tmpdir } from 'node:os';

const __dirname = dirname(fileURLToPath(import.meta.url));

/** Default document name inside the run folder. German, like everything a tester sees. */
export const DOC_BASENAME = 'bild-dokument';
/** The sidecar that tells ado-upload which screenshots this document already carries. */
export const SIDECAR_NAME = DOC_BASENAME + '.json';

/** Mirror of ado-automark's per-file ceiling. Reported against, never silently enforced. */
const MAX_ATTACH_BYTES = 20 * 1024 * 1024;

/** A browser gets one minute to print; a hung browser must not hang a tester's run. */
const RENDER_TIMEOUT_MS = 60 * 1000;

/* ------------------------------------------------------------------ data.js ----- */

/**
 * The steps of the run, from the report's own data.js.
 *
 * data.js is `var DATA={…};` — the report loads it as a script. Parsing it as JSON
 * after stripping that wrapper is exact, not a guess: INGenious writes it with
 * JSON.stringify. Returns [] on anything unexpected, because a caption we cannot
 * trust is worse than no caption, and the filename fallback still knows the numbers.
 */
export function parseDataJs(text) {
  if (typeof text !== 'string') return [];
  const start = text.indexOf('{');
  const end = text.lastIndexOf('}');
  if (start < 0 || end <= start) return [];
  let data;
  try { data = JSON.parse(text.slice(start, end + 1)); } catch { return []; }
  const steps = [];
  for (const exe of data.EXECUTIONS || []) {
    for (const iteration of exe.STEPS || []) {
      for (const entry of iteration.data || []) {
        const s = entry && entry.data;
        if (!s || typeof s.stepno !== 'number') continue;
        steps.push({
          no: s.stepno,
          name: String(s.stepName || ''),
          action: String(s.action || ''),
          status: String(s.status || ''),
          at: String(s.tStamp || ''),
          description: String(s.description || ''),
          // "\img\Foo.png" -> "img/Foo.png"
          image: s.link ? String(s.link).replace(/\\/g, '/').replace(/^\/+/, '') : null,
        });
      }
    }
  }
  return steps;
}

/** Run-level facts for the cover page. Never throws; missing fields simply stay out. */
export function parseRunMeta(text) {
  const meta = {};
  if (typeof text !== 'string') return meta;
  const start = text.indexOf('{');
  const end = text.lastIndexOf('}');
  if (start < 0 || end <= start) return meta;
  let data;
  try { data = JSON.parse(text.slice(start, end + 1)); } catch { return meta; }
  const exe = (data.EXECUTIONS || [])[0] || {};
  const put = (k, v) => { if (v !== undefined && v !== null && String(v) !== '') meta[k] = String(v); };
  put('Testfall', exe.testcaseName);
  put('Szenario', exe.scenarioName);
  put('Testset', data.testsetName);
  put('Release', data.releaseName);
  put('Browser', [exe.browser, exe.bversion].filter(Boolean).join(' '));
  put('Plattform', exe.platform);
  put('Start', exe.startTime);
  put('Ende', exe.endTime);
  put('Dauer', exe.exeTime);
  put('Schritte', exe.noTests);
  put('Ergebnis', (data.nofailTests && data.nofailTests !== '0') ? 'FEHLGESCHLAGEN' : 'BESTANDEN');
  return meta;
}

/**
 * The step NUMBER out of an INGenious screenshot filename, e.g.
 * "Beispielfall_Beispielfall_Step-11_15-30-48.png" -> 11.
 * Used both to order orphan images and to match an image back to its step when
 * data.js is absent. Returns null when the name carries no step number.
 */
export function stepNoFromFileName(name) {
  const m = /_Step-(\d+)[_.]/i.exec(String(name));
  return m ? parseInt(m[1], 10) : null;
}

/** "15-30-48" out of the same filename, so a caption has a time even without data.js. */
export function timeFromFileName(name) {
  const m = /_Step-\d+_(\d{2})-(\d{2})-(\d{2})\./i.exec(String(name));
  return m ? m[1] + ':' + m[2] + ':' + m[3] : '';
}

/* ------------------------------------------------------------------ pages ------- */

const IMAGE_EXT = new Set(['.png', '.jpg', '.jpeg', '.gif', '.webp']);

/** Every image under img/, newest INGenious layout. Relative, forward slashes. */
export function listImages(runDir) {
  const dir = join(runDir, 'img');
  if (!existsSync(dir)) return [];
  let names;
  try { names = readdirSync(dir); } catch { return []; }
  return names
    .filter((n) => IMAGE_EXT.has(extname(n).toLowerCase()))
    .map((n) => 'img/' + n);
}

/**
 * The pages of the document, in RUN ORDER.
 *
 * Three rules, and the second one is the whole reason the earlier bug happened:
 *
 *   1. A step that data.js links to an image becomes a page, captioned from data.js.
 *   2. Order is by step NUMBER, numerically. Sorted as text, "Step-11" precedes
 *      "Step-9" and the document narrates the run in an order it never happened in.
 *   3. An image data.js does not mention is STILL a page, appended at the end and
 *      labelled. A picture we cannot explain is a picture we keep anyway.
 */
export function buildPages(runDir, steps, images) {
  const have = new Set(images);
  const used = new Set();
  const pages = [];

  for (const s of steps.slice().sort((a, b) => a.no - b.no)) {
    if (!s.image) continue;
    if (!have.has(s.image)) continue;         // report references a file the folder lost
    if (used.has(s.image)) continue;
    used.add(s.image);
    pages.push({ rel: s.image, step: s, orphan: false });
  }

  const orphans = images
    .filter((rel) => !used.has(rel))
    .sort((a, b) => {
      const an = stepNoFromFileName(a), bn = stepNoFromFileName(b);
      if (an != null && bn != null && an !== bn) return an - bn;
      return a.localeCompare(b, undefined, { numeric: true });
    });
  for (const rel of orphans) {
    const no = stepNoFromFileName(rel);
    pages.push({
      rel, orphan: true,
      step: { no: no == null ? null : no, name: '', action: '', status: '', at: timeFromFileName(rel), description: '' },
    });
  }
  return pages;
}

/* -------------------------------------------------------------------- html ------ */

function esc(s) {
  return String(s ?? '')
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

/**
 * The readable part of an INGenious description.
 *
 * These arrive as HTML: a <br>-joined Java exception dump with a full stack trace and
 * a Playwright call log glued on. Taking the first line — the obvious move — yields
 * "Error: Error {", which is a caption that says nothing. The two facts a test manager
 * actually needs are further down and always in the same two places:
 *
 *   message='Timeout 30000ms exceeded.     -> WHAT went wrong
 *   - waiting for getByRole(… "Weiter") -> WHICH element it was waiting for
 *
 * So those are pulled out by name and joined. Everything else — the stack frames, the
 * temp paths of the Playwright driver, INGenious's own #CTAG end marker — is noise on
 * a printed page. Anything that does not match this shape falls back to the first line,
 * which is right for the short descriptions ("Opened Url: …") that need no surgery.
 */
export function shortDescription(text, limit = 220) {
  const lines = String(text ?? '')
    .replace(/#CTAG/g, '')
    .split(/<br\s*\/?>/i)
    .map((l) => l.replace(/<[^>]*>/g, '').replace(/\s+/g, ' ').trim())
    .filter(Boolean);
  const clip = (s) => (s.length > limit ? s.slice(0, limit - 1) + '…' : s);
  if (!lines.length) return '';

  const parts = [];
  const message = lines.find((l) => /^message='/.test(l));
  if (message) parts.push(message.replace(/^message='/, '').replace(/'$/, ''));
  const waiting = lines.find((l) => /waiting for /.test(l));
  if (waiting) parts.push('wartete auf ' + waiting.replace(/^[-\s]+/, '').replace(/^waiting for /, ''));
  if (parts.length) return clip(parts.join(' — '));

  return clip(lines[0]);
}

function statusClass(status) {
  const s = String(status || '').toUpperCase();
  if (s === 'FAIL') return 'fail';
  if (s === 'DONE' || s === 'PASS') return 'ok';
  return 'note';
}

function dataUri(file) {
  const ext = extname(file).toLowerCase();
  const mime = ext === '.jpg' || ext === '.jpeg' ? 'image/jpeg'
    : ext === '.gif' ? 'image/gif'
    : ext === '.webp' ? 'image/webp' : 'image/png';
  return 'data:' + mime + ';base64,' + readFileSync(file).toString('base64');
}

/**
 * One self-contained HTML: every image inlined as a data URI, no external file, no
 * font, no script. It has to survive being mailed, copied out of ADO, and opened on a
 * machine that has never seen this repo — so it may not reference anything at all.
 *
 * @page is landscape because every screenshot here is wider than it is tall; portrait
 * would print the same picture at roughly half the area for no gain.
 */
export function buildHtml({ runDir, tcId, pages, steps, meta }) {
  const total = pages.length;
  const runName = basename(runDir);
  const title = 'Testnachweis ' + (tcId ? 'TC-' + tcId : runName);

  const metaRows = Object.entries(meta).map(
    ([k, v]) => '<tr><th>' + esc(k) + '</th><td>' + esc(v) + '</td></tr>').join('\n');

  // Every step, not only the photographed ones: a step with no picture is a fact.
  const stepRows = steps.slice().sort((a, b) => a.no - b.no).map((s) => {
    const at = s.image ? pages.findIndex((p) => p.rel === s.image) : -1;
    return '<tr class="' + statusClass(s.status) + '">'
      + '<td class="num">' + esc(s.no) + '</td>'
      + '<td>' + esc(s.name) + '</td>'
      + '<td>' + esc(s.action) + '</td>'
      + '<td class="st">' + esc(s.status) + '</td>'
      + '<td class="num">' + (at >= 0 ? 'Bild ' + (at + 1) : '—') + '</td>'
      + '<td class="desc">' + esc(shortDescription(s.description, 120)) + '</td>'
      + '</tr>';
  }).join('\n');

  /**
   * Pictures are numbered, pages are not.
   *
   * The first draft printed "Seite 14 von 15" and was wrong on the real run: the step
   * table is as long as the run, so on a long run the cover spills onto a second sheet
   * and every page number after it — and every "S. N" cross-reference in the table —
   * is off by however many sheets the cover took. Chromium cannot tell the HTML how it
   * paginated, so a document that counts sheets is a document that miscounts them.
   * "Bild 14 von 14" is a number this file actually owns, and it is the number a reader
   * wants anyway: how far through the evidence they are, not which sheet they hold.
   */
  const foot = (label) => '<div class="foot">' + esc(title) + ' · Lauf ' + esc(runName)
    + ' · ' + esc(label) + '</div>';

  const shots = pages.map((p, i) => {
    const s = p.step;
    const head = p.orphan
      ? (s.no == null ? 'Screenshot ohne Schrittzuordnung' : 'Schritt ' + s.no + ' — ohne Eintrag im Bericht')
      : 'Schritt ' + s.no + ' — ' + (s.name || '(ohne Namen)');
    const bits = [];
    if (s.action) bits.push('Aktion: ' + s.action);
    if (s.at) bits.push(s.at);
    bits.push(basename(p.rel));
    const desc = shortDescription(s.description);
    return [
      '<section class="page">',
      '  <div class="cap ' + statusClass(s.status) + '">',
      '    <div class="cap-head"><span class="badge">' + esc(s.status || (p.orphan ? 'OHNE STATUS' : '')) + '</span>' + esc(head) + '</div>',
      '    <div class="cap-sub">' + esc(bits.join(' · ')) + '</div>',
      desc ? '    <div class="cap-desc">' + esc(desc) + '</div>' : '',
      '  </div>',
      '  <div class="shot"><img src="' + dataUri(join(runDir, p.rel)) + '" alt="' + esc(p.rel) + '"></div>',
      '  ' + foot('Bild ' + (i + 1) + ' von ' + total),
      '</section>',
    ].filter(Boolean).join('\n');
  }).join('\n');

  return `<!doctype html>
<html lang="de"><head><meta charset="utf-8"><title>${esc(title)}</title>
<style>
@page { size: A4 landscape; margin: 7mm; }
* { box-sizing: border-box; }
html, body { margin: 0; padding: 0; }
body { font-family: "Segoe UI", Arial, Helvetica, sans-serif; color: #14171a; font-size: 10pt;
       -webkit-print-color-adjust: exact; print-color-adjust: exact; }
h1 { font-size: 17pt; margin: 0 0 2mm; }
.sub { color: #5a6570; margin-bottom: 5mm; font-size: 9pt; }
.cover { page-break-after: always; }
.cover table { border-collapse: collapse; width: 100%; font-size: 8.5pt; }
.cover th, .cover td { border: 1px solid #d7dce1; padding: 1.1mm 2mm; text-align: left; vertical-align: top; }
.cover .meta { width: 45%; margin-bottom: 5mm; }
.cover .meta th { width: 32%; background: #f2f5f8; font-weight: 600; }
.cover h2 { font-size: 11pt; margin: 0 0 2mm; }
.steps th { background: #f2f5f8; font-weight: 600; }
.steps .num { text-align: right; white-space: nowrap; width: 8%; }
.steps .st { white-space: nowrap; width: 9%; font-weight: 600; }
.steps .desc { color: #5a6570; }
tr.fail .st { color: #b3261e; }
tr.ok .st { color: #1b6b2f; }
tr.note .st { color: #6b6b6b; }
.page { page-break-after: always; height: 189mm; display: flex; flex-direction: column; }
.page:last-child { page-break-after: auto; }
.cap { border-left: 3mm solid #9aa4ae; padding: 1.5mm 0 1.5mm 3mm; margin-bottom: 2.5mm; }
.cap.fail { border-left-color: #b3261e; }
.cap.ok { border-left-color: #1b6b2f; }
.cap-head { font-size: 12pt; font-weight: 600; }
.cap-sub { color: #5a6570; font-size: 8.5pt; margin-top: 0.8mm; }
.cap-desc { color: #333b42; font-size: 8.5pt; margin-top: 1mm; }
.badge { display: inline-block; min-width: 16mm; text-align: center; margin-right: 3mm;
         padding: 0.4mm 2mm; border-radius: 1mm; background: #e8ebee; color: #333b42;
         font-size: 8pt; font-weight: 700; letter-spacing: .04em; vertical-align: 1.5px; }
.fail .badge { background: #fbe5e3; color: #b3261e; }
.ok .badge { background: #e3f2e7; color: #1b6b2f; }
.shot { flex: 1 1 auto; min-height: 0; display: flex; align-items: center; justify-content: center;
        border: 1px solid #d7dce1; background: #fbfcfd; overflow: hidden; }
.shot img { max-width: 100%; max-height: 168mm; object-fit: contain; }
.foot { color: #8b949d; font-size: 7.5pt; padding-top: 1.5mm; }
</style></head><body>
<section class="cover">
  <h1>${esc(title)}</h1>
  <div class="sub">Alle ${total} Screenshot(s) dieses Laufs in einem Dokument · Lauf ${esc(runName)} · erzeugt ${esc(new Date().toISOString().replace('T', ' ').slice(0, 19))} UTC</div>
  ${metaRows ? '<table class="meta">' + metaRows + '</table>' : ''}
  <h2>Schritte</h2>
  <table class="steps"><tr><th class="num">Nr.</th><th>Schritt</th><th>Aktion</th><th>Status</th><th class="num">Bild</th><th>Meldung</th></tr>
${stepRows || '<tr><td colspan="6">Kein data.js im Laufordner — die Bilder folgen nach Dateinamen.</td></tr>'}
  </table>
  ${foot('Deckblatt und Schrittuebersicht')}
</section>
${shots}
</body></html>`;
}

/* ------------------------------------------------------------------ render ------ */

/**
 * Chromium-family browsers this device might have, best first.
 *
 * Edge leads deliberately: on a corporate Windows machine it is installed and managed,
 * so it is the one candidate that is there whether or not anyone ever ran a Playwright
 * install. The Playwright browsers come last for the same reason — they are the most
 * predictable build but the least certain to exist.
 */
export function browserCandidates(env = process.env) {
  const out = [];
  const push = (p) => { if (p && !out.includes(p)) out.push(p); };
  if (env.ING_CHROME) push(env.ING_CHROME);
  const pf86 = env['ProgramFiles(x86)'] || 'C:\\Program Files (x86)';
  const pf = env.ProgramFiles || 'C:\\Program Files';
  push(join(pf86, 'Microsoft', 'Edge', 'Application', 'msedge.exe'));
  push(join(pf, 'Microsoft', 'Edge', 'Application', 'msedge.exe'));
  push(join(pf86, 'Google', 'Chrome', 'Application', 'chrome.exe'));
  push(join(pf, 'Google', 'Chrome', 'Application', 'chrome.exe'));
  const root = env.PLAYWRIGHT_BROWSERS_PATH
    || join(env.LOCALAPPDATA || join(homedir(), 'AppData', 'Local'), 'ms-playwright');
  try {
    readdirSync(root)
      .filter((n) => /^chromium-\d+$/.test(n))
      .sort((a, b) => parseInt(b.slice(9), 10) - parseInt(a.slice(9), 10)) // newest build first
      .forEach((n) => { push(join(root, n, 'chrome-win64', 'chrome.exe')); push(join(root, n, 'chrome-win', 'chrome.exe')); });
  } catch { /* no playwright on this machine — the list above still stands */ }
  if (process.platform !== 'win32') {
    push('/usr/bin/microsoft-edge'); push('/usr/bin/google-chrome'); push('/usr/bin/chromium');
  }
  return out.filter((p) => existsSync(p));
}

/**
 * Print the HTML with the first browser that answers, and say WHICH one.
 *
 * --print-to-pdf-no-header removes Chromium's own header, which would otherwise stamp
 * the temp file:// path across the top of every page. Page numbering is in the HTML
 * instead, where it can name the test case too.
 *
 * A browser that exits 0 without writing a file counts as a failure here. That
 * combination is real — a policy-locked Edge can decline to run headless and still
 * exit clean — and treating it as success would produce a receipt announcing a PDF
 * that does not exist.
 */
export function renderPdf(htmlPath, pdfPath, env = process.env) {
  const tried = [];
  for (const exe of browserCandidates(env)) {
    const profile = mkdtempSync(join(tmpdir(), 'ing-evdoc-'));
    const r = spawnSync(exe, [
      '--headless', '--disable-gpu', '--no-sandbox', '--no-first-run',
      '--no-default-browser-check', '--disable-extensions', '--run-all-compositor-stages-before-draw',
      '--user-data-dir=' + profile,
      '--print-to-pdf-no-header', '--print-to-pdf=' + pdfPath,
      pathToFileURL(htmlPath).href,
    ], { encoding: 'utf8', timeout: RENDER_TIMEOUT_MS, windowsHide: true });
    rmSync(profile, { recursive: true, force: true });
    let bytes = 0;
    try { bytes = statSync(pdfPath).size; } catch { /* not written */ }
    if (bytes > 0) return { ok: true, engine: exe, bytes, tried };
    tried.push({
      exe,
      why: r.error ? String(r.error.code || r.error.message)
        : 'Exit ' + r.status + ', keine PDF geschrieben'
          + ((r.stderr || '').trim() ? ' — ' + (r.stderr || '').trim().split(/\r?\n/).slice(-1)[0] : ''),
    });
  }
  return { ok: false, engine: null, bytes: 0, tried };
}

/* -------------------------------------------------------------------- build ----- */

function mb(bytes) { return (bytes / 1048576).toFixed(1).replace('.', ',') + ' MB'; }

/**
 * Build the document for one run folder. Never throws.
 *
 * Returns the receipt, which is also written next to the document as bild-dokument.json.
 * `covers` in that receipt is the contract with ado-upload.mjs: these image files are
 * INSIDE the document, so they no longer need an attachment slot of their own. It lists
 * real relative paths rather than a count, because "the document covers all screenshots"
 * has to stay checkable against the folder rather than believed.
 */
export function buildDoc(opts = {}) {
  const runDir = resolve(opts.runDir);
  const receipt = {
    at: new Date().toISOString(), status: 'FEHLER', runDir, tcId: opts.tcId || null,
    format: null, doc: null, bytes: 0, imagePages: 0, images: 0, covers: [],
    engine: null, engineTried: [], overLimit: false, message: '',
  };
  if (!existsSync(runDir)) {
    receipt.message = 'Laufordner nicht gefunden: ' + runDir;
    return receipt;
  }
  if (!receipt.tcId) {
    const m = runDir.replace(/[\\/]+$/, '').match(/TC-(\d+)/);
    if (m) receipt.tcId = m[1];
  }

  const images = listImages(runDir);
  if (!images.length) {
    receipt.status = 'LEER';
    receipt.message = 'Keine Screenshots in ' + join(basename(runDir), 'img') + ' — kein Dokument erzeugt.';
    return receipt;
  }

  let steps = [], meta = {};
  const dataJs = join(runDir, 'data.js');
  if (existsSync(dataJs)) {
    try {
      const text = readFileSync(dataJs, 'utf8');
      steps = parseDataJs(text);
      meta = parseRunMeta(text);
    } catch { /* filenames still carry the step numbers */ }
  }

  const pages = buildPages(runDir, steps, images);
  receipt.images = images.length;
  // Image pages, NOT sheets. The cover carries one row per step, so on a long run it
  // spills onto a second sheet and a "15 Seiten" here would be as wrong as the footer
  // this file just stopped printing. One picture is one page; that much is owned.
  receipt.imagePages = pages.length;
  receipt.covers = pages.map((p) => p.rel);

  let html;
  try {
    html = buildHtml({ runDir, tcId: receipt.tcId, pages, steps, meta });
  } catch (e) {
    receipt.message = 'Dokument konnte nicht aufgebaut werden: ' + (e && e.message ? e.message : e);
    return receipt;
  }

  const wantHtml = opts.format === 'html';
  const htmlPath = join(runDir, DOC_BASENAME + '.html');
  const pdfPath = opts.out || join(runDir, DOC_BASENAME + '.pdf');

  // The HTML is written first in every case: it is both the print source and the
  // fallback deliverable, and writing it once keeps those from drifting apart.
  try { writeFileSync(htmlPath, html, 'utf8'); }
  catch (e) { receipt.message = 'HTML konnte nicht geschrieben werden: ' + e.message; return receipt; }

  if (!wantHtml && opts.format !== 'html') {
    const r = renderPdf(htmlPath, pdfPath, opts.env || process.env);
    receipt.engineTried = r.tried.map((t) => basename(t.exe) + ': ' + t.why);
    if (r.ok) {
      receipt.status = 'OK';
      receipt.format = 'pdf';
      receipt.doc = pdfPath;
      receipt.bytes = r.bytes;
      receipt.engine = r.engine;
      // The print source has served its purpose; leaving a 12-MB twin of the PDF in the
      // run folder would double the folder and invite the wrong file being attached.
      if (!opts.keep_html) { try { rmSync(htmlPath, { force: true }); } catch { /* harmless */ } }
    }
  }

  if (receipt.status !== 'OK') {
    // No browser, or the browser declined. The HTML stands on its own — one file,
    // images embedded, opens anywhere — so this is a different document, not a failure.
    let bytes = 0;
    try { bytes = statSync(htmlPath).size; } catch { /* just written, but never assume */ }
    receipt.status = 'OK';
    receipt.format = 'html';
    receipt.doc = htmlPath;
    receipt.bytes = bytes;
  }

  receipt.overLimit = receipt.bytes > MAX_ATTACH_BYTES;
  receipt.message = 'Bilddokument ' + basename(receipt.doc) + ' — ' + pages.length
    + ' Screenshot(s) auf je einer Seite plus Deckblatt, ' + mb(receipt.bytes)
    + (receipt.format === 'pdf' ? ' (gedruckt mit ' + basename(receipt.engine) + ')' : ' (HTML, kein Browser zum Drucken gefunden)')
    + (receipt.overLimit ? ' — ÜBER dem 20-MB-Anhanglimit, wird NICHT angehängt.' : '');

  try {
    writeFileSync(join(runDir, SIDECAR_NAME), JSON.stringify(receipt, null, 2), 'utf8');
  } catch (e) {
    // Without the sidecar ado-upload cannot know the document covers the screenshots,
    // so it will ration them as before. Degraded, not broken — but it must be said.
    receipt.message += ' (Hinweis: ' + SIDECAR_NAME + ' nicht schreibbar — ' + e.message + ')';
  }
  return receipt;
}

/**
 * The document ado-upload should attach for this run, or null.
 *
 * Reads the sidecar rather than guessing from a filename: a document left over from an
 * earlier build could otherwise be credited with covering screenshots taken after it.
 * Both the document and every image it claims must still exist on disk.
 */
export function readDocSidecar(runDir) {
  try {
    const s = JSON.parse(readFileSync(join(runDir, SIDECAR_NAME), 'utf8'));
    if (!s || !s.doc || !existsSync(s.doc)) return null;
    const covers = (Array.isArray(s.covers) ? s.covers : []).map((r) => String(r).replace(/\\/g, '/'));
    return { doc: s.doc, format: s.format, bytes: s.bytes, pages: s.pages, covers };
  } catch {
    return null;
  }
}

/* --------------------------------------------------------------------- CLI ------ */

function parseArgs(argv) {
  const o = {};
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--selftest' || a === '--json' || a === '--keep-html') o[a.slice(2).replace(/-/g, '_')] = true;
    else if (a.startsWith('--')) { o[a.slice(2).replace(/-/g, '_')] = argv[i + 1]; i++; }
  }
  return o;
}

function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.selftest) return selftest();
  if (!args.run_dir) {
    console.error('Aufruf: node tools/evidence-doc.mjs --run-dir <ordner> [--out <datei>] [--format pdf|html]');
    console.log('BILD-DOKUMENT FEHLER kein --run-dir angegeben');
    process.exit(2);
  }
  const receipt = buildDoc({ runDir: args.run_dir, out: args.out, format: args.format, tcId: args.tc, keep_html: args.keep_html });
  // Single line on purpose: ado-upload scans stdout for a line that is one JSON
  // object, exactly as it already does for ado-automark. Pretty-printing breaks that.
  if (args.json) console.log(JSON.stringify(receipt));
  else {
    process.stderr.write('[evidence-doc] ' + receipt.status + ' — ' + receipt.message + '\n');
    if (receipt.doc) process.stderr.write('[evidence-doc] Datei: ' + receipt.doc + '\n');
    for (const t of receipt.engineTried) process.stderr.write('[evidence-doc]   Browser abgelehnt — ' + t + '\n');
  }
  console.log('BILD-DOKUMENT ' + receipt.status + ' ' + receipt.message.replace(/[\r\n]+/g, ' '));
  process.exit(receipt.status === 'FEHLER' ? 1 : 0);
}

/* ---------------------------------------------------------------- selftest ------ */

function selftest() {
  const fails = [];
  const check = (name, cond) => { if (!cond) fails.push(name); };
  const tmp = join(tmpdir(), 'evidence-doc-selftest');
  rmSync(tmp, { recursive: true, force: true });
  mkdirSync(join(tmp, 'img'), { recursive: true });

  // A 1x1 PNG, so the fixture is a real image file and dataUri() has real bytes.
  const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==', 'base64');
  for (const [n, t] of [[3, '15-27-15'], [9, '15-30-18'], [11, '15-30-48'], [18, '15-34-21']]) {
    writeFileSync(join(tmp, 'img', 'Case_Case_Step-' + n + '_' + t + '.png'), PNG);
  }
  writeFileSync(join(tmp, 'img', 'Case_Case_Extra.png'), PNG); // no step number at all

  const DATA = {
    releaseName: 'Release1', testsetName: 'VideoSet', nofailTests: '1',
    EXECUTIONS: [{
      testcaseName: 'Case', scenarioName: 'Case', browser: 'Chromium', bversion: '145',
      platform: 'Windows 11', startTime: 'a', endTime: 'b', exeTime: '00:07:40', noTests: 18,
      STEPS: [{
        name: 'Iteration_1', type: 'iteration', data: [
          { data: { stepno: 1, stepName: 'Open', action: 'Open', status: 'DONE', tStamp: 't1', description: 'Opened Url' } },
          { data: { stepno: 3, stepName: 'Could not perfom [Fill] action', action: 'Fill', status: 'FAIL', tStamp: 't3', description: 'Error: boom<br>stack<br>#CTAG', link: '\\img\\Case_Case_Step-3_15-27-15.png' } },
          { data: { stepno: 9, stepName: 'Could not perfom [Click] action', action: 'Click', status: 'FAIL', tStamp: 't9', description: 'nine', link: '\\img\\Case_Case_Step-9_15-30-18.png' } },
          { data: { stepno: 11, stepName: 'Could not perfom [Click] action', action: 'Click', status: 'FAIL', tStamp: 't11', description: 'eleven', link: '\\img\\Case_Case_Step-11_15-30-48.png' } },
          { data: { stepno: 18, stepName: 'Could not perfom [Click] action', action: 'Click', status: 'FAIL', tStamp: 't18', description: 'eighteen', link: '\\img\\Case_Case_Step-18_15-34-21.png' } },
        ],
      }],
    }],
  };
  writeFileSync(join(tmp, 'data.js'), 'var DATA=' + JSON.stringify(DATA) + ';');

  const steps = parseDataJs(readFileSync(join(tmp, 'data.js'), 'utf8'));
  check('data.js yields every step, photographed or not', steps.length === 5);
  check('a step without a link has no image', steps.find((s) => s.no === 1).image === null);
  check('a link is normalised to a relative posix path',
    steps.find((s) => s.no === 3).image === 'img/Case_Case_Step-3_15-27-15.png');

  const meta = parseRunMeta(readFileSync(join(tmp, 'data.js'), 'utf8'));
  check('run metadata reaches the cover', meta.Browser === 'Chromium 145' && meta.Dauer === '00:07:40');
  check('a run with failures is not called BESTANDEN', meta.Ergebnis === 'FEHLGESCHLAGEN');

  const images = listImages(tmp);
  check('every image in img/ is found', images.length === 5);

  const pages = buildPages(tmp, steps, images);
  const order = pages.map((p) => p.rel);
  // THE earlier regression, in the document's own order this time.
  check('pages run in step-number order, not string order',
    order.slice(0, 4).join() === ['img/Case_Case_Step-3_15-27-15.png', 'img/Case_Case_Step-9_15-30-18.png',
      'img/Case_Case_Step-11_15-30-48.png', 'img/Case_Case_Step-18_15-34-21.png'].join());
  check('Step-9 comes before Step-11, which is the whole point',
    order.indexOf('img/Case_Case_Step-9_15-30-18.png') < order.indexOf('img/Case_Case_Step-11_15-30-48.png'));
  check('an image the report never mentions is kept, not dropped',
    order.includes('img/Case_Case_Extra.png'));
  check('the unexplained image is labelled as such',
    pages.find((p) => p.rel === 'img/Case_Case_Extra.png').orphan === true);
  check('EVERY screenshot becomes a page — that is the entire feature',
    pages.length === images.length);

  const html = buildHtml({ runDir: tmp, tcId: '12345', pages, steps, meta });
  check('the document embeds its images, referencing nothing external',
    (html.match(/src="data:image\/png;base64,/g) || []).length === 5 && !/src="(?!data:)/.test(html));
  check('a caption names its step', html.includes('Schritt 9 —'));
  check('the step index lists steps that took no picture', /<td class="num">1<\/td>/.test(html));
  check('a stack trace does not reach the page', !html.includes('stack'));
  check('the run is identified on the page', html.includes('TC-12345'));

  check('short description keeps the sentence and drops the trace',
    shortDescription('Error: boom<br>at Foo.bar<br>#CTAG') === 'Error: boom');
  // The real shape from run-20260722-133427. Taking the first line yields "Error: Error {",
  // which is a caption that tells a test manager nothing at all.
  const real = 'Error: Error {<br>  message=\'Timeout 30000ms exceeded.<br>  name=\'TimeoutError'
    + '<br>  stack=\'TimeoutError: Timeout 30000ms exceeded.<br>    at ProgressController.run (C:\\Temp\\x.js:86:28)'
    + '<br>}<br>Call log:<br>-   - waiting for getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Weiter"))<br>#CTAG';
  check('a timeout caption says what timed out and what it waited for, not "Error: Error {"',
    shortDescription(real) === 'Timeout 30000ms exceeded. — wartete auf getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Weiter"))');
  check('no stack frame and no driver temp path reaches the caption',
    !/ProgressController|C:\\Temp/.test(shortDescription(real)));
  check('step number is read numerically from the file name', stepNoFromFileName('X_Step-11_15-30-48.png') === 11);
  check('a file name without a step number says so', stepNoFromFileName('X_Extra.png') === null);

  // Build end to end. No browser is required: without one the HTML is the document.
  const built = buildDoc({ runDir: tmp, tcId: '12345' });
  check('a document is produced with or without a browser', built.status === 'OK' && existsSync(built.doc));
  check('the format actually delivered is named', built.format === 'pdf' || built.format === 'html');
  check('the document reports its own size', built.bytes > 0);
  check('the sidecar names every covered screenshot, by path not by count',
    built.covers.length === 5 && built.covers.includes('img/Case_Case_Extra.png'));

  const side = readDocSidecar(tmp);
  check('the sidecar is readable back', !!side && side.covers.length === 5 && existsSync(side.doc));

  const empty = buildDoc({ runDir: join(tmp, 'img') }); // a folder with no img/ subfolder
  check('a run with no screenshots is LEER, not a failure and not a fake document',
    empty.status === 'LEER' && empty.doc === null);
  check('a missing folder is an honest error',
    buildDoc({ runDir: join(tmp, 'does-not-exist') }).status === 'FEHLER');

  if (fails.length) {
    console.error('evidence-doc selftest FAILED: ' + fails.join(', '));
    process.exit(1);
  }
  console.log('evidence-doc selftest: GREEN — ' + pages.length + ' Seiten aus ' + images.length
    + ' Bildern, Format ' + built.format + ', ' + mb(built.bytes) + '.');
}

function invokedDirectly() {
  const entry = process.argv[1];
  if (!entry) return false;
  const real = (p) => resolve(p);
  return process.platform === 'win32'
    ? real(entry).toLowerCase() === real(fileURLToPath(import.meta.url)).toLowerCase()
    : real(entry) === real(fileURLToPath(import.meta.url));
}

if (invokedDirectly()) {
  try { main(); } catch (e) {
    process.stderr.write('[evidence-doc] unerwarteter Fehler: ' + (e && e.stack ? e.stack : e) + '\n');
    console.log('BILD-DOKUMENT FEHLER unerwarteter Fehler: ' + (e && e.message ? e.message : e));
    process.exit(1);
  }
}
