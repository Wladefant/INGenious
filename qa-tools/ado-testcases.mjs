#!/usr/bin/env node
/**
 * ado-testcases.mjs — pull the REAL test cases of an Azure DevOps Test Plan and
 * emit them as a companion queue JSON the Tester UI can load. Read-only against
 * ADO (only GETs). Node stdlib only.
 *
 * Auth: the PROVEN Entra-bearer flow. The token minting / caching / az-login
 * fallback is the SOURCE OF TRUTH in tools/ado-automark.mjs
 * (resource 499b84ac-1321-427f-aa17-267ca6975798 — Azure DevOps' own public
 * constant, identical for every customer — ~50-min cache at
 * %LOCALAPPDATA%\IngQaAutopilot\token.json, auto `az login --tenant <tenant>`
 * fallback with the tenant READ from the configuration, az.cmd candidate paths
 * incl. ~\azure-cli\bin\az.cmd). We IMPORT getOrFetchToken from there rather than
 * re-implement it, so both tools share one cache and one proven code path.
 *
 * Organisation, project, plan and project folder are NOT in this file. They come
 * from the environment or from ing-config.json beside the tools; see
 * tools/lib/ing-config.mjs.
 *
 * The interactive `az login` inside that flow is right at a command line and wrong
 * with no console: started by the INGenious Studio panel this tool runs on Java's
 * pipes, so az's instructions land in a log file nobody opens while the panel says
 * "wird aktualisiert…" for up to five minutes and then fails unexplained — the
 * display not matching reality, in its slowest form. So with no console attached
 * this tool declares itself non-interactive and refuses the login in about a
 * second, saying what is needed instead. Studio settles the sign-in BEFORE it
 * starts this tool (de/ing/qa/studio/AdoSignIn.java), in a window a tester can see.
 *
 * Flow (all api-version 7.1):
 *   1. GET _apis/testplan/Plans/{plan}/suites            (follow x-ms-continuationtoken)
 *   2. per suite: GET .../Suites/{id}/TestPoint          (follow x-ms-continuationtoken)
 *      -> collect UNIQUE test cases: id, name, suiteId, suiteName, outcome/state
 *   3. GET _apis/wit/workitems?ids=...                   (ALL fields by default, see below)
 *      -> parse the Steps XML (HTML-encoded) into plain step strings
 *   4. emit companion queue JSON to --out
 *   5. emit the PANEL CACHE JSON to --cache (see below)
 *
 * Two outputs, one fetch:
 *   --out    companion queue     (execution shape: adoId/title/steps/mapping/testset)
 *   --cache  panel cache         (reading shape: + suiteName/state/description/
 *                                 preconditions, consumed by the INGenious Studio
 *                                 plugin panels TestCaseChooserPanel /
 *                                 TestCaseOverviewPanel — they NEVER call ADO
 *                                 themselves, they only read this file)
 *
 * Why ALL fields: "Voraussetzungen" (the preconditions a tester must read before
 * starting) is NOT a standard ADO field. It is either a custom field of the
 * process template or folded into System.Description / Microsoft.VSTS.TCM.SystemInfo.
 * Naming a non-existent field in `fields=` makes ADO reject the whole batch, so the
 * work-item batch is fetched WITHOUT a field filter and the precondition field is
 * discovered by name (override with --precondition-field). `--fields-only` restores
 * the old narrow fetch (System.Title + Steps) and skips precondition discovery.
 *
 * How long it takes, and why that is a feature of the report rather than of the wait:
 * a large plan has hundreds of suites and thousands of cases, so the fetch is hundreds
 * of round trips and takes minutes, not the "eine Minute" the panel used to promise.
 * Three things follow, all of them here:
 *
 *   1. EVERY phase reports its own progress on STDERR, one line at a time, prefixed
 *      "[fortschritt] ". stdout keeps carrying only the payload. The Studio panel
 *      reads those lines as they appear and puts the newest one on screen, so a wait
 *      with a number in it stops looking like a hang.
 *   2. The per-suite test-point requests and the work-item batches run with a bounded
 *      number in flight (--parallel, default 8) instead of strictly one after another.
 *      Order is preserved, so the output is byte-identical to the sequential one.
 *   3. Every request has a FINITE timeout (--timeout-ms, default 60s) and retries a
 *      network error, a 429 or a 5xx a few times. A stalled socket used to hang the
 *      whole tool forever; now it gives up with a German sentence.
 *
 * Usage:
 *   node tools/ado-testcases.mjs [--org <org>] [--project <projekt>] [--plan <planId>]
 *                                [--out companion/queue/queue.ado.json] [--cache <path>]
 *                                [--precondition-field Custom.Voraussetzungen] [--fields-only]
 *                                [--parallel 8] [--timeout-ms 60000] [--quiet]
 *   node tools/ado-testcases.mjs --json       # print the panel cache to stdout, write nothing
 *   node tools/ado-testcases.mjs --selftest   # offline: fixtures only, no az, no network, exit 0
 */

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import http from 'node:http';
import https from 'node:https';
import process from 'node:process';
import { fileURLToPath } from 'node:url';
// Token logic lives in ado-automark.mjs (source of truth); importing shares the cache.
import { getOrFetchToken } from './ado-automark.mjs';
import { config, requireValue } from './lib/ing-config.mjs';

const API_VERSION = '7.1';
const __dirname = path.dirname(fileURLToPath(import.meta.url));

// Companion-queue defaults. mapping:"default" is an HONEST marker that no per-case
// INGenious testset mapping exists yet — every case points at the one shipped
// example project/set until real mappings are authored.
const RELEASE = 'Release1';
const TESTSET = 'Set1';

/**
 * Which organisation this run points at.
 *
 * Read, not known: organisation, project, test plan and the project folder used to be
 * literals right here. They are identifiers of one company's Azure DevOps — and of one
 * machine — and this file is a publication candidate. {@link config} takes them from the
 * environment or from ing-config.json beside the tools; anything nobody supplied stays
 * EMPTY and fails at first use with a German sentence naming the key, never with a guessed
 * organisation, which would read somebody else's test plan.
 *
 * `--org` / `--project` / `--plan` still win over all of it.
 */
const CFG = {
  get org() { return requireValue('org', 'Die Azure-DevOps-Organisation'); },
  get project() { return requireValue('project', 'Das Azure-DevOps-Projekt'); },
  get plan() { return requireValue('plan', 'Die Id des Testplans'); },
  get projectLocation() { return requireValue('projectLocation', 'Der Projektordner fuer die Warteschlange'); },
};

/**
 * How many ADO GETs may be in flight at once.
 *
 * <p>Eight, not one, and not fifty. One is what this tool did until 2026-08-05 and it is why a
 * refresh took minutes: the plan's suites are fetched one test-point request each, strictly in
 * sequence, so the whole run was hundreds of round-trip latencies added end to end with the link
 * idle in between. Fifty would trade that for HTTP 429 and a rate limit nobody asked for. The
 * requests are GETs — nothing here writes — so the only cost of overlapping them is politeness.
 */
const DEFAULT_PARALLEL = 8;
/** A request that has said nothing for this long is not slow, it is gone. */
const DEFAULT_TIMEOUT_MS = 60_000;
/** Network error, 429 or 5xx: tried this many times in total before the run gives up. */
const MAX_ATTEMPTS = 3;

/**
 * Where the Studio plugin panels look for the cache. Kept byte-identical to
 * AdoCache.cachePath() in ingenious-plugin — if you change one, change the other.
 *   1. $ING_ADO_CACHE
 *   2. %LOCALAPPDATA%\IngQaAutopilot\ado-testcases.json   (same dir as the token cache)
 *   3. ~/.IngQaAutopilot/ado-testcases.json
 */
function defaultCachePath() {
  const explicit = process.env.ING_ADO_CACHE;
  if (explicit && explicit.trim()) return explicit.trim();
  const local = process.env.LOCALAPPDATA;
  if (local && local.trim()) return path.join(local.trim(), 'IngQaAutopilot', 'ado-testcases.json');
  return path.join(os.homedir(), '.IngQaAutopilot', 'ado-testcases.json');
}

