#!/usr/bin/env node
/**
 * ado-testcases.mjs — pull the REAL test cases of an Azure DevOps Test Plan and
 * emit them as a companion queue JSON the Tester UI can load. Read-only against
 * ADO (only GETs). Node stdlib only.
 *
 * Auth: the PROVEN ING Entra-bearer flow. The token minting / caching / az-login
 * fallback is the SOURCE OF TRUTH in ing-qa-recorder/mvp/ado-automark.mjs
 * (resource 499b84ac-1321-427f-aa17-267ca6975798, tenant
 * 00000000-0000-0000-0000-000000000000, ~50-min cache at
 * %LOCALAPPDATA%\IngQaAutopilot\token.json, auto `az login --tenant` fallback,
 * az.cmd candidate paths incl. ~\azure-cli\bin\az.cmd). We IMPORT getOrFetchToken
 * from there rather than re-implement it, so both tools share one cache and one
 * proven code path.
 *
 * The interactive `az login` inside that flow is right at a command line and wrong
 * with no console: started by the INGenious Studio panel this tool runs on Java's
 * pipes, so az's instructions land in a log file nobody opens while the panel says
 * "wird aktualisiert…" for up to five minutes and then fails unexplained (issue
 * #128 — the display not matching reality, in its slowest form). So with no console
 * attached this tool declares itself non-interactive and refuses the login in about
 * a second, saying what is needed instead. Studio settles the sign-in BEFORE it
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
 * starting) is NOT a standard ADO field. It is either a custom field of your ADO
 * process or folded into System.Description / Microsoft.VSTS.TCM.SystemInfo.
 * Naming a non-existent field in `fields=` makes ADO reject the whole batch, so the
 * work-item batch is fetched WITHOUT a field filter and the precondition field is
 * discovered by name (override with --precondition-field). `--fields-only` restores
 * the old narrow fetch (System.Title + Steps) and skips precondition discovery.
 *
 * Usage:
 *   node tools/ado-testcases.mjs [--org <org>] [--project <project>] [--plan <id>]
 *                                # org/project/plan default to ado-config.json — see
 *                                # tools/README-ado-config.md
 *                                [--out companion/queue/queue.ado.json] [--cache <path>]
 *                                [--precondition-field Custom.Voraussetzungen] [--fields-only]
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
// Token logic AND the site configuration live in ado-automark.mjs (source of truth);
// importing shares one token cache and one ado-config.json.
import { getOrFetchToken, CFG } from '../ing-qa-recorder/mvp/ado-automark.mjs';

const API_VERSION = '7.1';
const __dirname = path.dirname(fileURLToPath(import.meta.url));

// Companion-queue defaults. mapping:"default" is an HONEST marker that no per-case
// INGenious testset mapping exists yet — every case points at one project/set until
// real mappings are authored. Set ING_QA_PROJECT_LOCATION to the INGenious project
// folder the queue should point at; unset, the marker says so in the file itself.
const PROJECT_LOCATION = process.env.ING_QA_PROJECT_LOCATION || '<nicht eingerichtet>';
const RELEASE = 'Release1';
const TESTSET = 'Set1';

/**
 * Which Azure DevOps this talks to — site configuration, never a literal in this file.
 * From ado-config.json or the environment (see tools/README-ado-config.md); --org /
 * --project / --plan on the command line still win over both. Read lazily, so `--help`
 * and `--selftest` work on a machine that has never been configured.
 */
