// ado-automark.mjs — the ADO auto-mark path that survives Conditional Access. A bare
// `az login` is not enough on a directory that enforces it; this uses the pattern that
// works: Entra bearer via `az account get-access-token --resource <ADO_RESOURCE_ID>`,
// auto `az login --tenant <tenant>` on demand, ~50-min token cache, then the ADO REST
// lifecycle to mark a Test Case Passed + attach the .docx evidence + comment the work
// item. No personal access token, ever — short-lived Entra tokens only.
//
// Tenant, organisation, project and plan are NOT in this file. They come from the
// environment or from ing-config.json beside the tools; see tools/lib/ing-config.mjs.
//
// Headless callers (pipeline agents) set ADO_BEARER=<Entra token for the ADO resource>.
// It takes precedence over the token cache and over az — same variable, same precedence
// as tools/ado-mark.mjs — and is the one path that provably never spawns az, so an agent
// can never be parked on an interactive login. Without it, a context that declares itself
// non-interactive (ADO_NONINTERACTIVE=1, TF_BUILD, CI) fails with that instruction instead
// of opening a browser prompt nobody will see. Still no PAT, in either path.
//
// Usage:
//   node ado-automark.mjs --test-case 12345 --evidence "C:\\path\\to\\Evidence" [--plan 678] [--suite 0] [--comment "..."] [--attach "C:\\path\\clip.webm" --attach "C:\\path\\log.html"] [--dry-run]
//   node ado-automark.mjs --selftest        # offline dry-run + assertions (no az, no network)
// --attach may repeat; each file (any extension: .zip/.webm/.html/.png…) is attached to the test
// RESULT (GeneralAttachment) AND linked on the work item. Files >20MB are skipped; missing files logged.
// Programmatic: import { markPassed } from './ado-automark.mjs'
//
// Defaults (override via env ADO_ORG/ADO_PROJECT/ADO_TEST_PLAN_ID/ADO_TEST_SUITE_ID/ADO_AUTOPILOT_DRYRUN):
import { spawnSync } from 'node:child_process';
import { readFileSync, writeFileSync, existsSync, mkdirSync, mkdtempSync, chmodSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { homedir, tmpdir } from 'node:os';
import { config, requireValue } from './lib/ing-config.mjs';

// Azure DevOps' own resource id. A constant of the Microsoft platform, identical for every
// customer and published in Microsoft's documentation — this one is not an organisation
// detail and stays here.
const ADO_RESOURCE_ID = '499b84ac-1321-427f-aa17-267ca6975798';
const API_VERSION = '7.1';
const MAX_ATTACH_BYTES = 20 * 1024 * 1024; // 20MB — files above this are skipped (no partial upload)

/**
 * Which organisation this run points at.
 *
 * Read, not known: tenant, organisation, project and plan used to be literals right here.
 * They are identifiers of one company's Azure DevOps, and this file is a publication
 * candidate. {@link config} takes them from the environment or from ing-config.json beside
 * the tools; anything nobody supplied stays EMPTY and fails at first use with a sentence
 * naming the key — never with a guessed organisation, which would write into a stranger's
 * Azure DevOps. A dry run is the one exemption, for the reason spelled out at the getters.
 */
const CFG = {
  // Der Probelauf kommt ohne Organisation aus — dieselbe DRY-Ausnahme, die der Token-Pfad
  // schon hat (getOrFetchToken gibt bei DRY sofort 'DRY-RUN-TOKEN' zurueck). Ein --dry-run
  // schreibt diese Namen ausschliesslich in den Plan und oeffnet nie einen Socket; er muss
  // deshalb auch dort laufen, wo es weder ing-config.json noch ADO_*-Variablen gibt (CI,
  // fremder Rechner). Geraten wird trotzdem nichts: der echte Lauf besteht unveraendert auf
  // requireValue und bricht mit dem deutschen Satz ab, statt in eine fremde Organisation zu
  // schreiben.
  get org() { return DRY ? (config().org || 'DRY-RUN-ORG') : requireValue('org', 'Die Azure-DevOps-Organisation'); },
  get project() { return DRY ? (config().project || 'DRY-RUN-PROJECT') : requireValue('project', 'Das Azure-DevOps-Projekt'); },
  get planId() { return parseInt(DRY ? (config().plan || '0') : requireValue('plan', 'Die Id des Testplans'), 10); },
  // 0 = auto-discover, and an unconfigured suite means exactly that, so this one has an
  // honest default rather than an error.
  get suiteId() { return parseInt(config().suite || '0', 10); },
};

const CACHE_DIR = join(process.env.LOCALAPPDATA || homedir(), 'IngQaAutopilot');
const TOKEN_CACHE = join(CACHE_DIR, 'token.json');
const SUITE_CACHE = join(CACHE_DIR, 'suite-cache.json');

let DRY = false;
const dryPlan = []; // recorded calls in dry-run

function log(m) { process.stderr.write('[ado-automark] ' + m + '\n'); }

/* ----------------------------- token via az ----------------------------- */

function azCandidates() {
  return [
    join(homedir(), 'azure-cli', 'bin', 'az.cmd'),
    'C:\\Program Files (x86)\\Microsoft SDKs\\Azure\\CLI2\\wbin\\az.cmd',
    'C:\\Program Files\\Microsoft SDKs\\Azure\\CLI2\\wbin\\az.cmd',
  ];
}

function runAz(args, timeoutMs, interactive = false) {
  const win = process.platform === 'win32';
  const opts = { timeout: timeoutMs, windowsHide: true, shell: win, encoding: 'utf8' };
  if (interactive) opts.stdio = 'inherit';
  // Try `az` on PATH first (shell resolves az.cmd on Windows), then known locations.
  let r = spawnSync('az', args, opts);
  if (r.error && r.error.code === 'ENOENT') {
    for (const c of azCandidates()) {
      if (existsSync(c)) { r = spawnSync(c, args, { ...opts, shell: false }); break; }
    }
  }
  return {
    code: typeof r.status === 'number' ? r.status : (r.error ? -1 : 1),
    stdout: r.stdout || '',
    stderr: r.stderr || (r.error ? String(r.error.message) : ''),
  };
}

/**
 * When a token really dies, according to the token.
 *
 * `az account get-access-token` does NOT always mint. It serves its own cache, so a token
 * handed to us "just now" can already be nearly an hour old — and until 2026-08-05 we wrote
 * `now + 50 minutes` over the top of that and believed it. Measured on a test device that day: the
 * cache file said the token was good until 12:46, the token itself expired at 12:19, and Azure
 * DevOps sided with the token. Every request after 12:19 came back as a 302 to the sign-in page,
 * which is how a fourteen-minute test-plan fetch and an ado-upload run both failed with what
 * looked like two unrelated bugs ("HTTP 302 <html>…", "Unexpected token '<' … is not valid
 * JSON") and were one.
 *
 * The `exp` claim is epoch seconds and unambiguous. az's own `expiresOn` is a LOCAL time with no
 * zone on it, so it is the worse of the two answers even though it is the more obvious one.
 *
 * @returns {number} epoch seconds, or 0 when this is not a readable JWT — then nothing is known
 *   and the caller keeps its old assumption rather than inventing a stricter one.
 */
function tokenExpiry(token) {
  try {
    const payload = String(token).split('.')[1];
    if (!payload) return 0;
    const claims = JSON.parse(Buffer.from(payload, 'base64url').toString('utf8'));
    return Number(claims.exp) > 0 ? Number(claims.exp) : 0;
  } catch { return 0; }
}

function readTokenCache() {
  try {
    if (!existsSync(TOKEN_CACHE)) return null;
    const c = JSON.parse(readFileSync(TOKEN_CACHE, 'utf8'));
    if (c && c.access_token && c.expires_at && Number(c.expires_at) > Math.floor(Date.now() / 1000) + 60) {
      // Belt and braces: an entry written by an older build carries the 50-minute guess, and
      // this is the read that would otherwise hand that stale token out one more time.
      const real = tokenExpiry(c.access_token);
      if (real > 0 && real <= Math.floor(Date.now() / 1000) + 60) return null;
      return c.access_token;
    }
  } catch { /* fall through */ }
  return null;
}

function cacheToken(token, expiresInSec) {
  try {
    mkdirSync(CACHE_DIR, { recursive: true });
    // The EARLIER of what we were told to assume and what the token says about itself.
    const assumed = Math.floor(Date.now() / 1000) + expiresInSec - 60;
    const real = tokenExpiry(token);
    const expiresAt = real > 0 ? Math.min(assumed, real - 60) : assumed;
    writeFileSync(TOKEN_CACHE, JSON.stringify({ access_token: token, expires_at: expiresAt }), 'utf8');
  } catch (e) { log('could not cache token (non-fatal): ' + e.message); }
}

function mintToken() {
  return runAz(['account', 'get-access-token', '--resource', ADO_RESOURCE_ID, '--query', 'accessToken', '-o', 'tsv'], 20000);
}

/**
 * Why an `az login` must NOT be opened here, or null when opening one is fine.
 *
 * The interactive login below is correct on a tester's laptop and wrong everywhere a
 * browser prompt would be shown to nobody: on a pipeline agent it simply waits out its
 * five-minute budget and then reports failure, which reads as a hang. Only explicit
 * signals count — a laptop and the Studio child process both come back null here, so the
 * proven local behaviour is untouched.
 */
function noninteractiveReason(env = process.env) {
  const explicit = String(env.ADO_NONINTERACTIVE || '').trim().toLowerCase();
  if (explicit && explicit !== '0' && explicit !== 'false' && explicit !== 'nein') {
    return 'ADO_NONINTERACTIVE=' + explicit;
  }
  if (env.TF_BUILD) return 'TF_BUILD (Azure Pipelines agent)';
  if (env.CI) return 'CI=' + String(env.CI);
  return null;
}

/** The one sentence a headless caller needs: what to set, and that it is not a PAT. */
function bearerHint() {
  return 'Set ADO_BEARER to a short-lived Entra token for the ADO resource '
    + ADO_RESOURCE_ID + ' (in Azure Pipelines: $(System.AccessToken)). No personal access token.';
}

export function getOrFetchToken() {
  if (DRY) return 'DRY-RUN-TOKEN';
  // Injected bearer wins over everything, exactly as in tools/ado-mark.mjs
  // (resolveCredential): same variable, same precedence, so the two markers cannot
  // drift into different auth rules. It is also the only path that is guaranteed never
  // to spawn az — no cache read, no mint, and no interactive login can follow a return
  // from here.
  const injected = String(process.env.ADO_BEARER || '').trim();
  if (injected) {
    log('using the injected ADO_BEARER (az is not consulted).');
    return injected;
  }
  const cached = readTokenCache();
  if (cached) return cached;

  let m = mintToken();
  if (m.code === 0 && m.stdout.trim()) {
    const t = m.stdout.trim();
    cacheToken(t, 50 * 60);
    log('token minted via az; cached ~50 min.');
    return t;
  }
  const err = (m.stderr || '').toLowerCase();
  const needsLogin = err.includes('az login') || err.includes('please run') || err.includes('no subscription') || err.includes('aadsts');
  if (needsLogin) {
    const headless = noninteractiveReason();
    if (headless) {
      log('no az session and no ADO_BEARER, and this is a non-interactive context (' + headless
        + ') — `az login` is NOT opened: it would wait on a browser prompt nobody can see. '
        + bearerHint());
      return null;
    }
    const tenantId = requireValue('tenantId', 'Das Entra-Verzeichnis (tenant) fuer die Anmeldung');
    log('no active az session — opening `az login --tenant ' + tenantId + '` (browser)…');
    const login = runAz(['login', '--tenant', tenantId], 5 * 60 * 1000, true);
    if (login.code !== 0) { log('az login did not complete (exit ' + login.code + ').'); return null; }
    m = mintToken();
    if (m.code === 0 && m.stdout.trim()) {
      const t = m.stdout.trim();
      cacheToken(t, 50 * 60);
      log('token minted after auto-login; cached ~50 min.');
      return t;
    }
    log('token mint still failing after az login: ' + m.stderr);
    return null;
  }
  log('az account get-access-token failed: ' + m.stderr);
  return null;
}

/* ----------------------------- ADO REST (fetch) ----------------------------- */

function cannedFor(method, url) {
  // Deterministic canned responses so --dry-run/--selftest run with no network.
  // The ids below are invented (4711/77777/99999/88888) so that a dry-run plan can be
  // pasted into a ticket without carrying a real suite id out of the organisation.
  if (/\/testplan\/suites\?testCaseId=/.test(url)) return { value: [{ id: 4711, plan: { id: CFG.planId } }] };
  if (/\/Suites\/\d+\/TestPoint\?testCaseId=/.test(url)) return { value: [{ id: 77777 }] };
  if (method === 'POST' && /\/test\/runs\?/.test(url)) return { id: 99999 };
  if (method === 'GET' && /\/test\/runs\/\d+\/results\?/.test(url)) return { value: [{ id: 88888 }] };
  if (/\/wit\/workitems\/\d+\?/.test(url)) return { fields: { 'Microsoft.VSTS.TCM.Steps': '<steps id="0" last="3"><step id="2" type="ValidateStep"/><step id="3" type="ActionStep"/></steps>' } };
  if (/\/wit\/attachments\?/.test(url)) return { url: 'https://dev.azure.com/_apis/wit/attachments/DRY' };
  return {};
}

async function adoFetch(method, url, token, body, contentType = 'application/json') {
  if (DRY) {
    dryPlan.push({ method, url: url.replace(/^https?:\/\/[^/]+/, ''), body: body && typeof body !== 'string' ? body : body || null });
    return cannedFor(method, url);
  }
  const headers = { Authorization: 'Bearer ' + token, Accept: 'application/json' };
  const init = { method, headers };
  if (body != null) {
    headers['Content-Type'] = contentType;
    init.body = typeof body === 'string' ? body : JSON.stringify(body);
  }
  const res = await fetch(url, init);
  const text = await res.text();
  if (!res.ok) throw new Error('ADO ' + method + ' ' + url + ' failed: HTTP ' + res.status + ' ' + text);
  return text.trim() ? JSON.parse(text) : {};
}

async function adoFetchRaw(url, token, bytes) {
  if (DRY) { dryPlan.push({ method: 'POST', url: url.replace(/^https?:\/\/[^/]+/, ''), body: { bytes: bytes.length } }); return cannedFor('POST', url); }
  const res = await fetch(url, { method: 'POST', headers: { Authorization: 'Bearer ' + token, Accept: 'application/json', 'Content-Type': 'application/octet-stream' }, body: bytes });
  const text = await res.text();
  if (!res.ok) throw new Error('ADO POST(raw) ' + url + ' failed: HTTP ' + res.status + ' ' + text);
  return text.trim() ? JSON.parse(text) : {};
}

async function adoFetchWithHeaders(url, token) {
  if (DRY) { dryPlan.push({ method: 'GET', url: url.replace(/^https?:\/\/[^/]+/, '') }); return { body: { value: [] }, continuationToken: null }; }
  const res = await fetch(url, { headers: { Authorization: 'Bearer ' + token, Accept: 'application/json' } });
  const text = await res.text();
  if (!res.ok) throw new Error('ADO GET ' + url + ' failed: HTTP ' + res.status + ' ' + text);
  return { body: text.trim() ? JSON.parse(text) : { value: [] }, continuationToken: res.headers.get('x-ms-continuationtoken') };
}

/* ----------------------------- suite cache ----------------------------- */

function cachedSuiteFor(planId, testCaseId) {
  try {
    if (!existsSync(SUITE_CACHE)) return 0;
    const all = JSON.parse(readFileSync(SUITE_CACHE, 'utf8'));
    const plan = all?.[String(planId)];
    return plan?.[String(testCaseId)] ? Number(plan[String(testCaseId)]) : 0;
  } catch { return 0; }
}
function cacheSuite(planId, testCaseId, suiteId) {
  if (DRY) return; // dry-run/selftest must not pollute the real on-disk suite cache (keeps --selftest idempotent)
  try {
    mkdirSync(CACHE_DIR, { recursive: true });
    let all = {};
    if (existsSync(SUITE_CACHE)) { try { all = JSON.parse(readFileSync(SUITE_CACHE, 'utf8')); } catch { all = {}; } }
    all[String(planId)] = all[String(planId)] || {};
    all[String(planId)][String(testCaseId)] = suiteId;
    writeFileSync(SUITE_CACHE, JSON.stringify(all), 'utf8');
  } catch (e) { log('could not write suite cache (non-fatal): ' + e.message); }
}

/* ----------------------------- suite/point resolution ----------------------------- */

async function tryGetPointId(baseUrl, planId, suiteId, testCaseId, token) {
  try {
    const pts = await adoFetch('GET', baseUrl + '/testplan/Plans/' + planId + '/Suites/' + suiteId + '/TestPoint?testCaseId=' + testCaseId + '&api-version=' + API_VERSION, token);
    const v = pts?.value || [];
    return v.length ? Number(v[0].id) : 0;
  } catch { return 0; }
}

async function findSuiteIdsByTestCaseId(org, planId, testCaseId, token) {
  const orgUrl = 'https://dev.azure.com/' + org + '/_apis';
  const attempts = [['testplan', '7.1'], ['testplan', '7.0'], ['testplan', '6.0'], ['test', '5.0']];
  for (const [ns, v] of attempts) {
    try {
      const resp = await adoFetch('GET', orgUrl + '/' + ns + '/suites?testCaseId=' + testCaseId + '&api-version=' + v, token);
      const values = resp?.value || [];
      const inPlan = values.filter((s) => s.plan && Number(s.plan.id) === planId).map((s) => Number(s.id));
      log('fast-path /' + ns + '/suites@' + v + ': ' + values.length + ' suite(s), ' + inPlan.length + ' in plan ' + planId + '.');
      return inPlan;
    } catch { /* try next */ }
  }
  log('fast-path TC->suites unavailable; will use cache then enumeration.');
  return null;
}

async function resolveSuiteAndPoint(baseUrl, org, planId, suiteId, testCaseId, token) {
  let effSuite = suiteId, point = 0;
  if (effSuite > 0) point = await tryGetPointId(baseUrl, planId, effSuite, testCaseId, token);

  if (point === 0) {
    const cached = cachedSuiteFor(planId, testCaseId);
    if (cached > 0) {
      const p = await tryGetPointId(baseUrl, planId, cached, testCaseId, token);
      if (p > 0) { effSuite = cached; point = p; log('cache hit: TC ' + testCaseId + ' -> suite ' + cached + '.'); }
    }
  }

  let fastAuthoritative = false;
  if (point === 0) {
    const cands = await findSuiteIdsByTestCaseId(org, planId, testCaseId, token);
    if (cands != null) {
      fastAuthoritative = true;
      for (const sid of cands) {
        const p = await tryGetPointId(baseUrl, planId, sid, testCaseId, token);
        if (p > 0) { effSuite = sid; point = p; cacheSuite(planId, testCaseId, sid); log('fast-path suite ' + sid + ' (cached).'); break; }
      }
    }
  }
  if (point === 0 && fastAuthoritative) {
    log('TC ' + testCaseId + ' not a member of any suite in plan ' + planId + '. Add it to a suite or override plan/suite.');
    return { suiteId: effSuite, pointId: 0 };
  }

  if (point === 0 && effSuite <= 0) {
    const suites = [];
    let cont = null, page = 0;
    while (page < 50) {
      let url = baseUrl + '/testplan/Plans/' + planId + '/Suites?asTreeView=true&api-version=' + API_VERSION;
      if (cont) url += '&continuationToken=' + encodeURIComponent(cont);
      const r = await adoFetchWithHeaders(url, token);
      suites.push(...((r.body && r.body.value) || []));
      cont = r.continuationToken; page++;
      if (!cont) break;
    }
    log('enumerating ' + suites.length + ' suite(s) of plan ' + planId + '.');
    for (const s of suites) {
      const p = await tryGetPointId(baseUrl, planId, Number(s.id), testCaseId, token);
      if (p > 0) { effSuite = Number(s.id); point = p; cacheSuite(planId, testCaseId, effSuite); log('auto-discovered suite ' + effSuite + '.'); break; }
    }
  }
  return { suiteId: effSuite, pointId: point };
}

/* ----------------------------- evidence / steps ----------------------------- */

function findDocx(folder) {
  if (!folder || !existsSync(folder)) return null;
  const stack = [folder];
  while (stack.length) {
    const d = stack.pop();
    let entries = [];
    try { entries = readdirSync(d); } catch { continue; }
    for (const name of entries) {
      const full = join(d, name);
      let st; try { st = statSync(full); } catch { continue; }
      if (st.isDirectory()) stack.push(full);
      else if (name.toLowerCase().endsWith('.docx')) return full;
    }
  }
  return null;
}

// Validate --attach files once: drop missing files (logged) and files >20MB (logged, no partial upload).
// __sizeOf is indirected so the offline selftest can fake a >20MB stat without a real big file.
function validateAttachments(paths) {
  const ok = [];
  for (const p of (paths || [])) {
    if (!p) continue;
    if (!existsSync(p)) { log('attach: file not found, skipping: ' + p); continue; }
    let size;
    try { size = __sizeOf(p); } catch (e) { log('attach: cannot stat, skipping: ' + p + ' (' + e.message + ')'); continue; }
    if (size > MAX_ATTACH_BYTES) { log('attach: skipping ' + basename(p) + ' — ' + (size / 1048576).toFixed(1) + ' MB exceeds 20 MB limit.'); continue; }
    ok.push(p);
  }
  return ok;
}

// Attach one file to the test RESULT (GeneralAttachment) — same call shape as the .docx path.
async function attachToResult(baseUrl, runId, resultId, token, filePath) {
  try {
    const b64 = DRY ? 'DRY' : readFileSync(filePath).toString('base64');
    await adoFetch('POST', baseUrl + '/test/runs/' + runId + '/results/' + resultId + '/attachments?api-version=' + API_VERSION, token,
      { stream: b64, fileName: basename(filePath), comment: 'ado-automark evidence', attachmentType: 'GeneralAttachment' });
    log('attached ' + basename(filePath) + ' to result ' + resultId + '.');
  } catch (e) { log('failed to attach ' + basename(filePath) + ' to result: ' + e.message); }
}

// Link one file on the WORK ITEM — upload to /wit/attachments then PATCH an AttachedFile relation.
async function linkToWorkItem(org, project, testCaseId, token, filePath) {
  try {
    const bytes = DRY ? Buffer.from('DRY') : readFileSync(filePath);
    const upload = await adoFetchRaw('https://dev.azure.com/' + org + '/' + project + '/_apis/wit/attachments?fileName=' + encodeURIComponent(basename(filePath)) + '&api-version=' + API_VERSION, token, bytes);
    const patchBody = [{ op: 'add', path: '/relations/-', value: { rel: 'AttachedFile', url: upload.url, attributes: { comment: 'ado-automark evidence ' + nowIso() } } }];
    await adoFetch('PATCH', 'https://dev.azure.com/' + org + '/' + project + '/_apis/wit/workitems/' + testCaseId + '?api-version=' + API_VERSION, token, patchBody, 'application/json-patch+json');
    log('attached ' + basename(filePath) + ' to work item ' + testCaseId + '.');
  } catch (e) { log('failed to attach ' + basename(filePath) + ' to work item: ' + e.message); }
}

function stepOutcomesFromXml(stepsXml) {
  const out = [];
  if (!stepsXml) return out;
  const re = /<step\b[^>]*\bid="(\d+)"/g;
  let m;
  while ((m = re.exec(stepsXml))) {
    const id = parseInt(m[1], 10);
    if (id > 0) out.push({ actionPath: id.toString(16).padStart(8, '0'), iterationId: 1, outcome: 'Passed' });
  }
  return out;
}

/* ----------------------------- mark Passed ----------------------------- */

export async function markPassed(opts) {
  const org = opts.org || CFG.org;
  const project = opts.project || CFG.project;
  const planId = opts.planId || CFG.planId;
  const suiteId = opts.suiteId != null ? opts.suiteId : CFG.suiteId;
  const testCaseId = Number(opts.testCaseId);
  const evidenceFolder = opts.evidence || null;
  const extraComment = opts.comment || '';
  const testLabel = opts.label || ('automark-' + testCaseId);
  if (!testCaseId) throw new Error('--test-case <id> required');

  const token = opts.token || getOrFetchToken();
  if (!token) { log('no usable token — skipping ADO update.'); return { ok: false, reason: 'no-token' }; }

  const baseUrl = 'https://dev.azure.com/' + org + '/' + project + '/_apis';
  const { suiteId: effSuite, pointId } = await resolveSuiteAndPoint(baseUrl, org, planId, suiteId, testCaseId, token);
  if (pointId === 0) { log('no Test Point for TC ' + testCaseId + ' (plan ' + planId + ', suite ' + effSuite + ').'); return { ok: false, reason: 'no-point' }; }

  // 2. create run
  const run = await adoFetch('POST', baseUrl + '/test/runs?api-version=' + API_VERSION, token,
    { name: 'AutoMark - ' + testLabel.replace(/[\r\n]/g, '') + ' - ' + nowIso(), plan: { id: planId }, pointIds: [pointId], automated: true });
  const runId = Number(run.id);

  // 3. get auto-generated result
  const results = await adoFetch('GET', baseUrl + '/test/runs/' + runId + '/results?api-version=' + API_VERSION, token);
  const resultId = Number((results.value || [])[0].id);

  // 4. step outcomes from TCM.Steps
  let actionResults = [];
  try {
    const wi = await adoFetch('GET', baseUrl + '/wit/workitems/' + testCaseId + '?$expand=all&api-version=' + API_VERSION, token);
    actionResults = stepOutcomesFromXml(wi.fields ? wi.fields['Microsoft.VSTS.TCM.Steps'] : null);
  } catch { /* not critical */ }

  // 5. PATCH result Passed
  let comment = 'Auto-marked by ado-automark on ' + nowIso() + '. Test: ' + testLabel;
  if (extraComment.trim()) comment += '\n\n' + extraComment;
  const patch = { id: resultId, outcome: 'Passed', state: 'Completed', comment };
  if (actionResults.length) patch.iterationDetails = [{ id: 1, outcome: 'Passed', comment, actionResults }];
  await adoFetch('PATCH', baseUrl + '/test/runs/' + runId + '/results?api-version=' + API_VERSION, token, [patch]);

  // 6. attach .docx to result
  const docx = findDocx(evidenceFolder);
  if (docx) {
    try {
      const b64 = DRY ? 'DRY' : readFileSync(docx).toString('base64');
      await adoFetch('POST', baseUrl + '/test/runs/' + runId + '/results/' + resultId + '/attachments?api-version=' + API_VERSION, token,
        { stream: b64, fileName: basename(docx), comment: 'ado-automark evidence', attachmentType: 'GeneralAttachment' });
      log('attached ' + basename(docx) + ' to result ' + resultId + '.');
    } catch (e) { log('failed to attach docx to result: ' + e.message); }
  }

  // 6b. attach any --attach evidence files to the result (before completeRun, like the .docx)
  const attachFiles = validateAttachments(opts.attach);
  for (const f of attachFiles) await attachToResult(baseUrl, runId, resultId, token, f);

  // 7. complete run
  await adoFetch('PATCH', baseUrl + '/test/runs/' + runId + '?api-version=' + API_VERSION, token, { state: 'Completed' });

  // 8. work-item HTML comment with both links
  const resultUrl = 'https://dev.azure.com/' + org + '/' + project + '/_testManagement/runs?_a=resultSummary&runId=' + runId + '&resultId=' + resultId;
  const runUrl = 'https://dev.azure.com/' + org + '/' + project + '/_TestManagement/Runs?runId=' + runId;
  try {
    const html = '<div><p><strong>Test Execution Passed</strong> - ' + nowIso() + '</p><ul>' +
      '<li><strong>Test Result:</strong> <a href="' + resultUrl + '">View Result Details</a></li>' +
      '<li><strong>Test Run:</strong> <a href="' + runUrl + '">' + runId + '</a></li>' +
      '<li><strong>Test:</strong> <code>' + testLabel + '</code></li>' +
      '<li>Auto-marked by ado-automark.</li></ul></div>';
    await adoFetch('POST', 'https://dev.azure.com/' + org + '/' + project + '/_apis/wit/workItems/' + testCaseId + '/comments?api-version=' + API_VERSION + '-preview.4', token, { text: html });
  } catch (e) { log('failed to add work-item comment: ' + e.message); }

  // 9. attach docx to work item
  if (docx) {
    try {
      const bytes = DRY ? Buffer.from('DRY') : readFileSync(docx);
      const upload = await adoFetchRaw('https://dev.azure.com/' + org + '/' + project + '/_apis/wit/attachments?fileName=' + encodeURIComponent(basename(docx)) + '&api-version=' + API_VERSION, token, bytes);
      const patchBody = [{ op: 'add', path: '/relations/-', value: { rel: 'AttachedFile', url: upload.url, attributes: { comment: 'ado-automark evidence ' + nowIso() } } }];
      await adoFetch('PATCH', 'https://dev.azure.com/' + org + '/' + project + '/_apis/wit/workitems/' + testCaseId + '?api-version=' + API_VERSION, token, patchBody, 'application/json-patch+json');
      log('attached ' + basename(docx) + ' to work item ' + testCaseId + '.');
    } catch (e) { log('failed to attach docx to work item: ' + e.message); }
  }

  // 9b. link any --attach evidence files on the work item (after completeRun, like the .docx)
  for (const f of attachFiles) await linkToWorkItem(org, project, testCaseId, token, f);

  log('DONE. TC ' + testCaseId + ' marked Passed. ' + runUrl);
  return { ok: true, runId, resultId, suiteId: effSuite, pointId, runUrl };
}

function basename(p) { return String(p).split(/[\\/]/).pop(); }
function nowIso() { return new Date(__now()).toISOString(); }
// __now indirection keeps the file testable without real clocks if ever needed.
function __now() { return Date.now(); }
// __sizeOf indirection lets the offline selftest fake a >20MB stat without a real big file.
let __sizeOf = (p) => statSync(p).size;

/* ----------------------------- CLI ----------------------------- */

function parseArgs(argv) {
  const o = {};
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--dry-run') o.dryRun = true;
    else if (a === '--selftest') o.selftest = true;
    else if (a === '--attach') { (o.attach = o.attach || []).push(argv[i + 1]); i++; }
    else if (a.startsWith('--')) { o[a.slice(2)] = argv[i + 1]; i++; }
  }
  return o;
}