function parseArgs(argv) {
  const a = {
    // null, not a literal: resolved from the configuration at first USE, so `--selftest`
    // still runs on a machine that has no ing-config.json at all.
    org: null,
    project: null,
    plan: null,
    out: 'companion/queue/queue.ado.json',
    cache: null,
    selftest: false,
    json: false,
    fieldsOnly: false,
    preconditionField: null,
    cacheExplicit: false,
    parallel: positiveInt(process.env.ING_ADO_PARALLEL, DEFAULT_PARALLEL),
    timeoutMs: positiveInt(process.env.ING_ADO_TIMEOUT_MS, DEFAULT_TIMEOUT_MS),
    quiet: false,
  };
  for (let i = 0; i < argv.length; i++) {
    const k = argv[i];
    if (k === '--selftest') a.selftest = true;
    else if (k === '--json') a.json = true;
    else if (k === '--quiet') a.quiet = true;
    else if (k === '--fields-only') a.fieldsOnly = true;
    else if (k === '--org') a.org = argv[++i];
    else if (k === '--project') a.project = argv[++i];
    else if (k === '--plan') a.plan = argv[++i];
    else if (k === '--out') a.out = argv[++i];
    else if (k === '--cache') { a.cache = argv[++i]; a.cacheExplicit = true; }
    else if (k === '--precondition-field') a.preconditionField = argv[++i];
    else if (k === '--parallel') a.parallel = positiveInt(argv[++i], DEFAULT_PARALLEL);
    else if (k === '--timeout-ms') a.timeoutMs = positiveInt(argv[++i], DEFAULT_TIMEOUT_MS);
  }
  if (!a.cache) a.cache = defaultCachePath();
  return a;
}

function positiveInt(raw, fallback) {
  const n = Number.parseInt(String(raw ?? '').trim(), 10);
  return Number.isFinite(n) && n > 0 ? n : fallback;
}

/* ------------------------------ progress ------------------------------ */

/**
 * The line a waiting tester is owed.
 *
 * <p>STDERR, never stdout: `--json` writes the whole cache to stdout and a progress line in the
 * middle of it would make it unparseable. Written with {@link fs.writeSync} rather than
 * console.error because the consumer is a pipe — Studio's {@code ProcessBuilder} — and a buffered
 * write would arrive in one burst at the end, which is exactly the silence being fixed.
 *
 * <p>Throttled to one line per {@code minGapMs}, except when {@code force} says a phase changed.
 * Thousands of cases would otherwise be thousands of status-line repaints.
 */
const PROGRESS_PREFIX = '[fortschritt] ';

function makeProgress(sink, minGapMs = 900) {
  let last = 0;
  return (text, force = false) => {
    const now = Date.now();
    if (!force && now - last < minGapMs) return;
    last = now;
    sink(PROGRESS_PREFIX + text);
  };
}

function writeStderrLine(line) {
  try { fs.writeSync(2, line + '\n'); } catch { /* a lost progress line costs nothing */ }
}

/* ------------------------------ bounded parallelism ------------------------------ */

/**
 * `worker` over every item, at most `limit` in flight, results in INPUT ORDER.
 *
 * <p>The order is the contract, not a side effect: {@link collectTestCases} deduplicates test
 * cases "first suite wins", so a run whose suites completed in a different order would produce a
 * different — and equally correct-looking — cache. Returning in input order makes the parallel
 * fetch produce byte-identical output to the sequential one it replaces.
 */
async function mapLimit(items, limit, worker) {
  const out = new Array(items.length);
  let next = 0;
  const lanes = Math.max(1, Math.min(limit, items.length));
  await Promise.all(Array.from({ length: lanes }, async () => {
    for (;;) {
      const i = next++;
      if (i >= items.length) return;
      out[i] = await worker(items[i], i);
    }
  }));
  return out;
}

/* ------------------------------ HTML/XML parsing ------------------------------ */

function safeCodePoint(n) {
  try { return String.fromCodePoint(n); } catch { return ''; }
}

