#!/usr/bin/env node
/**
 * object-store.mjs — a searchable store over everything the automation already knows:
 * object repositories, reusable components (Bausteine) and test cases.
 *
 * WHY THIS EXISTS
 * ---------------
 * Every question below is asked in practice and cannot be answered today without reading
 * every file by hand:
 *
 *   · Which selectors do we already have, and which appear in more than one place?
 *   · Which objects are maintained twice under different names?
 *   · Which selectors are fragile — and where would a rename break several test cases?
 *   · Which test cases share a step sequence that should have been a Baustein?
 *
 * It is a READER. It never writes into a project, never opens a browser, never uses the
 * network and needs no npm package. Everything it reports is derived from files on disk,
 * plus — optionally — the live verdicts of tools/selector-uniqueness.mjs, which is the only
 * component that can decide ambiguity, because ambiguity is a property of (selector, page
 * state) and not of a selector alone.
 *
 * HONESTY CONTRACT
 * ----------------
 * Static analysis can prove that two selector STRINGS are identical. It cannot prove that
 * two different strings address the same element, and it cannot prove that a selector is
 * ambiguous. Those two findings are reported ONLY when a selector-uniqueness receipt
 * supplies them, and they are labelled as measured. Everything else is labelled as a
 * static reading, and "fragile" always means "structurally suspect", never "broken".
 */

import { readFileSync, writeFileSync, existsSync, readdirSync, statSync } from 'node:fs';
import { join, resolve, basename, relative, sep } from 'node:path';

const EXIT = { OK: 0, FINDINGS: 1, NOTHING_INDEXED: 2, ARGS_REJECTED: 3, NOT_RESOLVED: 5 };

/**
 * The exit code from the totals, and nothing else. Pure, so the selftest reaches every branch.
 *
 * Four of the six finding counters — duplicateSelectors, fragile, shadowed, measuredAmbiguous
 * — can only ever be non-zero if OBJECTS were indexed. With an empty object set they are the
 * initial values of counters, not measurements, and the tool had no way left of reaching
 * anything but EXIT.OK on their account. The empty guard that was supposed to catch this read
 *
 *     objects === 0 && testCases === 0
 *
 * so it only fired when the test cases were missing too, and a project with plenty of test
 * cases and an ObjectRepository nobody could read — INGenious's own Projects/CLIDemo, whose
 * objects sit in the old binary IOR.object — walked straight past it and reported
 * "0 fragil · 0 doppelt gepflegt · 0 unbenutzt" as measured findings, exit 0.
 */
function overallExit(t) {
  const findings = t.duplicateSelectors + t.fragile + t.shadowed
    + t.bausteinCandidates + t.measuredAmbiguous + t.brokenCalls;
  if (findings > 0) return EXIT.FINDINGS;
  return t.objects === 0 ? EXIT.NOTHING_INDEXED : EXIT.OK;
}

const USAGE = `
object-store.mjs — index object repositories, Bausteine and test cases, and answer the
questions a tester or test lead asks about them daily.

  node tools/object-store.mjs --project <dir> [--project <dir> ...] [options]
  node tools/object-store.mjs --scan <dir> [options]
  node tools/object-store.mjs --selftest

  --project <dir>      an INGenious project root (the folder holding ObjectRepository/)
  --scan <dir>         search below <dir> for INGenious projects and index every one
  --betroffen <was>    ONE question instead of the whole catalogue: this element changed —
                       which test cases break now? <was> is an element name, "Seite.Element",
                       "Projekt/Seite.Element", or the selector itself. Answers through
                       Bausteine too, which is where the breakage nobody sees comes from.
  --uniqueness <file>  a selector-uniqueness.mjs receipt; merges MEASURED live verdicts
                       (repeatable — one per probed page state)
  --json <file>        write the whole index and every finding
  --html <file>        write one self-contained page a human can open with no server
  --min-sequence <n>   shortest repeated step run reported as a Baustein candidate (default 3)
  --quiet              totals only

Exit: 0 nothing to report · 1 findings exist · 2 nothing could be indexed · 3 arguments
rejected · 5 --betroffen named something this index does not contain
`.trim();

// ---------------------------------------------------------------------------
// Arguments
// ---------------------------------------------------------------------------
function parseArgs(argv) {
  const out = { projects: [], scans: [], uniqueness: [], minSequence: 3 };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    const next = () => argv[++i];
    switch (a) {
      case '--project': out.projects.push(next()); break;
      case '--scan': out.scans.push(next()); break;
      case '--uniqueness': out.uniqueness.push(next()); break;
      case '--betroffen': case '--impact': out.betroffen = next(); break;
      case '--json': out.json = next(); break;
      case '--html': out.html = next(); break;
      case '--min-sequence': out.minSequence = Number(next()); break;
      case '--quiet': out.quiet = true; break;
      case '--selftest': out.selftest = true; break;
      case '-h': case '--help': out.help = true; break;
      default: out.unknown = a;
    }
  }
  return out;
}

// ---------------------------------------------------------------------------
// Object Repository — the same shape selector-uniqueness.mjs reads, deliberately.
// ---------------------------------------------------------------------------

// Engine attribute precedence: WebOR.OBJECT_PROPS. getElementsInternal() takes the FIRST
// non-empty attribute and breaks, so every later attribute is dead weight the engine
// never looks at. That is why `shadowed` is reported at all.
const OBJECT_PROPS = ['Role', 'Text', 'Label', 'Placeholder', 'xpath', 'css',
  'AltText', 'Title', 'TestId', 'ChainedLocator', 'JSPath'];

const YAML_TO_PROP = {
  role: 'Role', text: 'Text', label: 'Label', placeholder: 'Placeholder',
  xpath: 'xpath', css: 'css', alttext: 'AltText', title: 'Title', testid: 'TestId',
  chainedlocator: 'ChainedLocator', jspath: 'JSPath',
};

function unquote(raw) {
  const v = String(raw).trim();
  if (v.length >= 2 && ((v[0] === '"' && v.endsWith('"')) || (v[0] === "'" && v.endsWith("'")))) {
    const inner = v.slice(1, -1);
    return v[0] === '"'
      ? inner.replace(/\\(["\\nt])/g, (_, c) => (c === 'n' ? '\n' : c === 't' ? '\t' : c))
      : inner.replace(/''/g, "'");
  }
  return v;
}

function parseOrYaml(file) {
  const lines = readFileSync(file, 'utf8').split(/\r?\n/);
  let page = basename(file).replace(/\.ya?ml$/i, '');
  let scope = '';
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
      if (m[1] === 'page' && m[2].trim()) page = unquote(m[2]);
      if (m[1] === 'scope' && m[2].trim()) scope = unquote(m[2]);
      if (m[1] === 'elements') inElements = true;
      continue;
    }
    if (!inElements) continue;

    const m = /^\s*([^:]+?)\s*:\s*(.*)$/.exec(line);
    if (!m) continue;
    const key = m[1].trim();
    const val = m[2];

    if (indent === 2) {
      current = { name: unquote(key), attrs: {}, frame: '', line: i + 1 };
      elements.push(current);
    } else if (current && val.trim()) {
      const k = key.toLowerCase();
      if (k === 'frame') current.frame = unquote(val);
      else if (k === 'exact' || k === 'description') { /* not a locator */ }
      else if (YAML_TO_PROP[k]) current.attrs[YAML_TO_PROP[k]] = unquote(val);
    }
  }
  return { page, scope, elements };
}

/** Which attribute the engine actually uses, and which it silently ignores. */
function effectiveAttribute(attrs) {
  const present = OBJECT_PROPS.filter((p) => (attrs[p] || '').trim() !== '');
  return { prop: present[0] || null, shadowed: present.slice(1) };
}

// ---------------------------------------------------------------------------
// CSV steps — test cases and Bausteine share one row shape.
// ---------------------------------------------------------------------------

/** Split one CSV line honouring double quotes and "" escapes. */
function splitCsvLine(line) {
  const out = [];
  let cur = '';
  let quoted = false;
  for (let i = 0; i < line.length; i++) {
    const c = line[i];
    if (quoted) {
      if (c === '"') {
        if (line[i + 1] === '"') { cur += '"'; i++; } else quoted = false;
      } else cur += c;
    } else if (c === '"') quoted = true;
    else if (c === ',') { out.push(cur); cur = ''; }
    else cur += c;
  }
  out.push(cur);
  return out;
}

function readStepCsv(file) {
  const text = readFileSync(file, 'utf8').replace(/^\uFEFF/, '');
  const lines = text.split(/\r?\n/).filter((l) => l.trim() !== '');
  if (lines.length === 0) return [];
  const header = splitCsvLine(lines[0]).map((h) => h.trim().toLowerCase());
  const idx = (name) => header.indexOf(name);
  const iStep = idx('step'), iObj = idx('objectname'), iDesc = idx('description');
  const iAct = idx('action'), iIn = idx('input'), iCond = idx('condition'), iRef = idx('reference');
  const rows = [];
  for (let i = 1; i < lines.length; i++) {
    const c = splitCsvLine(lines[i]);
    const at = (j) => (j >= 0 && j < c.length ? c[j].trim() : '');
    rows.push({
      step: at(iStep), objectName: at(iObj), description: at(iDesc), action: at(iAct),
      input: at(iIn), condition: at(iCond), reference: at(iRef), line: i + 1,
    });
  }
  return rows;
}

/**
 * INGenious 3.1 rewrites ReusableComponents/ to YAML on first open
 * (ProjectMigrator.java). A store that only reads .csv goes blind the moment a project is
 * opened in 3.1, so both shapes are read here.
 */
function readStepYaml(file) {
  const lines = readFileSync(file, 'utf8').split(/\r?\n/);
  const rows = [];
  let cur = null;
  const flush = () => { if (cur) rows.push(cur); cur = null; };
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    if (!line.trim() || line.trim().startsWith('#')) continue;
    const item = /^\s*-\s*(.*)$/.exec(line);
    if (item) {
      flush();
      cur = { step: String(rows.length + 1), objectName: '', description: '', action: '',
        input: '', condition: '', reference: '', line: i + 1 };
      const rest = item[1];
      const kv = /^([A-Za-z_][\w]*)\s*:\s*(.*)$/.exec(rest);
      if (kv) assignYamlStep(cur, kv[1], kv[2]);
      continue;
    }
    const kv = /^\s+([A-Za-z_][\w]*)\s*:\s*(.*)$/.exec(line);
    if (kv && cur) assignYamlStep(cur, kv[1], kv[2]);
  }
  flush();
  return rows;
}