/**
 * Offline proof of the two rules that decide whether this can run unattended.
 *
 * 1. An injected ADO_BEARER is used, and az is never spawned. Proven, not assumed: a fake
 *    `az` is put first on PATH which records every invocation — the recording must stay
 *    empty. This runs with DRY switched off, because the dry-run stub returns a token
 *    before any of the real resolution happens and would prove nothing.
 * 2. Without a bearer, a context that declares itself non-interactive fails with the
 *    instruction rather than opening a login. Proven in a child process (an empty token
 *    cache has to be arranged before the module loads), under a 60s cap: the interactive
 *    branch would sit on its five-minute budget, so a hang fails this check by timing out.
 *
 * Neither case reaches the network: the first returns before the first fetch, the second
 * never obtains a token at all.
 */
function selftestAuth() {
  const dir = mkdtempSync(join(tmpdir(), 'ado-automark-auth-'));
  const shimDir = join(dir, 'bin');
  mkdirSync(shimDir, { recursive: true });
  // A fake az that answers the way a logged-out az does, and records what it was asked.
  writeFileSync(join(shimDir, 'az.cmd'),
    '@echo off\r\n>>"%AZ_SHIM_LOG%" echo %*\r\n'
    + "echo ERROR: Please run 'az login' to setup account. 1>&2\r\nexit /b 1\r\n", 'utf8');
  writeFileSync(join(shimDir, 'az'),
    '#!/bin/sh\necho "$@" >> "$AZ_SHIM_LOG"\n'
    + 'echo "ERROR: Please run \'az login\' to setup account." >&2\nexit 1\n', 'utf8');
  try { chmodSync(join(shimDir, 'az'), 0o755); } catch { /* windows */ }
  const withShim = (p) => shimDir + (process.platform === 'win32' ? ';' : ':') + (p || '');

  // --- 1. injected bearer, in process ---
  const injectedLog = join(dir, 'az-calls-injected.log');
  const saved = { PATH: process.env.PATH, ADO_BEARER: process.env.ADO_BEARER, AZ_SHIM_LOG: process.env.AZ_SHIM_LOG };
  process.env.PATH = withShim(saved.PATH);
  process.env.AZ_SHIM_LOG = injectedLog;
  process.env.ADO_BEARER = 'SELFTEST-INJECTED-BEARER';
  let injectedToken = null;
  DRY = false;
  try {
    injectedToken = getOrFetchToken();
  } finally {
    DRY = true;
    process.env.PATH = saved.PATH;
    if (saved.ADO_BEARER === undefined) delete process.env.ADO_BEARER; else process.env.ADO_BEARER = saved.ADO_BEARER;
    if (saved.AZ_SHIM_LOG === undefined) delete process.env.AZ_SHIM_LOG; else process.env.AZ_SHIM_LOG = saved.AZ_SHIM_LOG;
  }

  // --- 2. no bearer, non-interactive, in a child with an empty token cache ---
  const headlessLog = join(dir, 'az-calls-headless.log');
  const cwd = join(dir, 'cwd');
  mkdirSync(join(cwd, 'generated'), { recursive: true });
  const env = { ...process.env };
  delete env.ADO_BEARER;
  delete env.ADO_AUTOPILOT_DRYRUN;              // a dry run would answer the wrong question
  env.ADO_NONINTERACTIVE = '1';
  env.LOCALAPPDATA = join(dir, 'appdata');      // an empty cache: nothing can be found there
  // Belt as well as braces. The child must not obtain a token at all — that is the thing
  // being tested — but a test that could create a real run if one assumption slipped is
  // not a test anybody should run on a machine with a live az session. Plan 0 holds no
  // test point, and no run is ever created before a point is resolved. If this belt is
  // what stops it, authHeadlessSaysWhat fails and says so.
  env.ADO_TEST_PLAN_ID = '0';
  // Diesem Kind ist der Probelauf ausdruecklich verwehrt, also greift die DRY-Ausnahme an
  // den Gettern hier nicht — ohne ing-config.json bliebe es an der Organisation haengen und
  // erreichte die Auth-Frage nie, die es beantworten soll. Es bekommt darum erkennbar
  // erfundene Namen mit, nach demselben Muster wie Plan 0 darueber: ohne Token kommt kein
  // einziger Request zustande, und diese Organisation gibt es nicht.
  env.ADO_ORG = 'SELFTEST-ORG';
  env.ADO_PROJECT = 'SELFTEST-PROJECT';
  env.PATH = withShim(process.env.PATH);
  env.AZ_SHIM_LOG = headlessLog;
  const child = spawnSync(process.execPath, [fileURLToPath(import.meta.url), '--test-case', '12345'],
    { cwd, env, encoding: 'utf8', timeout: 60000, windowsHide: true });
  const childOut = (child.stdout || '') + (child.stderr || '');
  const azAsked = existsSync(headlessLog) ? readFileSync(headlessLog, 'utf8') : '';

  return {
    authInjectedBearerUsed: injectedToken === 'SELFTEST-INJECTED-BEARER',
    authInjectedSpawnsNoAz: !existsSync(injectedLog),
    authHeadlessAnswered: typeof child.status === 'number',       // a hang would time out
    authHeadlessFails: child.status !== 0,
    authHeadlessSaysWhat: /ADO_BEARER/.test(childOut) && /ADO_NONINTERACTIVE=1/.test(childOut),
    authHeadlessRulesOutPat: /No personal access token/.test(childOut),
    authHeadlessTriedMint: /get-access-token/.test(azAsked),
    authHeadlessNeverLogsIn: !/(^|\s)login(\s|$)/m.test(azAsked),
    authHeadlessNoRun: /"ok":\s*false/.test(childOut) && !/"runId":\s*\d/.test(childOut),
  };
}