// Decode the common HTML entities ADO uses in TCM step strings. &amp; is decoded
// LAST so an already-decoded '&' is never re-interpreted.
function decodeEntities(s) {
  return String(s)
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&nbsp;/g, ' ')
    .replace(/&#(\d+);/g, (_, d) => safeCodePoint(parseInt(d, 10)))
    .replace(/&#x([0-9a-fA-F]+);/g, (_, h) => safeCodePoint(parseInt(h, 16)))
    .replace(/&amp;/g, '&');
}

// A single <parameterizedString> cell -> plain text: decode the HTML-encoded
// markup, strip tags, decode once more (handles doubly-encoded entities like
// &amp;nbsp;), then collapse whitespace.
function cleanCell(html) {
  if (!html) return '';
  let t = decodeEntities(html);
  t = t.replace(/<[^>]+>/g, ' ');
  t = decodeEntities(t);
  return t.replace(/\s+/g, ' ').trim();
}

// Microsoft.VSTS.TCM.Steps XML -> array of plain step strings. Each <step> holds
// two <parameterizedString> children: [0] action, [1] expected result.
function parseStepsXml(xml) {
  if (!xml) return [];
  const steps = [];
  const stepRe = /<step\b[^>]*>([\s\S]*?)<\/step>/g;
  let m;
  while ((m = stepRe.exec(xml))) {
    const inner = m[1];
    const psRe = /<parameterizedString\b[^>]*>([\s\S]*?)<\/parameterizedString>/g;
    const parts = [];
    let p;
    while ((p = psRe.exec(inner))) parts.push(cleanCell(p[1]));
    const action = parts[0] || '';
    const expected = parts[1] || '';
    let text = action;
    if (expected) text = text ? text + ' → Erwartet: ' + expected : 'Erwartet: ' + expected;
    text = text.trim();
    if (text) steps.push(text);
  }
  return steps;
}

// Same as cleanCell but keeps paragraph/line breaks, which matters for a
// description or a precondition block a tester actually has to read.
function cleanRich(html) {
  if (!html) return '';
  let t = decodeEntities(String(html));
  t = t.replace(/<\s*br\s*\/?\s*>/gi, '\n');
  t = t.replace(/<\s*\/\s*(p|div|li|tr|h[1-6])\s*>/gi, '\n');
  t = t.replace(/<\s*li\b[^>]*>/gi, '- ');
  t = t.replace(/<[^>]+>/g, ' ');
  t = decodeEntities(t);
  return t
    .split('\n')
    .map((l) => l.replace(/[ \t ]+/g, ' ').trim())
    .filter((l, i, arr) => l !== '' || (i > 0 && arr[i - 1] !== ''))
    .join('\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
}

/* ------------------------------ precondition discovery ------------------------------ */

// "Voraussetzungen" is not a standard ADO field. Match on the field's reference
// name so a custom field of the process template (Custom.Voraussetzungen,
// Custom.Vorbedingungen, …) is found without hardcoding the exact reference name we
// cannot verify from here.
const PRECONDITION_NAME_RE = /(voraussetzung|vorbedingung|precondition|prerequisit)/i;
// ADO's "System Information" is the field test cases most often abuse for
// preconditions when no custom field exists. Last resort before giving up.
const PRECONDITION_FALLBACKS = ['Microsoft.VSTS.TCM.SystemInfo'];

function fieldText(fields, name) {
  const v = fields ? fields[name] : null;
  if (v == null) return '';
  if (typeof v === 'string') return cleanRich(v);
  if (typeof v === 'number' || typeof v === 'boolean') return String(v);
  return '';
}

/**
 * -> { field, value }. `field` names WHICH ADO field the text came from, so the
 * panel can tell the tester where "Voraussetzungen" is sourced from instead of
 * silently presenting an empty box as "no preconditions".
 */
function pickPrecondition(fields, explicit) {
  if (!fields) return { field: null, value: '' };
  if (explicit) {
    const v = fieldText(fields, explicit);
    return { field: explicit, value: v };
  }
  for (const key of Object.keys(fields)) {
    if (!PRECONDITION_NAME_RE.test(key)) continue;
    const v = fieldText(fields, key);
    if (v) return { field: key, value: v };
  }
  for (const key of PRECONDITION_FALLBACKS) {
    const v = fieldText(fields, key);
    if (v) return { field: key, value: v };
  }
  return { field: null, value: '' };
}

/* ------------------------------ TestPoint extraction ------------------------------ */

function extractPoint(pt) {
  let id = '';
  if (pt && pt.testCaseReference && pt.testCaseReference.id != null) id = String(pt.testCaseReference.id);
  else if (pt && pt.testCase && pt.testCase.id != null) id = String(pt.testCase.id);
  let name = '';
  if (pt && pt.testCaseReference && pt.testCaseReference.name != null) name = String(pt.testCaseReference.name);
  else if (pt && pt.testCaseReference && pt.testCaseReference.testCaseTitle != null) name = String(pt.testCaseReference.testCaseTitle);
  let outcome = '';
  if (pt && pt.results && pt.results.outcome != null) outcome = String(pt.results.outcome);
  else if (pt && pt.outcome != null) outcome = String(pt.outcome);
  let state = '';
  if (pt && pt.results && pt.results.state != null) state = String(pt.results.state);
  else if (pt && pt.results && pt.results.lastResultState != null) state = String(pt.results.lastResultState);
  else if (pt && pt.state != null) state = String(pt.state);
  return { testCaseId: id, name, outcome, state };
}

/* ------------------------------ HTTP transport ------------------------------ */

/**
 * One GET, with an END to it.
 *
 * <p>Until 2026-08-05 this function had no timeout at all. A socket that opened and then said
 * nothing — a dropped VPN, a proxy that accepted the connection and forgot it — left the promise
 * unsettled forever, and node with a pending handle does not exit. From the panel that is
 * indistinguishable from work in progress: "Testfälle werden aus ADO geladen…" until somebody
 * kills Studio. A wait without an end is the defect, whatever caused it.
 *
 * <p>{@code req.setTimeout} arms the SOCKET, so it covers the response body as well as the
 * headers: a body that stops arriving half way through trips it too.
 */
function httpGetRaw(urlStr, token, timeoutMs = DEFAULT_TIMEOUT_MS) {
  return new Promise((resolve, reject) => {
    const u = new URL(urlStr);
    const lib = u.protocol === 'http:' ? http : https;
    const req = lib.request({
      method: 'GET',
      hostname: u.hostname,
      port: u.port || (u.protocol === 'https:' ? 443 : 80),
      path: u.pathname + u.search,
      headers: { Accept: 'application/json', Authorization: 'Bearer ' + token },
    }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => resolve({
        statusCode: res.statusCode || 0,
        body: Buffer.concat(chunks).toString('utf8'),
        continuationToken: res.headers['x-ms-continuationtoken'] || null,
        retryAfter: res.headers['retry-after'] || null,
      }));
    });
    req.setTimeout(timeoutMs, () => {
      // destroy(err) makes the 'error' handler below reject with THIS message rather than a
      // bare ECONNRESET, so the sentence that reaches the tester names the wait, not the socket.
      req.destroy(new Error('Azure DevOps hat auf ' + u.pathname + ' innerhalb von '
        + Math.round(timeoutMs / 1000) + ' Sekunden nicht geantwortet.'));
    });
    req.on('error', reject);
    req.end();
  });
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** Worth trying again: nothing about the request was wrong, only its moment. */
function retryable(statusCode) {
  return statusCode === 429 || statusCode === 408 || (statusCode >= 500 && statusCode < 600);
}

/**
 * Whether Azure DevOps answered "you are not signed in".
 *
 * <p>It does not say so with a 401. It answers a bearer it will not accept with a <b>302 to the
 * Entra sign-in page</b> — an HTML document, on an endpoint that has only ever returned JSON.
 * Measured on a Testgeraet on 2026-08-05: every request of a 14-minute run succeeded until the
 * token's real expiry passed mid-run, and from that second on the same URLs redirected. Read as an
 * ordinary HTTP failure this produces a wall of markup and no instruction; read for what it is,
 * it is the one problem in this tool a tester can actually fix herself.
 */
function saysNotSignedIn(res) {
  if (res.statusCode === 401 || res.statusCode === 403) return true;
  if (res.statusCode < 300 || res.statusCode >= 400) return false;
  return /_signin|login\.microsoftonline|vssps/i.test(String(res.body || ''));
}

const NOT_SIGNED_IN = 'Die Anmeldung bei Azure DevOps ist abgelaufen — Azure DevOps hat statt '
  + 'der Testfaelle die Anmeldeseite geschickt. Bitte in Studio neu anmelden und erneut auf '
  + '"Aus ADO aktualisieren" klicken.';

/**
 * When a bearer token really stops working, out of the token itself.
 *
 * <p>This is the fact the 2026-08-05 outage turned on. {@code ado-automark.mjs} caches every
 * minted token for a flat 50 minutes — but {@code az} hands back tokens from its OWN cache, so
 * the one written at 11:57 had been minted at 11:03 and died at 12:19 while our file went on
 * claiming 12:46. Measured on a Testgeraet: the cache believed it had 49 minutes left when it
 * had 22.
 * A token's {@code exp} claim is the only authority on this, and it is readable without a
 * library and without a network call.
 *
 * <p>The token is decoded, never logged and never returned — only the number.
 *
 * @returns {number} epoch seconds, or 0 when this is not a readable JWT (then nothing is assumed
 *     and the token is used, which is exactly the old behaviour)
 */
function tokenExpiry(token) {
  try {
    const payload = String(token).split('.')[1];
    if (!payload) return 0;
    const claims = JSON.parse(Buffer.from(payload, 'base64url').toString('utf8'));
    return Number(claims.exp) > 0 ? Number(claims.exp) : 0;
  } catch {
    return 0;
  }
}

/**
 * Whether this token would die during the run that is about to start.
 *
 * <p>The margin is generous on purpose: a token with four minutes left passes every "is it
 * expired" test and still cannot survive a fetch of this plan. Checking expiry without checking
 * headroom would have prevented the 12:24 failure and not the 12:10 one.
 */
function expiringSoon(token, marginSeconds = 15 * 60) {
  const exp = tokenExpiry(token);
  return exp > 0 && exp < Math.floor(Date.now() / 1000) + marginSeconds;
}

/**
 * A new token, without asking anybody anything.
 *
 * <p>The cache file is removed first — that is the whole trick. {@code getOrFetchToken} returns
 * the cached token whenever the file claims it is still good, so with the stale file in place it
 * would hand back the very token ADO just rejected. Deleting it makes the next call mint. No
 * auth logic is reimplemented here: the mint, the tenant, the resource and the az fallbacks all
 * stay in {@code ado-automark.mjs}, which is the one flow proven to survive Conditional Access.
 *
 * <p>Silent by design. az issues a fresh token from the machine's existing session with no
 * prompt and no window — verified on a Testgeraet on 2026-08-05, where a mint took under a
 * second while a person was doing nothing. A sign-in belongs on screen only when THIS fails.
 */
function renewToken() {
  try {
    const local = process.env.LOCALAPPDATA;
    const cache = local && local.trim()
      ? path.join(local.trim(), 'IngQaAutopilot', 'token.json')
      : path.join(os.homedir(), '.IngQaAutopilot', 'token.json');
    fs.rmSync(cache, { force: true });
  } catch { /* an unremovable cache only means the mint below may return the same token */ }
  return getOrFetchToken();
}

// A transport is: async (url) => { statusCode, body(parsed), continuationToken }.
function makeRealTransport(token, opts = {}) {
  const timeoutMs = opts.timeoutMs || DEFAULT_TIMEOUT_MS;
  const attempts = opts.attempts || MAX_ATTEMPTS;
  // The token is not a constant: this run takes minutes and the token it started with can die
  // in the middle of it. `renew` mints a new one; `renewing` makes eight parallel lanes that all
  // hit the redirect at the same instant queue behind ONE mint instead of eight.
  const renew = opts.renew || null;
  let current = token;
  let renewals = 0;
  let renewing = null;
  async function renewOnce(usedToken) {
    // Somebody else already replaced the token this lane was using — just take theirs.
    if (usedToken !== current) return current;
    if (!renewing) {
      renewing = (async () => {
        if (!renew || renewals >= 2) return null;
        renewals++;
        const fresh = await renew();
        if (fresh && fresh !== current) current = fresh;
        return fresh && fresh !== usedToken ? current : null;
      })().finally(() => { renewing = null; });
    }
    return renewing;
  }
  return async (url) => {
    let lastError = null;
    for (let attempt = 1; attempt <= attempts; attempt++) {
      let res;
      const used = current;
      try {
        res = await httpGetRaw(url, current, timeoutMs);
      } catch (err) {
        // A timeout or a reset. Retrying is the difference between a 700-request run that
        // survives one blip and one that throws away nine minutes of work because of it.
        lastError = err;
        if (attempt === attempts) break;
        await sleep(attempt * 1000);
        continue;
      }
      if (res.statusCode >= 200 && res.statusCode < 300) {
        let body = {};
        if (res.body) {
          try { body = JSON.parse(res.body); }
          catch { throw new Error('non-JSON response from ' + url + ': ' + res.body.slice(0, 200)); }
        }
        return { statusCode: res.statusCode, body, continuationToken: res.continuationToken };
      }
      if (saysNotSignedIn(res)) {
        // Once, silently, and then either on or out. Renewing needs no human — az hands out a
        // fresh token without a prompt when the machine has a session, which it does — so the
        // right behaviour is to fix it and carry on, not to stop and ask. What must NOT happen
        // is what happened on 2026-08-05: the redirect treated as an ordinary HTTP failure,
        // reported as a wall of HTML after fourteen minutes.
        const fresh = await renewOnce(used);
        // A renewal is not a failed attempt: the request was never answered on its merits.
        // Renewals are capped inside renewOnce, so this cannot spin.
        if (fresh) { attempt--; continue; }
        throw new Error(NOT_SIGNED_IN);
      }
      if (!retryable(res.statusCode) || attempt === attempts) {
        throw new Error('ADO GET ' + url + ' failed: HTTP ' + res.statusCode + ' '
          + (res.body || '').slice(0, 300));
      }
      // ADO says how long to wait when it throttles; obeying it is why raising --parallel
      // costs a pause rather than a failed refresh.
      const after = positiveInt(res.retryAfter, 0);
      await sleep(after ? after * 1000 : attempt * 1000);
    }
    throw new Error('ADO GET ' + url + ' failed after ' + attempts + ' attempts: '
      + (lastError && lastError.message ? lastError.message : lastError));
  };
}

/* ------------------------------ pipeline ------------------------------ */

// Follow x-ms-continuationtoken until exhausted; concatenate every page's .value.
async function fetchAllPages(transport, url) {
  const items = [];
  let cont = null;
  let guard = 0;
  do {
    const u = cont ? url + '&continuationToken=' + encodeURIComponent(cont) : url;
    const { body, continuationToken } = await transport(u);
    const value = body && Array.isArray(body.value) ? body.value : [];
    items.push(...value);
    cont = continuationToken || null;
    guard++;
  } while (cont && guard < 200);
  return items;
}

/**
 * Fields that name a PERSON. Dropped from every suite object before it reaches the cache,
 * deliberately and by name rather than by picking the few fields we happen to want.
 *
 * The rule everywhere else here is "keep what ADO sent, choose at display time" — the next
 * question then costs no fetch. People are the one exception: the cache is a file on a
 * tester's laptop and travels in the hand-off package, and who last edited a suite is not
 * needed to draw a tree. `id`, `name`, `parentSuite`, `suiteType`, revisions and timestamps
 * are all kept; identities are not.
 */
const PERSONAL_KEYS = [
  'lastUpdatedBy', 'createdBy', 'assignedTo', 'owner', 'tester', 'defaultTesters',
  'changedBy', 'identity', 'ownerName',
];

/** One ADO object, minus the people in it. Everything else is copied verbatim. */
function withoutPeople(raw) {
  if (!raw || typeof raw !== 'object') return {};
  const out = {};
  for (const [key, value] of Object.entries(raw)) {
    if (PERSONAL_KEYS.includes(key)) continue;
    out[key] = value;
  }
  return out;
}

/**
 * Every suite of the plan, and every test case in them.
 *
 * <p>THREE things come back, and until 2026-08-03 only the first did:
 *
 *   cases       — one entry per test case, first suite wins. Unchanged: the flat list in
 *                 the panel and the companion queue both key on this and neither may grow
 *                 duplicates.
 *   suites      — the plan's own structure, verbatim minus people. This is what was being
 *                 thrown away: `parentSuite` was read off every suite object and dropped,
 *                 so the hierarchy was in memory on every run and in no file afterwards.
 *   memberships — which case hangs in which suite, WITH the test point it hangs by. A case
 *                 belongs to several suites; the flat list can only say one, and saying one
 *                 was indistinguishable from there being one.
 *
 * <p>In a large plan hundreds of suites carry the SAME NAME. A name is therefore not an
 * identifier, and everything here keys on `id` — the name is carried along to be shown and
 * never to be matched. (That is the same defect native publishing was turned off for:
 * AzureClient resolves a suite by name and takes the first hit.)
 *
 * <p>Test points are NOT copied verbatim, and that is the one deliberate exception to the
 * rule above: there is one point per case per suite per configuration, so a large plan has
 * tens of thousands of them and the cache is already 13 MB. What a membership needs to be
 * useful is named instead — the suite, the point, its configuration and its outcome.
 */
async function collectTestCases(transport, base, plan, opts = {}) {
  const progress = opts.progress || (() => {});
  const parallel = opts.parallel || DEFAULT_PARALLEL;

  progress('Suiten des Testplans ' + plan + ' werden geholt…', true);
  const rawSuites = await fetchAllPages(transport, base + '/testplan/Plans/' + plan + '/suites?api-version=' + API_VERSION);
  const usable = rawSuites.filter((s) => s && s.id != null && String(s.id) !== '');
  progress(usable.length + ' Suiten gefunden. Testpunkte werden geholt…', true);

  // One request per suite. Sequentially that is hundreds of round trips end to end, which is
  // where the minutes went; `parallel` of them overlap, and mapLimit hands the results back in
  // suite order so the "first suite wins" dedup below is unaffected.
  let fetched = 0;
  let seenPoints = 0;
  const perSuite = await mapLimit(usable, parallel, async (s) => {
    const suiteId = String(s.id);
    const points = await fetchAllPages(
      transport,
      base + '/testplan/Plans/' + plan + '/Suites/' + suiteId + '/TestPoint?api-version=' + API_VERSION,
    );
    fetched++;
    seenPoints += points.length;
    progress('Testpunkte: ' + fetched + '/' + usable.length + ' Suiten · '
      + seenPoints + ' Testpunkte');
    return { suiteId, suiteName: s.name != null ? String(s.name) : '', points };
  });
  progress('Testpunkte: ' + usable.length + '/' + usable.length + ' Suiten · '
    + seenPoints + ' Testpunkte', true);

  const byId = new Map(); // first-seen wins -> unique test cases in stable order
  const suites = usable.map(withoutPeople);
  const memberships = [];
  // Built while walking the points instead of joined afterwards. The join it replaces was
  // `memberships.filter(...)` PER CASE — thousands of cases against tens of thousands of
  // memberships, a few hundred million comparisons that burned minutes of pure CPU with the
  // network idle and not one byte of output, the most convincing possible imitation of a hang.
  const suiteIdsByCase = new Map();
  for (const { suiteId, suiteName, points } of perSuite) {
    for (const pt of points) {
      const e = extractPoint(pt);
      if (!e.testCaseId) continue;
      // Recorded for EVERY suite the case hangs in, before the dedup below — that is the
      // whole point of this list.
      memberships.push({
        testCaseId: e.testCaseId,
        suiteId,
        pointId: pt && pt.id != null ? String(pt.id) : '',
        configurationId: pt && pt.configuration && pt.configuration.id != null
          ? String(pt.configuration.id) : '',
        configurationName: pt && pt.configuration && pt.configuration.name != null
          ? String(pt.configuration.name) : '',
        outcome: e.outcome,
        state: e.state,
      });
      let hangsIn = suiteIdsByCase.get(e.testCaseId);
      if (!hangsIn) {
        hangsIn = new Set();
        suiteIdsByCase.set(e.testCaseId, hangsIn);
      }
      hangsIn.add(suiteId);
      if (byId.has(e.testCaseId)) continue;
      byId.set(e.testCaseId, {
        id: e.testCaseId,
        name: e.name,
        suiteId,
        suiteName,
        outcome: e.outcome,
        state: e.state,
        steps: [],
      });
    }
  }
  // Hung on the case as well as listed separately: the panel reads one case at a time and
  // would otherwise have to scan the whole membership list to answer "where does this hang".
  // A Set preserves insertion order, so this is the same list the filter produced.
  for (const c of byId.values()) {
    c.suiteIds = [...(suiteIdsByCase.get(c.id) || [])];
  }
  return { cases: [...byId.values()], suites, memberships };
}

/**
 * Fills in title + steps, and (unless `limitFields`) description + preconditions.
 *
 * `limitFields` reproduces the original narrow `fields=` fetch. It is kept as an
 * escape hatch: if the all-fields batch ever turns out to be too heavy or a work
 * item type rejects it, `--fields-only` still produces a working companion queue —
 * only the panel cache then loses description/Voraussetzungen.
 */
async function enrichWorkItems(transport, base, cases, opts = {}, chunkSize = 200) {
  const limitFields = !!opts.limitFields;
  const progress = opts.progress || (() => {});
  const parallel = opts.parallel || DEFAULT_PARALLEL;

  const chunks = [];
  for (let i = 0; i < cases.length; i += chunkSize) chunks.push(cases.slice(i, i + chunkSize));
  progress('Testfall-Details: 0/' + cases.length + ' (' + chunks.length + ' Abfragen)', true);

  let done = 0;
  await mapLimit(chunks, parallel, async (chunk) => {
    const ids = chunk.map((c) => c.id).join(',');
    // $expand=links yields _links.html.href — the browser URL ADO ITSELF hands out,
    // which is what "In Azure DevOps oeffnen" should open. ADO rejects $expand
    // together with a fields= filter, so the narrow fetch cannot carry it.
    const url = base + '/wit/workitems?ids=' + ids
      + (limitFields ? '&fields=System.Title,Microsoft.VSTS.TCM.Steps' : '&$expand=links')
      + '&api-version=' + API_VERSION;
    const { body } = await transport(url);
    const value = body && Array.isArray(body.value) ? body.value : [];
    const wiById = new Map(value.map((w) => [String(w.id), w]));
    for (const c of chunk) {
      const w = wiById.get(String(c.id));
      if (!w || !w.fields) continue;
      const title = w.fields['System.Title'];
      if (title) c.title = String(title);
      c.steps = parseStepsXml(w.fields['Microsoft.VSTS.TCM.Steps']);
      if (limitFields) continue;
      // Verified against a real plan (2026-07-27): ADO returns the PROJECT GUID here
      // (…/<org>/<guid>/_workitems/edit/<id>), not the project name. Both routes
      // resolve; keeping ADO's own href means we never guess.
      const href = w._links && w._links.html && w._links.html.href;
      if (href) c.url = String(href);
      c.description = fieldText(w.fields, 'System.Description');
      // System.State is the work-item state (Design/Ready/Closed) — a different
      // thing from the TestPoint's run state already in c.state, so keep both.
      c.workItemState = fieldText(w.fields, 'System.State');
      const pre = pickPrecondition(w.fields, opts.preconditionField);
      c.preconditions = pre.value;
      c.preconditionField = pre.field;
    }
    done += chunk.length;
    progress('Testfall-Details: ' + done + '/' + cases.length);
  });
  progress('Testfall-Details: ' + cases.length + '/' + cases.length, true);
}

function buildQueue(cases, cfg) {
  // The folder every case points at: from the run's own configuration, never a literal —
  // the path used to name a machine, a user account and the application in one string.
  const projectLocation = cfg.projectLocation || CFG.projectLocation;
  return {
    _note: 'Generated by tools/ado-testcases.mjs from ADO Test Plan ' + cfg.plan
      + ' (org ' + cfg.org + ' / project ' + cfg.project + '). One case per ADO test case, '
      + 'deduplicated across suites. mapping:"default" marks that NO per-case INGenious '
      + 'testset mapping exists yet — every case points at ' + RELEASE + '/' + TESTSET
      + ' of the configured project folder until real mappings are authored. '
      + 'acceptanceCriteria is empty (ADO steps carry action+expected, not separate criteria).',
    testCases: cases.map((c) => ({
      adoId: String(c.id),
      title: c.title || c.name || ('Testfall ' + c.id),
      steps: Array.isArray(c.steps) ? c.steps : [],
      acceptanceCriteria: [],
      projectLocation,
      release: RELEASE,
      testset: TESTSET,
      dataCriteriaTags: [],
      mapping: 'default',
    })),
  };
}

/**
 * The panel cache: what a TESTER needs to read before starting, as opposed to what
 * the engine needs to run. Consumed by the INGenious Studio plugin panels, which
 * never talk to ADO themselves.
 */
function buildCache(cases, cfg, suites = [], memberships = []) {
  const withPre = cases.filter((c) => c.preconditions).length;
  const preFields = [...new Set(cases.map((c) => c.preconditionField).filter(Boolean))];
  return {
    _note: 'Panel cache generated by tools/ado-testcases.mjs from ADO Test Plan '
      + cfg.plan + ' (org ' + cfg.org + ' / project ' + cfg.project + '). Read-only '
      + 'snapshot for the INGenious Studio panels; refresh by re-running the tool. '
      + 'preconditionField names the ADO field "Voraussetzungen" was read from — '
      + 'null means no precondition-looking field carried text on that case. '
      + 'url is ADO\'s OWN browser link (_links.html.href) for the work item; empty '
      + 'means ADO did not hand one out and the panel falls back to building '
      + 'https://dev.azure.com/<org>/<project>/_workitems/edit/<id> from org/project above. '
      + 'suites is the plan\'s OWN structure, copied from ADO minus every field naming a '
      + 'person — parentSuite.id is what makes it a tree. testCases[].suiteIds lists EVERY '
      + 'suite a case hangs in; suiteId/suiteName name only the first and are kept so an '
      + 'older reader still works. Everything is keyed on ids: a large plan carries hundreds '
      + 'of suites of the same name, so a name is not an identifier here.',
    generatedAt: new Date().toISOString(),
    org: cfg.org,
    project: cfg.project,
    plan: String(cfg.plan),
    preconditionFieldsSeen: preFields,
    casesWithPreconditions: withPre,
    casesWithUrl: cases.filter((c) => c.url).length,
    suiteCount: suites.length,
    membershipCount: memberships.length,
    suites,
    memberships,
    testCases: cases.map((c) => ({
      adoId: String(c.id),
      title: c.title || c.name || ('Testfall ' + c.id),
      url: c.url || '',
      suiteId: String(c.suiteId || ''),
      suiteName: String(c.suiteName || ''),
      suiteIds: Array.isArray(c.suiteIds) ? c.suiteIds : [],
      state: String(c.workItemState || ''),
      outcome: String(c.outcome || ''),
      description: c.description || '',
      preconditions: c.preconditions || '',
      preconditionField: c.preconditionField || null,
      steps: Array.isArray(c.steps) ? c.steps : [],
    })),
  };
}

async function generate(transport, cfg) {
  const base = 'https://dev.azure.com/' + cfg.org + '/' + cfg.project + '/_apis';
  const progress = cfg.progress || (() => {});
  const parallel = cfg.parallel || DEFAULT_PARALLEL;
  const { cases, suites, memberships } = await collectTestCases(transport, base, cfg.plan,
    { progress, parallel });
  await enrichWorkItems(transport, base, cases, {
    limitFields: !!cfg.fieldsOnly,
    preconditionField: cfg.preconditionField,
    progress,
    parallel,
  });
  return {
    queue: buildQueue(cases, cfg),
    cache: buildCache(cases, cfg, suites, memberships),
    cases,
    suites,
    memberships,
  };
}

/* ------------------------------ selftest ------------------------------ */

function assert(cond, msg) {
  if (!cond) throw new Error('assertion failed: ' + msg);
}

function fixtureTransport(fixturesDir) {
  const load = (name) => JSON.parse(fs.readFileSync(path.join(fixturesDir, name), 'utf8'));
  return async (url) => {
    if (/\/wit\/workitems\?/.test(url)) {
      // No `fields=` filter -> the ALL-FIELDS batch the panel cache needs.
      const name = /[?&]fields=/.test(url) ? 'workitems.batch.json' : 'workitems.allfields.json';
      return { statusCode: 200, body: load(name), continuationToken: null };
    }
    if (/\/TestPoint/.test(url)) {
      if (/\/Suites\/1000\//.test(url)) return { statusCode: 200, body: load('points.suite1000.json'), continuationToken: null };
      if (/\/Suites\/2000\//.test(url)) return { statusCode: 200, body: load('points.suite2000.json'), continuationToken: null };
      return { statusCode: 200, body: { value: [] }, continuationToken: null };
    }
    if (/\/suites/.test(url)) {
      // Page 1 hands out a continuation token; page 2 ends the paging.
      if (/continuationToken=CT-SUITES-2/.test(url)) return { statusCode: 200, body: load('suites.page2.json'), continuationToken: null };
      return { statusCode: 200, body: load('suites.page1.json'), continuationToken: 'CT-SUITES-2' };
    }
    throw new Error('selftest transport: unmatched url ' + url);
  };
}

/**
 * A plan the size of the real one, out of thin air. No fixture file could carry this and no
 * fixture file should: what it proves is not a shape but a COST, and the cost only appears at
 * scale. `suiteCount` suites, `pointsPerSuite` points each, drawn from a pool of `caseCount`
 * cases, so most cases hang in several suites — which is what the plan actually looks like and
 * what made the old membership join quadratic.
 */
function syntheticTransport({ suiteCount, pointsPerSuite, caseCount }) {
  const suites = Array.from({ length: suiteCount }, (_, i) => ({
    id: 1000 + i, name: 'Suite ' + i, suiteType: 'StaticTestSuite',
  }));
  return async (url) => {
    if (/\/wit\/workitems\?/.test(url)) {
      const ids = /[?&]ids=([^&]+)/.exec(url)[1].split(',');
      return {
        statusCode: 200,
        body: { value: ids.map((id) => ({ id: Number(id), fields: { 'System.Title': 'Fall ' + id } })) },
        continuationToken: null,
      };
    }
    const suite = /\/Suites\/(\d+)\//.exec(url);
    if (suite) {
      const s = Number(suite[1]) - 1000;
      const value = Array.from({ length: pointsPerSuite }, (_, k) => {
        const caseId = 900000 + ((s * pointsPerSuite + k) % caseCount);
        return { id: s * pointsPerSuite + k, testCaseReference: { id: caseId, name: 'Fall ' + caseId } };
      });
      return { statusCode: 200, body: { value }, continuationToken: null };
    }
    return { statusCode: 200, body: { value: suites }, continuationToken: null };
  };
}

/** A JWT-shaped string whose only real claim is `exp`. Never a credential — nothing signs it. */
function fakeToken(expEpochSeconds) {
  const b64 = (o) => Buffer.from(JSON.stringify(o), 'utf8').toString('base64url');
  return b64({ alg: 'none' }) + '.' + b64({ exp: expEpochSeconds }) + '.x';
}

/**
 * Azure DevOps as it behaved on 2026-08-05: JSON while the token is alive, and a 302 to the
 * Entra sign-in page from the moment it is not. `tokens` is the list the renewal hands out in
 * order, so a test can say "the first one is dead, the second one works".
 */
function expiringAdoServer(deadTokens) {
  return new Promise((resolve) => {
    const seen = [];
    const server = http.createServer((req, res) => {
      const bearer = String(req.headers.authorization || '').replace(/^Bearer /, '');
      seen.push(bearer);
      if (deadTokens.has(bearer)) {
        // Not a 401. This is the shape ADO really answers with, and reading it as an ordinary
        // failure is what produced a wall of HTML instead of an instruction.
        res.writeHead(302, { Location: 'https://spsprodweu4.vssps.visualstudio.com/_signin' });
        res.end('<html><head><title>Object moved</title></head><body>'
          + '<h2>Object moved to <a href="https://spsprodweu4.vssps.visualstudio.com/_signin">here</a>.</h2>'
          + '</body></html>');
        return;
      }
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ value: [] }));
    });
    server.listen(0, '127.0.0.1', () => resolve({
      port: server.address().port,
      seen,
      close: () => server.close(),
    }));
  });
}