function assignYamlStep(row, key, value) {
  const k = key.toLowerCase();
  const v = unquote(value);
  if (k === 'objectname' || k === 'object') row.objectName = v;
  else if (k === 'action') row.action = v;
  else if (k === 'input') row.input = v;
  else if (k === 'condition') row.condition = v;
  else if (k === 'reference') row.reference = v;
  else if (k === 'description') row.description = v;
  else if (k === 'step') row.step = v;
}

const readSteps = (file) => (/\.ya?ml$/i.test(file) ? readStepYaml(file) : readStepCsv(file));

// ---------------------------------------------------------------------------
// Indexing
// ---------------------------------------------------------------------------

function walk(dir, keep, out = []) {
  if (!existsSync(dir)) return out;
  for (const name of readdirSync(dir)) {
    const full = join(dir, name);
    let st;
    try { st = statSync(full); } catch { continue; }
    if (st.isDirectory()) walk(full, keep, out);
    else if (keep(full, name)) out.push(full);
  }
  return out;
}

const isProject = (dir) =>
  existsSync(join(dir, 'ObjectRepository')) || existsSync(join(dir, 'TestPlan'));

function findProjects(root, out = [], depth = 0) {
  if (!existsSync(root) || depth > 6) return out;
  let st;
  try { st = statSync(root); } catch { return out; }
  if (!st.isDirectory()) return out;
  const name = basename(root);
  if (name === 'node_modules' || name === '.git' || name === 'target' || name === 'Results') return out;
  if (isProject(root)) { out.push(root); return out; }
  for (const child of readdirSync(root)) findProjects(join(root, child), out, depth + 1);
  return out;
}

/**
 * The Reference cell of a step, read the way the engine reads it.
 *
 * Mirrors {@code ResolvedWebObject.PageRef.parse}, and the detail that matters is what it
 * does NOT do: "[Shared] X" is the only token that means the shared repository. "[Project] X",
 * a bare "X", and even an unknown word in brackets ("[Foo] X") all resolve against the
 * project — the bracket is stripped and the scope falls back to PROJECT. There is no
 * project-then-shared fallback for objects. That fallback exists only for Baustein calls
 * (ReusableRef), and reading the two grammars as one is exactly how a catalogue comes to
 * report a shared object as unused.
 *
 * @returns {{scope: 'project'|'shared', page: string}|null} null when the cell is empty
 */
function parsePageRef(reference) {
  const s = String(reference || '').trim();
  if (!s) return null;
  const m = /^\[([^\]]*)\]\s*(.*)$/.exec(s);
  if (!m) return { scope: 'project', page: s };
  return { scope: m[1].trim().toLowerCase() === 'shared' ? 'shared' : 'project', page: m[2].trim() };
}

const GROUP_NAME_RE = /^([^:]+):(.+)$/;

/**
 * A step that calls a Baustein, or null.
 *
 * One-for-one with INGenious's own two-part rule, which reading the Action cell alone
 * cannot reproduce:
 *
 *   TestStep.isReusableStep()          ObjectName == "Execute" AND Action matches ".+:.+"
 *   ReusableRef.parse(action)          "[Project] Group:Name" | "[Shared] Group:Name"
 *                                      | "Group:Name" (legacy, unscoped)
 *   TestStep.getEffectiveReusableRef() an unscoped Action takes its scope from the
 *                                      step's REFERENCE cell instead
 *
 * Both halves of the old rule here were wrong in the same direction. It ignored the
 * ObjectName, so any ordinary step whose Action happened to contain a colon counted as a
 * call; and it split the Action on its first colon, so "[Project] Anmeldung:Starten" was
 * read as a group literally named "[Project] Anmeldung". The first invents calls that do
 * not exist, the second loses the ones that do — and a lost call is silent, because an
 * unresolved call simply looks like a step.
 *
 * @returns {{scope: 'PROJECT'|'SHARED'|'UNSCOPED', group: string, name: string}|null}
 */
function parseReusableCall(step) {
  if (String(step.objectName || '').trim() !== 'Execute') return null;
  const action = String(step.action || '').trim();
  if (!GROUP_NAME_RE.test(action)) return null;

  let scope = 'UNSCOPED';
  let rest = action;
  const scoped = /^\[\s*([^\]]*)\s*\]\s*(.*)$/.exec(action);
  if (scoped) {
    const word = scoped[1].trim().toLowerCase();
    if (word === 'project') { scope = 'PROJECT'; rest = scoped[2].trim(); }
    else if (word === 'shared') { scope = 'SHARED'; rest = scoped[2].trim(); }
    // else: an unknown scope word is left in the raw action, as the engine's own fallback
    // (getReusableData) does when ReusableRef.parse throws.
  }

  const m = GROUP_NAME_RE.exec(rest);
  if (!m) return null;
  const group = m[1].trim();
  const name = m[2].trim();
  if (!group || !name) return null;

  if (scope === 'UNSCOPED') {
    const ref = String(step.reference || '').trim();
    if (/^\[Project\]/i.test(ref)) scope = 'PROJECT';
    else if (/^\[Shared\]/i.test(ref)) scope = 'SHARED';
  }
  return { scope, group, name };
}

/** How the shared area names itself everywhere a project label is printed. */
const SHARED_LABEL = '[Shared]';

/**
 * The shared area belonging to a project, whether or not anything is in it.
 *
 * Projects live at {@code <appRoot>/Projects/<Name>} and the shared trees at
 * {@code <appRoot>/Shared/…}, so the app root is the project's grandparent. That is the same
 * path {@code ObjectRepository.getSharedORRepLocation()} and
 * {@code Project.getSharedReusableComponentsPath()} build from the running install's
 * {@code user.dir}, and the same convention {@code tools/curation-check.mjs} already uses.
 */
function sharedAreaOf(projectRoot) {
  const root = resolve(projectRoot, '..', '..', 'Shared');
  return {
    root,
    objectRepository: join(root, 'SharedObjectRepository'),
    reusableComponents: join(root, 'SharedReusableComponents'),
  };
}

/** Every element in one Object-Repository tree, tagged with the scope it was found in. */
function readObjectTree(dir, { project, projectRoot, scope }) {
  const objects = [];
  for (const f of walk(dir, (_p, n) => /\.ya?ml$/i.test(n))) {
    const parsed = parseOrYaml(f);
    for (const el of parsed.elements) {
      const { prop, shadowed } = effectiveAttribute(el.attrs);
      objects.push({
        project, projectRoot, scope, page: parsed.page, declaredScope: parsed.scope,
        element: el.name, attrs: el.attrs, attribute: prop,
        value: prop ? el.attrs[prop] : null, shadowed, frame: el.frame,
        file: f, line: el.line,
      });
    }
  }
  return objects;
}

/** Every Baustein in one ReusableComponents tree. */
function readComponentTree(dir, { project, projectRoot, scope }) {
  const components = [];
  for (const f of walk(dir, (_p, n) => /\.(csv|ya?ml)$/i.test(n))) {
    const rel = relative(dir, f).split(sep);
    const name = basename(f).replace(/\.(csv|ya?ml)$/i, '');
    const group = rel.length > 1 ? rel.slice(0, -1).join('/') : '';
    let steps = [];
    try { steps = readSteps(f); } catch { continue; }
    components.push({ project, projectRoot, scope, group, name, file: f,
      format: /\.ya?ml$/i.test(f) ? 'yaml' : 'csv', steps });
  }
  return components;
}

function indexProject(projectRoot) {
  const label = basename(projectRoot);
  const shared = sharedAreaOf(projectRoot);
  const tag = { project: label, projectRoot, scope: 'project' };

  const objects = readObjectTree(join(projectRoot, 'ObjectRepository'), tag);
  const components = readComponentTree(join(projectRoot, 'ReusableComponents'), tag);

  const testCases = [];
  const planDir = join(projectRoot, 'TestPlan');
  for (const f of walk(planDir, (_p, n) => /\.(csv|ya?ml)$/i.test(n))) {
    const rel = relative(planDir, f).split(sep);
    const name = basename(f).replace(/\.(csv|ya?ml)$/i, '');
    const scenario = rel.length > 1 ? rel.slice(0, -1).join('/') : '';
    let steps = [];
    try { steps = readSteps(f); } catch { continue; }
    testCases.push({ project: label, projectRoot, scope: 'project', sharedRoot: shared.root,
      scenario, name, file: f, steps });
  }
  for (const c of components) c.sharedRoot = shared.root;

  return { root: projectRoot, label, sharedRoot: shared.root, objects, testCases, components };
}

/**
 * The shared trees, indexed ONCE per app root.
 *
 * Indexing them per project would be the worse bug of the two this function fixes: every
 * shared object would appear once per project that can see it, and the catalogue would
 * then report each of them as "doppelt gepflegt" against itself.
 */
function indexShared(sharedRoot) {
  const area = { root: sharedRoot,
    objectRepository: join(sharedRoot, 'SharedObjectRepository'),
    reusableComponents: join(sharedRoot, 'SharedReusableComponents') };
  const tag = { project: SHARED_LABEL, projectRoot: sharedRoot, scope: 'shared' };
  const objects = readObjectTree(area.objectRepository, tag);
  const components = readComponentTree(area.reusableComponents, tag);
  for (const c of components) c.sharedRoot = sharedRoot;
  return { root: sharedRoot, label: SHARED_LABEL, objects, components };
}