async function main() {
  const a = parseArgs(process.argv.slice(2));
  if (process.env.ADO_AUTOPILOT_DRYRUN === 'true' || a.dryRun || a.selftest) DRY = true;

  if (a.selftest) {
    dryPlan.length = 0;
    // Two small real files of different extensions + one small file we fake as >20MB.
    const pngPath = join(tmpdir(), 'ado-automark-selftest-a.png');
    const zipPath = join(tmpdir(), 'ado-automark-selftest-b.zip');
    const bigPath = join(tmpdir(), 'ado-automark-selftest-big.webm');
    writeFileSync(pngPath, Buffer.from('PNGDATA'));
    writeFileSync(zipPath, Buffer.from('ZIPDATA'));
    writeFileSync(bigPath, Buffer.from('WEBMDATA'));
    const realSizeOf = __sizeOf;
    __sizeOf = (p) => p.endsWith('selftest-big.webm') ? 25 * 1024 * 1024 : realSizeOf(p); // fake >20MB, no real big file
    const res = await markPassed({
      testCaseId: 12345, evidence: null, label: 'selftest', comment: 'Freitext-Kommentar (synthetisch)',
      attach: [pngPath, zipPath, bigPath, join(tmpdir(), 'ado-automark-selftest-missing.zip')],
    });
    __sizeOf = realSizeOf;
    // assertions
    const urls = dryPlan.map((c) => c.method + ' ' + c.url);
    const need = [
      /GET .*\/testplan\/suites\?testCaseId=12345/,
      /GET .*\/Suites\/\d+\/TestPoint\?testCaseId=12345/,
      /POST .*\/test\/runs\?/,
      /GET .*\/test\/runs\/99999\/results\?/,
      /GET .*\/wit\/workitems\/12345\?/,
      /POST .*\/test\/runs\/99999\/results\?/, // PATCH-as-POST? no—we use PATCH; check below
    ];
    const has = (re) => urls.some((u) => re.test(u));
    const checks = {
      fastPathSuites: has(/GET .*\/testplan\/suites\?testCaseId=12345/),
      testPoint: has(/GET .*\/Suites\/\d+\/TestPoint\?testCaseId=12345/),
      createRun: has(/POST .*\/test\/runs\?/),
      getResults: has(/GET .*\/test\/runs\/99999\/results\?/),
      readSteps: has(/GET .*\/wit\/workitems\/12345\?/),
      patchResultPassed: dryPlan.some((c) => /\/test\/runs\/99999\/results\?/.test(c.url) && c.method === 'PATCH' && Array.isArray(c.body) && c.body[0] && c.body[0].outcome === 'Passed'),
      completeRun: dryPlan.some((c) => c.method === 'PATCH' && /\/test\/runs\/99999\?/.test(c.url) && c.body && c.body.state === 'Completed'),
      wiComment: has(/POST .*\/wit\/workItems\/12345\/comments\?/),
      stepOutcomes: dryPlan.some((c) => c.method === 'PATCH' && /results\?/.test(c.url) && Array.isArray(c.body) && c.body[0] && Array.isArray(c.body[0].iterationDetails) && c.body[0].iterationDetails[0].actionResults.length === 2),
      // --attach: png + zip flow into BOTH the result attachment and the work-item relation; big is skipped, missing is skipped.
      attachPngResult: dryPlan.some((c) => c.method === 'POST' && /\/test\/runs\/99999\/results\/88888\/attachments\?/.test(c.url) && c.body && c.body.fileName === 'ado-automark-selftest-a.png' && c.body.attachmentType === 'GeneralAttachment'),
      attachZipResult: dryPlan.some((c) => c.method === 'POST' && /\/test\/runs\/99999\/results\/88888\/attachments\?/.test(c.url) && c.body && c.body.fileName === 'ado-automark-selftest-b.zip' && c.body.attachmentType === 'GeneralAttachment'),
      attachPngWorkItemUpload: dryPlan.some((c) => c.method === 'POST' && /\/wit\/attachments\?fileName=ado-automark-selftest-a\.png/.test(c.url)),
      attachZipWorkItemUpload: dryPlan.some((c) => c.method === 'POST' && /\/wit\/attachments\?fileName=ado-automark-selftest-b\.zip/.test(c.url)),
      attachWorkItemRelations: dryPlan.filter((c) => c.method === 'PATCH' && /\/wit\/workitems\/12345\?/.test(c.url) && Array.isArray(c.body) && c.body[0] && c.body[0].value && c.body[0].value.rel === 'AttachedFile').length === 2,
      attachResultCount: dryPlan.filter((c) => c.method === 'POST' && /\/test\/runs\/99999\/results\/88888\/attachments\?/.test(c.url)).length === 2,
      attachBigSkipped: !dryPlan.some((c) => /selftest-big\.webm/.test(c.url) || (c.body && JSON.stringify(c.body).includes('selftest-big'))),
      attachMissingSkipped: !dryPlan.some((c) => /selftest-missing/.test(c.url) || (c.body && JSON.stringify(c.body).includes('selftest-missing'))),
      // Auth: the pipeline-facing half. Runs last so a failure here cannot be confused
      // with a failure of the lifecycle above.
      ...selftestAuth(),
    };
    writeFileSync(join(process.cwd(), 'generated', 'ado-automark-plan.json'), JSON.stringify(dryPlan, null, 2), 'utf8');
    const failed = Object.entries(checks).filter(([, v]) => !v).map(([k]) => k);
    if (failed.length || !res.ok) {
      console.error('ado-automark selftest FAILED:', failed.join(', '), 'res=', JSON.stringify(res));
      process.exit(1);
    }
    console.log('ado-automark selftest: GREEN —', dryPlan.length, 'calls, all assertions passed. plan -> generated/ado-automark-plan.json');
    return;
  }

  if (!a['test-case']) { console.error('Usage: node ado-automark.mjs --test-case <id> [--evidence <folder>] [--plan N] [--suite N] [--comment "..."] [--attach <file> ...] [--dry-run]'); process.exit(2); }
  try { mkdirSync(join(process.cwd(), 'generated'), { recursive: true }); } catch {}
  const res = await markPassed({
    testCaseId: a['test-case'], evidence: a.evidence, comment: a.comment, label: a.label, attach: a.attach,
    planId: a.plan ? parseInt(a.plan, 10) : undefined, suiteId: a.suite != null ? parseInt(a.suite, 10) : undefined,
  });
  if (DRY) { writeFileSync(join(process.cwd(), 'generated', 'ado-automark-plan.json'), JSON.stringify(dryPlan, null, 2), 'utf8'); log('dry-run plan -> generated/ado-automark-plan.json (' + dryPlan.length + ' calls)'); }
  console.log(JSON.stringify(res));
  process.exit(res.ok ? 0 : 1);
}

// run as CLI
if (process.argv[1] && process.argv[1].replace(/\\/g, '/').endsWith('ado-automark.mjs')) {
  main().catch((e) => { console.error('ado-automark error:', e.message); process.exit(1); });
}
