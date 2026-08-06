#!/usr/bin/env node
/**
 * selector-uniqueness.mjs — counts how many elements each Object-Repository entry
 * of an INGenious project actually matches on a live page.
 *
 * WHY THIS EXISTS
 * ---------------
 * Uniqueness is not a property of a selector. It is a property of (selector, page state).
 * No static reading of a saved test case can decide it, so this tool opens a real page.
 *
 * The INGenious engine already fails loudly on an ambiguous selector at REPLAY time:
 * Playwright strict mode raises and the engine copies the message into the report
 * verbatim, list of matches and all. Two things that does not cover:
 *
 *   1. It only happens at replay. The recording is already saved, handed over and
 *      possibly promoted before anyone learns the selector was ambiguous.
 *   2. One branch of the engine is NOT strict: AutomationObject.getElements(FrameLocator, ..)
 *      builds `framelocator.locator("css=" + value).first()`. Inside a frame, an ambiguous
 *      css selector silently takes the first match, clicks the wrong element and the test
 *      passes GREEN. Proven against tools/fixtures/ambiguous-selectors.
 *
 * This tool decides both, before replay, using Locator.count() — which returns a number
 * instead of throwing, so it reports the frame case the engine cannot see.
 *
 * HONESTY CONTRACT
 * ----------------
 * Every object lands in exactly one of: AMBIGUOUS, UNIQUE, or a could-not-test reason.
 * "Not present on this page" is NOT a pass — an object belonging to a later screen simply
 * was not tested. If nothing could be decided, the tool exits CANNOT_TELL (2), never 0.
 *
 * Node built-ins + the `playwright` package. If playwright is missing the tool exits
 * CANNOT_TELL, it never guesses.
 */

import { readFileSync, readdirSync, existsSync, statSync, writeFileSync } from 'node:fs';
import { join, resolve, basename } from 'node:path';
import { launchWithFallback } from './lib/find-browser.mjs';

// ---------------------------------------------------------------------------
// Exit contract — mirrors tools/ingenious-run.mjs: only 0 is green.
// ---------------------------------------------------------------------------
const EXIT = {
  CLEAN: 0, // EVERY object was tested and every one matched exactly one element
  AMBIGUOUS: 1, // at least one object matched 2+ elements
  CANNOT_TELL: 2, // at least one object could not be tested (absent, unsupported, unreachable)
  ARGS_REJECTED: 3,
};

const USAGE = `
selector-uniqueness.mjs — count real matches for each Object-Repository entry

  node tools/selector-uniqueness.mjs --project <dir> --url <url> [options]
  node tools/selector-uniqueness.mjs --selftest

  --project <dir>        INGenious project root (the folder holding ObjectRepository/)
  --url <url>            page to probe, opened once
  --page <name>          only probe this OR page (repeatable); default: all
  --storage-state <file> Playwright storageState JSON, for a target behind a login
  --browser <name>       chromium (default) | firefox | webkit
  --executable-path <f>  browser binary to use instead of the bundled one
  --timeout-ms <n>       page load timeout, default 30000
  --settle-ms <n>        wait after load before counting, default 500
  --json <file>          write the full result
  --headed               show the browser
  --selftest             probe the bundled ambiguous fixture end to end

Exit: 0 every object tested and unique · 1 ambiguity found
      2 could not tell (something was not testable here) · 3 arguments rejected

Exit 0 is deliberately hard to reach: partial coverage is reported as 2, because an
object that is not on the probed page was not checked and must not read as verified.
`.trim();

// The order the engine resolves attributes in — WebOR.OBJECT_PROPS. getElementsInternal()
// takes the FIRST non-empty attribute and breaks, so every later attribute is dead weight.
const OBJECT_PROPS = [
  'Role',
  'Text',
  'Label',
  'Placeholder',
  'xpath',
  'css',
  'AltText',
  'Title',
  'TestId',
  'ChainedLocator',
  'JSPath',
];

// YAML key (lowercased) -> engine attribute name, per YamlElementDefinition.toWebORObject.
const YAML_TO_PROP = {
  role: 'Role',
  text: 'Text',
  label: 'Label',
  placeholder: 'Placeholder',
  xpath: 'xpath',
  css: 'css',
  alttext: 'AltText',
  title: 'Title',
  testid: 'TestId',
  chainedlocator: 'ChainedLocator',
  jspath: 'JSPath',
};

