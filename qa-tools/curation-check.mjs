#!/usr/bin/env node
/**
 * Mechanically evaluate the 13-item curation gate checklist for one INGenious test case.
 * Node.js stdlib only. See tools/README-curation-check.md.
 */

import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

function die(msg) {
  console.error(msg);
  process.exit(EXIT_FAIL); // a usage error shares the FAIL code; see overallExit() below
}

/** Hand-rolled argv parser: --project, --scenario, --testcase, repeatable --run, optional --json. */
function parseArgs(argv) {
  const args = {
    project: null,
    scenario: null,
    testcase: null,
    run: [],
    json: null,
  };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--project') {
      args.project = argv[++i];
    } else if (a === '--scenario') {
      args.scenario = argv[++i];
    } else if (a === '--testcase') {
      args.testcase = argv[++i];
    } else if (a === '--run') {
      args.run.push(argv[++i]);
    } else if (a === '--json') {
      args.json = argv[++i];
    } else if (a === '--selftest') {
      args.selftest = true;
    }
  }
  return args;
}

const USAGE =
  'Usage: node tools/curation-check.mjs --project <dir> --scenario <name> --testcase <name> [--run <parsed-results.json>]... [--json <out>]\n'
  + '       node tools/curation-check.mjs --selftest';

const STEP_HEADER = [
  'Step',
  'ObjectName',
  'Description',
  'Action',
  'Input',
  'Condition',
  'Reference',
];

const EXISTENCE_ASSERTIONS = new Set(
  [
    'assertElementIsVisible',
    'assertElementIsHidden',
    'assertElementIsAttached',
    'assertElementIsNotVisible',
    'assertElementPresent',
    'assertElementNotPresent',
    'assertElementDisplayed',
    'assertElementNotDisplayed',
  ].map((s) => s.toLowerCase()),
);

const CREDENTIAL_KEY_RE =
  /^(pass|password|pwd|secret|token|api[_\s-]?key|apikey|credential)$/i;

// ---------------------------------------------------------------------------
// Path helpers
// ---------------------------------------------------------------------------

/** Relative path from project root with forward slashes. */
function relProject(projectRoot, absPath) {
  return path.relative(projectRoot, absPath).split(path.sep).join('/');
}

/** Recursively list files under dir matching predicate; empty if dir missing. */
function walkFiles(dir, predicate) {
  const out = [];
  if (!dir || !fs.existsSync(dir)) return out;
  let st;
  try {
    st = fs.statSync(dir);
  } catch {
    return out;
  }
  if (!st.isDirectory()) return out;

  const stack = [dir];
  while (stack.length) {
    const cur = stack.pop();
    let entries;
    try {
      entries = fs.readdirSync(cur, { withFileTypes: true });
    } catch {
      continue;
    }
    for (const ent of entries) {
      const full = path.join(cur, ent.name);
      if (ent.isDirectory()) {
        stack.push(full);
      } else if (ent.isFile() && predicate(full, ent.name)) {
        out.push(full);
      }
    }
  }
  return out;
}

// ---------------------------------------------------------------------------
// CSV (RFC4180-ish)
// ---------------------------------------------------------------------------

/**
 * Parse a CSV text into rows of string cells.
 * Tracks 1-based physical line number of each data row.
 * @returns {{ headers: string[], rows: { cells: string[], line: number }[] }}
 */
function parseCsv(text) {
  const rows = [];
  let cells = [];
  let field = '';
  let inQuotes = false;
  let line = 1;
  let rowStartLine = 1;
  let i = 0;

  while (i < text.length) {
    const ch = text[i];

    if (inQuotes) {
      if (ch === '"') {
        if (text[i + 1] === '"') {
          field += '"';
          i += 2;
          continue;
        }
        inQuotes = false;
        i++;
        continue;
      }
      if (ch === '\n') line++;
      field += ch;
      i++;
      continue;
    }

    if (ch === '"') {
      inQuotes = true;
      i++;
      continue;
    }
    if (ch === ',') {
      cells.push(field);
      field = '';
      i++;
      continue;
    }
    if (ch === '\r') {
      i++;
      continue;
    }
    if (ch === '\n') {
      cells.push(field);
      field = '';
      // Skip completely blank trailing lines later; still record non-empty rows
      const isBlank = cells.every((c) => c === '');
      if (!isBlank || rows.length > 0) {
        rows.push({ cells, line: rowStartLine });
      }
      cells = [];
      line++;
      rowStartLine = line;
      i++;
      continue;
    }
    field += ch;
    i++;
  }

  // Final field / row (no trailing newline)
  if (field.length > 0 || cells.length > 0) {
    cells.push(field);
    const isBlank = cells.every((c) => c === '');
    if (!isBlank) {
      rows.push({ cells, line: rowStartLine });
    }
  }

  // Drop trailing blank rows
  while (rows.length && rows[rows.length - 1].cells.every((c) => c.trim() === '')) {
    rows.pop();
  }

  if (rows.length === 0) {
    return { headers: [], rows: [] };
  }

  const headers = rows[0].cells.map((h) => h.trim());
  const dataRows = rows.slice(1).filter((r) => !r.cells.every((c) => c.trim() === ''));
  return { headers, rows: dataRows };
}

/** Map a CSV data row to an object keyed by header names. */
function rowToObject(headers, cells) {
  const obj = {};
  for (let i = 0; i < headers.length; i++) {
    obj[headers[i]] = cells[i] !== undefined ? cells[i] : '';
  }
  return obj;
}

/** Read and parse a step CSV; returns array of step objects with .line. */
function readStepCsv(filePath) {
  const text = fs.readFileSync(filePath, 'utf8');
  const { headers, rows } = parseCsv(text);
  return rows.map((r) => {
    const obj = rowToObject(headers, r.cells);
    return {
      step: obj.Step ?? '',
      objectName: obj.ObjectName ?? '',
      description: obj.Description ?? '',
      action: obj.Action ?? '',
      input: obj.Input ?? '',
      condition: obj.Condition ?? '',
      reference: obj.Reference ?? '',
      line: r.line,
    };
  });
}

/**
 * Read a step file that INGenious 3.1 has migrated to YAML.
 *
 * The migrated shape is fixed and shallow — `steps:` holding a list of maps whose keys are
 * `step`, `object`, `description`, `action`, `input`, `condition`, `reference`. Measured on
 * a real project migration on 2026-08-03, not guessed from a schema.
 */