/**
 * A server that answers the TCP handshake and then says nothing, ever — the shape of a dropped
 * VPN or a proxy that accepted the connection and forgot it. Loopback only: this is the one
 * assertion that cannot be made without a socket, and it is the assertion that matters most,
 * because "no timeout" is invisible in every test that does not wait forever.
 */
function silentServer() {
  return new Promise((resolve) => {
    const held = [];
    const server = http.createServer((req, res) => { held.push(res); });
    server.listen(0, '127.0.0.1', () => resolve({
      port: server.address().port,
      close: () => { for (const r of held) r.destroy(); server.close(); },
    }));
  });
}

/**
 * The organisation the selftest pretends to point at. Invented, and its OWN — not the
 * machine's configuration: an offline proof that changed answer depending on which
 * ing-config.json happened to lie beside it would prove nothing, and it must stay runnable on
 * a machine that has no configuration at all. Matches the fixtures under fixtures/ado-testcases.
 */
const SELFTEST_CFG = {
  org: 'ExampleOrg',
  project: 'ExampleProject',
  plan: '1000000',
  projectLocation: 'C:/Beispiel/INGenious-Projekt',
};

async function runSelftest(args) {
  const fixturesDir = path.join(__dirname, 'fixtures', 'ado-testcases');
  const { queue, cache } = await generate(fixtureTransport(fixturesDir), SELFTEST_CFG);
  const cases = queue.testCases;

  // 1. continuation-token pagination: suite 2000 lives on page 2, so its case only
  //    appears if the pager followed x-ms-continuationtoken.
  const c1 = cases.find((c) => c.adoId === '4711');
  const c2 = cases.find((c) => c.adoId === '4712');
  assert(c1, 'case 4711 present');
  assert(c2, 'case 4712 present -> proves suites.page2 fetched via continuation token');

  // 2. de-duplication across suites: 4711 is a member of BOTH suites but must
  //    appear exactly once.
  assert(cases.length === 2, 'expected 2 unique cases (dedup across suites), got ' + cases.length);
  assert(cases.filter((c) => c.adoId === '4711').length === 1, '4711 deduplicated to one entry');

  // 3. steps-XML parsing: HTML-encoded action + expected, &amp; decode, tag strip.
  assert(c1.steps.length === 2, 'case 4711 parsed 2 steps, got ' + c1.steps.length);
  assert(
    c1.steps[0] === 'Kundensuche & Kundenuebersicht oeffnen → Erwartet: Suchmaske erscheint',
    'step 0 action decoded/stripped correctly, got: ' + JSON.stringify(c1.steps[0]),
  );
  assert(/100€ erfassen/.test(c1.steps[1]), 'numeric entity &#8364; decoded to euro, got: ' + JSON.stringify(c1.steps[1]));

  // 4. emitted queue shape / honest defaults.
  const shaped = cases.every((c) =>
    c.mapping === 'default'
    && Array.isArray(c.acceptanceCriteria) && c.acceptanceCriteria.length === 0
    && Array.isArray(c.dataCriteriaTags)
    && c.projectLocation === SELFTEST_CFG.projectLocation
    && c.release === RELEASE && c.testset === TESTSET
    && typeof c.title === 'string' && c.title.length > 0);
  assert(shaped, 'every case carries mapping:"default", empty acceptanceCriteria, and the projectLocation/Release1/Set1 defaults');
  assert(c1.title === 'Beispiel SYSTEMTEST: Kundensuche + Kundenuebersicht (Set1)', 'title comes from System.Title, got: ' + c1.title);

  // 5. panel cache: suite names survive, description is readable, and the
  //    Voraussetzungen field is discovered by NAME (custom field, not hardcoded).
  const p1 = cache.testCases.find((c) => c.adoId === '4711');
  const p2 = cache.testCases.find((c) => c.adoId === '4712');
  assert(p1 && p2, 'panel cache carries both cases');
  assert(p1.suiteName === 'Kundensuche Suite', 'cache keeps suiteName, got: ' + p1.suiteName);
  assert(p1.state === 'Design', 'cache keeps System.State, got: ' + p1.state);
  assert(p1.preconditionField === 'Custom.Voraussetzungen',
    'precondition discovered on the CUSTOM field by name, got: ' + p1.preconditionField);
  assert(/aktives Girokonto/.test(p1.preconditions), 'precondition text extracted, got: ' + JSON.stringify(p1.preconditions));
  assert(/- ein aktives Girokonto/.test(p1.preconditions), 'list items become "- " lines, got: ' + JSON.stringify(p1.preconditions));
  assert(!/<|&amp;/.test(p1.preconditions), 'precondition text is plain, no markup left: ' + JSON.stringify(p1.preconditions));
  assert(p2.preconditionField === 'Microsoft.VSTS.TCM.SystemInfo',
    'case without a custom field falls back to SystemInfo, got: ' + p2.preconditionField);
  assert(/Gemeinschaftskonten/.test(p1.description) && p1.description.includes('\n'),
    'description keeps paragraph breaks, got: ' + JSON.stringify(p1.description));
  assert(cache.preconditionFieldsSeen.length === 2 && cache.casesWithPreconditions === 2,
    'cache summarises which precondition fields were seen');

  // 5c. THE PLAN'S STRUCTURE, which used to be read on every run and written down nowhere.
  //     parentSuite was the field that made it a tree and the field that was dropped.
  assert(Array.isArray(cache.suites) && cache.suites.length === 2,
    'cache carries the suites as their own collection, got: ' + JSON.stringify(cache.suites));
  const root = cache.suites.find((s) => String(s.id) === '1000');
  const child = cache.suites.find((s) => String(s.id) === '2000');
  assert(root && child, 'both suites are in the cache');
  assert(!root.parentSuite, 'suite 1000 is a root');
  assert(child.parentSuite && String(child.parentSuite.id) === '1000',
    'suite 2000 keeps its parent -> the hierarchy survives, got: ' + JSON.stringify(child.parentSuite));
  assert(root.suiteType === 'StaticTestSuite' && root.revision === 7,
    'fields ADO sent are kept verbatim rather than picked one by one');

  // 5d. …minus the people. The cache lives on a tester's laptop and travels in the hand-off
  //     package; who last edited a suite is not needed to draw a tree.
  assert(!('lastUpdatedBy' in root),
    'lastUpdatedBy is stripped, got: ' + JSON.stringify(root.lastUpdatedBy));
  assert(!/eine\.person@example\.invalid|Eine echte Person/.test(JSON.stringify(cache)),
    'no personal identity anywhere in the cache');
  assert(root.lastUpdatedDate === '2026-07-01T08:12:44.000Z',
    'the TIMESTAMP is kept — only the identity goes, got: ' + root.lastUpdatedDate);

  // 5e. A case hangs in several suites. The flat list still shows it once (assertion 2);
  //     the information about where else it hangs is no longer thrown away with it.
  assert(p1.suiteIds.length === 2 && p1.suiteIds.includes('1000') && p1.suiteIds.includes('2000'),
    '4711 records BOTH suites it hangs in, got: ' + JSON.stringify(p1.suiteIds));
  assert(p2.suiteIds.length === 1 && p2.suiteIds[0] === '2000',
    '4712 hangs in one suite, got: ' + JSON.stringify(p2.suiteIds));
  assert(p1.suiteId === '1000', 'suiteId still names the FIRST suite, so an older reader works');
  assert(cache.memberships.length === 3,
    'one membership per test point, not per case: got ' + cache.memberships.length);
  const m = cache.memberships.find((x) => x.testCaseId === '4711' && x.suiteId === '2000');
  assert(m && m.pointId === '77003',
    'a membership names the test point it hangs by, got: ' + JSON.stringify(m));
  assert(cache.suiteCount === 2 && cache.membershipCount === 3,
    'the cache counts what it carries');

  // 5b. the browser URL comes from ADO's own _links.html.href — never constructed here.
  //     A case ADO gave no href for stays EMPTY rather than carrying a guessed link;
  //     the panel constructs from org/project in that case and says so.
  assert(p1.url === 'https://dev.azure.com/ExampleOrg/a1b2c3d4-5e6f-4a7b-8c9d-0e1f2a3b4c5d/_workitems/edit/4711',
    'url taken verbatim from _links.html.href, got: ' + p1.url);
  assert(p2.url === '', 'case without _links carries an EMPTY url, never a guessed one, got: ' + JSON.stringify(p2.url));
  assert(cache.casesWithUrl === 1, 'cache counts how many cases carry an ADO url, got: ' + cache.casesWithUrl);
  assert(cache.org === SELFTEST_CFG.org && cache.project === SELFTEST_CFG.project,
    'cache carries org/project so the panel can construct a fallback URL');

  // 6. --fields-only keeps the proven narrow fetch working (no precondition data).
  const narrow = await generate(fixtureTransport(fixturesDir), { ...SELFTEST_CFG, fieldsOnly: true });
  assert(narrow.queue.testCases.length === 2, '--fields-only still produces the queue');
  assert(narrow.cache.testCases.every((c) => c.preconditions === '' && c.preconditionField === null),
    '--fields-only honestly yields an empty precondition, never a fabricated one');
  assert(narrow.cache.testCases.every((c) => c.url === ''),
    '--fields-only cannot ask for $expand=links, so it yields no url (panel falls back to org/project)');

  // 7. THE WAIT HAS AN END. Until 2026-08-05 httpGetRaw armed no timeout at all, so a socket
  //    that opened and then went quiet left the promise unsettled and node alive with nothing
  //    to do — "Testfälle werden aus ADO geladen…" until somebody killed Studio. This is the
  //    only assertion here that needs a socket, and it is the one that would otherwise never
  //    fail in a test: an infinite wait passes every check that does not outlast it.
  const stalled = await silentServer();
  try {
    const t0 = Date.now();
    let refused = null;
    try {
      await httpGetRaw('http://127.0.0.1:' + stalled.port + '/testplan/Plans/1/suites', 'x', 400);
      assert(false, 'a server that never answers must NOT resolve');
    } catch (err) {
      refused = err;
    }
    const waited = Date.now() - t0;
    assert(waited < 5000, 'gave up on the silent server in ' + waited + 'ms, not eventually');
    assert(/nicht geantwortet/.test(refused.message),
      'the give-up names the wait in German, got: ' + refused.message);
    assert(/400 Sekunden|innerhalb von 0 Sekunden|Sekunden/.test(refused.message),
      'the sentence says how long was waited, got: ' + refused.message);
  } finally {
    stalled.close();
  }

  // 8. THE JOIN IS NOT QUADRATIC. `suiteIds` used to be built by filtering the whole
  //    membership list once per case: thousands of cases against tens of thousands of
  //    memberships is hundreds of millions of comparisons, minutes of pure CPU with the
  //    network idle and not one byte of output. A budget, not a shape: the defect was a cost.
  const big = { suiteCount: 300, pointsPerSuite: 300, caseCount: 3000 };
  const t1 = Date.now();
  const grown = await collectTestCases(syntheticTransport(big), 'https://example.invalid/_apis', '1',
    { parallel: 16 });
  const elapsed = Date.now() - t1;
  assert(grown.cases.length === big.caseCount,
    'synthetic plan yields ' + big.caseCount + ' unique cases, got ' + grown.cases.length);
  assert(grown.memberships.length === big.suiteCount * big.pointsPerSuite,
    'one membership per point, got ' + grown.memberships.length);
  // 1000ms, measured rather than guessed: the map does this in ~50ms and the per-case filter it
  // replaces needs ~2900ms for the same input on a fast desktop and more on a tester's laptop.
  // A budget between the two is the only form this assertion can take — the defect was a cost.
  assert(elapsed < 1000, 'a 300x300 plan is joined in ' + elapsed + 'ms — the per-case filter '
    + 'this replaces needs seconds for the same input, and minutes for the real plan');

  // 8b. …and running the suites in parallel did not reorder them. "First suite wins" decides
  //     which suite name a case is filed under, so a run whose suites completed out of order
  //     would produce a different cache that looked equally correct.
  const first = grown.cases[0];
  assert(first.id === '900000' && first.suiteId === '1000',
    'case 900000 is still filed under the FIRST suite that listed it, got '
      + first.id + '/' + first.suiteId);
  assert(grown.suites.length === big.suiteCount && String(grown.suites[0].id) === '1000'
    && String(grown.suites[big.suiteCount - 1].id) === String(1000 + big.suiteCount - 1),
    'the suites come back in plan order');

  // 9. THE WAIT REPORTS ITSELF. Silence for minutes is the defect the tester actually met;
  //    a fetch that says "Testpunkte: 120/300 Suiten" is the same wait and not the same bug.
  const lines = [];
  await generate(fixtureTransport(fixturesDir), { ...SELFTEST_CFG, progress: makeProgress((l) => lines.push(l), 0) });
  assert(lines.length >= 3, 'the run reports its phases, got ' + JSON.stringify(lines));
  assert(lines.every((l) => l.startsWith(PROGRESS_PREFIX)),
    'every progress line carries the prefix the panel keys on, got ' + JSON.stringify(lines));
  assert(lines.some((l) => /Suiten/.test(l)) && lines.some((l) => /Testpunkte: \d+\/\d+/.test(l))
    && lines.some((l) => /Testfall-Details: \d+\/\d+/.test(l)),
    'the three long phases each report a COUNT, not just a name: ' + JSON.stringify(lines));

  // 10. THE EXPIRED TOKEN — the 2026-08-05 outage, reproduced in a second.
  //
  //     10a. Its own `exp` is believed, not the cache's guess. The file said 12:46, the token
  //          said 12:19, ADO agreed with the token; a run that trusted the file spent 844
  //          seconds finding that out.
  const nowSec = Math.floor(Date.now() / 1000);
  assert(tokenExpiry(fakeToken(nowSec + 3600)) === nowSec + 3600, 'exp is read out of the token');
  assert(tokenExpiry('not-a-jwt') === 0, 'an unreadable token asserts nothing');
  assert(expiringSoon(fakeToken(nowSec - 60)), 'a token that has already expired is refused');
  assert(expiringSoon(fakeToken(nowSec + 4 * 60)),
    'four minutes left does not survive a fetch of this plan and counts as expiring');
  assert(!expiringSoon(fakeToken(nowSec + 70 * 60)), 'a fresh token is used as it is');
  assert(!expiringSoon('not-a-jwt'),
    'nothing is assumed about a token whose expiry cannot be read — the old behaviour, kept');

  //     10b. A redirect is renewed through, silently and exactly once — no prompt, no window,
  //          no wall of HTML, and no fourteen minutes.
  const dead = fakeToken(nowSec - 60);
  const alive = fakeToken(nowSec + 3600);
  const ado = await expiringAdoServer(new Set([dead]));
  try {
    let renewals = 0;
    const t = makeRealTransport(dead, {
      timeoutMs: 4000,
      renew: () => { renewals++; return alive; },
    });
    const answer = await t('http://127.0.0.1:' + ado.port + '/_apis/testplan/Plans/1/suites?api-version=7.1');
    assert(Array.isArray(answer.body.value), 'the request went through after the renewal');
    assert(renewals === 1, 'renewed exactly once, got ' + renewals);
    assert(ado.seen.length === 2 && ado.seen[0] === dead && ado.seen[1] === alive,
      'the dead token was tried once and the fresh one carried the retry');

    //   10c. …and when the renewal cannot help, it stops AT ONCE with a sentence a tester can
    //        act on. Not a timeout, not markup, not after every remaining suite has been tried.
    const stuck = makeRealTransport(dead, { timeoutMs: 4000, renew: () => dead });
    const t0 = Date.now();
    let refused = null;
    try {
      await stuck('http://127.0.0.1:' + ado.port + '/_apis/testplan/Plans/1/suites?api-version=7.1');
      assert(false, 'a token ADO keeps rejecting must not be retried into a timeout');
    } catch (err) {
      refused = err;
    }
    assert(Date.now() - t0 < 4000, 'gave up immediately, not after the retry schedule');
    assert(refused.message === NOT_SIGNED_IN,
      'the failure is the German instruction, not the redirect HTML: ' + refused.message);
    assert(!/<html|Object moved|_signin/i.test(refused.message),
      'no markup reaches the tester: ' + refused.message);
  } finally {
    ado.close();
  }

  console.log('ado-testcases selftest: GREEN — ' + cases.length + ' unique cases, pagination + steps-XML parse + shape assertions passed.');
  console.log('  panel cache: ' + cache.testCases.length + ' case(s), preconditions found on '
    + cache.casesWithPreconditions + ' via ' + cache.preconditionFieldsSeen.join(' + ') + '.');
  console.log('  finite wait: a silent server is given up on, not waited out.');
  console.log('  expired token: renewed silently once, then refused with an instruction.');
  console.log('  ' + big.suiteCount + '×' + big.pointsPerSuite + ' plan joined in ' + elapsed
    + 'ms, order preserved; ' + lines.length + ' progress lines emitted.');

  // `--selftest --cache <path>` writes the FIXTURE-derived cache. That is how the
  // plugin's sample cache is produced: same code path as production, zero real data.
  if (args && args.cacheExplicit) {
    const out = path.isAbsolute(args.cache) ? args.cache : path.join(process.cwd(), args.cache);
    fs.mkdirSync(path.dirname(out), { recursive: true });
    fs.writeFileSync(out, JSON.stringify(cache, null, 2) + '\n', 'utf8');
    console.log('  fixture cache written -> ' + out);
  }
}