// The engine builds these itself from a bespoke chain grammar (AutomationObject.chainLocators
// / createJSPathLocator). Reimplementing that grammar here would risk probing a locator the
// engine never builds, so these are reported as not tested rather than guessed at.
const UNSUPPORTED_PROPS = new Set(['ChainedLocator', 'JSPath']);

// ---------------------------------------------------------------------------
// Arguments
// ---------------------------------------------------------------------------
function parseArgs(argv) {
  const out = { pages: [], browser: 'chromium', timeoutMs: 30000, settleMs: 500 };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    const next = () => argv[++i];
    switch (a) {
      case '--project': out.project = next(); break;
      case '--url': out.url = next(); break;
      case '--page': out.pages.push(next()); break;
      case '--storage-state': out.storageState = next(); break;
      case '--browser': out.browser = next(); break;
      case '--executable-path': out.executablePath = next(); break;
      case '--timeout-ms': out.timeoutMs = Number(next()); break;
      case '--settle-ms': out.settleMs = Number(next()); break;
      case '--json': out.json = next(); break;
      case '--headed': out.headed = true; break;
      case '--selftest': out.selftest = true; break;
      case '-h': case '--help': out.help = true; break;
      default: out.unknown = a;
    }
  }
  return out;
}

// ---------------------------------------------------------------------------
// Object Repository
// ---------------------------------------------------------------------------