function readStepYaml(filePath) {
  const lines = fs.readFileSync(filePath, 'utf8').split(/\r?\n/);
  const steps = [];
  let current = null;
  const flush = () => {
    if (current) steps.push(current);
    current = null;
  };
  const blank = (line) => ({
    step: '', objectName: '', description: '', action: '',
    input: '', condition: '', reference: '', line,
  });
  const assign = (row, key, raw) => {
    const k = key.toLowerCase();
    let v = String(raw).trim();
    if (v.length >= 2 && ((v[0] === '"' && v.endsWith('"')) || (v[0] === "'" && v.endsWith("'")))) {
      v = v[0] === '"' ? v.slice(1, -1).replace(/\\(["\\nt])/g, (_, c) =>
        (c === 'n' ? '\n' : c === 't' ? '\t' : c)) : v.slice(1, -1).replace(/''/g, "'");
    }
    if (k === 'object' || k === 'objectname') row.objectName = v;
    else if (k === 'action') row.action = v;
    else if (k === 'input') row.input = v;
    else if (k === 'condition') row.condition = v;
    else if (k === 'reference') row.reference = v;
    else if (k === 'description') row.description = v;
    else if (k === 'step') row.step = v;
  };
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    if (!line.trim() || line.trim().startsWith('#')) continue;
    const item = /^\s*-\s*(.*)$/.exec(line);
    if (item) {
      flush();
      current = blank(i + 1);
      const kv = /^([A-Za-z_][\w]*)\s*:\s*(.*)$/.exec(item[1]);
      if (kv) assign(current, kv[1], kv[2]);
      continue;
    }
    const kv = /^\s+([A-Za-z_][\w]*)\s*:\s*(.*)$/.exec(line);
    if (kv && current) assign(current, kv[1], kv[2]);
  }
  flush();
  return steps;
}

/** Read a step file in whichever shape it is on disk. */
function readSteps(filePath) {
  return /\.ya?ml$/i.test(filePath) ? readStepYaml(filePath) : readStepCsv(filePath);
}

/**
 * The step file for a test case or component, in whichever shape the project is in.
 *
 * INGenious 3.1 migrates TestPlan/, TestLab/ AND ReusableComponents/ from CSV to YAML —
 * and it does so on a plain engine run, not only when Studio opens the project (measured
 * 2026-08-03: one `ingenious-run.mjs` invocation converted all three and left a
 * `.migration-backup/`). A checker nailed to `.csv` therefore reports a healthy project as
 * having no steps at all the first time anybody runs it.
 *
 * @returns {string|null} the first shape that exists
 */
function resolveStepFile(base) {
  for (const ext of ['.csv', '.yaml', '.yml']) {
    const candidate = `${base}${ext}`;
    if (fs.existsSync(candidate)) return candidate;
  }
  return null;
}

/** Read a data sheet CSV into { headers, rows: { cells, line, map }[] }. */
function readDataSheet(filePath) {
  if (!fs.existsSync(filePath)) return null;
  const text = fs.readFileSync(filePath, 'utf8');
  const { headers, rows } = parseCsv(text);
  return {
    headers,
    rows: rows.map((r) => ({
      cells: r.cells,
      line: r.line,
      map: rowToObject(headers, r.cells),
    })),
  };
}

// ---------------------------------------------------------------------------
// Properties
// ---------------------------------------------------------------------------

/** Parse key=value properties file; missing file → {}. */
function readProperties(filePath) {
  const out = {};
  if (!fs.existsSync(filePath)) return out;
  let text;
  try {
    text = fs.readFileSync(filePath, 'utf8');
  } catch {
    return out;
  }
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith('#') || line.startsWith('!')) continue;
    const eq = line.indexOf('=');
    if (eq < 0) continue;
    const key = line.slice(0, eq).trim();
    const value = line.slice(eq + 1);
    if (key) out[key] = value;
  }
  return out;
}

// ---------------------------------------------------------------------------
// Object Repository YAML (tolerant line parser)
// ---------------------------------------------------------------------------

/** Strip surrounding quotes and unescape common YAML string escapes. */
function unquoteYamlValue(raw) {
  let s = raw.trim();
  if (
    (s.startsWith('"') && s.endsWith('"') && s.length >= 2) ||
    (s.startsWith("'") && s.endsWith("'") && s.length >= 2)
  ) {
    s = s.slice(1, -1);
  }
  s = s.replace(/\\"/g, '"').replace(/\\\\/g, '\\');
  return s;
}

/**
 * Parse one OR YAML file into element entries.
 * Shape: page, scope, elements: { Name: { type: value, ... } }
 * @returns {{ page: string, elementName: string, type: string, value: string, file: string, line: number }[]}
 */
function parseOrYaml(filePath, projectRoot) {
  const entries = [];
  let text;
  try {
    text = fs.readFileSync(filePath, 'utf8');
  } catch {
    return entries;
  }

  const lines = text.split(/\r?\n/);
  let page = '';
  let inElements = false;
  let currentName = null;
  let currentNameLine = 0;
  let currentProps = []; // { type, value, line }

  function flushElement() {
    if (!currentName) return;
    for (const p of currentProps) {
      entries.push({
        page,
        elementName: currentName,
        type: p.type,
        value: p.value,
        file: relProject(projectRoot, filePath),
        line: p.line,
      });
    }
    currentName = null;
    currentNameLine = 0;
    currentProps = [];
  }

  for (let idx = 0; idx < lines.length; idx++) {
    const raw = lines[idx];
    const lineNo = idx + 1;
    // Preserve leading spaces for indent; strip trailing
    const trimmedEnd = raw.replace(/\s+$/, '');
    if (trimmedEnd.trim() === '' || trimmedEnd.trim().startsWith('#')) continue;

    const indent = trimmedEnd.match(/^[ ]*/)[0].length;
    const content = trimmedEnd.slice(indent);

    if (indent === 0) {
      if (content.startsWith('page:')) {
        flushElement();
        page = unquoteYamlValue(content.slice('page:'.length));
        inElements = false;
      } else if (content === 'elements:' || content.startsWith('elements:')) {
        flushElement();
        inElements = true;
      } else if (content.startsWith('scope:')) {
        // ignore
      }
      continue;
    }

    if (!inElements) continue;

    // Element name at 2-space indent: "Username:"
    if (indent === 2 && content.endsWith(':') && !content.includes(' ')) {
      flushElement();
      currentName = content.slice(0, -1).trim();
      currentNameLine = lineNo;
      currentProps = [];
      continue;
    }

    // Property at 4-space indent: "type: value" or start of multi-line
    if (indent === 4 && currentName) {
      const colon = content.indexOf(':');
      if (colon > 0) {
        const type = content.slice(0, colon).trim();
        let value = content.slice(colon + 1);
        // Leading space after colon is optional
        if (value.startsWith(' ')) value = value.slice(1);
        value = unquoteYamlValue(value);
        currentProps.push({ type, value, line: lineNo });
      }
      continue;
    }

    // Continuation: further-indented line that is NOT a new type:value pair
    if (indent > 4 && currentName && currentProps.length > 0) {
      const cont = content.trim();
      // If it looks like a new type: value at deeper indent, treat as continuation
      // of previous value (OR chainedLocator multi-line style).
      const prev = currentProps[currentProps.length - 1];
      prev.value = prev.value + unquoteYamlValue(cont);
      continue;
    }
  }

  flushElement();
  return entries;
}

/** Load all OR entries from project ObjectRepository and optional Shared OR. */
function loadObjectRepository(projectRoot) {
  const entries = [];
  const orDir = path.join(projectRoot, 'ObjectRepository');
  const sharedDir = path.resolve(projectRoot, '..', '..', 'Shared', 'SharedObjectRepository');

  for (const dir of [orDir, sharedDir]) {
    const files = walkFiles(dir, (_full, name) => /\.ya?ml$/i.test(name));
    for (const f of files) {
      entries.push(...parseOrYaml(f, projectRoot));
    }
  }
  return entries;
}

// ---------------------------------------------------------------------------
// Step resolution
// ---------------------------------------------------------------------------

const GROUP_NAME_RE = /^([^:]+):(.+)$/;
const SCOPE_PREFIX_RE = /^\[\s*([^\]]*)\s*\]\s*(.*)$/;

/**
 * The directory a component of the given scope lives in.
 *
 * PROJECT  -> <project>/ReusableComponents
 * SHARED   -> <appRoot>/Shared/SharedReusableComponents, where appRoot is the project's
 *             grandparent (projects live at <appRoot>/Projects/<Name>). This mirrors
 *             Project.getSharedReusableComponentsPath(), which builds the same path from
 *             the running install's user.dir, and the same two-levels-up convention this
 *             file already uses for the shared Object Repository.
 */