// ---------------------------------------------------------------------------
// Selector normalisation — so `[name="x"]` and `[name='x']` are seen as one selector.
// ---------------------------------------------------------------------------
function normalizeSelector(prop, value) {
  if (value == null) return null;
  let s = String(value).trim();
  s = s.replace(/\s+/g, ' ');
  // Quote style inside attribute predicates is a writing habit, not a difference.
  s = s.replace(/(['"])((?:\\.|(?!\1)[^\\])*)\1/g, (_m, _q, inner) => `"${inner}"`);
  return `${prop}\u0000${s}`;
}

// ---------------------------------------------------------------------------
// Fragility — a STATIC reading. Never claims a selector is broken.
// ---------------------------------------------------------------------------
const FRAGILE_RULES = [
  { id: 'generated-id',
    // Wicket, JSF and friends number their component ids per render. Measured against a live
    // application on 2026-08-03: two consecutive loads of the SAME page produced different
    // id sets, and `#id10` from an older recording matched nothing at all.
    test: (s) => /(^|[\s>~+])#id\d+\b/.test(s) || /\[id=["']?id\d+["']?\]/.test(s)
      || /@id\s*=\s*["']id\d+["']/.test(s),
    why: 'framework-generierte ID (idNN) — ändert sich pro Render' },
  { id: 'positional',
    test: (s) => /nth-child\(|nth-of-type\(|\bnth=\d+|\[\d+\]\s*$|\[\d+\]\[/.test(s),
    why: 'positionsabhängig (Index/nth) — bricht, wenn eine Zeile dazukommt' },
  { id: 'absolute-xpath',
    test: (s) => /^\/html(\[\d+\])?\//i.test(s),
    why: 'absoluter XPath ab /html — bricht bei jeder Layout-Änderung' },
  { id: 'generated-class',
    test: (s) => /\.[A-Za-z][A-Za-z0-9]*[-_][A-Za-z0-9]{5,}\b/.test(s),
    why: 'generierter Klassen-Hash — ändert sich beim nächsten Build' },
  { id: 'deep-chain',
    test: (s) => (s.match(/\s*>\s*/g) || []).length >= 4,
    why: 'tiefe Struktur-Kette — an das DOM-Layout genagelt' },
  { id: 'text-match',
    test: (s) => /contains\(\s*text\(\)|has-text\(|^text=/.test(s),
    why: 'an sichtbaren Text gebunden — bricht bei Umbenennung oder Sprachwechsel' },
];

function fragility(value) {
  if (!value) return [];
  const s = String(value);
  return FRAGILE_RULES.filter((r) => r.test(s)).map((r) => ({ id: r.id, why: r.why }));
}

// ---------------------------------------------------------------------------
// Live verdicts from selector-uniqueness receipts — the MEASURED half.
// ---------------------------------------------------------------------------
function loadUniqueness(files) {
  const byKey = new Map();
  const sources = [];
  for (const f of files) {
    let doc;
    try { doc = JSON.parse(readFileSync(f, 'utf8')); } catch { continue; }
    sources.push({ file: f, url: doc.url || null, project: doc.project || null });
    for (const r of doc.results || []) {
      const key = `${r.page}\u0000${r.element}`;
      const prev = byKey.get(key);
      // A decided verdict beats a "not tested on this page state" one: the object simply
      // was not on that screen, which is not information about the selector.
      const decided = r.verdict === 'AMBIGUOUS' || r.verdict === 'UNIQUE'
        || r.verdict === 'AMBIGUOUS_FRAME';
      if (!prev || (decided && !prev.decided)) {
        byKey.set(key, { verdict: r.verdict, count: r.count, decided,
          identity: r.identity || null,
          silentAtReplay: Boolean(r.silentAtReplay), url: doc.url || null });
      }
    }
  }
  return { byKey, sources };
}

// ---------------------------------------------------------------------------
// Analysis
// ---------------------------------------------------------------------------

function analyse(index, opts) {
  const shared = index.shared || [];
  const objects = [...index.projects.flatMap((p) => p.objects),
    ...shared.flatMap((s) => s.objects)];
  const testCases = index.projects.flatMap((p) => p.testCases);
  const components = [...index.projects.flatMap((p) => p.components),
    ...shared.flatMap((s) => s.components)];

  // --- 1. the same selector string under two or more names ---------------------
  const bySelector = new Map();
  for (const o of objects) {
    if (!o.attribute) continue;
    const key = normalizeSelector(o.attribute, o.value);
    if (!key) continue;
    if (!bySelector.has(key)) bySelector.set(key, []);
    bySelector.get(key).push(o);
  }
  const duplicates = [];
  for (const [key, group] of bySelector) {
    if (group.length < 2) continue;
    const [attribute, value] = key.split('\u0000');
    const names = new Set(group.map((o) => o.element));
    const projects = new Set(group.map((o) => o.project));
    duplicates.push({
      attribute, value, occurrences: group.length,
      distinctNames: names.size, crossProject: projects.size > 1,
      where: group.map((o) => ({ project: o.project, page: o.page, element: o.element,
        file: o.file, line: o.line })),
    });
  }
  duplicates.sort((a, b) => b.occurrences - a.occurrences || b.distinctNames - a.distinctNames);

  // --- 2. usage: which test cases / Bausteine reference each object -------------
  //
  // A step names an object by ObjectName and its page by the Reference column, and that
  // Reference also carries the SCOPE. Resolution therefore has to know which repository a
  // holder may look into, and the answer is not simply "its own": a test case may name a
  // shared object, and a shared Baustein names a project object of whichever project runs it.
  const SEP = String.fromCharCode(0);
  const objectKey = (repo, page, element) => [repo, page, element].join(SEP);
  const byName = new Map();
  const byElementOnly = new Map();
  // A project object is keyed by its project root and a shared object by its shared root, so
  // two objects carrying the same page and element name in the two repositories stay apart.
  for (const o of objects) {
    const k = objectKey(o.projectRoot, o.page, o.element);
    if (!byName.has(k)) byName.set(k, []);
    byName.get(k).push(o);
    const e = [o.projectRoot, o.element].join(SEP);
    if (!byElementOnly.has(e)) byElementOnly.set(e, []);
    byElementOnly.get(e).push(o);
  }

  // Which project roots a SHARED holder may resolve a "[Project]" reference against: every
  // project sharing its app root. A shared Baustein genuinely means a different object in
  // each of them, and naming all of them is the honest reading — renaming that object in any
  // one of those projects does break the Baustein there.
  const projectRootsBySharedRoot = new Map();
  for (const p of index.projects) {
    if (!projectRootsBySharedRoot.has(p.sharedRoot)) projectRootsBySharedRoot.set(p.sharedRoot, []);
    projectRootsBySharedRoot.get(p.sharedRoot).push(p.root);
  }

  /** The repositories a holder may look into for a reference of the given scope. */
  function searchRoots(holder, scope) {
    if (scope === 'shared') return holder.sharedRoot ? [holder.sharedRoot] : [];
    if (holder.scope === 'shared') return projectRootsBySharedRoot.get(holder.projectRoot) || [];
    return [holder.projectRoot];
  }

  const usage = new Map(); // object identity -> [{kind, key, label, file, line, weak}]
  const identity = (o) => [o.project, o.page, o.element].join(SEP);
  for (const o of objects) usage.set(identity(o), []);

  function noteUse(step, holder) {
    const name = String(step.objectName || '').trim();
    if (!name || name === 'Browser' || name === 'Execute') return;
    const ref = parsePageRef(step.reference);
    const roots = searchRoots(holder, ref ? ref.scope : 'project');
    const matches = [];
    let weak = false;
    if (ref && ref.page) {
      for (const r of roots) matches.push(...(byName.get(objectKey(r, ref.page, name)) || []));
    }
    // No Reference cell, or one naming a page this repository does not have: fall back to the
    // name alone and mark the match weak, so nobody reads a guess as a fact.
    if (matches.length === 0) {
      weak = true;
      for (const r of roots) matches.push(...(byElementOnly.get([r, name].join(SEP)) || []));
    }
    for (const o of matches) {
      usage.get(identity(o))?.push({ kind: holder.kind, key: holder.key, label: holder.label,
        file: holder.file, line: step.line, weak });
    }
  }

  // --- 2b. the call graph: which Bausteine a test case actually runs -------------
  //
  // Without this the catalogue answers "which test cases NAME this object", which is not the
  // question anybody asks. A test case that calls "Anmeldung:Starten" breaks when an object
  // inside that Baustein changes, and nothing in the test case's own file says so.
  const componentKey = (repo, group, name) => [repo, group, name].join(SEP);
  const componentsByKey = new Map();
  for (const c of components) {
    componentsByKey.set(componentKey(c.projectRoot, c.group, c.name), c);
  }

  /** Resolve one Execute step to the Baustein it runs, following the engine's own fallback. */
  function resolveCall(holder, call) {
    const projectRoots = holder.scope === 'shared'
      ? (projectRootsBySharedRoot.get(holder.projectRoot) || [])
      : [holder.projectRoot];
    const sharedRoots = holder.sharedRoot ? [holder.sharedRoot] : [];
    const roots = call.scope === 'PROJECT' ? projectRoots
      : call.scope === 'SHARED' ? sharedRoots
        : [...projectRoots, ...sharedRoots]; // UNSCOPED: project first, then shared
    const hits = [];
    for (const r of roots) {
      const c = componentsByKey.get(componentKey(r, call.group, call.name));
      if (c) hits.push(c);
    }
    // UNSCOPED takes the FIRST hit and stops, because that is what the engine does; it is a
    // fallback order, not a set of candidates.
    return call.scope === 'UNSCOPED' ? hits.slice(0, 1) : hits;
  }

  const holders = [
    ...testCases.map((tc) => ({ ...tc, kind: 'testcase',
      label: `${tc.scenario ? tc.scenario + ':' : ''}${tc.name}`,
      key: ['TC', tc.projectRoot, tc.scenario, tc.name].join(SEP) })),
    ...components.map((c) => ({ ...c, kind: 'component',
      label: `${c.group ? c.group + ':' : ''}${c.name}`,
      key: componentKey(c.projectRoot, c.group, c.name) })),
  ];

  const calls = new Map();     // holder key -> Set(component key)
  const brokenCalls = [];      // an Execute step naming a Baustein nobody can find
  for (const h of holders) {
    const reached = new Set();
    calls.set(h.key, reached);
    for (const step of h.steps) {
      const call = parseReusableCall(step);
      if (!call) continue;
      const hits = resolveCall(h, call);
      if (hits.length === 0) {
        // Not a cosmetic gap: the engine emits an unresolvable Execute as an ordinary step,
        // so a test case calling a Baustein that is not there fails silently at runtime.
        brokenCalls.push({ from: h.label, kind: h.kind, project: h.project, scope: call.scope,
          group: call.group, name: call.name, file: h.file, line: step.line });
        continue;
      }
      for (const c of hits) reached.add(componentKey(c.projectRoot, c.group, c.name));
    }
    for (const step of h.steps) noteUse(step, h);
  }

  /** Every Baustein a holder reaches, at any depth. A cycle stops at the repeat. */
  function reachable(startKey) {
    const seen = new Set();
    const stack = [...(calls.get(startKey) || [])];
    while (stack.length) {
      const k = stack.pop();
      if (seen.has(k)) continue;
      seen.add(k);
      for (const next of calls.get(k) || []) stack.push(next);
    }
    return seen;
  }
  // Reverse the graph once: component key -> the test cases that reach it, at any depth.
  const testCasesReaching = new Map();
  for (const h of holders) {
    if (h.kind !== 'testcase') continue;
    for (const compKey of reachable(h.key)) {
      if (!testCasesReaching.has(compKey)) testCasesReaching.set(compKey, new Set());
      testCasesReaching.get(compKey).add(h.label);
    }
  }

  // --- 3. blast radius: changing this object breaks these test cases -------------
  //
  // Two lists, deliberately kept apart. `testCases` is what names the object out loud;
  // `viaComponents` is what breaks anyway, because it runs a Baustein that names it. The
  // second cannot be seen by reading a test case, and it is usually the larger of the two.
  const blastRadius = objects.map((o) => {
    const uses = usage.get(identity(o)) || [];
    const cases = new Set(uses.filter((u) => u.kind === 'testcase').map((u) => u.label));
    const comps = new Set(uses.filter((u) => u.kind === 'component').map((u) => u.label));
    const indirect = new Map(); // test case label -> the Bausteine that carry it there
    for (const u of uses) {
      if (u.kind !== 'component') continue;
      for (const tcLabel of testCasesReaching.get(u.key) || []) {
        if (cases.has(tcLabel)) continue; // already breaks directly; never counted twice
        if (!indirect.has(tcLabel)) indirect.set(tcLabel, new Set());
        indirect.get(tcLabel).add(u.label);
      }
    }
    const viaComponents = [...indirect.entries()]
      .map(([testCase, via]) => ({ testCase, via: [...via] }));
    return {
      project: o.project, scope: o.scope, page: o.page, element: o.element,
      attribute: o.attribute, value: o.value, file: o.file, line: o.line,
      testCases: [...cases], components: [...comps], viaComponents,
      breaks: cases.size + viaComponents.length,
      uses: uses.length, weakOnly: uses.length > 0 && uses.every((u) => u.weak),
    };
  });
  const unused = blastRadius.filter((b) => b.uses === 0);
  const wideBlast = blastRadius.filter((b) => b.breaks + b.components.length >= 2)
    .sort((a, b) => (b.breaks + b.components.length) - (a.breaks + a.components.length));

  // --- 4. fragile selectors, and the measured verdict where one exists ----------
  const { byKey: liveByKey, sources: liveSources } = loadUniqueness(opts.uniqueness || []);
  const fragile = [];
  const measured = [];
  for (const o of objects) {
    const reasons = fragility(o.value);
    const live = liveByKey.get(`${o.page}\u0000${o.element}`) || null;
    const blast = blastRadius.find((b) => b.project === o.project && b.page === o.page
      && b.element === o.element);
    // How many test cases BREAK, not how many files mention it: a Baustein is not a thing
    // that can fail a release, and counting it beside test cases hid the number that can.
    const impact = blast ? blast.breaks : 0;
    if (reasons.length) {
      fragile.push({ project: o.project, page: o.page, element: o.element,
        attribute: o.attribute, value: o.value, file: o.file, line: o.line,
        reasons, impact, live });
    }
    if (live && live.decided) {
      measured.push({ project: o.project, page: o.page, element: o.element,
        attribute: o.attribute, value: o.value, verdict: live.verdict, count: live.count,
        identity: live.identity || null,
        silentAtReplay: live.silentAtReplay, url: live.url, impact });
    }
  }

  // --- the duplicate nothing static can find --------------------------------
  //
  // Two entries whose selector STRINGS differ but which landed on the SAME element. Each
  // is unique on its own, so the uniqueness probe is happy and the duplicate scan above
  // sees two unrelated selectors. Only a measured fingerprint relates them.
  const byIdentity = new Map();
  for (const m of measured) {
    if (!m.identity) continue;
    if (!byIdentity.has(m.identity)) byIdentity.set(m.identity, []);
    byIdentity.get(m.identity).push(m);
  }
  const aliasGroups = [...byIdentity.entries()]
    .filter(([, g]) => g.length > 1)
    .map(([identity, g]) => ({
      identity,
      members: g.map((m) => ({ project: m.project, page: m.page, element: m.element,
        attribute: m.attribute, value: m.value, impact: m.impact })),
      // Two names for one element are merely redundant when they agree about what it is.
      // Names that disagree are worse: at least one test case is asserting something its
      // selector does not actually prove.
      namesDisagree: new Set(g.map((m) => m.element.toLowerCase())).size > 1,
      url: g[0].url,
    }));
  aliasGroups.sort((a, b) => b.members.length - a.members.length);
  fragile.sort((a, b) => b.impact - a.impact || b.reasons.length - a.reasons.length);
  const ambiguousMeasured = measured.filter((m) => m.verdict !== 'UNIQUE');

  // --- 5. attributes the engine will never look at -----------------------------
  const shadowed = objects.filter((o) => (o.shadowed || []).length > 0).map((o) => ({
    project: o.project, page: o.page, element: o.element,
    uses: o.attribute, ignores: o.shadowed,
    ignoredValues: o.shadowed.map((p) => ({ prop: p, value: o.attrs[p] })),
    file: o.file, line: o.line,
  }));

  // --- 6. repeated step runs that should have been a Baustein ------------------
  //
  // A step is fingerprinted by what it DOES (object + action + reference), never by its
  // description or its input — two test cases that open two different customers through
  // the same six steps are exactly the case worth finding.
  const stepPrint = (s) => {
    const call = parseReusableCall(s);
    if (call) return `CALL\u0000${call.group}:${call.name}`;
    return [String(s.objectName || '').trim(), String(s.action || '').trim(),
      String(s.reference || '').trim()].join('\u0000');
  };
  const caseHolders = testCases.map((tc) => ({ kind: 'testcase', project: tc.project,
    label: `${tc.scenario ? tc.scenario + ':' : ''}${tc.name}`, file: tc.file, steps: tc.steps }));
  const seqs = new Map();
  const minLen = Math.max(2, opts.minSequence || 3);
  for (const h of caseHolders) {
    const prints = h.steps.map(stepPrint);
    for (let len = minLen; len <= prints.length; len++) {
      for (let i = 0; i + len <= prints.length; i++) {
        const window = prints.slice(i, i + len);
        // A run that is only a Baustein call is not a candidate — it already is one.
        if (window.every((p) => p.startsWith('CALL\u0000'))) continue;
        const key = window.join('\u0001');
        if (!seqs.has(key)) seqs.set(key, { len, occurrences: [] });
        seqs.get(key).occurrences.push({ project: h.project, label: h.label, file: h.file, at: i + 1,
          steps: h.steps.slice(i, i + len).map((s) => ({ objectName: s.objectName,
            action: s.action, description: s.description })) });
      }
    }
  }
  let bausteinCandidates = [];
  for (const [, v] of seqs) {
    const distinct = new Set(v.occurrences.map((o) => `${o.project}\u0000${o.label}`));
    if (distinct.size < 2) continue;
    bausteinCandidates.push({ length: v.len, inTestCases: distinct.size,
      occurrences: v.occurrences, steps: v.occurrences[0].steps });
  }
  // Keep only maximal runs: a repeated 6-step run also repeats as 5, 4 and 3, and
  // reporting all of them would bury the finding under its own substrings.
  bausteinCandidates.sort((a, b) => b.length - a.length || b.inTestCases - a.inTestCases);
  const kept = [];
  for (const cand of bausteinCandidates) {
    const covered = kept.some((k) =>
      k.inTestCases >= cand.inTestCases
      && cand.occurrences.every((o) => k.occurrences.some((ko) =>
        ko.label === o.label && ko.at <= o.at && ko.at + k.length >= o.at + cand.length)));
    if (!covered) kept.push(cand);
  }
  bausteinCandidates = kept;

  return {
    duplicates, blastRadius, wideBlast, unused, fragile, measured, ambiguousMeasured,
    aliasGroups, shadowed, bausteinCandidates, liveSources, brokenCalls,
    totals: {
      projects: index.projects.length,
      sharedAreas: shared.filter((s) => s.objects.length + s.components.length > 0).length,
      objects: objects.length,
      sharedObjects: objects.filter((o) => o.scope === 'shared').length,
      testCases: testCases.length,
      components: components.length,
      sharedComponents: components.filter((c) => c.scope === 'shared').length,
      steps: testCases.reduce((n, tc) => n + tc.steps.length, 0),
      brokenCalls: brokenCalls.length,
      duplicateSelectors: duplicates.length,
      duplicatedObjects: duplicates.reduce((n, d) => n + d.occurrences, 0),
      crossProjectDuplicates: duplicates.filter((d) => d.crossProject).length,
      fragile: fragile.length,
      shadowed: shadowed.length,
      unused: unused.length,
      bausteinCandidates: bausteinCandidates.length,
      measuredObjects: measured.length,
      measuredAmbiguous: ambiguousMeasured.length,
      aliasGroups: aliasGroups.length,
      aliasGroupsDisagreeing: aliasGroups.filter((g) => g.namesDisagree).length,
    },
  };
}

// ---------------------------------------------------------------------------
// The one question — "this element changed, what breaks?"
// ---------------------------------------------------------------------------

/**
 * Find the object(s) a human meant, without guessing between them.
 *
 * Tried from most precise to least, and the FIRST tier that matches anything wins. A tester
 * looking at a rename in the application knows the element name; a developer looking at a
 * DOM change knows the selector; neither of them knows the page qualifier by heart. Falling
 * through the tiers is what lets both of them ask the same question.
 */
function findObjects(blastRadius, query) {
  const q = String(query || '').trim();
  if (!q) return { tier: null, matches: [] };
  const lower = q.toLowerCase();
  const full = (b) => `${b.project}/${b.page}.${b.element}`.toLowerCase();
  const paged = (b) => `${b.page}.${b.element}`.toLowerCase();
  const tiers = [
    ['Projekt/Seite.Element', (b) => full(b) === lower],
    ['Seite.Element', (b) => paged(b) === lower],
    ['Elementname', (b) => b.element.toLowerCase() === lower],
    ['Selektor (genau)', (b) => String(b.value || '').trim() === q],
    ['Selektor (enthalten)', (b) => String(b.value || '').toLowerCase().includes(lower)],
    ['Elementname (enthalten)', (b) => b.element.toLowerCase().includes(lower)],
  ];
  for (const [tier, test] of tiers) {
    const matches = blastRadius.filter(test);
    if (matches.length) return { tier, matches };
  }
  return { tier: null, matches: [] };
}

/**
 * Print which test cases break, and return the exit code.
 *
 * The two lists are never merged. A test case that names the object breaks visibly, and
 * somebody reading it would have seen the name. A test case that breaks through a Baustein
 * is the expensive half: nothing in its own file mentions the object at all, so the only way
 * to know is to have followed the call — which is what this tool now does.
 */
function answerImpact(a, index, query) {
  const line = '─'.repeat(74);
  const { tier, matches } = findObjects(a.blastRadius, query);
  console.log(line);
  console.log(`Was bricht, wenn sich "${query}" ändert?`);
  console.log(line);

  if (matches.length === 0) {
    console.error(`  KEINE ANTWORT — in diesem Index gibt es weder ein Objekt noch einen `
      + `Selektor, auf den "${query}" passt.`);
    console.error(`  Indexiert wurden ${a.totals.objects} Objekt(e) aus `
      + `${index.projects.length} Projekt(e) und ${a.totals.sharedAreas} gemeinsame(n) Bereich(e).`);
    console.error('  Das ist KEIN "nichts bricht" — es wurde nichts geprüft.');
    console.log(line);
    return EXIT.NOT_RESOLVED;
  }

  console.log(`  ${matches.length} Objekt(e) getroffen über: ${tier}`);
  const breaking = new Map(); // test case label -> how it breaks
  for (const b of matches) {
    console.log(`\n  ${b.project}/${b.page}.${b.element}`
      + (b.scope === 'shared' ? '   (gemeinsamer Objektkatalog)' : ''));
    console.log(`      ${b.attribute} = ${JSON.stringify(b.value)}`);
    console.log(`      ${b.file}:${b.line}`);
    if (b.weakOnly) {
      console.log('      ⚠ Die Zuordnung stützt sich nur auf den Namen — die Schritte, die '
        + 'dieses Objekt benutzen, nennen keine Seite. Die Liste unten kann zu groß sein.');
    }
    if (b.testCases.length) {
      console.log(`      bricht DIREKT (${b.testCases.length}):`);
      for (const tc of b.testCases.sort()) {
        console.log(`          ${tc}`);
        if (!breaking.has(tc)) breaking.set(tc, 'direkt');
      }
    }
    if (b.viaComponents.length) {
      console.log(`      bricht ÜBER EINEN BAUSTEIN (${b.viaComponents.length}) — im Testfall `
        + 'selbst steht davon nichts:');
      for (const v of b.viaComponents.sort((x, y) => x.testCase.localeCompare(y.testCase))) {
        console.log(`          ${v.testCase}   ←  ${v.via.join(', ')}`);
        if (!breaking.has(v.testCase)) breaking.set(v.testCase, 'baustein');
      }
    }
    // A Baustein that no test case reaches cannot break a release, but it is also not
    // nothing: it is a Baustein waiting to be called. Named, and kept out of the count.
    const unreached = b.components.filter((c) => !b.viaComponents.some((v) => v.via.includes(c)));
    if (unreached.length) {
      console.log(`      benutzt von Baustein(en), die zurzeit kein Testfall aufruft: `
        + unreached.join(', '));
    }
    if (b.breaks === 0 && b.components.length === 0) {
      console.log('      bricht nichts — dieses Objekt wird von keinem Schritt benutzt.');
    }
  }

  console.log(`\n${line}`);
  const viaCount = [...breaking.values()].filter((v) => v === 'baustein').length;
  console.log(`  ${breaking.size} Testfall/-fälle brechen`
    + (viaCount ? `, davon ${viaCount} nur über einen Baustein` : ''));
  if (a.totals.measuredObjects === 0) {
    console.log('  (statisch gelesen — ob der neue Selektor eindeutig trifft, sagt erst '
      + 'tools/selector-uniqueness.mjs an der echten Seite)');
  }
  console.log(line);
  return EXIT.OK;
}

// ---------------------------------------------------------------------------
// Reporting — console
// ---------------------------------------------------------------------------
function report(a, quiet) {
  const line = '─'.repeat(74);
  const t = a.totals;
  console.log(line);
  console.log('object-store — was die Automatisierung schon weiß');
  console.log(line);
  console.log(`  ${t.projects} Projekt(e) · ${t.objects} Objekt(e) · ${t.testCases} Testfall/`
    + `-fälle mit ${t.steps} Schritten · ${t.components} Baustein(e)`);
  // Said out loud whether it is zero or not: "no shared objects" and "the shared repository
  // was never looked at" read identically in a total, and only one of them is a clean result.
  console.log(`  davon aus dem gemeinsamen Bereich (${t.sharedAreas} gefunden): `
    + `${t.sharedObjects} Objekt(e), ${t.sharedComponents} Baustein(e)`);

  if (!quiet) {
    if (a.duplicates.length) {
      console.log(`\n  DOPPELT GEPFLEGT — derselbe Selektor unter mehreren Namen (${a.duplicates.length}):`);
      for (const d of a.duplicates.slice(0, 12)) {
        console.log(`      ${d.attribute} = ${JSON.stringify(d.value).slice(0, 84)}`);
        console.log(`        ${d.occurrences}× ${d.crossProject ? '(projektübergreifend) ' : ''}`
          + d.where.map((w) => `${w.project}/${w.page}.${w.element}`).join('  ·  '));
      }
      if (a.duplicates.length > 12) console.log(`      … und ${a.duplicates.length - 12} weitere`);
    }

    if (a.ambiguousMeasured.length) {
      console.log(`\n  GEMESSEN MEHRDEUTIG — aus einem selector-uniqueness-Beleg (${a.ambiguousMeasured.length}):`);
      for (const m of a.ambiguousMeasured) {
        console.log(`      ${m.project}/${m.page}.${m.element} trifft ${m.count} Element(e)`
          + (m.silentAtReplay ? '  ← fällt beim Abspielen NICHT auf' : '')
          + (m.impact ? `  · betrifft ${m.impact} Testfall/Baustein` : ''));
      }
    }

    if (a.aliasGroups.length) {
      console.log(`\n  DASSELBE ELEMENT UNTER MEHREREN NAMEN — gemessen (${a.aliasGroups.length}):`);
      for (const g of a.aliasGroups) {
        console.log('      ' + g.members.map((m) => `${m.project}/${m.page}.${m.element}`).join('  ==  ')
          + (g.namesDisagree ? '   ← die Namen sagen Verschiedenes über dasselbe Element' : ''));
        for (const m of g.members) {
          console.log(`        ${m.attribute} = ${JSON.stringify(m.value).slice(0, 78)}`
            + (m.impact ? `  · ${m.impact} Testfall/Baustein` : ''));
        }
      }
    }

    if (a.fragile.length) {
      console.log(`\n  FRAGIL (statisch gelesen, keine Messung) (${a.fragile.length}):`);
      for (const f of a.fragile.slice(0, 12)) {
        console.log(`      ${f.project}/${f.page}.${f.element}  ${f.attribute}=`
          + `${JSON.stringify(f.value).slice(0, 70)}`);
        console.log(`        ${f.reasons.map((r) => r.why).join(' · ')}`
          + (f.impact ? `  · Umbenennen trifft ${f.impact} Testfall/Baustein` : '')
          + (f.live ? `  · live: ${f.live.verdict}${f.live.count != null ? ` (${f.live.count})` : ''}` : ''));
      }
      if (a.fragile.length > 12) console.log(`      … und ${a.fragile.length - 12} weitere`);
    }

    if (a.shadowed.length) {
      console.log(`\n  IGNORIERTE ATTRIBUTE — die Engine nimmt nur das erste (${a.shadowed.length}):`);
      for (const s of a.shadowed) {
        console.log(`      ${s.project}/${s.page}.${s.element}: nutzt ${s.uses}, ignoriert `
          + s.ignoredValues.map((v) => `${v.prop}=${JSON.stringify(v.value).slice(0, 40)}`).join(', '));
      }
    }

    if (a.brokenCalls.length) {
      console.log(`\n  BAUSTEIN-AUFRUF INS LEERE — der Baustein ist nicht auffindbar (${a.brokenCalls.length}):`);
      // The engine emits an unresolvable Execute as an ordinary step, so nothing fails and
      // nothing is logged: the test case simply skips what it thought it was running.
      for (const c of a.brokenCalls.slice(0, 10)) {
        console.log(`      ${c.project}/${c.from} ruft `
          + `${c.scope === 'SHARED' ? '[Shared] ' : c.scope === 'PROJECT' ? '[Project] ' : ''}`
          + `${c.group}:${c.name} — den gibt es nicht (${c.file}:${c.line})`);
      }
    }

    if (a.wideBlast.length) {
      console.log(`\n  BREITE WIRKUNG — eine Änderung hier bricht mehrere Testfälle (${a.wideBlast.length}):`);
      for (const b of a.wideBlast.slice(0, 10)) {
        const via = b.viaComponents.map((v) => `${v.testCase} (über ${v.via.join(', ')})`);
        console.log(`      ${b.project}/${b.page}.${b.element} → `
          + [...b.testCases, ...via].join(', ')
          + (b.testCases.length + via.length === 0 && b.components.length
            ? `nur Baustein(e): ${b.components.join(', ')}` : ''));
      }
    }

    if (a.bausteinCandidates.length) {
      console.log(`\n  BAUSTEIN-KANDIDATEN — geteilte Schrittfolgen (${a.bausteinCandidates.length}):`);
      for (const c of a.bausteinCandidates.slice(0, 8)) {
        console.log(`      ${c.length} Schritte in ${c.inTestCases} Testfällen: `
          + c.occurrences.map((o) => o.label).join('  ·  '));
        for (const s of c.steps) console.log(`          ${s.objectName} — ${s.action}`);
      }
    }

    if (a.unused.length) {
      console.log(`\n  VON KEINEM SCHRITT BENUTZT (${a.unused.length}):`);
      console.log('      ' + a.unused.map((u) => `${u.project}/${u.page}.${u.element}`).join(', '));
    }
  }

  console.log(`\n${line}`);
  // Five of the six numbers on the balance line are measurements OVER THE OBJECT SET. With an
  // empty object set they are not measurements at all, they are the initial values of five
  // counters — and "0 fragil · 0 doppelt gepflegt · 0 unbenutzt" is then a report about a
  // catalogue that was never found. It happens for real: INGenious's own Projects/CLIDemo
  // keeps its objects in the old binary IOR.object, which no YAML reader can see.
  if (t.objects === 0) {
    console.log('  NICHT GEPRÜFT — kein einziges Objekt wurde indexiert. Doppelt gepflegte '
      + 'Selektoren, Fragilität, ignorierte Attribute, unbenutzte Objekte und Mehrdeutigkeit');
    console.log('  sind Aussagen ÜBER Objekte; ohne Objekte gibt es dazu keine Zahl, nur eine '
      + 'Null, die wie eine aussieht. Das ist KEIN "nichts bricht" — es wurde nichts geprüft.');
    console.log(`  Geprüft werden konnte nur, was an Testfällen hängt: `
      + `${t.bausteinCandidates} Baustein-Kandidat(en) · ${t.brokenCalls} Aufruf(e) ins Leere `
      + `(aus ${t.testCases} Testfall/-fällen mit ${t.steps} Schritten).`);
    console.log('  Liegen die Objekte im alten Binärformat IOR.object, muss das Projekt einmal '
      + 'in Studio geöffnet und gespeichert werden, bevor hier etwas zu messen ist.');
    console.log(line);
    return;
  }
  console.log(`  ${t.duplicateSelectors} doppelt gepflegte Selektor(en) (${t.duplicatedObjects} Objekte, `
    + `${t.crossProjectDuplicates} projektübergreifend) · ${t.fragile} fragil · `
    + `${t.shadowed} mit ignorierten Attributen · ${t.unused} unbenutzt · `
    + `${t.bausteinCandidates} Baustein-Kandidat(en) · ${t.brokenCalls} Aufruf(e) ins Leere`);
  if (t.measuredObjects) {
    console.log(`  gemessen: ${t.measuredObjects} Objekt(e) live entschieden, davon `
      + `${t.measuredAmbiguous} mehrdeutig`);
  } else {
    console.log('  keine Messung eingelesen — Mehrdeutigkeit ist damit NICHT geprüft '
      + '(--uniqueness <beleg.json>)');
  }
  console.log(line);
}

// ---------------------------------------------------------------------------
// Reporting — one self-contained page, no server, no network
// ---------------------------------------------------------------------------
const esc = (s) => String(s ?? '').replace(/[&<>"']/g,
  (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

function html(a, index) {
  const t = a.totals;
  const card = (n, l, tone = '') =>
    `<div class="card ${tone}"><div class="n">${n}</div><div class="l">${esc(l)}</div></div>`;

  const dupRows = a.duplicates.map((d) => `<tr>
    <td><code>${esc(d.attribute)}</code></td>
    <td><code class="sel">${esc(d.value)}</code></td>
    <td class="num">${d.occurrences}</td>
    <td>${d.crossProject ? '<span class="tag warn">projektübergreifend</span>' : ''}</td>
    <td>${d.where.map((w) => `<span class="tag">${esc(w.project)}/${esc(w.page)}.<b>${esc(w.element)}</b></span>`).join(' ')}</td>
  </tr>`).join('');

  const fragRows = a.fragile.map((f) => `<tr>
    <td>${esc(f.project)}/${esc(f.page)}.<b>${esc(f.element)}</b></td>
    <td><code>${esc(f.attribute)}</code></td>
    <td><code class="sel">${esc(f.value)}</code></td>
    <td>${f.reasons.map((r) => `<span class="tag warn">${esc(r.why)}</span>`).join(' ')}</td>
    <td class="num">${f.impact || ''}</td>
    <td>${f.live ? `<span class="tag ${f.live.verdict === 'UNIQUE' ? 'ok' : 'bad'}">${esc(f.live.verdict)}${f.live.count != null ? ` (${f.live.count})` : ''}</span>` : '<span class="muted">nicht gemessen</span>'}</td>
  </tr>`).join('');

  const measRows = a.measured.map((m) => `<tr>
    <td>${esc(m.project)}/${esc(m.page)}.<b>${esc(m.element)}</b></td>
    <td><code class="sel">${esc(m.value)}</code></td>
    <td><span class="tag ${m.verdict === 'UNIQUE' ? 'ok' : 'bad'}">${esc(m.verdict)}</span></td>
    <td class="num">${m.count ?? ''}</td>
    <td>${m.silentAtReplay ? '<span class="tag bad">fällt beim Abspielen nicht auf</span>' : ''}</td>
    <td class="muted">${esc(m.url || '')}</td>
  </tr>`).join('');

  const blastRows = a.blastRadius.filter((b) => b.uses > 0)
    .sort((x, y) => y.breaks - x.breaks || y.components.length - x.components.length)
    .map((b) => `<tr>
    <td>${esc(b.project)}/${esc(b.page)}.<b>${esc(b.element)}</b>${b.scope === 'shared' ? ' <span class="tag warn">gemeinsam</span>' : ''}</td>
    <td><code class="sel">${esc(b.value)}</code></td>
    <td class="num">${b.breaks}</td>
    <td>${b.testCases.map((x) => `<span class="tag bad">${esc(x)}</span>`).join(' ')}
        ${b.viaComponents.map((v) => `<span class="tag bad">${esc(v.testCase)}</span><span class="muted">&nbsp;über ${esc(v.via.join(', '))}</span>`).join(' ')}
        ${b.breaks === 0 ? b.components.map((x) => `<span class="tag">${esc(x)}</span>`).join(' ') : ''}</td>
  </tr>`).join('');

  const brokenRows = a.brokenCalls.map((c) => `<tr>
    <td>${esc(c.project)}/<b>${esc(c.from)}</b></td>
    <td><code>${esc((c.scope === 'SHARED' ? '[Shared] ' : c.scope === 'PROJECT' ? '[Project] ' : '') + c.group + ':' + c.name)}</code></td>
    <td class="muted">${esc(c.file)}:${c.line}</td>
  </tr>`).join('');

  const shadowRows = a.shadowed.map((s) => `<tr>
    <td>${esc(s.project)}/${esc(s.page)}.<b>${esc(s.element)}</b></td>
    <td><code>${esc(s.uses)}</code></td>
    <td>${s.ignoredValues.map((v) => `<span class="tag warn"><code>${esc(v.prop)}</code>=${esc(String(v.value).slice(0, 60))}</span>`).join(' ')}</td>
  </tr>`).join('');

  const bausteinBlocks = a.bausteinCandidates.map((c) => `<div class="bs">
    <div class="bsh"><b>${c.length} Schritte</b> in ${c.inTestCases} Testfällen —
      ${c.occurrences.map((o) => `<span class="tag">${esc(o.label)}</span>`).join(' ')}</div>
    <ol>${c.steps.map((s) => `<li><code>${esc(s.objectName)}</code> — ${esc(s.action)}
      <span class="muted">${esc(s.description || '')}</span></li>`).join('')}</ol>
  </div>`).join('');

  const aliasBlocks = a.aliasGroups.map((g) => `<div class="bs">
    <div class="bsh">${g.members.map((m) => `<span class="tag ${g.namesDisagree ? 'bad' : 'warn'}">${esc(m.project)}/${esc(m.page)}.<b>${esc(m.element)}</b></span>`).join(' == ')}
      ${g.namesDisagree ? '<span class="tag bad">die Namen sagen Verschiedenes über dasselbe Element</span>' : ''}</div>
    <ul>${g.members.map((m) => `<li><code>${esc(m.attribute)}</code> = <code class="sel">${esc(m.value)}</code>${m.impact ? ` <span class="muted">— ${m.impact} Testfall/Baustein</span>` : ''}</li>`).join('')}</ul>
  </div>`).join('');

  const unusedList = a.unused.map((u) =>
    `<span class="tag">${esc(u.project)}/${esc(u.page)}.<b>${esc(u.element)}</b></span>`).join(' ');

  // The shared area is listed even when it is empty, and it says so. "There are no shared
  // objects" and "the shared repository was never read" look the same in a total, and only
  // one of the two is a clean result.
  const projList = index.projects.map((p) =>
    `<li><b>${esc(p.label)}</b> <span class="muted">${esc(p.root)}</span> — ${p.objects.length} Objekte, ${p.testCases.length} Testfälle, ${p.components.length} Bausteine</li>`).join('')
    + (index.shared || []).map((s) => `<li><b>${esc(SHARED_LABEL)}</b> <span class="muted">${esc(s.root)}</span> — ${s.objects.length} Objekte, ${s.components.length} Bausteine${s.objects.length + s.components.length === 0 ? ' <span class="tag">gelesen, leer</span>' : ''}</li>`).join('');

  const srcList = a.liveSources.length
    ? a.liveSources.map((s) => `<li><code>${esc(s.file)}</code> ${s.url ? `— ${esc(s.url)}` : ''}</li>`).join('')
    : '<li class="muted">Keine Messung eingelesen. Mehrdeutigkeit ist damit <b>nicht geprüft</b> — sie lässt sich nur an einer echten Seite entscheiden (<code>tools/selector-uniqueness.mjs --json</code>).</li>';

  const section = (id, title, body, count) => !body ? '' : `
    <section id="${id}"><h2>${esc(title)} <span class="c">${count}</span></h2>${body}</section>`;

  return `<title>Object Store — ${esc(index.projects.map((p) => p.label).join(', '))}</title>
<style>
 :root{--bg:#fff;--fg:#16181d;--mut:#6b7280;--line:#e5e7eb;--card:#f8fafc;--acc:#ff6200;
   --ok:#0f766e;--okbg:#ccfbf1;--warn:#92400e;--warnbg:#fef3c7;--bad:#9f1239;--badbg:#ffe4e6;}
 @media (prefers-color-scheme:dark){:root{--bg:#0d1117;--fg:#e6edf3;--mut:#8b949e;
   --line:#26303b;--card:#161b22;--ok:#5eead4;--okbg:#0f3b36;--warn:#fcd34d;--warnbg:#4a3410;
   --bad:#fda4af;--badbg:#4c1026;}}
 :root[data-theme=dark]{--bg:#0d1117;--fg:#e6edf3;--mut:#8b949e;--line:#26303b;--card:#161b22;
   --ok:#5eead4;--okbg:#0f3b36;--warn:#fcd34d;--warnbg:#4a3410;--bad:#fda4af;--badbg:#4c1026;}
 :root[data-theme=light]{--bg:#fff;--fg:#16181d;--mut:#6b7280;--line:#e5e7eb;--card:#f8fafc;
   --ok:#0f766e;--okbg:#ccfbf1;--warn:#92400e;--warnbg:#fef3c7;--bad:#9f1239;--badbg:#ffe4e6;}
 *{box-sizing:border-box}
 body{margin:0;padding:2rem 1.25rem 4rem;background:var(--bg);color:var(--fg);
   font:15px/1.55 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;
   max-width:1200px;margin-inline:auto}
 h1{font-size:1.6rem;margin:0 0 .25rem}
 h2{font-size:1.1rem;margin:2.5rem 0 .75rem;padding-bottom:.4rem;border-bottom:2px solid var(--acc)}
 h2 .c{font-weight:400;color:var(--mut);font-size:.85rem}
 .sub{color:var(--mut);margin:0 0 1.5rem}
 .cards{display:flex;flex-wrap:wrap;gap:.75rem;margin:1.5rem 0}
 .card{background:var(--card);border:1px solid var(--line);border-radius:10px;
   padding:.75rem 1rem;min-width:120px;flex:1 1 120px}
 .card .n{font-size:1.7rem;font-weight:700;line-height:1.1}
 .card .l{color:var(--mut);font-size:.8rem}
 .card.bad .n{color:var(--bad)} .card.warn .n{color:var(--warn)}
 .wrap{overflow-x:auto;border:1px solid var(--line);border-radius:10px}
 table{border-collapse:collapse;width:100%;font-size:.86rem;min-width:640px}
 th,td{text-align:left;padding:.5rem .7rem;border-bottom:1px solid var(--line);vertical-align:top}
 th{background:var(--card);font-weight:600;position:sticky;top:0}
 tr:last-child td{border-bottom:none}
 td.num{text-align:right;font-variant-numeric:tabular-nums;font-weight:600}
 code{font:12.5px/1.4 ui-monospace,SFMono-Regular,Consolas,monospace;
   background:var(--card);padding:.1rem .3rem;border-radius:4px;border:1px solid var(--line)}
 code.sel{word-break:break-all;display:inline-block;max-width:34rem}
 .tag{display:inline-block;background:var(--card);border:1px solid var(--line);
   border-radius:999px;padding:.08rem .5rem;font-size:.78rem;margin:.1rem .1rem}
 .tag.ok{background:var(--okbg);color:var(--ok);border-color:transparent}
 .tag.warn{background:var(--warnbg);color:var(--warn);border-color:transparent}
 .tag.bad{background:var(--badbg);color:var(--bad);border-color:transparent}
 .muted{color:var(--mut)}
 .bs{border:1px solid var(--line);border-radius:10px;padding:.75rem 1rem;margin-bottom:.75rem}
 .bsh{margin-bottom:.4rem}
 ol,ul{margin:.3rem 0;padding-left:1.4rem}
 .note{background:var(--card);border-left:3px solid var(--acc);padding:.7rem 1rem;
   border-radius:0 8px 8px 0;margin:1rem 0;font-size:.88rem}
 #q{width:100%;padding:.6rem .8rem;font-size:.95rem;border:1px solid var(--line);
   border-radius:8px;background:var(--card);color:var(--fg);margin-bottom:1rem}
</style>
<h1>Object Store</h1>
<p class="sub">Was die Automatisierung schon weiß — Objektkataloge, Bausteine und Testfälle,
an einem Ort durchsuchbar. Erzeugt ${esc(new Date().toISOString())}.</p>

${t.objects === 0 ? `<div class="note"><b>NICHT GEPRÜFT — kein einziges Objekt wurde indexiert.</b>
 Die sechs Kacheln rechts von „Bausteine" sind Aussagen ÜBER Objekte. Ohne Objektmenge sind
 sie keine Befunde, sondern die Anfangswerte von Zählern, und stehen hier nur, damit die
 Seite nicht so tut, als wären sie gemessen worden. Das ist KEIN „nichts bricht" — es wurde
 nichts geprüft. Liegen die Objekte im alten Binärformat <code>IOR.object</code>, muss das
 Projekt einmal in Studio geöffnet und gespeichert werden, bevor hier etwas zu messen ist.</div>` : ''}

<div class="cards">
 ${card(t.objects, 'Objekte', t.objects === 0 ? 'bad' : '')}
 ${card(t.testCases, 'Testfälle')}
 ${card(t.steps, 'Schritte')}
 ${card(t.components, 'Bausteine')}
 ${card(t.objects === 0 ? '—' : t.duplicateSelectors, 'doppelt gepflegt', t.duplicateSelectors ? 'bad' : '')}
 ${card(t.objects === 0 ? '—' : t.fragile, 'fragil', t.fragile ? 'warn' : '')}
 ${card(t.objects === 0 ? '—' : t.measuredAmbiguous, 'gemessen mehrdeutig', t.measuredAmbiguous ? 'bad' : '')}
 ${card(t.objects === 0 ? '—' : t.aliasGroups, 'gleiches Element, mehrere Namen', t.aliasGroups ? 'bad' : '')}
 ${card(t.objects === 0 ? '—' : t.unused, 'unbenutzt', t.unused ? 'warn' : '')}
 ${card(t.brokenCalls, 'Baustein-Aufrufe ins Leere', t.brokenCalls ? 'bad' : '')}
</div>

<input id="q" placeholder="Filtern — Selektor, Objektname, Testfall …" autocomplete="off">

<div class="note"><b>Was hier bewiesen ist und was nicht.</b> Dass zwei Objekte <i>denselben
Selektor-Text</i> tragen, ist statisch bewiesen. Ob ein Selektor <i>mehrdeutig</i> ist, kann
statisch niemand entscheiden — Eindeutigkeit ist eine Eigenschaft von (Selektor, Seitenzustand).
Diese Spalten sind nur gefüllt, wenn ein Messbeleg eingelesen wurde.</div>

<section><h2>Indexierte Projekte <span class="c">${index.projects.length}</span></h2>
<ul>${projList}</ul>
<h3 style="font-size:.95rem;margin:1rem 0 .3rem">Eingelesene Messbelege</h3>
<ul>${srcList}</ul></section>

${section('dup', 'Doppelt gepflegt — derselbe Selektor unter mehreren Namen',
  dupRows ? `<div class="wrap"><table><thead><tr><th>Attribut</th><th>Selektor</th>
  <th>Anzahl</th><th></th><th>Wo</th></tr></thead><tbody>${dupRows}</tbody></table></div>` : '',
  a.duplicates.length)}

${section('meas', 'Gemessen an einer echten Seite',
  measRows ? `<div class="wrap"><table><thead><tr><th>Objekt</th><th>Selektor</th><th>Ergebnis</th>
  <th>Treffer</th><th></th><th>Seite</th></tr></thead><tbody>${measRows}</tbody></table></div>` : '',
  a.measured.length)}

${section('alias', 'Dasselbe Element unter mehreren Namen (gemessen)',
  aliasBlocks ? `<div class="note">Diese Paare findet <b>kein</b> statischer Vergleich: die
  Selektor-Texte sind verschieden, und jeder für sich trifft genau ein Element. Erst der
  gemessene Fingerabdruck der Position im Dokument zeigt, dass dasselbe Element gemeint ist.
  Tragen die beiden Namen verschiedene Bedeutungen, beweist mindestens ein Testfall etwas
  anderes als sein Name behauptet.</div>${aliasBlocks}` : '', a.aliasGroups.length)}

${section('frag', 'Fragile Selektoren (statisch gelesen)',
  fragRows ? `<div class="wrap"><table><thead><tr><th>Objekt</th><th>Attribut</th><th>Selektor</th>
  <th>Warum</th><th>Wirkung</th><th>Gemessen</th></tr></thead><tbody>${fragRows}</tbody></table></div>` : '',
  a.fragile.length)}

${section('shadow', 'Attribute, die die Engine nie ansieht',
  shadowRows ? `<div class="note">Die Engine nimmt das <b>erste</b> nicht-leere Attribut in der
  Reihenfolge ${esc(OBJECT_PROPS.join(' → '))} und bricht ab. Alles Weitere ist totes Gewicht —
  und ein stiller Fallstrick, wenn jemand den ignorierten Wert pflegt.</div>
  <div class="wrap"><table><thead><tr><th>Objekt</th><th>benutzt</th><th>ignoriert</th></tr></thead>
  <tbody>${shadowRows}</tbody></table></div>` : '', a.shadowed.length)}

${section('blast', 'Was bricht, wenn sich dieses Element ändert',
  blastRows ? `<div class="note">Die Spalte <b>Bricht</b> zählt <b>Testfälle</b>, nicht Dateien.
  Steht hinter einem Testfall ein <i>über …</i>, dann kommt er über einen Baustein dorthin —
  in seiner eigenen Datei steht der Objektname überhaupt nicht, und genau diese Treffer sieht
  beim Lesen niemand. Ein Objekt, das nur Bausteine benutzen, die zurzeit kein Testfall
  aufruft, steht mit 0 da: es kann heute keine Freigabe kippen.</div>
  <div class="wrap"><table><thead><tr><th>Objekt</th><th>Selektor</th>
  <th>Bricht</th><th>Testfälle</th></tr></thead><tbody>${blastRows}</tbody></table></div>` : '',
  a.blastRadius.filter((b) => b.uses > 0).length)}

${section('broken', 'Baustein-Aufrufe ins Leere',
  brokenRows ? `<div class="note">Ein <code>Execute</code>-Schritt, dessen Baustein nirgends zu
  finden ist. Die Engine führt ihn als gewöhnlichen Schritt aus — es gibt keinen Fehler und
  keinen Logeintrag, der Testfall überspringt einfach, was er auszuführen glaubte.</div>
  <div class="wrap"><table><thead><tr><th>Ruft aus</th><th>ruft auf</th><th>Stelle</th></tr></thead>
  <tbody>${brokenRows}</tbody></table></div>` : '', a.brokenCalls.length)}

${section('baust', 'Baustein-Kandidaten — geteilte Schrittfolgen', bausteinBlocks,
  a.bausteinCandidates.length)}

${section('unused', 'Von keinem Schritt benutzt', unusedList ? `<p>${unusedList}</p>` : '',
  a.unused.length)}

<script>
 // Filtering only hides rows; it never changes a number, so the totals above always
 // describe the whole index and not the current filter.
 const q = document.getElementById('q');
 q.addEventListener('input', () => {
   const v = q.value.trim().toLowerCase();
   for (const row of document.querySelectorAll('tbody tr, .bs')) {
     row.style.display = !v || row.textContent.toLowerCase().includes(v) ? '' : 'none';
   }
 });
</script>`;
}

// ---------------------------------------------------------------------------
// Selftest — a fixture whose findings are known by construction, not by a prior run.
// ---------------------------------------------------------------------------
function selftest() {
  const here = new URL('.', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1');
  // Projects/ and Shared/ side by side, because that is the layout the engine builds its
  // shared paths from. A fixture laid out any other way would prove the shared half works
  // in a shape that never occurs.
  const project = join(here, 'fixtures', 'object-store', 'Projects', 'StoreProbe');
  if (!existsSync(project)) {
    console.error(`SELFTEST CANNOT RUN — fixture missing: ${project}`);
    process.exit(EXIT.NOTHING_INDEXED);
  }
  const indexed = indexProject(project);
  const index = { projects: [indexed], shared: [indexShared(indexed.sharedRoot)] };
  const a = analyse(index, { uniqueness: [], minSequence: 3 });

  const problems = [];
  const eq = (what, got, want) => {
    if (got !== want) problems.push(`${what}: expected ${want}, got ${got}`);
  };
  // Every expectation below follows from the fixture files, which are written to contain
  // exactly one of each finding.
  eq('objects', a.totals.objects, 9);
  eq('shared objects', a.totals.sharedObjects, 2);
  eq('test cases', a.totals.testCases, 3);
  eq('components', a.totals.components, 2);
  eq('shared components', a.totals.sharedComponents, 1);
  eq('duplicate selectors', a.totals.duplicateSelectors, 1);
  eq('shadowed', a.totals.shadowed, 1);
  eq('unused', a.totals.unused, 1);
  eq('broken Baustein calls', a.totals.brokenCalls, 0);

  const dup = a.duplicates[0];
  if (!dup || dup.occurrences !== 2) problems.push('duplicate group should hold exactly 2 objects');
  // The two duplicate entries differ only in quote style — proving normalisation works.
  if (dup && new Set(dup.where.map((w) => w.element)).size !== 2) {
    problems.push('the duplicate should be two DIFFERENT names');
  }
  const genId = a.fragile.find((f) => f.reasons.some((r) => r.id === 'generated-id'));
  if (!genId) problems.push('the #id42 selector must be reported as a generated id');
  const positional = a.fragile.find((f) => f.reasons.some((r) => r.id === 'positional'));
  if (!positional) problems.push('the nth-child selector must be reported as positional');
  const cand = a.bausteinCandidates[0];
  if (!cand) problems.push('the 3 shared steps must surface as a Baustein candidate');
  else {
    if (cand.length < 3) problems.push(`Baustein candidate too short: ${cand.length}`);
    if (cand.inTestCases !== 2) problems.push(`Baustein candidate should span 2 test cases, got ${cand.inTestCases}`);
  }
  const wide = a.wideBlast.find((b) => b.element === 'SearchInput');
  if (!wide) problems.push('SearchInput is used by both test cases and must show a wide blast radius');

  const blast = (element) => a.blastRadius.find((b) => b.element === element);

  // The shared half. "Fall C" names BenutzerFeld out loud through a "[Shared] AnmeldeSeite"
  // reference, and a store that only walks <project>/ObjectRepository cannot see the object
  // at all — it would report it as belonging to nothing.
  const benutzer = blast('BenutzerFeld');
  if (!benutzer) problems.push('the shared object BenutzerFeld was not indexed at all');
  else if (!benutzer.testCases.includes('Probe:Fall C')) {
    problems.push(`the "[Shared] AnmeldeSeite" reference in Fall C must resolve to the shared `
      + `object; got ${JSON.stringify(benutzer.testCases)}`);
  }

  // The expensive question. PasswortFeld appears in NO test case file: Fall C reaches it by
  // calling "[Shared] Gemeinsam:Anmelden". Anything that reads only the test cases says this
  // object breaks nothing.
  const passwort = blast('PasswortFeld');
  if (!passwort) problems.push('the shared object PasswortFeld was not indexed at all');
  else {
    if (passwort.testCases.length !== 0) {
      problems.push('PasswortFeld is named by no test case directly; it must not be listed as '
        + `a direct break: ${JSON.stringify(passwort.testCases)}`);
    }
    const via = passwort.viaComponents.find((v) => v.testCase === 'Probe:Fall C');
    if (!via) problems.push('Fall C must break through the Baustein Gemeinsam:Anmelden');
    else if (!via.via.includes('Gemeinsam:Anmelden')) {
      problems.push(`the path must name the Baustein: ${JSON.stringify(via.via)}`);
    }
    if (passwort.breaks !== 1) problems.push(`PasswortFeld must break exactly 1 test case, got ${passwort.breaks}`);
  }

  // Two levels: Fall C -> [Shared] Gemeinsam:Anmelden -> [Project] Probe:SucheOeffnen ->
  // Suchfeld. Depth 1 is easy to get right by accident; depth 2 is not.
  const suchfeld = blast('Suchfeld');
  if (!suchfeld || !suchfeld.viaComponents.some((v) => v.testCase === 'Probe:Fall C')) {
    problems.push('Suchfeld sits two Baustein calls below Fall C and must still be reported '
      + `as breaking it; got ${JSON.stringify(suchfeld ? suchfeld.viaComponents : null)}`);
  }

  // The call grammar itself. The Execute action "[Shared] Gemeinsam:Anmelden" split on its
  // first colon yields a group literally named "[Shared] Gemeinsam", which resolves to
  // nothing — and an unresolved call is silent, so the old reading lost this quietly.
  const fallC = a.blastRadius.filter((b) => b.viaComponents.some((v) => v.testCase === 'Probe:Fall C'));
  if (fallC.length === 0) problems.push('no object at all was reached through Fall C\'s Execute step');

  // --- the empty object set is not a clean result ---------------------------------------
  // Red against the version before this change, where the guard was an `&&` and a project
  // with test cases but no readable ObjectRepository exited 0 with five counters presented
  // as findings.
  const totals = (over) => ({
    objects: 0, testCases: 0, duplicateSelectors: 0, fragile: 0, shadowed: 0,
    bausteinCandidates: 0, measuredAmbiguous: 0, brokenCalls: 0, ...over,
  });
  if (overallExit(totals({ objects: 0, testCases: 6 })) !== EXIT.NOTHING_INDEXED) {
    problems.push('a project with test cases but NO indexed object must not exit 0 — five of '
      + 'the six finding counters are statements about objects that were never found');
  }
  if (overallExit(totals({ objects: 0, testCases: 0 })) !== EXIT.NOTHING_INDEXED) {
    problems.push('an entirely empty index must not exit 0 either');
  }
  if (overallExit(totals({ objects: 0, testCases: 6, bausteinCandidates: 1 })) !== EXIT.FINDINGS) {
    problems.push('a finding that does NOT depend on objects still has to be reported as one');
  }
  if (overallExit(totals({ objects: 9, testCases: 3 })) !== EXIT.OK) {
    problems.push('a real, clean measurement over a real object set must still exit 0');
  }
  if (overallExit(totals({ objects: 9, testCases: 3, fragile: 1 })) !== EXIT.FINDINGS) {
    problems.push('a real finding must still exit 1');
  }
  if (overallExit(a.totals) !== EXIT.FINDINGS) {
    problems.push(`the fixture itself carries findings and must exit 1, got ${overallExit(a.totals)}`);
  }

  if (problems.length) {
    console.error('object-store selftest: RED\n  ' + problems.join('\n  '));
    process.exit(EXIT.FINDINGS);
  }
  console.log('object-store selftest: GREEN — 9 objects (2 of them shared), 3 test cases and '
    + '2 Bausteine (1 shared) indexed; the duplicate pair, the generated id, the positional '
    + 'selector, the shadowed attribute, the unused object and the shared 3-step run were each '
    + 'found exactly once; the "[Shared] AnmeldeSeite" object reference resolved into the shared '
    + 'repository, and Fall C was reported as breaking on two objects it never names — one a '
    + 'Baustein deep, one two Bausteine deep.');
  process.exit(EXIT.OK);
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------
const args = parseArgs(process.argv.slice(2));
if (args.help) { console.log(USAGE); process.exit(EXIT.OK); }
if (args.unknown) {
  console.error(`unknown argument: ${args.unknown}\n\n${USAGE}`);
  process.exit(EXIT.ARGS_REJECTED);
}
if (args.selftest) selftest();

const roots = [];
for (const p of args.projects) roots.push(resolve(p));
for (const s of args.scans) roots.push(...findProjects(resolve(s)));
const uniqueRoots = [...new Set(roots)];

if (uniqueRoots.length === 0) {
  console.error(`no project given\n\n${USAGE}`);
  process.exit(EXIT.ARGS_REJECTED);
}
for (const r of uniqueRoots) {
  if (!existsSync(r)) {
    console.error(`project not found: ${r}`);
    process.exit(EXIT.ARGS_REJECTED);
  }
}

const projects = uniqueRoots.map(indexProject);
// One shared area per app root, however many projects sit under it. Indexing it per project
// would report every shared object as a duplicate of itself.
const sharedRoots = [...new Set(projects.map((p) => p.sharedRoot))];
const index = { projects, shared: sharedRoots.map(indexShared) };
const analysis = analyse(index, args);

if (args.betroffen != null) {
  process.exit(answerImpact(analysis, index, args.betroffen));
}

if (analysis.totals.objects === 0 && analysis.totals.testCases === 0) {
  console.error('CANNOT TELL — nothing was indexed: no ObjectRepository entries and no test '
    + 'cases were found under the given project(s). This is not a clean result.');
  process.exit(EXIT.NOTHING_INDEXED);
}

report(analysis, args.quiet);

if (args.json) {
  writeFileSync(args.json, JSON.stringify({
    generatedAt: new Date().toISOString(),
    projects: index.projects.map((p) => ({ label: p.label, root: p.root,
      objects: p.objects.length, testCases: p.testCases.length, components: p.components.length })),
    ...analysis,
  }, null, 2));
  console.log(`  JSON:  ${args.json}`);
}
if (args.html) {
  writeFileSync(args.html, html(analysis, index));
  console.log(`  Seite: ${args.html}`);
}

process.exit(overallExit(analysis.totals));