/* ------------------------------ main ------------------------------ */

/**
 * No console, no interactive login.
 *
 * `az login` prints what it wants into the terminal it was started from. Started by
 * Studio there is no terminal — only a pipe Java drains into a log file — so the
 * prompt is invisible and the five-minute wait is spent on a question nobody was
 * asked. ADO_NONINTERACTIVE=1 makes getOrFetchToken refuse instead, in about a
 * second, with an instruction (ado-automark.mjs, commit 976586c).
 *
 * stdin decides, because that is what a console actually is: a human redirecting
 * stdout to a file still has one and still keeps the proven interactive path. An
 * explicit ADO_NONINTERACTIVE always wins, including ADO_NONINTERACTIVE=0 to force
 * the login back on.
 *
 * @returns {boolean} whether this run refused the interactive login
 */
function refuseInvisibleLogin(env = process.env) {
  if (String(env.ADO_NONINTERACTIVE ?? '').trim() !== '') return false;
  if (process.stdin.isTTY) return false;
  env.ADO_NONINTERACTIVE = '1';
  return true;
}

async function main() {
  const args = parseArgs(process.argv.slice(2));

  if (args.selftest) {
    await runSelftest(args);
    return;
  }

  const noConsole = refuseInvisibleLogin();
  let token = getOrFetchToken();
  // Checked BEFORE the first request, not discovered by it. A token that expires during the
  // fetch is the defect that cost 844 seconds on 2026-08-05: the cache said it was good for
  // another 49 minutes, the token itself had 22, and the run needed 14.
  if (token && expiringSoon(token)) {
    const fresh = renewToken();
    if (fresh) token = fresh;
  }
  if (!token) {
    // Technical line first, tester sentence LAST: the Studio panel shows the last
    // non-blank line of this output (AdoCache.lastLine), and what belongs in front of
    // a tester is what they can do, not a tenant GUID. The tenant is READ, never known:
    // unconfigured it stays a placeholder rather than becoming a guess.
    const tenant = config().tenantId || '<tenantId aus ing-config.json>';
    console.error('Kein Azure-DevOps-Token: `az login --tenant ' + tenant
      + '` auf einem Conditional-Access-konformen Rechner'
      + (noConsole ? ' (dieser Aufruf hat kein Konsolenfenster, deshalb wurde keine Anmeldung geoeffnet).' : '.'));
    console.error('Anmeldung bei Azure DevOps noetig — bitte einmal anmelden und erneut versuchen.');
    process.exit(1);
  }

  const started = Date.now();
  const progress = args.quiet ? () => {} : makeProgress(writeStderrLine);
  const transport = makeRealTransport(token, {
    timeoutMs: args.timeoutMs,
    renew: () => {
      progress('Anmeldung wird still erneuert…', true);
      return renewToken();
    },
  });
  // The CLI flag wins; otherwise the configuration answers, and an unanswered key stops the
  // run here with a sentence naming it rather than pointing the fetch at a guessed org.
  const cfg = {
    ...args,
    progress,
    org: args.org || CFG.org,
    project: args.project || CFG.project,
    plan: args.plan || CFG.plan,
  };
  const { queue, cache, cases } = await generate(transport, cfg);
  const seconds = Math.round((Date.now() - started) / 1000);

  // --json: the panel cache on stdout, nothing written. Lets a caller (or a panel)
  // consume the fetch without agreeing on a file location first.
  if (args.json) {
    process.stdout.write(JSON.stringify(cache, null, 2) + '\n');
    return;
  }

  progress('Datei wird geschrieben…', true);

  const outPath = path.isAbsolute(args.out) ? args.out : path.join(process.cwd(), args.out);
  fs.mkdirSync(path.dirname(outPath), { recursive: true });
  fs.writeFileSync(outPath, JSON.stringify(queue, null, 2) + '\n', 'utf8');

  // Written atomically-ish: the panel may be reading it while we refresh.
  const cachePath = path.isAbsolute(args.cache) ? args.cache : path.join(process.cwd(), args.cache);
  fs.mkdirSync(path.dirname(cachePath), { recursive: true });
  const tmp = cachePath + '.tmp';
  fs.writeFileSync(tmp, JSON.stringify(cache, null, 2) + '\n', 'utf8');
  fs.renameSync(tmp, cachePath);

  // Count summary.
  const bySuite = new Map();
  for (const c of cases) {
    const k = c.suiteName || c.suiteId;
    bySuite.set(k, (bySuite.get(k) || 0) + 1);
  }
  console.log('ado-testcases: ' + cases.length + ' unique test case(s) from plan ' + cfg.plan
    + ' -> ' + outPath);
  for (const [suite, n] of bySuite) console.log('  suite ' + suite + ': ' + n + ' case(s)');
  const withSteps = cases.filter((c) => c.steps && c.steps.length > 0).length;
  console.log('  ' + withSteps + '/' + cases.length + ' case(s) have parsed steps.');
  console.log('  panel cache -> ' + cachePath);
  console.log('  ' + cache.casesWithPreconditions + '/' + cases.length + ' case(s) have Voraussetzungen'
    + (cache.preconditionFieldsSeen.length
      ? ' (field: ' + cache.preconditionFieldsSeen.join(' + ') + ')'
      : ' — NO precondition field found; pass --precondition-field <ref> if the process uses another name'));
  // LAST line on purpose: AdoCache.lastLine() puts exactly this in front of the tester, so it
  // is written for her — how many, from how many folders, and how long it took.
  console.log(cases.length + ' Testfälle aus ' + cache.suiteCount + ' Suiten geladen ('
    + seconds + ' Sekunden).');
}

main().catch((e) => {
  console.error('ado-testcases error: ' + (e && e.message ? e.message : e));
  process.exit(1);
});