function componentSearchRoots(projectRoot, scope) {
  const project = path.join(projectRoot, 'ReusableComponents');
  const shared = path.resolve(
    projectRoot, '..', '..', 'Shared', 'SharedReusableComponents',
  );
  if (scope === 'PROJECT') return [project];
  if (scope === 'SHARED') return [shared];
  return [project, shared]; // UNSCOPED: project first, then shared — the engine's fallback
}

/**
 * Parse an Execute step into { scope, group, name }, or null when it is not a call.
 *
 * A one-for-one mirror of INGenious's own two-part rule, which no amount of reading the
 * Action cell alone can reproduce:
 *
 *   TestStep.isReusableStep()          Object == "Execute" AND Action matches ".+:.+"
 *   ReusableRef.parse(action)          "[Project] Group:Name" | "[Shared] Group:Name"
 *                                      | "Group:Name" (legacy, unscoped)
 *   TestStep.getEffectiveReusableRef() when the Action is unscoped, the scope is taken
 *                                      from the step's REFERENCE cell instead
 *
 * The scope therefore lives in one of two cells, and a checker that splits the Action on
 * its first colon reads "[Project] Kundensuche" as the group name and finds nothing — which
 * is silent, because an unresolved call is simply emitted as a leaf step.
 *
 * Unknown scope words follow the engine: ReusableRef.parse throws, and getReusableData()
 * falls back to a plain two-way split of the raw Action.
 */