function siteDefaults() {
  return { org: CFG.org, project: CFG.project, plan: String(CFG.planId) };
}

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
    // Deliberately NOT filled from configuration here: parseArgs must work on a machine
    // that has never been configured (--selftest, --help). Resolved in main(), and only
    // when a value is actually needed. A flag given on the command line still wins.
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
  };
  for (let i = 0; i < argv.length; i++) {
    const k = argv[i];
    if (k === '--selftest') a.selftest = true;
    else if (k === '--json') a.json = true;
    else if (k === '--fields-only') a.fieldsOnly = true;
    else if (k === '--org') a.org = argv[++i];
    else if (k === '--project') a.project = argv[++i];
    else if (k === '--plan') a.plan = argv[++i];
    else if (k === '--out') a.out = argv[++i];
    else if (k === '--cache') { a.cache = argv[++i]; a.cacheExplicit = true; }
    else if (k === '--precondition-field') a.preconditionField = argv[++i];
  }
  if (!a.cache) a.cache = defaultCachePath();
  return a;
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
// name so a custom ING field (Custom.Voraussetzungen, Custom.Vorbedingungen, …) is
// found without hardcoding the exact reference name we cannot verify from here.
const PRECONDITION_NAME_RE = /(voraussetzung|vorbedingung|precondition|prerequisit)/i;
// ADO's "System Information" is the field ING test cases most often abuse for
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

function httpGetRaw(urlStr, token) {
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
      }));
    });
    req.on('error', reject);
    req.end();
  });
}