/** Strip surrounding quotes from a scalar YAML value. */
function unquote(raw) {
  const v = raw.trim();
  if (v.length >= 2 && ((v[0] === '"' && v.endsWith('"')) || (v[0] === "'" && v.endsWith("'")))) {
    const inner = v.slice(1, -1);
    return v[0] === '"' ? inner.replace(/\\(["\\nt])/g, (_, c) =>
      c === 'n' ? '\n' : c === 't' ? '\t' : c) : inner.replace(/''/g, "'");
  }
  return v;
}

/**
 * Parse an INGenious OR page YAML. The shape is fixed and shallow:
 *   page: <name>
 *   elements:
 *     <ElementName>:
 *       <attr>: <value>
 * A general YAML parser would be more code and no more correct for this shape.
 */
function parseOrYaml(file) {
  const text = readFileSync(file, 'utf8');
  const lines = text.split(/\r?\n/);
  let pageName = basename(file).replace(/\.ya?ml$/i, '');
  const elements = [];
  let inElements = false;
  let current = null;

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    if (!line.trim() || line.trim().startsWith('#')) continue;
    const indent = line.length - line.trimStart().length;

    if (indent === 0) {
      inElements = false;
      current = null;
      const m = /^([A-Za-z_][\w-]*)\s*:\s*(.*)$/.exec(line);
      if (!m) continue;
      if (m[1] === 'page' && m[2].trim()) pageName = unquote(m[2]);
      if (m[1] === 'elements') inElements = true;
      continue;
    }
    if (!inElements) continue;

    const m = /^\s*([^:]+?)\s*:\s*(.*)$/.exec(line);
    if (!m) continue;
    const key = m[1].trim();
    const val = m[2];

    if (indent === 2) {
      current = { name: unquote(key), attrs: {}, frame: '', line: i + 1, file };
      elements.push(current);
    } else if (current && val.trim()) {
      const k = key.toLowerCase();
      if (k === 'frame') current.frame = unquote(val);
      else if (k === 'exact' || k === 'description') { /* not a locator */ }
      else if (YAML_TO_PROP[k]) current.attrs[YAML_TO_PROP[k]] = unquote(val);
    }
  }
  return { page: pageName, elements };
}

function loadObjectRepository(projectRoot) {
  const dir = join(projectRoot, 'ObjectRepository', 'Web');
  if (!existsSync(dir) || !statSync(dir).isDirectory()) return null;
  const pages = [];
  for (const f of readdirSync(dir)) {
    if (!/\.ya?ml$/i.test(f)) continue;
    pages.push(parseOrYaml(join(dir, f)));
  }
  return pages;
}

/**
 * Which attribute the engine will actually use, and which ones it will ignore.
 * getElementsInternal() iterates OBJECT_PROPS order and breaks on the first non-empty.
 */
function effectiveAttribute(attrs) {
  const present = OBJECT_PROPS.filter((p) => (attrs[p] || '').trim() !== '');
  return { prop: present[0] || null, shadowed: present.slice(1) };
}

// ---------------------------------------------------------------------------
// Locator construction — deliberately mirrors AutomationObject.getElements(..)
// ---------------------------------------------------------------------------

/** Role attribute value is "ROLE;name" (createRoleLocator splits on ';'). */
function roleParts(value) {
  const parts = value.split(';');
  return { role: parts[0].trim().toLowerCase(), name: parts.length > 1 ? parts[1] : undefined };
}

/**
 * Resolve the frame chain. `frame` is a ';'-separated list of frame selectors
 * (AutomationObject.switchFrame). Returns { root } or { error }.
 * A frame selector matching 2+ iframes is itself an ambiguity, and it is reported
 * as one instead of being silently resolved.
 */
async function resolveFrameRoot(page, frameSpec) {
  if (!frameSpec || !frameSpec.trim()) return { root: page, scopeLabel: 'page' };
  const selectors = frameSpec.split(';').map((s) => s.trim()).filter(Boolean);
  let searchRoot = page;
  let frameRoot = null;
  for (const sel of selectors) {
    const n = await (frameRoot ? frameRoot.locator(sel) : searchRoot.locator(sel)).count();
    if (n === 0) return { error: 'FRAME_NOT_PRESENT', detail: `frame selector "${sel}" matched 0 elements` };
    if (n > 1) return { error: 'FRAME_AMBIGUOUS', detail: `frame selector "${sel}" matched ${n} iframes` };
    frameRoot = frameRoot ? frameRoot.frameLocator(sel) : searchRoot.frameLocator(sel);
  }
  return { root: frameRoot, scopeLabel: `frame(${frameSpec})` };
}

/** Build the locator the engine would build for this attribute against this root. */
function buildLocator(root, prop, value) {
  switch (prop) {
    case 'Text': return root.getByText(value);
    case 'Label': return root.getByLabel(value);
    case 'Placeholder': return root.getByPlaceholder(value);
    case 'AltText': return root.getByAltText(value);
    case 'Title': return root.getByTitle(value);
    case 'TestId': return root.getByTestId(value);
    case 'css': return root.locator('css=' + value);
    case 'xpath': return root.locator('xpath=' + value);
    case 'Role': {
      const { role, name } = roleParts(value);
      return name === undefined ? root.getByRole(role) : root.getByRole(role, { name });
    }
    default: return null;
  }
}

// ---------------------------------------------------------------------------
// Probe
// ---------------------------------------------------------------------------

const DECIDED = new Set(['AMBIGUOUS', 'UNIQUE']);

/**
 * Where an element sits in the document, as tag names and sibling indexes only.
 *
 * Deliberately content-free: no text, no attribute values, no ids. For a banking
 * application the receipt travels in hand-over packages, so a fingerprint that carried
 * customer data would be a leak. Depth is capped so a deep tree cannot produce an
 * unbounded string.
 */
const IDENTITY_FN = (el) => {
  const path = [];
  let n = el;
  while (n && n.nodeType === 1 && path.length < 16) {
    const p = n.parentElement;
    if (!p) break;
    path.unshift(`${n.tagName.toLowerCase()}:${[...p.children].indexOf(n)}`);
    n = p;
  }
  return path.join('/');
};

async function probe(pagesToProbe, page, opts) {
  const results = [];
  for (const orPage of pagesToProbe) {
    for (const el of orPage.elements) {
      const base = {
        page: orPage.page,
        element: el.name,
        frame: el.frame || '',
        file: el.file,
        line: el.line,
      };
      const { prop, shadowed } = effectiveAttribute(el.attrs);

      if (!prop) {
        results.push({ ...base, verdict: 'NO_LOCATOR', detail: 'the entry defines no locator attribute at all' });
        continue;
      }
      const value = el.attrs[prop];
      const common = { ...base, attribute: prop, value, shadowed };

      if (UNSUPPORTED_PROPS.has(prop)) {
        results.push({
          ...common,
          verdict: 'NOT_TESTED_UNSUPPORTED',
          detail: `${prop} uses the engine's own chain grammar; this probe does not reimplement it, so it was not tested`,
        });
        continue;
      }

      let root, scopeLabel;
      try {
        const r = await resolveFrameRoot(page, el.frame);
        if (r.error) {
          results.push({ ...common, verdict: r.error === 'FRAME_AMBIGUOUS' ? 'AMBIGUOUS_FRAME' : 'NOT_TESTED_FRAME', detail: r.detail });
          continue;
        }
        root = r.root;
        scopeLabel = r.scopeLabel;
      } catch (e) {
        results.push({ ...common, verdict: 'NOT_TESTED_ERROR', detail: 'frame resolution failed: ' + e.message });
        continue;
      }

      let count;
      let matches = [];
      let identity = null;
      try {
        const loc = buildLocator(root, prop, value);
        if (!loc) {
          results.push({ ...common, verdict: 'NOT_TESTED_UNSUPPORTED', detail: `no probe mapping for attribute ${prop}` });
          continue;
        }
        count = await loc.count();
        if (count >= 1) {
          // A position-in-tree fingerprint of the first match. Two entries that resolve to
          // the SAME element are the one duplicate no amount of reading can find: their
          // selector strings differ, so nothing static relates them, and each is unique on
          // its own so the count says nothing either. It carries no page content, only
          // tag names and sibling indexes, so it is safe to write into a receipt.
          identity = await loc.first().evaluate(IDENTITY_FN).catch(() => null);
        }
        if (count >= 2) {
          const n = Math.min(count, 5);
          for (let i = 0; i < n; i++) {
            matches.push(
              (await loc.nth(i).evaluate((e) => e.outerHTML.slice(0, 160)).catch(() => '<unreadable>')),
            );
          }
        }
      } catch (e) {
        results.push({ ...common, verdict: 'NOT_TESTED_ERROR', detail: 'locator could not be built or counted: ' + e.message });
        continue;
      }

      if (count === 0) {
        results.push({
          ...common, scope: scopeLabel, count,
          verdict: 'NOT_TESTED_ABSENT',
          detail: 'matched 0 elements in this page state — not present here, so uniqueness was not tested',
        });
      } else if (count === 1) {
        results.push({ ...common, scope: scopeLabel, count, identity, verdict: 'UNIQUE' });
      } else {
        // The one shape the engine will NOT catch at replay: css inside a frame is
        // resolved with .first() (AutomationObject.java css branch of the FrameLocator
        // overload), so the wrong element is clicked and the run stays green.
        const silent = Boolean(el.frame && el.frame.trim()) && prop === 'css';
        results.push({
          ...common, scope: scopeLabel, count, matches, identity,
          verdict: 'AMBIGUOUS',
          silentAtReplay: silent,
          detail: silent
            ? `matched ${count} elements; inside a frame the engine takes .first() — this will NOT fail at replay, it will click the wrong element and pass`
            : `matched ${count} elements; Playwright strict mode will fail this step at replay`,
        });
      }
    }
  }
  return results;
}

// ---------------------------------------------------------------------------
// Reporting
// ---------------------------------------------------------------------------
function summarize(results) {
  const s = { total: results.length, ambiguous: 0, silent: 0, unique: 0, notTested: 0 };
  for (const r of results) {
    if (r.verdict === 'AMBIGUOUS' || r.verdict === 'AMBIGUOUS_FRAME') {
      s.ambiguous++;
      if (r.silentAtReplay) s.silent++;
    } else if (r.verdict === 'UNIQUE') s.unique++;
    else s.notTested++;
  }
  s.decided = s.ambiguous + s.unique;
  return s;
}

function report(results, summary, url) {
  const line = '─'.repeat(72);
  console.log(line);
  console.log(`selector-uniqueness — ${url}`);
  console.log(line);

  for (const r of results) {
    const where = `${r.page}.${r.element}`;
    if (r.verdict === 'AMBIGUOUS' || r.verdict === 'AMBIGUOUS_FRAME') {
      const tag = r.silentAtReplay ? 'AMBIGUOUS (SILENT AT REPLAY)' : 'AMBIGUOUS';
      console.log(`\n  ${tag}  ${where}`);
      console.log(`      ${r.attribute} = ${JSON.stringify(r.value)}${r.frame ? `  frame=${r.frame}` : ''}`);
      console.log(`      ${r.detail}`);
      for (const m of r.matches || []) console.log(`        · ${m}`);
      console.log(`      ${r.file}:${r.line}`);
    }
  }

  const notTested = results.filter((r) => !DECIDED.has(r.verdict) && r.verdict !== 'AMBIGUOUS_FRAME');
  if (notTested.length) {
    console.log(`\n  NOT TESTED (${notTested.length}) — these are not passes:`);
    for (const r of notTested) {
      console.log(`      ${r.page}.${r.element}  [${r.verdict}] ${r.detail}`);
    }
  }

  // Two entries that landed on the SAME element. Each is unique on its own, so nothing
  // above complains — yet one of the two names is redundant, and if they disagree about
  // what the element MEANS, one of them is lying about what its test case proves.
  const byIdentity = new Map();
  for (const r of results) {
    if (!r.identity || !DECIDED.has(r.verdict)) continue;
    if (!byIdentity.has(r.identity)) byIdentity.set(r.identity, []);
    byIdentity.get(r.identity).push(r);
  }
  const aliases = [...byIdentity.values()].filter((g) => g.length > 1);
  if (aliases.length) {
    console.log(`\n  DASSELBE ELEMENT UNTER MEHREREN NAMEN (${aliases.length}) — `
      + 'jede Angabe für sich ist eindeutig, gemeint ist aber dasselbe Element:');
    for (const group of aliases) {
      console.log('      ' + group.map((r) => `${r.page}.${r.element}`).join('  ==  '));
      for (const r of group) console.log(`        ${r.attribute} = ${JSON.stringify(r.value)}`);
    }
  }

  const shadowing = results.filter((r) => (r.shadowed || []).length > 0);
  if (shadowing.length) {
    console.log(`\n  NOTE — attributes the engine will ignore (it uses the first non-empty only):`);
    for (const r of shadowing) {
      console.log(`      ${r.page}.${r.element}: uses ${r.attribute}, ignores ${r.shadowed.join(', ')}`);
    }
  }

  console.log(`\n${line}`);
  console.log(
    `  ${summary.total} object(s): ${summary.ambiguous} ambiguous` +
      (summary.silent ? ` (${summary.silent} silent at replay)` : '') +
      `, ${summary.unique} unique, ${summary.notTested} not tested`,
  );
  console.log(line);
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------
function fail(code, msg) {
  console.error(msg);
  process.exit(code);
}

async function loadPlaywright() {
  try {
    return await import('playwright');
  } catch {
    return null;
  }
}

async function launchBrowser(engine, args) {
  const opts = { headless: !args.headed };
  if (args.executablePath) opts.executablePath = args.executablePath;
  return launchWithFallback(engine, args.browser, opts);
}

async function run(args) {
  const projectRoot = resolve(args.project);
  if (!existsSync(projectRoot)) return fail(EXIT.ARGS_REJECTED, `project not found: ${projectRoot}`);

  const allPages = loadObjectRepository(projectRoot);
  if (allPages === null) {
    return fail(EXIT.ARGS_REJECTED, `no ObjectRepository/Web under ${projectRoot}`);
  }
  const pagesToProbe = args.pages.length
    ? allPages.filter((p) => args.pages.includes(p.page))
    : allPages;
  if (pagesToProbe.length === 0) {
    return fail(EXIT.ARGS_REJECTED, `no OR page matched --page ${args.pages.join(', ')}`);
  }
  const objectCount = pagesToProbe.reduce((n, p) => n + p.elements.length, 0);
  if (objectCount === 0) {
    console.error('CANNOT TELL — the object repository holds no entries; nothing to check.');
    process.exit(EXIT.CANNOT_TELL);
  }

  const pw = await loadPlaywright();
  if (!pw) {
    console.error(
      'CANNOT TELL — the `playwright` package is not resolvable from here, so no page could be\n' +
        'opened and no selector was checked. Install it (npm i -D playwright) and re-run.',
    );
    process.exit(EXIT.CANNOT_TELL);
  }

  const engine = pw[args.browser];
  if (!engine) return fail(EXIT.ARGS_REJECTED, `unknown --browser ${args.browser}`);

  let browser, context, page;
  try {
    browser = await launchBrowser(engine, args);
    context = await browser.newContext(
      args.storageState ? { storageState: args.storageState } : {},
    );
    page = await context.newPage();
    await page.goto(args.url, { timeout: args.timeoutMs, waitUntil: 'load' });
    if (args.settleMs > 0) await page.waitForTimeout(args.settleMs);
  } catch (e) {
    if (browser) await browser.close().catch(() => {});
    console.error(
      `CANNOT TELL — could not open ${args.url}: ${e.message}\n` +
        'No selector was checked. This is not a pass.',
    );
    process.exit(EXIT.CANNOT_TELL);
  }

  let results;
  try {
    results = await probe(pagesToProbe, page, args);
  } finally {
    await browser.close().catch(() => {});
  }

  const summary = summarize(results);
  report(results, summary, args.url);

  if (args.json) {
    writeFileSync(
      args.json,
      JSON.stringify({ url: args.url, project: projectRoot, summary, results }, null, 2),
    );
    console.log(`  receipt: ${args.json}`);
  }

  if (summary.ambiguous > 0) process.exit(EXIT.AMBIGUOUS);
  if (summary.notTested > 0) {
    // Partial coverage is not a clean result. An object that is not on this page state
    // was not checked, and saying "clean" would let an untested object read as verified.
    console.error(
      `\nCANNOT TELL — ${summary.decided} of ${summary.total} object(s) were decided and none of ` +
        `those is ambiguous, but ${summary.notTested} could not be tested on this page state.\n` +
        'Probe the page(s) where those objects actually appear before calling this test case clean.',
    );
    process.exit(EXIT.CANNOT_TELL);
  }
  process.exit(EXIT.CLEAN);
}

// ---------------------------------------------------------------------------
// Selftest — probes the bundled fixture, whose ambiguity is known by construction.
// ---------------------------------------------------------------------------
async function selftest() {
  const here = new URL('.', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1');
  const fixtureDir = join(here, 'fixtures', 'ambiguous-selectors');
  const project = join(fixtureDir, 'AmbiguityProbe');

  const pw = await loadPlaywright();
  if (!pw) {
    console.error('SELFTEST CANNOT RUN — playwright is not resolvable. Not a pass, not a failure.');
    process.exit(EXIT.CANNOT_TELL);
  }

  const { createServer } = await import('node:http');
  const server = createServer((req, res) => {
    const p = (req.url || '/').split('?')[0];
    const file = p === '/frame.html' ? 'frame.html' : 'index.html';
    res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
    res.end(readFileSync(join(fixtureDir, file)));
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const url = `http://127.0.0.1:${server.address().port}/`;

  // Through the same finder the real run uses. Launching pw.chromium directly works on a
  // developer machine and fails on the device this ships to, which has no pinned
  // playwright revision and cannot install one — so the selftest would be red exactly
  // where it is the only check available.
  const browser = await launchWithFallback(pw.chromium, 'chromium', { headless: true });
  const page = await (await browser.newContext()).newPage();
  await page.goto(url);
  await page.waitForTimeout(300);
  const orPages = loadObjectRepository(project).filter((p) => p.page === 'AmbiguityPage');
  const results = await probe(orPages, page, {});
  await browser.close();
  server.close();

  // Expectations follow from the fixture markup, not from a previous run of this tool.
  const expect = {
    KontoAnlegen: ['UNIQUE', 1],
    Ergebnis: ['UNIQUE', 1],
    WeiterButton: ['AMBIGUOUS', 2],
    PrimaryButton: ['AMBIGUOUS', 4],
    KontonummerFeld: ['AMBIGUOUS', 2],
    FrameErgebnis: ['UNIQUE', 1],
    FramePrimaryButton: ['AMBIGUOUS', 3],
    FrameSpeichern: ['AMBIGUOUS', 2],
    // The honesty path: an attribute this probe does not reimplement must be reported
    // as not tested, never guessed at and never counted as clean.
    ChainedBeispiel: ['NOT_TESTED_UNSUPPORTED', undefined],
  };
  const problems = [];
  for (const [name, [verdict, count]] of Object.entries(expect)) {
    const r = results.find((x) => x.element === name);
    if (!r) { problems.push(`${name}: missing from results`); continue; }
    if (r.verdict !== verdict) problems.push(`${name}: expected ${verdict}, got ${r.verdict}`);
    else if (count !== undefined && r.count !== count) {
      problems.push(`${name}: expected count ${count}, got ${r.count}`);
    }
  }
  const silent = results.find((r) => r.element === 'FramePrimaryButton');
  if (!silent || silent.silentAtReplay !== true) {
    problems.push('FramePrimaryButton: expected silentAtReplay=true (frame + css takes .first())');
  }
  const loud = results.find((r) => r.element === 'FrameSpeichern');
  if (loud && loud.silentAtReplay === true) {
    problems.push('FrameSpeichern: role inside a frame IS strict, must not be flagged silent');
  }

  if (problems.length) {
    console.error('selector-uniqueness selftest: RED\n  ' + problems.join('\n  '));
    process.exit(EXIT.AMBIGUOUS);
  }
  console.log(
    `selector-uniqueness selftest: GREEN — ${Object.keys(expect).length} objects on the real ` +
      'fixture page: 5 ambiguous (1 of them silent at replay), 3 unique, 1 reported as not tested.',
  );
  process.exit(EXIT.CLEAN);
}

const args = parseArgs(process.argv.slice(2));
if (args.help) { console.log(USAGE); process.exit(EXIT.CLEAN); }
if (args.unknown) fail(EXIT.ARGS_REJECTED, `unknown argument: ${args.unknown}\n\n${USAGE}`);
if (args.selftest) await selftest();
else if (!args.project || !args.url) fail(EXIT.ARGS_REJECTED, USAGE);
else await run(args);