function parseReusableCall(step) {
  if ((step.objectName || '').trim() !== 'Execute') return null;
  const action = (step.action || '').trim();
  if (!GROUP_NAME_RE.test(action)) return null;

  let scope = 'UNSCOPED';
  let rest = action;
  const scoped = SCOPE_PREFIX_RE.exec(action);
  if (scoped) {
    const word = scoped[1].trim().toLowerCase();
    if (word === 'project') { scope = 'PROJECT'; rest = scoped[2].trim(); }
    else if (word === 'shared') { scope = 'SHARED'; rest = scoped[2].trim(); }
    // else: unknown scope — leave the raw action to the split below, as the engine does
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

/**
 * Resolve a test case into leaf steps, expanding component references.
 * @returns {{ leaves: object[], components: { group: string, name: string }[], warnings: string[] }}
 */
function resolveSteps(projectRoot, scenario, testcase) {
  const testCaseFile = resolveStepFile(path.join(projectRoot, 'TestPlan', scenario, testcase));
  if (!testCaseFile) {
    die(`Error: test case file not found: `
      + `${path.join(projectRoot, 'TestPlan', scenario, testcase)}.csv|.yaml|.yml`);
  }

  const leaves = [];
  const components = [];
  const warnings = [];
  const seenComponents = new Set();

  function resolveFile(filePath, componentPath, depth, stackVisited) {
    if (depth > 10) {
      warnings.push(`Max component depth (10) reached at ${relProject(projectRoot, filePath)}`);
      return;
    }

    let steps;
    try {
      steps = readSteps(filePath);
    } catch (err) {
      warnings.push(`Failed to read ${relProject(projectRoot, filePath)}: ${err.message}`);
      return;
    }

    const relFile = relProject(projectRoot, filePath);

    for (const step of steps) {
      const call = parseReusableCall(step);
      let expanded = false;

      if (call) {
        const { scope, group, name } = call;
        let compPath = null;
        for (const root of componentSearchRoots(projectRoot, scope)) {
          compPath = resolveStepFile(path.join(root, group, name));
          if (compPath) break;
        }
        if (compPath) {
          expanded = true;
          const key = `${group}:${name}`;
          if (!seenComponents.has(key)) {
            seenComponents.add(key);
            components.push({ group, name });
          }
          if (stackVisited.has(key)) {
            warnings.push(
              `Cycle detected: ${[...stackVisited, key].join(' → ')} at ${relFile}:${step.line}`,
            );
            // Stop recursing; do not add as leaf
          } else {
            const nextVisited = new Set(stackVisited);
            nextVisited.add(key);
            resolveFile(compPath, [...componentPath, key], depth + 1, nextVisited);
          }
        } else {
          // An Execute step that names a component nobody can find is not a leaf step —
          // it is a broken test case, and saying so is the whole point of the check.
          warnings.push(
            `Unresolved component ${scope === 'SHARED' ? '[Shared] ' : ''}${group}:${name} `
            + `at ${relFile}:${step.line}`,
          );
        }
      }

      if (!expanded) {
        leaves.push({
          action: step.action || '',
          objectName: step.objectName || '',
          description: step.description || '',
          input: step.input || '',
          condition: step.condition || '',
          reference: step.reference || '',
          file: relFile,
          line: step.line,
          componentPath: [...componentPath],
        });
      }
    }
  }

  resolveFile(testCaseFile, [], 0, new Set());
  return { leaves, components, warnings, testCaseFile };
}

// ---------------------------------------------------------------------------
// Input classification
// ---------------------------------------------------------------------------

/**
 * Classify a leaf step Input string.
 *
 * A leading `@` is NOT a file reference — it is the engine's escape for "take what follows
 * literally, do not read it as a data sheet":
 *
 *   DataProcessor.resolveIn(inp)          if (inp.startsWith("@")) inp = trimFirst(inp);
 *   DataProcessor.isInputPatternDynamic   "^(@|=|>|%)(.*)" — the @ branch is a STATIC string
 *   DataProcessor.isInputPatternDataSheet only reached when the dynamic branch did not match
 *
 * So `@Accounts Overview` is an inline literal, `@%NeueNummer%` is a runtime variable, and
 * `@Kunden:Nummer` is the literal text "Kunden:Nummer" and expressly NOT a sheet lookup.
 * Filing all three under one opaque `fileref` kind is what let criteria 11 and 12 walk past
 * 26 of the 50 inputs of a real ported test case without counting or naming a single one —
 * and then report "22/24 parameterized (92%)" as if 24 were the whole population.
 *
 * @returns {{ kind: 'none'|'variable'|'sheet'|'literal', value: string, escaped: boolean,
 *             sheet?: string, column?: string }}
 *   `value` is what the engine would actually use — the input with the `@` removed.
 */
function classifyInput(input, projectRoot) {
  if (input == null || String(input).trim() === '') {
    return { kind: 'none', value: '', escaped: false };
  }
  let s = String(input).trim();

  // The escape is consumed first, exactly as resolveIn does, and then whatever is left is
  // classified on its merits. What it can never become again is a sheet reference.
  let escaped = false;
  if (s.startsWith('@')) {
    escaped = true;
    s = s.slice(1).trim();
    if (s === '') return { kind: 'none', value: '', escaped };
  }

  if (s.length >= 2 && s.startsWith('%') && s.endsWith('%')) {
    return { kind: 'variable', value: s, escaped };
  }

  if (!escaped) {
    const colon = s.indexOf(':');
    if (colon > 0) {
      const left = s.slice(0, colon).trim();
      const right = s.slice(colon + 1).trim();
      if (left && right) {
        const sheetPath = path.join(projectRoot, 'TestData', `${left}.csv`);
        if (fs.existsSync(sheetPath)) {
          return { kind: 'sheet', value: s, escaped, sheet: left, column: right };
        }
      }
    }
  }

  return { kind: 'literal', value: s, escaped };
}

/** Percent-var key inside %key% (without the percents). */
function percentVarKey(input) {
  const s = String(input).trim();
  if (s.length >= 2 && s.startsWith('%') && s.endsWith('%')) {
    return s.slice(1, -1);
  }
  return null;
}

// ---------------------------------------------------------------------------
// Masking & secret patterns
// ---------------------------------------------------------------------------

/** Mask a literal for evidence: keep first 3 + last 2; short strings → first + asterisks. */
function maskLiteral(value) {
  const s = String(value);
  if (s.length <= 5) {
    return s[0] + '*'.repeat(Math.max(1, s.length - 1));
  }
  const mid = Math.max(1, s.length - 5);
  return s.slice(0, 3) + '*'.repeat(mid) + s.slice(-2);
}

const IBAN_RE = /\b[A-Z]{2}\d{2}[A-Z0-9]{10,30}\b/g;
const DIGITS_RE = /\d{8,}/g;

/**
 * Scan a string for iban/digits secrets; push findings.
 * @param {string} text
 * @param {string} keyForCredential - column/object/description/properties key
 * @param {string} where - human-readable location description
 * @param {string} loc - file:line
 * @param {object[]} findings
 */
function scanForSecrets(text, keyForCredential, where, loc, findings) {
  if (text == null || String(text).trim() === '') return;
  const s = String(text);

  // credential: whole value if key matches
  if (CREDENTIAL_KEY_RE.test(String(keyForCredential || '').trim()) && s.trim() !== '') {
    findings.push({
      kind: 'credential',
      literal: s.trim(),
      where,
      loc,
    });
  }

  IBAN_RE.lastIndex = 0;
  let m;
  while ((m = IBAN_RE.exec(s)) !== null) {
    findings.push({ kind: 'iban', literal: m[0], where, loc });
  }

  DIGITS_RE.lastIndex = 0;
  while ((m = DIGITS_RE.exec(s)) !== null) {
    findings.push({ kind: 'digits', literal: m[0], where, loc });
  }
}

// ---------------------------------------------------------------------------
// Reference parsing for OR scope
// ---------------------------------------------------------------------------

/** Parse "[Project] PageName" or "[Shared] PageName" → page name or null. */
function parseReferencePage(reference) {
  if (!reference) return null;
  const m = String(reference).trim().match(/^\[(Project|Shared)\]\s*(.+)$/i);
  if (!m) return null;
  return m[2].trim();
}

// ---------------------------------------------------------------------------
// Criteria builders
// ---------------------------------------------------------------------------

/**
 * "I measured and found nothing" and "there was nothing to measure" are two different
 * answers, and only the first one is a pass. A criterion whose whole surface was empty —
 * no object repository, no data-bearing input, no scanned cell — reports UNMEAS instead of
 * borrowing the credibility of a PASS it never earned. Same third state, same reasoning and
 * same exit code as green-audit.mjs (#191).
 */
const UNMEASURED = 'UNMEAS';

const EXIT_CLEAN = 0;
const EXIT_FAIL = 1;
const EXIT_UNMEASURABLE = 3;

function criterion(id, title, gate, verdict, evidence, locations) {
  return { id, title, gate, verdict, evidence, locations: locations || [] };
}

/**
 * The process exit code, separated from all I/O so the selftest can reach every branch.
 *
 * A warning counts here for the same reason UNMEAS does: every warning this tool raises —
 * an unresolvable Baustein, a cycle, a step file it could not read — means the criteria
 * above judged a SMALLER test case than the one on disk. Printing that to stderr and then
 * exiting 0 tells a pipeline the opposite of what happened.
 */
function overallExit({ fail, unmeasured, warnings }) {
  if (fail > 0) return EXIT_FAIL;
  if (unmeasured > 0 || warnings > 0) return EXIT_UNMEASURABLE;
  return EXIT_CLEAN;
}

/** 01 MANUAL */
function crit01(leaves, components) {
  const compList =
    components.length === 0
      ? 'none'
      : components.map((c) => `${c.group}:${c.name}`).join(', ');
  return criterion(
    'scope-one-scenario',
    'One business scenario per test',
    'Scope',
    'MANUAL',
    `Business-scenario boundaries are a human judgement about intent. Hint: ${leaves.length} leaf step(s); components pulled in: ${compList}.`,
    [],
  );
}

/** 02 MANUAL */
function crit02() {
  return criterion(
    'scope-matches-ado-ac',
    'Matches the linked ADO test case acceptance criteria',
    'Scope',
    'MANUAL',
    'Requires reading the linked ADO work item; no ADO link exists in the project files.',
    [],
  );
}

/** 03 MANUAL */
function crit03(leaves) {
  const clicks = leaves.filter((s) => /click/i.test(s.action)).length;
  return criterion(
    'scope-no-accidental-clicks',
    'No accidental clicks or exploratory detours remain',
    'Scope',
    'MANUAL',
    `The intent of a click cannot be inferred from the step table. Hint: ${clicks} Click-type leaf step(s).`,
    [],
  );
}

/** 04 MANUAL */
function crit04(leaves, orEntries) {
  const inScopeKeys = new Set();
  for (const step of leaves) {
    const page = parseReferencePage(step.reference);
    if (page && step.objectName) {
      inScopeKeys.add(`${page}\0${step.objectName}`);
    }
  }

  const tally = {
    label: 0,
    text: 0,
    role: 0,
    placeholder: 0,
    css: 0,
    xpath: 0,
    chainedLocator: 0,
  };
  const known = new Set(Object.keys(tally));

  for (const e of orEntries) {
    const key = `${e.page}\0${e.elementName}`;
    if (!inScopeKeys.has(key)) continue;
    const t = e.type;
    if (known.has(t)) tally[t]++;
  }

  const parts = Object.entries(tally)
    .filter(([, n]) => n > 0)
    .map(([t, n]) => `${t}=${n}`);
  const hint =
    parts.length > 0
      ? `Hint: OR locator types used by this test case: ${parts.join(', ')}.`
      : 'Hint: no in-scope OR locator types found for this test case.';

  return criterion(
    'selectors-user-facing',
    'User-facing / label / testID-based selectors only',
    'Selectors',
    'MANUAL',
    `Whether a selector is user-facing is a judgement call. ${hint}`,
    [],
  );
}

/** 05 MECHANICAL — OR reuse / duplicate selectors */
function crit05(leaves, orEntries) {
  // Index entries by type+value
  const bySig = new Map(); // sig -> entries[]
  for (const e of orEntries) {
    const sig = `${e.type}\0${e.value}`;
    if (!bySig.has(sig)) bySig.set(sig, []);
    bySig.get(sig).push(e);
  }

  // Duplicate pairs: same type+value, different (page, elementName)
  const allDupPairs = [];
  for (const [, group] of bySig) {
    // Unique by page+elementName
    const uniq = [];
    const seen = new Set();
    for (const e of group) {
      const k = `${e.page}\0${e.elementName}`;
      if (seen.has(k)) continue;
      seen.add(k);
      uniq.push(e);
    }
    if (uniq.length < 2) continue;
    for (let i = 0; i < uniq.length; i++) {
      for (let j = i + 1; j < uniq.length; j++) {
        allDupPairs.push([uniq[i], uniq[j]]);
      }
    }
  }

  const inScopeKeys = new Set();
  for (const step of leaves) {
    const page = parseReferencePage(step.reference);
    if (page && step.objectName) {
      inScopeKeys.add(`${page}\0${step.objectName}`);
    }
  }

  const inScopePairs = allDupPairs.filter(([a, b]) => {
    const ka = `${a.page}\0${a.elementName}`;
    const kb = `${b.page}\0${b.elementName}`;
    return inScopeKeys.has(ka) || inScopeKeys.has(kb);
  });

  const locations = [];
  const pairDescs = [];
  for (const [a, b] of inScopePairs) {
    locations.push(`${a.file}:${a.line}`, `${b.file}:${b.line}`);
    pairDescs.push(
      `${a.page}.${a.elementName} == ${b.page}.${b.elementName} (${a.type}=${a.value})`,
    );
  }

  // Nothing to compare is not "nothing wrong". Either no object repository was readable at
  // all — INGenious's own Projects/CLIDemo keeps its objects in the old binary IOR.object,
  // which this parser cannot see — or no leaf step names a page, so the in-scope set this
  // criterion filters against is empty and the filter decided nothing.
  if (orEntries.length === 0) {
    return criterion(
      'selectors-or-reuse',
      'Reuses shared object-repository entries where one exists',
      'Selectors',
      UNMEASURED,
      'NOT MEASURED: no ObjectRepository entry was readable under this project '
      + '(none present, or the objects are in the old binary IOR.object format). '
      + 'No selector was compared with any other.',
      [],
    );
  }
  if (inScopeKeys.size === 0) {
    return criterion(
      'selectors-or-reuse',
      'Reuses shared object-repository entries where one exists',
      'Selectors',
      UNMEASURED,
      `NOT MEASURED: ${orEntries.length} object(s) were read, but no leaf step of this test `
      + 'case names a page in its Reference cell, so not one of them could be tied to this '
      + `test case. The ${allDupPairs.length} duplicate pair(s) found project-wide were `
      + 'therefore judged against an empty in-scope set.',
      [],
    );
  }

  let evidence =
    `${allDupPairs.length} duplicate selector pair(s) project-wide, ` +
    `${inScopePairs.length} involving elements used by this test case`;
  if (pairDescs.length) {
    evidence += ': ' + pairDescs.join('; ');
  }

  return criterion(
    'selectors-or-reuse',
    'Reuses shared object-repository entries where one exists',
    'Selectors',
    inScopePairs.length > 0 ? 'FAIL' : 'PASS',
    evidence,
    locations,
  );
}

/** 06 MANUAL */
function crit06(leaves, orEntries) {
  const inScopeKeys = new Set();
  for (const step of leaves) {
    const page = parseReferencePage(step.reference);
    if (page && step.objectName) {
      inScopeKeys.add(`${page}\0${step.objectName}`);
    }
  }

  const brittle = [];
  for (const e of orEntries) {
    const key = `${e.page}\0${e.elementName}`;
    if (!inScopeKeys.has(key)) continue;
    const v = e.value || '';
    // xpath positional index like [1], or coordinate-ish patterns
    const looksPositional = /\[\d+\]/.test(v);
    const looksCoord =
      /\b(x|y|top|left|width|height)\s*[:=]\s*\d+/i.test(v) ||
      /\(\s*\d+\s*,\s*\d+\s*\)/.test(v);
    if (looksPositional || looksCoord) {
      brittle.push(`${e.page}.${e.elementName} (${e.type}=${v})`);
    }
  }

  const hint =
    brittle.length > 0
      ? `Hint: possibly brittle selectors: ${brittle.join('; ')}.`
      : 'Hint: no positional-index or coordinate-like selectors found among in-scope OR entries.';

  return criterion(
    'selectors-no-brittle',
    'No raw coordinates or brittle DOM index-based selectors',
    'Selectors',
    'MANUAL',
    `Brittleness depends on the DOM stability of the app, not on the selector string alone. ${hint}`,
    [],
  );
}

/** 07 MECHANICAL — at least one assertion */
function crit07(leaves) {
  const assertions = leaves.filter((s) => {
    const a = (s.action || '').trim().toLowerCase();
    return a.startsWith('assert') || a.startsWith('verify');
  });

  const evidenceParts = assertions.map(
    (s) => `${s.action} (${s.objectName || '—'})`,
  );
  const evidence =
    assertions.length === 0
      ? 'No assertion or verify steps found.'
      : `${assertions.length} assertion(s): ${evidenceParts.join('; ')}`;

  const locations = assertions.map((s) => `${s.file}:${s.line}`);

  return criterion(
    'assertions-present',
    'At least one assertion per business-meaningful state change',
    'Assertions',
    assertions.length >= 1 ? 'PASS' : 'FAIL',
    evidence,
    locations,
  );
}

/** 08 MANUAL */
function crit08(leaves) {
  const existence = leaves.filter((s) =>
    EXISTENCE_ASSERTIONS.has((s.action || '').trim().toLowerCase()),
  );
  const list =
    existence.length === 0
      ? 'none'
      : existence.map((s) => `${s.action} (${s.objectName || '—'})`).join('; ');

  return criterion(
    'assertions-business-outcome',
    'Assertions check business outcomes, not just element exists',
    'Assertions',
    'MANUAL',
    `Whether an assertion encodes a business outcome is a human judgement. Hint: existence-only assertions found: ${list}.`,
    [],
  );
}

/** 09 MANUAL */
function crit09() {
  return criterion(
    'assertions-negative-case',
    'Negative/error case covered if ADO acceptance criteria describe one',
    'Assertions',
    'MANUAL',
    'Depends on the ADO acceptance criteria, which are not in the project tree.',
    [],
  );
}

/** 10 MANUAL */
function crit10() {
  return criterion(
    'assertions-no-weakening',
    'HARD GATE: no assertion removed or weakened vs. the recording without a named human sign-off',
    'Assertions',
    'MANUAL',
    'Requires the original recording plus a named human sign-off; neither is in the project tree.',
    [],
  );
}

/**
 * Collect data-sheet rows consumed by this test case for a given sheet input.
 * Matches (Scenario, Flow) to (scenario, testcase) or component (group, name).
 */
function matchingSheetRows(sheet, scenario, testcase, components, leaves, projectRoot) {
  const sheetPath = path.join(projectRoot, 'TestData', `${sheet}.csv`);
  const data = readDataSheet(sheetPath);
  if (!data) return [];

  const flows = new Set();
  flows.add(`${scenario}\0${testcase}`);
  for (const c of components) {
    flows.add(`${c.group}\0${c.name}`);
  }
  // Also match component paths from leaves that used this sheet
  for (const leaf of leaves) {
    for (const key of leaf.componentPath || []) {
      const [g, ...rest] = key.split(':');
      flows.add(`${g}\0${rest.join(':')}`);
    }
  }

  return data.rows.filter((r) => {
    const sc = (r.map.Scenario ?? r.map.scenario ?? '').trim();
    const fl = (r.map.Flow ?? r.map.flow ?? '').trim();
    return flows.has(`${sc}\0${fl}`);
  });
}

/** 11 MECHANICAL — no hardcoded secrets */
function crit11(leaves, components, scenario, testcase, projectRoot) {
  const findings = [];
  // Every string this criterion actually looked at. Zero of them means the verdict below is
  // about nothing at all, and must not read as a clean bill of health.
  let scanned = 0;

  // (a) leaf step Input (literal only) and Condition
  for (const step of leaves) {
    const loc = `${step.file}:${step.line}`;
    const cls = classifyInput(step.input, projectRoot);
    if (cls.kind === 'literal') {
      const key = step.objectName || step.description || '';
      scanned++;
      scanForSecrets(
        // cls.value, not step.input: an account number written "@1234567890" is the literal
        // 1234567890 to the engine, and scanning the raw cell would still see it — but the
        // masked evidence line has to quote what the engine uses, not the escape.
        cls.value,
        key,
        `step input ${step.action} (${step.objectName || '—'})`,
        loc,
        findings,
      );
    }
    if (step.condition && String(step.condition).trim() !== '') {
      scanned++;
      const key = step.objectName || step.description || '';
      scanForSecrets(
        step.condition,
        key,
        `step condition ${step.action} (${step.objectName || '—'})`,
        loc,
        findings,
      );
    }
  }

  // (b) data-sheet cells for consumed rows
  const sheetNames = new Set();
  for (const step of leaves) {
    const cls = classifyInput(step.input, projectRoot);
    if (cls.kind === 'sheet' && cls.sheet) sheetNames.add(cls.sheet);
  }

  for (const sheet of sheetNames) {
    const sheetPath = path.join(projectRoot, 'TestData', `${sheet}.csv`);
    const rows = matchingSheetRows(
      sheet,
      scenario,
      testcase,
      components,
      leaves,
      projectRoot,
    );
    const data = readDataSheet(sheetPath);
    if (!data) continue;

    for (const row of rows) {
      const loc = `${relProject(projectRoot, sheetPath)}:${row.line}`;
      for (const col of data.headers) {
        const val = row.map[col];
        if (val == null || String(val).trim() === '') continue;
        // Skip structural columns
        if (/^(Scenario|Flow|Iteration|SubIteration)$/i.test(col)) continue;
        scanned++;
        scanForSecrets(
          String(val),
          col,
          `sheet ${sheet}.${col}`,
          loc,
          findings,
        );
      }
    }
  }

  // (c) userDefinedSettings.Properties — only keys referenced by percent-var inputs
  const propsPath = path.join(projectRoot, 'Settings', 'userDefinedSettings.Properties');
  const props = readProperties(propsPath);
  const neededKeys = new Set();
  for (const step of leaves) {
    const cls = classifyInput(step.input, projectRoot);
    if (cls.kind === 'variable') {
      // cls.value, not step.input — "@%Kontonummer%" is the variable %Kontonummer%, and
      // reading the raw cell here would leave the leading @ in place and match no key.
      const k = percentVarKey(cls.value);
      if (k) neededKeys.add(k);
    }
  }

  const propsRel = relProject(projectRoot, propsPath);
  for (const key of neededKeys) {
    if (!(key in props)) continue;
    // Properties file has no stable per-key line easily without re-scan; use line 1 fallback
    // by scanning the file for the key line.
    let lineNo = 1;
    try {
      const text = fs.readFileSync(propsPath, 'utf8');
      const lines = text.split(/\r?\n/);
      for (let i = 0; i < lines.length; i++) {
        if (lines[i].trim().startsWith(key + '=')) {
          lineNo = i + 1;
          break;
        }
      }
    } catch {
      /* keep 1 */
    }
    scanned++;
    scanForSecrets(
      props[key],
      key,
      `settings ${key}`,
      `${propsRel}:${lineNo}`,
      findings,
    );
  }

  // Dedupe identical findings
  const seen = new Set();
  const unique = [];
  for (const f of findings) {
    const k = `${f.kind}\0${f.literal}\0${f.where}\0${f.loc}`;
    if (seen.has(k)) continue;
    seen.add(k);
    unique.push(f);
  }

  // No literal input, no condition, no consumed sheet cell, no referenced settings key: the
  // three surfaces this criterion knows about were all empty, so it found nothing because it
  // read nothing. Saying "no secrets found" there is a claim about a file it never opened.
  if (scanned === 0) {
    return criterion(
      'data-no-hardcoded',
      'No hardcoded customer/account numbers or secrets',
      'Data',
      UNMEASURED,
      `NOT MEASURED: no scannable value exists on any of the three surfaces `
      + `(${leaves.length} leaf step(s) carry no literal input and no condition; no data `
      + 'sheet is consumed; no referenced settings key is set). Nothing was scanned.',
      [],
    );
  }

  const evidence =
    unique.length === 0
      ? `No hardcoded customer/account numbers or secrets found in ${scanned} scanned value(s).`
      : unique
          .map((f) => `${f.kind}: ${maskLiteral(f.literal)} (${f.where})`)
          .join('; ');

  const locations = unique.map((f) => f.loc);

  return criterion(
    'data-no-hardcoded',
    'No hardcoded customer/account numbers or secrets',
    'Data',
    unique.length > 0 ? 'FAIL' : 'PASS',
    evidence,
    locations,
  );
}

/** 12 MECHANICAL — parameterized inputs */
function crit12(leaves, projectRoot) {
  const considered = [];
  for (const step of leaves) {
    const cls = classifyInput(step.input, projectRoot);
    if (cls.kind === 'variable' || cls.kind === 'sheet' || cls.kind === 'literal') {
      considered.push({ step, cls });
    }
  }

  if (considered.length === 0) {
    const withInput = leaves.filter((s) => String(s.input ?? '').trim() !== '').length;
    return criterion(
      'data-parameterized',
      'Test data parameterized via data sheet / variables (no inline literals)',
      'Data',
      UNMEASURED,
      `NOT MEASURED: none of the ${leaves.length} leaf step(s) carries a data-bearing input `
      + `(${withInput} step(s) have a non-empty Input cell). There was no population to take `
      + 'a percentage of.',
      [],
    );
  }

  const parameterized = considered.filter(
    (c) => c.cls.kind === 'variable' || c.cls.kind === 'sheet',
  );
  const literals = considered.filter((c) => c.cls.kind === 'literal');
  const pct = Math.round((parameterized.length / considered.length) * 100);

  // The denominator is named against the number of non-empty Input cells on disk, because a
  // ratio whose denominator is a filtered subset is the exact shape of the claim this whole
  // tool exists to catch: 22/24 (92%) read as an audit of the test case when 26 further
  // inputs had been dropped before the count began.
  const withInput = leaves.filter((s) => String(s.input ?? '').trim() !== '').length;
  let evidence = `${parameterized.length}/${considered.length} data-bearing inputs parameterized (${pct}%)`
    + `; ${considered.length} of ${withInput} non-empty Input cell(s) are data-bearing`;
  if (literals.length > 0) {
    const offenders = literals.map(
      (c) =>
        `${c.step.action} (${c.step.objectName || '—'}) input=${maskLiteral(c.cls.value)}`,
    );
    evidence += '; offenders: ' + offenders.join('; ');
  }

  return criterion(
    'data-parameterized',
    'Test data parameterized via data sheet / variables (no inline literals)',
    'Data',
    literals.length === 0 ? 'PASS' : 'FAIL',
    evidence,
    literals.map((c) => `${c.step.file}:${c.step.line}`),
  );
}

/** 13 MECHANICAL or MANUAL — green twice */
function crit13(scenario, testcase, runPaths) {
  const name = `${scenario}:${testcase}`;

  if (runPaths.length < 2) {
    return criterion(
      'repeatability-green-twice',
      'Runs green twice in a row, headless, via CLI',
      'Repeatability',
      'MANUAL',
      `Fewer than two parsed run results supplied (${runPaths.length} given); pass --run twice to decide mechanically.`,
      [],
    );
  }

  const statuses = [];
  const locations = [];
  let allPass = true;

  for (const runPath of runPaths) {
    let doc;
    try {
      const text = fs.readFileSync(path.resolve(runPath), 'utf8');
      doc = JSON.parse(text);
    } catch (err) {
      allPass = false;
      statuses.push(`unreadable(${runPath}): ${err.message}`);
      continue;
    }

    const runDir = doc.runDir != null ? String(doc.runDir) : runPath;
    locations.push(runDir);

    const cases = Array.isArray(doc.testCases) ? doc.testCases : [];
    const entry = cases.find((tc) => tc && tc.name === name);
    if (!entry) {
      allPass = false;
      statuses.push(`${runDir}=ABSENT`);
    } else {
      const st = entry.status;
      statuses.push(`${runDir}=${st}`);
      if (st !== 'PASS') allPass = false;
    }
  }

  return criterion(
    'repeatability-green-twice',
    'Runs green twice in a row, headless, via CLI',
    'Repeatability',
    allPass ? 'PASS' : 'FAIL',
    `Run results for ${name}: ${statuses.join('; ')}`,
    locations,
  );
}

/** 14 MANUAL */
function crit14() {
  return criterion(
    'repeatability-dirty-marking',
    'Leaves no dirty state, or explicitly marks its claimed pool entry dirty/used',
    'Repeatability',
    'MANUAL',
    'Pool claim/release happens outside the test case definition (see tools/claim-data.mjs).',
    [],
  );
}

// ---------------------------------------------------------------------------
// Console output
// ---------------------------------------------------------------------------

/** Fixed-width verdict tag for aligned columns. */
function verdictTag(verdict) {
  // PASS / FAIL / MANUAL / UNMEAS — pad to 6 chars inside brackets: [PASS  ] [UNMEAS]
  const v = String(verdict);
  return `[${v.padEnd(6)}]`;
}

/** Print the readable console summary. */
function printConsoleSummary(doc) {
  const lines = [];
  lines.push(`Curation gate: ${doc.testcase}`);
  lines.push(`Project: ${doc.project}`);
  lines.push('');

  doc.criteria.forEach((c, idx) => {
    const n = String(idx + 1).padStart(2, '0');
    lines.push(`${verdictTag(c.verdict)} ${n} ${c.id} - ${c.title}`);
    lines.push(`         ${c.evidence}`);
    for (const loc of c.locations || []) {
      lines.push(`         at ${loc}`);
    }
  });

  lines.push('');
  const { pass, fail, manual, unmeasured } = doc.summary;
  lines.push(
    `Summary: ${pass} PASS - ${fail} FAIL - ${manual} MANUAL - ${unmeasured} ${UNMEASURED}`,
  );

  if (fail > 0) {
    const failing = doc.criteria.filter((c) => c.verdict === 'FAIL').map((c) => c.id);
    lines.push(`FAILED: ${failing.join(', ')}`);
  }
  if (unmeasured > 0) {
    const un = doc.criteria.filter((c) => c.verdict === UNMEASURED).map((c) => c.id);
    lines.push(`NOT MEASURED: ${un.join(', ')} — this is not a pass, nothing was checked there.`);
  }

  // The warnings used to go to stderr and stop there — off the summary, out of the JSON and
  // out of the exit code — while every criterion above had silently judged a smaller test
  // case than the one on disk. They belong on the balance sheet.
  if (doc.warnings.length > 0) {
    lines.push('');
    lines.push(
      `INCOMPLETE: ${doc.warnings.length} part(s) of this test case could not be read, so `
      + 'every criterion above was decided on LESS than what is on disk:',
    );
    for (const w of doc.warnings) lines.push(`  - ${w}`);
  }

  console.log(lines.join('\n'));
}

// ---------------------------------------------------------------------------
// main
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// selftest — the component-scope counter-examples, offline
// ---------------------------------------------------------------------------

/**
 * Resolve every case in tools/fixtures/reusable-scope and assert the leaf counts.
 *
 * Each row is a shape that a Baustein call really takes on disk. Four of them resolved to
 * nothing before the scope-aware resolver existed, and one of them resolved when it must
 * not — see the fixture's README for which is which.
 */
function runSelftest() {
  const fixture = path.resolve(
    path.dirname(fileURLToPath(import.meta.url)),
    'fixtures', 'reusable-scope', 'Projects', 'ScopeProbe',
  );

  const cases = [
    { tc: 'Unscoped',                  leaves: 3, components: ['Kundensuche:PartnerOeffnen'], warnings: 0 },
    { tc: 'Projektweit',               leaves: 3, components: ['Kundensuche:PartnerOeffnen'], warnings: 0 },
    { tc: 'Gemeinsam',                 leaves: 2, components: ['Gemeinsam:Anmelden'],       warnings: 0 },
    { tc: 'Gemeinsam ueber Reference', leaves: 2, components: ['Gemeinsam:Anmelden'],       warnings: 0 },
    { tc: 'Verschachtelt',             leaves: 4,
      components: ['Kundensuche:KundeOeffnen', 'Kundensuche:PartnerOeffnen'],                   warnings: 0 },
    { tc: 'Kein Execute',              leaves: 1, components: [],                           warnings: 0 },
    { tc: 'Nicht auffindbar',          leaves: 1, components: [],                           warnings: 1 },
  ];

  let failed = 0;
  for (const c of cases) {
    const { leaves, components, warnings } = resolveSteps(fixture, 'Probe', c.tc);
    const got = {
      leaves: leaves.length,
      components: components.map((x) => `${x.group}:${x.name}`),
      warnings: warnings.length,
    };
    const ok =
      got.leaves === c.leaves &&
      got.warnings === c.warnings &&
      got.components.join('|') === c.components.join('|');
    if (!ok) failed++;
    console.log(
      `${ok ? 'PASS' : 'FAIL'}  ${c.tc.padEnd(26)} `
      + `leaves=${got.leaves} (want ${c.leaves})  `
      + `components=[${got.components.join(', ')}] (want [${c.components.join(', ')}])  `
      + `warnings=${got.warnings} (want ${c.warnings})`,
    );
  }

  const extra = runVerdictSelftest(fixture);
  const total = cases.length + extra.total;
  failed += extra.failed;

  console.log(
    failed === 0
      ? `\nselftest: ${total} case(s) passed`
      : `\nselftest: ${failed} of ${total} case(s) FAILED`,
  );
  process.exit(failed === 0 ? 0 : 1);
}

/**
 * The counter-examples for the four things this tool used to claim without measuring.
 *
 * Every row below is red against the version before this change — that is the point of
 * writing them down. `@`-escaped inputs were filed under an opaque `fileref` kind that
 * criteria 11 and 12 both skipped, so a test case could carry 26 embedded values and be
 * reported as `no data-bearing inputs`, PASS, exit 0. And an empty surface — no object
 * repository, no scannable value, no data-bearing input — was reported as a PASS rather
 * than as the absence of a measurement.
 *
 * The criteria are called directly. They are pure functions of the leaf list, so no engine,
 * no project on disk and no network is needed for any of it.
 */
function runVerdictSelftest(fixture) {
  const rows = [];
  const check = (name, cond, detail) => rows.push({ name, ok: Boolean(cond), detail });

  const leaf = (over) => ({
    action: 'Fill', objectName: 'Feld', description: '', input: '', condition: '',
    reference: '', file: 'TestPlan/P/T.yaml', line: 1, componentPath: [], ...over,
  });
  const or = (over) => ({
    page: 'Seite', elementName: 'E', type: 'css', value: '#a',
    file: 'ObjectRepository/Web/Seite.yaml', line: 1, ...over,
  });

  // --- 1. the @ escape is not an opaque "fileref" -------------------------------------
  {
    const c = (s) => classifyInput(s, fixture);
    check('@ + text is the literal behind it',
      c('@Accounts Overview').kind === 'literal' && c('@Accounts Overview').value === 'Accounts Overview',
      JSON.stringify(c('@Accounts Overview')));
    check('@ + %var% is a runtime variable',
      c('@%NeueNummer%').kind === 'variable' && c('@%NeueNummer%').value === '%NeueNummer%',
      JSON.stringify(c('@%NeueNummer%')));
    // The escape exists precisely to stop the sheet lookup, so a fix that merely strips the
    // @ and re-runs the whole classifier would turn this into a data sheet reference and be
    // wrong in the other direction. TestData/Kunden.csv is present in the fixture.
    check('@ + Sheet:Column stays a literal — the escape blocks the lookup',
      c('@Kunden:Nummer').kind === 'literal' && c('@Kunden:Nummer').value === 'Kunden:Nummer',
      JSON.stringify(c('@Kunden:Nummer')));
    check('an unescaped Sheet:Column is still a data sheet reference',
      c('Kunden:Nummer').kind === 'sheet' && c('Kunden:Nummer').sheet === 'Kunden',
      JSON.stringify(c('Kunden:Nummer')));
  }

  // --- 2. criterion 12 counts the escaped inputs ----------------------------------------
  {
    const leaves = [
      leaf({ action: 'assertElementContainsText', input: '@Accounts Overview' }),
      leaf({ action: 'setViewPortSize', input: '@1600,1000', line: 2 }),
      leaf({ action: 'SelectSingleByText', input: '@%NeueNummer%', line: 3 }),
    ];
    const c12 = crit12(leaves, fixture);
    check('12 fails on @-escaped inline literals (was: skipped entirely)',
      c12.verdict === 'FAIL' && /1\/3/.test(c12.evidence), c12.verdict + ' — ' + c12.evidence);
    check('12 names the escaped literal without its @',
      /input=Acc\*+ew/.test(c12.evidence), c12.evidence);
  }

  // --- 3. criterion 11 sees an @-escaped account number ----------------------------------
  {
    const leaves = [leaf({ action: 'Fill', input: '@1234567890' })];
    const c11 = crit11(leaves, [], 'S', 'T', fixture);
    check('11 fails on an @-escaped account number (was: PASS)',
      c11.verdict === 'FAIL' && /digits/.test(c11.evidence), c11.verdict + ' — ' + c11.evidence);
  }

  // --- 4. an empty surface is UNMEAS, never PASS -----------------------------------------
  {
    const only = [leaf({ action: 'Click', input: '' })];
    check('12 over zero data-bearing inputs is not a pass',
      crit12(only, fixture).verdict === UNMEASURED, crit12(only, fixture).verdict);
    check('11 over zero scannable values is not a pass',
      crit11(only, [], 'S', 'T', fixture).verdict === UNMEASURED,
      crit11(only, [], 'S', 'T', fixture).verdict);
    check('05 over an unreadable object repository is not a pass',
      crit05(only, []).verdict === UNMEASURED, crit05(only, []).verdict);
    check('05 over objects none of which is in scope is not a pass',
      crit05(only, [or({}), or({ elementName: 'F' })]).verdict === UNMEASURED,
      crit05(only, [or({}), or({ elementName: 'F' })]).verdict);
    // and the measurement that IS real still decides normally
    const inScope = [leaf({ objectName: 'E', reference: '[Project] Seite' })];
    check('05 still FAILs on a real in-scope duplicate',
      crit05(inScope, [or({}), or({ elementName: 'F' })]).verdict === 'FAIL',
      crit05(inScope, [or({}), or({ elementName: 'F' })]).evidence);
  }

  // --- 5. warnings and UNMEAS reach the exit code ----------------------------------------
  {
    check('a clean run still exits 0',
      overallExit({ fail: 0, unmeasured: 0, warnings: 0 }) === 0);
    check('an unresolvable Baustein cannot end in exit 0 (was: exit 0)',
      overallExit({ fail: 0, unmeasured: 0, warnings: 1 }) === EXIT_UNMEASURABLE);
    check('an unmeasured criterion cannot end in exit 0 (was: exit 0)',
      overallExit({ fail: 0, unmeasured: 1, warnings: 0 }) === EXIT_UNMEASURABLE);
    check('a real FAIL still wins over both',
      overallExit({ fail: 1, unmeasured: 1, warnings: 1 }) === EXIT_FAIL);
  }

  let failed = 0;
  console.log('');
  for (const r of rows) {
    if (!r.ok) failed++;
    console.log(`${r.ok ? 'PASS' : 'FAIL'}  ${r.name}${r.ok || !r.detail ? '' : `  → got ${r.detail}`}`);
  }
  return { total: rows.length, failed };
}

function main() {
  const args = parseArgs(process.argv.slice(2));

  if (args.selftest) {
    runSelftest();
    return;
  }

  if (!args.project || !args.scenario || !args.testcase) {
    die(USAGE);
  }

  const projectRoot = path.resolve(args.project);
  let st;
  try {
    st = fs.statSync(projectRoot);
  } catch {
    die(`Error: project dir not found: ${args.project}`);
  }
  if (!st.isDirectory()) {
    die(`Error: project dir not found: ${args.project}`);
  }

  const scenario = args.scenario;
  const testcase = args.testcase;

  const { leaves, components, warnings, testCaseFile } = resolveSteps(
    projectRoot,
    scenario,
    testcase,
  );

  const orEntries = loadObjectRepository(projectRoot);

  const criteria = [
    crit01(leaves, components),
    crit02(),
    crit03(leaves),
    crit04(leaves, orEntries),
    crit05(leaves, orEntries),
    crit06(leaves, orEntries),
    crit07(leaves),
    crit08(leaves),
    crit09(),
    crit10(),
    crit11(leaves, components, scenario, testcase, projectRoot),
    crit12(leaves, projectRoot),
    crit13(scenario, testcase, args.run),
    crit14(),
  ];

  // Warnings are not one of the 14 rows, but they must not be silent either: an Execute step
  // whose component nobody can find used to be counted as an ordinary leaf step, and that is
  // exactly the kind of quiet wrong answer this tool exists to prevent.
  for (const w of warnings) {
    console.error(`WARN: ${w}`);
  }

  const summary = {
    pass: criteria.filter((c) => c.verdict === 'PASS').length,
    fail: criteria.filter((c) => c.verdict === 'FAIL').length,
    manual: criteria.filter((c) => c.verdict === 'MANUAL').length,
    unmeasured: criteria.filter((c) => c.verdict === UNMEASURED).length,
    warnings: warnings.length,
  };

  const doc = {
    testcase: `${scenario}:${testcase}`,
    project: projectRoot,
    testCaseFile: relProject(projectRoot, testCaseFile),
    generatedAt: new Date().toISOString(),
    criteria,
    warnings,
    summary,
  };

  printConsoleSummary(doc);

  if (args.json) {
    const outPath = path.resolve(args.json);
    fs.mkdirSync(path.dirname(outPath), { recursive: true });
    fs.writeFileSync(outPath, JSON.stringify(doc, null, 2) + '\n', 'utf8');
    console.log(`Wrote ${outPath}`);
  }

  process.exit(overallExit({
    fail: summary.fail,
    unmeasured: summary.unmeasured,
    warnings: warnings.length,
  }));
}

main();