// A transport is: async (url) => { statusCode, body(parsed), continuationToken }.
function makeRealTransport(token) {
  return async (url) => {
    const res = await httpGetRaw(url, token);
    if (res.statusCode < 200 || res.statusCode >= 300) {
      throw new Error('ADO GET ' + url + ' failed: HTTP ' + res.statusCode + ' ' + (res.body || '').slice(0, 300));
    }
    let body = {};
    if (res.body) {
      try { body = JSON.parse(res.body); }
      catch { throw new Error('non-JSON response from ' + url + ': ' + res.body.slice(0, 200)); }
    }
    return { statusCode: res.statusCode, body, continuationToken: res.continuationToken };
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

async function collectTestCases(transport, base, plan) {
  const suites = await fetchAllPages(transport, base + '/testplan/Plans/' + plan + '/suites?api-version=' + API_VERSION);
  const byId = new Map(); // first-seen wins -> unique test cases in stable order
  for (const s of suites) {
    const suiteId = s && s.id != null ? String(s.id) : '';
    const suiteName = s && s.name != null ? String(s.name) : '';
    if (!suiteId) continue;
    const points = await fetchAllPages(
      transport,
      base + '/testplan/Plans/' + plan + '/Suites/' + suiteId + '/TestPoint?api-version=' + API_VERSION,
    );
    for (const pt of points) {
      const e = extractPoint(pt);
      if (!e.testCaseId || byId.has(e.testCaseId)) continue;
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
  return [...byId.values()];
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
  for (let i = 0; i < cases.length; i += chunkSize) {
    const chunk = cases.slice(i, i + chunkSize);
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
      // Verified against the real plan (2026-07-27): ADO returns the PROJECT GUID here
      // (…/beispiel-org/<guid>/_workitems/edit/<id>), not the project name. Both routes
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
  }
}

function buildQueue(cases, cfg) {
  return {
    _note: 'Generated by tools/ado-testcases.mjs from ADO Test Plan ' + cfg.plan
      + ' (org ' + cfg.org + ' / project ' + cfg.project + '). One case per ADO test case, '
      + 'deduplicated across suites. mapping:"default" marks that NO per-case INGenious '
      + 'testset mapping exists yet — every case points at ' + RELEASE + '/' + TESTSET
      + ' of the shipped Beispielanwendung project until real mappings are authored. '
      + 'acceptanceCriteria is empty (ADO steps carry action+expected, not separate criteria).',
    testCases: cases.map((c) => ({
      adoId: String(c.id),
      title: c.title || c.name || ('Testfall ' + c.id),
      steps: Array.isArray(c.steps) ? c.steps : [],
      acceptanceCriteria: [],
      projectLocation: PROJECT_LOCATION,
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
function buildCache(cases, cfg) {
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
      + 'https://dev.azure.com/<org>/<project>/_workitems/edit/<id> from org/project above.',
    generatedAt: new Date().toISOString(),
    org: cfg.org,
    project: cfg.project,
    plan: String(cfg.plan),
    preconditionFieldsSeen: preFields,
    casesWithPreconditions: withPre,
    casesWithUrl: cases.filter((c) => c.url).length,
    testCases: cases.map((c) => ({
      adoId: String(c.id),
      title: c.title || c.name || ('Testfall ' + c.id),
      url: c.url || '',
      suiteId: String(c.suiteId || ''),
      suiteName: String(c.suiteName || ''),
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
  const cases = await collectTestCases(transport, base, cfg.plan);
  await enrichWorkItems(transport, base, cases, {
    limitFields: !!cfg.fieldsOnly,
    preconditionField: cfg.preconditionField,
  });
  return { queue: buildQueue(cases, cfg), cache: buildCache(cases, cfg), cases };
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

async function runSelftest(args) {
  const fixturesDir = path.join(__dirname, 'fixtures', 'ado-testcases');
  // Invented values: the selftest must pass unconfigured and must never name a real org.
  const DEFAULTS = { org: 'selftest-org', project: 'selftest-project', plan: '1' };
  const { queue, cache } = await generate(fixtureTransport(fixturesDir), DEFAULTS);
  const cases = queue.testCases;

  // 1. continuation-token pagination: suite 2000 lives on page 2, so its case only
  //    appears if the pager followed x-ms-continuationtoken.
  const c1 = cases.find((c) => c.adoId === '3951650');
  const c2 = cases.find((c) => c.adoId === '3951651');
  assert(c1, 'case 3951650 present');
  assert(c2, 'case 3951651 present -> proves suites.page2 fetched via continuation token');

  // 2. de-duplication across suites: 3951650 is a member of BOTH suites but must
  //    appear exactly once.
  assert(cases.length === 2, 'expected 2 unique cases (dedup across suites), got ' + cases.length);
  assert(cases.filter((c) => c.adoId === '3951650').length === 1, '3951650 deduplicated to one entry');

  // 3. steps-XML parsing: HTML-encoded action + expected, &amp; decode, tag strip.
  assert(c1.steps.length === 2, 'case 3951650 parsed 2 steps, got ' + c1.steps.length);
  assert(
    c1.steps[0] === 'Partner-Suche & Kunde-360 oeffnen → Erwartet: Suchmaske erscheint',
    'step 0 action decoded/stripped correctly, got: ' + JSON.stringify(c1.steps[0]),
  );
  assert(/100€ erfassen/.test(c1.steps[1]), 'numeric entity &#8364; decoded to euro, got: ' + JSON.stringify(c1.steps[1]));

  // 4. emitted queue shape / honest defaults.
  const shaped = cases.every((c) =>
    c.mapping === 'default'
    && Array.isArray(c.acceptanceCriteria) && c.acceptanceCriteria.length === 0
    && Array.isArray(c.dataCriteriaTags)
    && c.projectLocation === PROJECT_LOCATION
    && c.release === RELEASE && c.testset === TESTSET
    && typeof c.title === 'string' && c.title.length > 0);
  assert(shaped, 'every case carries mapping:"default", empty acceptanceCriteria, and the Beispielanwendung/Release1/Set1 defaults');
  assert(c1.title === 'Beispielanwendung SYSTEMTEST: Partner-Suche + Kunde-360 (Set1)', 'title comes from System.Title, got: ' + c1.title);

  // 5. panel cache: suite names survive, description is readable, and the
  //    Voraussetzungen field is discovered by NAME (custom field, not hardcoded).
  const p1 = cache.testCases.find((c) => c.adoId === '3951650');
  const p2 = cache.testCases.find((c) => c.adoId === '3951651');
  assert(p1 && p2, 'panel cache carries both cases');
  assert(p1.suiteName === 'Partner-Suche Suite', 'cache keeps suiteName, got: ' + p1.suiteName);
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

  // 5b. the browser URL comes from ADO's own _links.html.href — never constructed here.
  //     A case ADO gave no href for stays EMPTY rather than carrying a guessed link;
  //     the panel constructs from org/project in that case and says so.
  assert(p1.url === 'https://dev.azure.com/beispiel-org/11111111-1111-1111-1111-111111111111/_workitems/edit/3951650',
    'url taken verbatim from _links.html.href, got: ' + p1.url);
  assert(p2.url === '', 'case without _links carries an EMPTY url, never a guessed one, got: ' + JSON.stringify(p2.url));
  assert(cache.casesWithUrl === 1, 'cache counts how many cases carry an ADO url, got: ' + cache.casesWithUrl);
  assert(cache.org === DEFAULTS.org && cache.project === DEFAULTS.project,
    'cache carries org/project so the panel can construct a fallback URL');

  // 6. --fields-only keeps the proven narrow fetch working (no precondition data).
  const narrow = await generate(fixtureTransport(fixturesDir), { ...DEFAULTS, fieldsOnly: true });
  assert(narrow.queue.testCases.length === 2, '--fields-only still produces the queue');
  assert(narrow.cache.testCases.every((c) => c.preconditions === '' && c.preconditionField === null),
    '--fields-only honestly yields an empty precondition, never a fabricated one');
  assert(narrow.cache.testCases.every((c) => c.url === ''),
    '--fields-only cannot ask for $expand=links, so it yields no url (panel falls back to org/project)');

  console.log('ado-testcases selftest: GREEN — ' + cases.length + ' unique cases, pagination + steps-XML parse + shape assertions passed.');
  console.log('  panel cache: ' + cache.testCases.length + ' case(s), preconditions found on '
    + cache.casesWithPreconditions + ' via ' + cache.preconditionFieldsSeen.join(' + ') + '.');

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

  // Site configuration, resolved only now — after --selftest, so an unconfigured machine
  // can still run the offline test. Throws by name if a setting is missing.
  const site = siteDefaults();
  args.org ??= site.org;
  args.project ??= site.project;
  args.plan ??= site.plan;

  const noConsole = refuseInvisibleLogin();
  const token = getOrFetchToken();
  if (!token) {
    // Technical line first, tester sentence LAST: the Studio panel shows the last
    // non-blank line of this output (AdoCache.lastLine), and what belongs in front of
    // a tester is what they can do, not a tenant GUID.
    console.error('Kein Azure-DevOps-Token: `az login --tenant '
      + CFG.tenantId + '` auf einem Conditional-Access-konformen Rechner'
      + (noConsole ? ' (dieser Aufruf hat kein Konsolenfenster, deshalb wurde keine Anmeldung geoeffnet).' : '.'));
    console.error('Anmeldung bei Azure DevOps noetig — bitte einmal anmelden und erneut versuchen.');
    process.exit(1);
  }

  const { queue, cache, cases } = await generate(makeRealTransport(token), args);

  // --json: the panel cache on stdout, nothing written. Lets a caller (or a panel)
  // consume the fetch without agreeing on a file location first.
  if (args.json) {
    process.stdout.write(JSON.stringify(cache, null, 2) + '\n');
    return;
  }

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
  console.log('ado-testcases: ' + cases.length + ' unique test case(s) from plan ' + args.plan
    + ' -> ' + outPath);
  for (const [suite, n] of bySuite) console.log('  suite ' + suite + ': ' + n + ' case(s)');
  const withSteps = cases.filter((c) => c.steps && c.steps.length > 0).length;
  console.log('  ' + withSteps + '/' + cases.length + ' case(s) have parsed steps.');
  console.log('  panel cache -> ' + cachePath);
  console.log('  ' + cache.casesWithPreconditions + '/' + cases.length + ' case(s) have Voraussetzungen'
    + (cache.preconditionFieldsSeen.length
      ? ' (field: ' + cache.preconditionFieldsSeen.join(' + ') + ')'
      : ' — NO precondition field found; pass --precondition-field <ref> if the process uses another name'));
}

main().catch((e) => {
  console.error('ado-testcases error: ' + (e && e.message ? e.message : e));
  process.exit(1);
});
