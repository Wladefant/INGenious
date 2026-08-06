#!/usr/bin/env node
/**
 * review-recording.mjs — reads a freshly recorded INGenious test case and writes a
 * REVIEW PROPOSAL for a human. It never edits the project.
 *
 * WHY THIS EXISTS
 * ---------------
 * The INGenious Studio recorder writes a step list that is complete and unreadable.
 * Three facts about it are not opinions, they are in the source of the build that runs
 * on the laptop (INGenious/, release/3.1.0):
 *
 *   1. Every recorded step gets an EMPTY description.
 *      IDE/src/main/java/com/ing/ide/main/playwrightrecording/LiveRecordingParser.java:189
 *          step.setDescription("");
 *      A recording therefore arrives with 40 rows and 0 words of intent.
 *
 *   2. Objects the recorder cannot name become "Refactor_Object", "Refactor_Object_1", …
 *      PlaywrightRecordingParser.java:82 resolveUniqueObjectName / :595 :641
 *      The name carries no meaning, so neither does the step that uses it.
 *
 *   3. The recorder fills whichever locator attribute Playwright happened to emit —
 *      Role, xpath, Text, css, Label, AltText (PlaywrightRecordingParser.java:377-383).
 *      The engine then uses only the FIRST non-empty one in WebOR.OBJECT_PROPS order
 *      and silently ignores the rest.
 *
 * So a raw recording is: no names, no assertions, literal test data baked into the steps,
 * and locators nobody has judged. This tool says so, step by step, in German, and proposes
 * a concrete replacement for each finding.
 *
 * WHAT IT IS NOT
 * --------------
 * It is DETERMINISTIC. No model call, no network. Same input, same output, byte for byte.
 * That is deliberate:
 *   - the recording is ING test material and must not leave the machine;
 *   - a demo in front of colleagues has to produce the same page twice;
 *   - every proposal has to be traceable to a rule an engineer can argue with, and a rule
 *     you can read is a rule you can overrule.
 * It names WHAT HAPPENED. It cannot know WHY, so wherever intent is required — an expected
 * text, a business assertion — it writes a placeholder and says a human must fill it.
 *
 * IT NEVER WRITES INTO THE PROJECT. There is no --apply flag and there will not be one.
 * A tool that edits a test and reports success is the exact failure this project spent a
 * week removing. The output is a proposal; a person accepts it in Studio.
 *
 * WHAT IT DELIBERATELY DOES NOT DECIDE
 * ------------------------------------
 * Selector UNIQUENESS. Uniqueness is a property of (selector, page state), not of a file,
 * and no static reading can decide it. tools/selector-uniqueness.mjs already answers it by
 * opening the real page. This tool prints the exact command to run and reports the question
 * as OPEN — never as passed.
 *
 * Node built-ins only. Runs on the work laptop's Node with no install step.
 */

import { readFileSync, readdirSync, existsSync, statSync, writeFileSync, mkdirSync } from 'node:fs';
import { join, resolve, basename, dirname, relative } from 'node:path';

// ---------------------------------------------------------------------------
// Exit contract — mirrors tools/ingenious-run.mjs and tools/selector-uniqueness.mjs.
// ---------------------------------------------------------------------------
const EXIT = {
  NOTHING_TO_PROPOSE: 0, // the case was read and there is nothing to improve
  PROPOSALS: 1, // findings were written — the normal outcome for a fresh recording
  CANNOT_TELL: 2, // the case or the object repository could not be read
  ARGS_REJECTED: 3,
};

const USAGE = `
review-recording.mjs — turn a raw INGenious recording into a review proposal

  node tools/review-recording.mjs --project <dir> --case <TestPlan/Scn/Case.yaml> [options]
  node tools/review-recording.mjs --selftest

  --project <dir>   INGenious project root (the folder holding ObjectRepository/ and TestPlan/)
  --case <path>     the recorded test case, relative to the project or absolute
  --out <dir>       also write <Case>.review.md there (default: print only)
  --json <file>     also write the findings as JSON
  --quiet           suppress the report on stdout

Exit: 0 nothing to propose · 1 proposals written · 2 could not read the input
      3 arguments rejected

There is no --apply. The tool proposes; you accept it in Studio.
`.trim();

// The order the engine resolves attributes in — WebOR.OBJECT_PROPS. getElementsInternal()
// takes the FIRST non-empty attribute and breaks, so every later attribute is dead weight.
// Same list as tools/selector-uniqueness.mjs, for the same reason.
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

// ---------------------------------------------------------------------------
// Arguments
// ---------------------------------------------------------------------------
function parseArgs(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    const next = () => argv[++i];
    switch (a) {
      case '--project': out.project = next(); break;
      case '--case': out.testCase = next(); break;
      case '--out': out.out = next(); break;
      case '--json': out.json = next(); break;
      case '--quiet': out.quiet = true; break;
      case '--selftest': out.selftest = true; break;
      case '-h': case '--help': out.help = true; break;
      default: out.unknown = a;
    }
  }
  return out;
}

// ---------------------------------------------------------------------------
// YAML — two fixed, shallow shapes. A general parser would be more code and no more
// correct for these, and it would be a dependency this laptop cannot install.
// ---------------------------------------------------------------------------

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

/**
 * Parse an INGenious test case YAML (TestCaseYaml/StepYaml, schemaVersion 1):
 *   schemaVersion: 1
 *   testCase: <name>
 *   scenario: <name>
 *   steps:
 *     - step: 1
 *       object: "…"
 *       description: "…"
 *       action: "…"
 *       input: "…"
 *       condition: "…"
 *       reference: "…"
 */
function parseTestCaseYaml(file) {
  const lines = readFileSync(file, 'utf8').split(/\r?\n/);
  const tc = { name: basename(file).replace(/\.ya?ml$/i, ''), scenario: '', steps: [] };
  let inSteps = false;
  let current = null;

  for (const line of lines) {
    if (!line.trim() || /^\s*#/.test(line)) continue;
    const indent = line.length - line.trimStart().length;

    if (indent === 0) {
      inSteps = false;
      current = null;
      const m = /^([A-Za-z_][\w-]*)\s*:\s*(.*)$/.exec(line);
      if (!m) continue;
      if ((m[1] === 'testCase' || m[1] === 'name' || m[1] === 'reusable') && m[2].trim()) {
        tc.name = unquote(m[2]);
      }
      if (m[1] === 'scenario' && m[2].trim()) tc.scenario = unquote(m[2]);
      if (m[1] === 'steps') inSteps = true;
      continue;
    }
    if (!inSteps) continue;

    const item = /^\s*-\s*(.*)$/.exec(line);
    if (item) {
      current = { step: null, object: '', description: '', action: '', input: '', condition: '', reference: '' };
      tc.steps.push(current);
      if (item[1].trim()) assignField(current, item[1]);
      continue;
    }
    if (current) assignField(current, line);
  }
  return tc;
}

function assignField(step, raw) {
  const m = /^\s*([A-Za-z_][\w-]*)\s*:\s*(.*)$/.exec(raw);
  if (!m) return;
  const key = m[1];
  const val = unquote(m[2]);
  if (key === 'step') step.step = Number(val) || null;
  else if (key in step) step[key] = val;
}

/** Parse an INGenious Object Repository page YAML. */
function parseOrYaml(file) {
  const lines = readFileSync(file, 'utf8').split(/\r?\n/);
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
      current = { name: unquote(key), attrs: {}, frame: '', page: pageName, file, line: i + 1 };
      elements.push(current);
    } else if (current && val.trim()) {
      const k = key.toLowerCase();
      if (k === 'frame') current.frame = unquote(val);
      else if (YAML_TO_PROP[k]) current.attrs[YAML_TO_PROP[k]] = unquote(val);
    }
  }
  return { page: pageName, elements };
}

function loadObjectRepository(projectRoot) {
  const dir = join(projectRoot, 'ObjectRepository', 'Web');
  if (!existsSync(dir) || !statSync(dir).isDirectory()) return null;
  const byName = new Map();
  const pages = [];
  for (const f of readdirSync(dir)) {
    if (!/\.ya?ml$/i.test(f)) continue;
    const p = parseOrYaml(join(dir, f));
    pages.push(p);
    for (const el of p.elements) if (!byName.has(el.name)) byName.set(el.name, el);
  }
  return { pages, byName };
}

// ---------------------------------------------------------------------------
// Vocabulary
// ---------------------------------------------------------------------------

/** Objects the recorder could not name. PlaywrightRecordingParser resolveUniqueObjectName. */
const UNNAMED = /^Refactor_Object(_\d+)?$/;

/** Buttons/links that commit something. A click on one of these changes state. */
const COMMITTING =
  /(speichern|save|submit|eintragen|anlegen|ausf(ü|ue)hren|execute|weiterleiten|weiter|suchen|search|(ü|ue)bernehmen|l(ö|oe)schen|delete|(ä|ae)ndern|anmelden|login|best(ä|ae)tigen|confirm|pr(ü|ue)fen|senden|send|absenden|ok|ja)/i;

const ASSERT_ACTION = /^(assert|verify)/i;
const WAIT_ACTION = /^wait/i;

/** Actions that write into the application. */
const MUTATING_ACTION = /^(click|doubleclick|rightclick|fill|type|check|uncheck|select|press|clear|set)/i;

// ---------------------------------------------------------------------------
// Check 1 — Benennung. Every recorded step has description "".
// ---------------------------------------------------------------------------

function humanObject(name) {
  if (!name) return '';
  if (UNNAMED.test(name)) return null; // deliberately unnameable — see check 1b
  return name
    .replace(/[_\-]+/g, ' ')
    .replace(/([a-zäöüß])([A-ZÄÖÜ])/g, '$1 $2')
    .trim();
}

function proposeDescription(step) {
  const obj = humanObject(step.object);
  const action = (step.action || '').trim();
  const input = (step.input || '').trim();
  const literal = input.startsWith('@') ? input.slice(1) : input;

  if (/^open$/i.test(action)) return `Die Anwendung öffnen: ${literal || '<Adresse>'}`;
  if (obj === null) return null; // cannot name a step whose object has no name

  if (/^fill$/i.test(action) || /^type/i.test(action)) {
    return `Feld „${obj}" ausfüllen mit „${literal || '<Wert>'}"`;
  }
  if (/^selectsingleby/i.test(action) || /^select/i.test(action)) {
    return `In „${obj}" den Eintrag „${literal || '<Wert>'}" auswählen`;
  }
  if (/^check$/i.test(action)) return `Kontrollkästchen „${obj}" ankreuzen`;
  if (/^uncheck$/i.test(action)) return `Haken bei „${obj}" entfernen`;
  if (/^doubleclick$/i.test(action)) return `„${obj}" doppelt anklicken`;
  if (/^click$/i.test(action)) return `„${obj}" anklicken`;
  if (WAIT_ACTION.test(action)) return `Warten, bis „${obj}" sichtbar ist`;
  if (/^assertelementcontainstext$/i.test(action)) {
    return `Prüfen: „${obj}" enthält den Text „${literal || '<erwarteter Text>'}"`;
  }
  if (ASSERT_ACTION.test(action)) return `Prüfen: „${obj}" ist sichtbar`;
  if (/^setviewportsize$/i.test(action)) return `Fenstergröße auf ${literal} setzen`;
  return null; // unknown action — a human names it, the tool does not guess
}

// ---------------------------------------------------------------------------
// Check 3 — statically decidable selector risk.
// Uniqueness is NOT decided here. See the header.
// ---------------------------------------------------------------------------

const RISK_RULES = [
  {
    id: 'POSITION',
    title: 'Positionsabhängiger Selektor',
    test: (v) =>
      /nth-child|nth-of-type|:first|:last|first-child|last-child|\[\s*\d+\s*\]|\bclass\s*=\s*["']?(even|odd)["']?|\.even\b|\.odd\b/i.test(v),
    why:
      'Der Selektor trifft eine Zeile/Position, nicht einen Inhalt. Sobald die Trefferliste ' +
      'anders sortiert ist oder eine Zeile dazukommt, trifft er einen anderen Datensatz — und ' +
      'der Test läuft grün auf dem falschen Kunden.',
    fix:
      'An den Inhalt anheften statt an die Position, z. B. die Zeile über die gesuchte Nummer ' +
      'oder den Namen auswählen (`:text-is(\'…\')`) oder ein eindeutiges Attribut der Zeile nutzen.',
  },
  {
    id: 'TEXT',
    title: 'Selektor hängt am sichtbaren deutschen Text',
    test: (v) => /contains\(\s*text\(\)|text\(\)\s*=|:text-is\(|:has-text\(/i.test(v),
    attrTest: (prop) => prop === 'Text',
    why:
      'Die Oberfläche ist deutsch. Wird die Beschriftung umbenannt, ein Leerzeichen ergänzt oder ' +
      'die Oberfläche zweisprachig, findet der Selektor nichts mehr. Das ist der häufigste ' +
      'Bruchgrund im bestehenden Testbestand.',
    fix:
      'Auf ein stabiles Attribut wechseln (`name`, `wicket-path`, `id` sofern nicht generiert) ' +
      'oder Rolle+Name verwenden.',
  },
  {
    id: 'GENERATED_ID',
    // Wicket ids are `id` + a short hex counter: id10, id1f, id2. Requiring at least one
    // digit keeps real words ("#idea", "#identifier") out.
    title: 'Server-generierte ID',
    test: (v) => /(#|\bid\s*=\s*["']?|@id\s*=\s*['"])id[0-9a-f]*\d[0-9a-f]*\b/i.test(v),
    why:
      'Wicket vergibt IDs wie `id10` / `id1f` beim Rendern. Sie ändern sich mit jedem Deployment ' +
      'und teilweise mit der Seitenreihenfolge.',
    fix: 'Stattdessen den Wicket-`name`- bzw. `wicket-path`-Pfad verwenden, der fachlich benannt ist.',
  },
  {
    id: 'XPATH_TREE',
    title: 'XPath über den Dokumentbaum',
    test: (v) => /^\s*(xpath=)?\/\/?[a-z]+(\[\d+\])?\/[a-z]+/i.test(v) && /\/(tbody|tr|td|div|table)\[/i.test(v),
    why: 'Ein Pfad durch den Seitenbaum bricht bei jeder Layout-Änderung, auch wenn das Feld selbst gleich bleibt.',
    fix: 'Das Element direkt über sein Attribut ansprechen statt über den Weg dorthin.',
  },
];

function assessObject(el) {
  const present = OBJECT_PROPS.filter((p) => (el.attrs[p] || '').trim() !== '');
  const effective = present[0] || null;
  const shadowed = present.slice(1);
  const risks = [];

  if (effective) {
    const value = el.attrs[effective];
    for (const rule of RISK_RULES) {
      const hit = (rule.attrTest && rule.attrTest(effective)) || rule.test(value);
      if (hit) risks.push({ id: rule.id, title: rule.title, why: rule.why, fix: rule.fix });
    }
  }
  return { effective, shadowed, risks, value: effective ? el.attrs[effective] : null };
}

// ---------------------------------------------------------------------------
// Check 4 — data baked into the steps.
// ---------------------------------------------------------------------------

// Ordered most-specific first. The last entry is deliberately vague: from the value alone
// a bare digit run cannot be told apart — an account number, an access number and a phone
// number all look the same. The tool says that instead of picking one.
const DATA_SHAPES = [
  { id: 'IBAN', title: 'IBAN', test: (v) => /^[A-Z]{2}\d{2}[A-Z0-9]{10,30}$/.test(v.replace(/\s/g, '')) },
  { id: 'EMAIL', title: 'E-Mail-Adresse', test: (v) => /^[^@\s]+@[^@\s]+\.[A-Za-z]{2,}$/.test(v) },
  { id: 'DATUM', title: 'Datum', test: (v) => /^\d{1,2}\.\d{1,2}\.\d{2,4}$/.test(v) },
  { id: 'BETRAG', title: 'Betrag', test: (v) => /^-?\d{1,3}(\.\d{3})*,\d{2}$/.test(v) || /^-?\d+[.,]\d{2}$/.test(v) },
  { id: 'PLZ', title: 'Postleitzahl', test: (v) => /^\d{5}$/.test(v) },
  {
    id: 'TELEFON',
    title: 'Rufnummer',
    test: (v) => /^[\d\s/+()-]{7,}$/.test(v) && /[\s/+()-]/.test(v) && /\d{5,}/.test(v.replace(/\D/g, '')),
  },
  { id: 'NUMMER', title: 'mehrstellige Zahl — Konto-, Zugangs- oder Rufnummer', test: (v) => /^\d{6,}$/.test(v) },
];

/**
 * The engine reads Input of the form `Sheet:Column` (or `{Sheet:Column}`) as a data-sheet
 * lookup unless it is escaped with a leading `@`.
 *   Engine/…/execution/data/DataProcessor.java:47 isInputPatternDataSheet
 *   Engine/…/execution/run/TestCaseRunner.java:324
 * A recorded literal that happens to contain a colon — "Hauptstr.:12", "10:30" — is therefore
 * silently turned into a lookup that fails at runtime. That is a real trap, and static.
 */
function looksLikeDataSheetRef(input) {
  if (!input || input.startsWith('@')) return false;
  return /^[A-Za-z].*:[A-Za-z].*$/.test(input) || /^\{[^}\d:][^}:]*:[^}\d:][^}:]*\}$/.test(input);
}

/** The sheet a `Sheet:Column` reference points at, or null. */
function refSheet(input) {
  const inner = input.startsWith('{') ? input.slice(1, -1) : input;
  const sheet = inner.split(':')[0];
  return sheet ? sheet.trim() : null;
}

/** Does TestData/<Sheet>.csv exist? A reference to a missing sheet fails at runtime. */
function dataSheetExists(projectRoot, sheet) {
  const dir = join(projectRoot, 'TestData');
  if (!existsSync(dir)) return false;
  const want = `${sheet.toLowerCase()}.csv`;
  return readdirSync(dir).some((f) => f.toLowerCase() === want);
}

function dataColumnName(step) {
  const obj = step.object && !UNNAMED.test(step.object) ? step.object : 'Wert';
  return obj.replace(/[^A-Za-z0-9]/g, '') || 'Wert';
}

// ---------------------------------------------------------------------------
// The pass
// ---------------------------------------------------------------------------

function review(tc, or, projectRoot, casePath) {
  const findings = [];
  const usedObjects = new Map(); // name -> [step numbers]

  for (const s of tc.steps) {
    const n = s.step ?? tc.steps.indexOf(s) + 1;
    if (s.object && s.object !== 'Browser') {
      if (!usedObjects.has(s.object)) usedObjects.set(s.object, []);
      usedObjects.get(s.object).push(n);
    }

    // --- 1. Benennung -----------------------------------------------------
    if (!(s.description || '').trim()) {
      const proposed = proposeDescription(s);
      if (proposed) {
        findings.push({
          check: 'BENENNUNG',
          step: n,
          object: s.object,
          title: 'Schritt ohne Beschreibung',
          detail:
            'Der Rekorder schreibt jede Beschreibung leer (LiveRecordingParser.java:189). ' +
            'Ohne Text ist im Bericht nicht zu erkennen, welcher fachliche Schritt fehlgeschlagen ist.',
          proposal: { field: 'description', value: proposed },
          humanNeeded: false,
        });
      } else {
        findings.push({
          check: 'BENENNUNG',
          step: n,
          object: s.object,
          title: 'Schritt ohne Beschreibung — Benennung nicht ableitbar',
          detail: UNNAMED.test(s.object || '')
            ? `Das Objekt heißt „${s.object}". Der Rekorder vergibt diesen Namen, wenn er aus dem ` +
              'Selektor keinen bilden konnte (PlaywrightRecordingParser.java:82). Aus einem ' +
              'bedeutungslosen Namen lässt sich keine Beschreibung ableiten.'
            : `Die Aktion „${s.action}" ist diesem Werkzeug nicht bekannt; es rät nicht.`,
          proposal: {
            field: 'description',
            value: '<von Ihnen zu benennen: was tut dieser Schritt fachlich?>',
          },
          humanNeeded: true,
        });
      }
    }

    // --- 4. Testdaten -----------------------------------------------------
    const input = (s.input || '').trim();
    if (input && MUTATING_ACTION.test(s.action || '')) {
      const literal = input.startsWith('@') ? input.slice(1) : input;
      const isRef = looksLikeDataSheetRef(input);

      if (!isRef) {
        const shape = DATA_SHAPES.find((d) => d.test(literal));
        if (shape) {
          const col = dataColumnName(s);
          findings.push({
            check: 'TESTDATEN',
            step: n,
            object: s.object,
            title: `Fest eingetragener Wert (${shape.title})`,
            detail:
              `Der Wert „${literal}" steht im Schritt selbst. Der Testfall läuft damit für genau ` +
              'diesen einen Kunden. Für einen zweiten Kunden muss jemand den Schritt anfassen.',
            proposal: {
              field: 'input',
              value: `${tc.name}:${col}`,
              extra:
                `Spalte „${col}" in TestData/${tc.name}.csv anlegen und den Wert dort eintragen. ` +
                'Die Engine löst `Sheet:Spalte` zur Laufzeit auf ' +
                '(DataProcessor.isInputPatternDataSheet).',
            },
            humanNeeded: false,
          });
        }
      } else if (!dataSheetExists(projectRoot, refSheet(input) || '')) {
        // The engine will read this as Sheet:Column. There is no such sheet, so it is either
        // a literal the recorder captured that happens to contain a colon, or a reference to
        // a sheet nobody created. Both fail at runtime; neither is visible before the run.
        findings.push({
          check: 'TESTDATEN',
          step: n,
          object: s.object,
          title: 'Eingabe wird als Datenblatt-Verweis gelesen — das Datenblatt gibt es nicht',
          detail:
            `„${input}" passt auf das Muster \`Blatt:Spalte\`, aber \`TestData/${refSheet(input)}.csv\` ` +
            'existiert im Projekt nicht. Die Engine löst den Wert deshalb zur Laufzeit nicht auf und ' +
            'bricht ab, statt den Text einzugeben (DataProcessor.java:47, TestCaseRunner.java:324).',
          proposal: {
            field: 'input',
            value: `@${input}`,
            extra:
              'Ein führendes `@` erzwingt den Literalwert. War ein Datenblatt gemeint, muss ' +
              `\`TestData/${refSheet(input)}.csv\` mit der Spalte angelegt werden.`,
          },
          humanNeeded: true,
        });
      }
    }
  }

  // --- 2. Prüfpunkte ------------------------------------------------------
  const assertions = tc.steps.filter((s) => ASSERT_ACTION.test(s.action || ''));
  if (assertions.length === 0) {
    findings.push({
      check: 'PRUEFPUNKT',
      step: null,
      object: null,
      title: 'Der Testfall enthält keine einzige Prüfung',
      detail:
        `${tc.steps.length} Schritte, davon 0 Prüfungen. Dieser Test kann nur scheitern, wenn ein ` +
        'Klick ins Leere geht. Eine falsch gespeicherte Adresse, eine Fehlermeldung der Anwendung ' +
        'oder ein leeres Ergebnis machen ihn nicht rot.',
      proposal: null,
      humanNeeded: true,
    });
  }

  for (let i = 0; i < tc.steps.length; i++) {
    const s = tc.steps[i];
    const n = s.step ?? i + 1;
    if (!/^click$/i.test(s.action || '')) continue;
    if (!COMMITTING.test(s.object || '')) continue;

    // Is there an assertion before the next mutating step?
    let hasAssert = false;
    for (let j = i + 1; j < tc.steps.length; j++) {
      const t = tc.steps[j];
      if (ASSERT_ACTION.test(t.action || '')) { hasAssert = true; break; }
      if (MUTATING_ACTION.test(t.action || '')) break;
    }
    if (hasAssert) continue;

    findings.push({
      check: 'PRUEFPUNKT',
      step: n,
      object: s.object,
      title: `Nach „${s.object}" wird nichts geprüft`,
      detail:
        'Dieser Klick verändert etwas in der Anwendung. Danach folgt weder eine Prüfung noch ein ' +
        'Warten auf die Rückmeldung. Bleibt die Bestätigung aus oder erscheint eine Fehlermeldung, ' +
        'merkt der Testlauf davon nichts.',
      proposal: {
        insertAfter: n,
        steps: [
          {
            object: '<Objekt der Bestätigungsmeldung>',
            action: 'waitForElementToBeVisible',
            description: 'Warten, bis die Rückmeldung der Anwendung erscheint',
          },
          {
            object: '<Objekt der Bestätigungsmeldung>',
            action: 'assertElementContainsText',
            input: '<erwarteter Text — den kennt nur der Fachbereich>',
            description: 'Prüfen: die Anwendung bestätigt den Vorgang',
          },
        ],
      },
      humanNeeded: true,
    });
  }

  // --- 3. Selektoren ------------------------------------------------------
  const objectFindings = [];
  const pagesInvolved = new Set();

  for (const [name, steps] of usedObjects) {
    const el = or.byName.get(name);
    if (!el) {
      objectFindings.push({
        check: 'SELEKTOR',
        object: name,
        steps,
        title: 'Objekt fehlt im Object Repository',
        detail:
          `Die Schritte ${steps.join(', ')} verweisen auf „${name}", das Repository kennt es aber ` +
          'nicht. Der Lauf bricht an dieser Stelle ab.',
        humanNeeded: true,
      });
      continue;
    }
    pagesInvolved.add(el.page);
    const a = assessObject(el);

    if (!a.effective) {
      objectFindings.push({
        check: 'SELEKTOR',
        object: name,
        steps,
        page: el.page,
        title: 'Objekt ohne jeden Selektor',
        detail: 'Der Eintrag definiert kein einziges Locator-Attribut.',
        humanNeeded: true,
      });
      continue;
    }
    for (const r of a.risks) {
      objectFindings.push({
        check: 'SELEKTOR',
        object: name,
        steps,
        page: el.page,
        attribute: a.effective,
        value: a.value,
        title: r.title,
        detail: r.why,
        proposal: { text: r.fix },
        humanNeeded: true,
        where: `${relative(projectRoot, el.file).replace(/\\/g, '/')}:${el.line}`,
      });
    }
    if (a.shadowed.length) {
      objectFindings.push({
        check: 'SELEKTOR',
        object: name,
        steps,
        page: el.page,
        attribute: a.effective,
        title: 'Weitere Selektoren werden von der Engine ignoriert',
        detail:
          `Die Engine nimmt das erste gefüllte Attribut in der Reihenfolge ${OBJECT_PROPS.join(' → ')} ` +
          `und bricht ab. Verwendet wird „${a.effective}"; ignoriert werden: ${a.shadowed.join(', ')}. ` +
          'Wer den ignorierten Wert pflegt, pflegt einen toten Eintrag.',
        proposal: { text: `Nicht benötigte Attribute löschen oder „${a.effective}" leeren, wenn ein anderes gelten soll.` },
        humanNeeded: true,
        where: `${relative(projectRoot, el.file).replace(/\\/g, '/')}:${el.line}`,
      });
    }
  }

  return { findings, objectFindings, pagesInvolved: [...pagesInvolved], usedObjects };
}

// ---------------------------------------------------------------------------
// Report
// ---------------------------------------------------------------------------

function renderReport(tc, res, projectRoot, casePath, projectArg) {
  const L = [];
  const byCheck = (c) => res.findings.filter((f) => f.check === c);
  const naming = byCheck('BENENNUNG');
  const asserts = byCheck('PRUEFPUNKT');
  const data = byCheck('TESTDATEN');
  const total = res.findings.length + res.objectFindings.length;

  L.push(`# Prüfvorschlag zur Aufnahme „${tc.name}"`);
  L.push('');
  L.push(`Projekt: \`${basename(projectRoot)}\` · Szenario: \`${tc.scenario || '—'}\` · Schritte: ${tc.steps.length}`);
  L.push('');
  L.push('> **Dieses Dokument ändert nichts.** Es schlägt vor. Übernommen wird jeder Punkt von Hand');
  L.push('> im Studio. Das Werkzeug hat keinen `--apply`-Schalter und bekommt keinen.');
  L.push('');
  L.push('| Prüfung | Befunde |');
  L.push('|---|---:|');
  L.push(`| 1 Benennung — Schritte ohne Beschreibung | ${naming.length} |`);
  L.push(`| 2 Prüfpunkte — Zustandsänderungen ohne Prüfung | ${asserts.length} |`);
  L.push(`| 3 Selektoren — statisch erkennbare Risiken | ${res.objectFindings.length} |`);
  L.push(`| 4 Testdaten — fest eingetragene Werte | ${data.length} |`);
  L.push(`| **Summe** | **${total}** |`);
  L.push('');

  // ---- 1
  L.push('## 1 Benennung');
  L.push('');
  if (!naming.length) {
    L.push('Jeder Schritt trägt eine Beschreibung. Nichts vorzuschlagen.');
  } else {
    L.push(`${naming.length} von ${tc.steps.length} Schritten haben keine Beschreibung. Der Rekorder`);
    L.push('schreibt sie grundsätzlich leer (`LiveRecordingParser.java:189` — `step.setDescription("")`).');
    L.push('');
    L.push('| Schritt | Objekt | Vorschlag für `description` |');
    L.push('|---:|---|---|');
    for (const f of naming) {
      const mark = f.humanNeeded ? ' ⚠' : '';
      L.push(`| ${f.step} | \`${f.object || '—'}\` | ${f.proposal.value}${mark} |`);
    }
    const open = naming.filter((f) => f.humanNeeded);
    if (open.length) {
      L.push('');
      L.push(`⚠ ${open.length} Schritt(e) kann dieses Werkzeug **nicht** benennen — es rät nicht:`);
      L.push('');
      for (const f of open) L.push(`- Schritt ${f.step}: ${f.detail}`);
    }
  }
  L.push('');

  // ---- 2
  L.push('## 2 Prüfpunkte');
  L.push('');
  if (!asserts.length) {
    L.push('Nach jeder zustandsändernden Aktion folgt eine Prüfung. Nichts vorzuschlagen.');
  } else {
    for (const f of asserts) {
      L.push(`### ${f.step ? `Schritt ${f.step} — ` : ''}${f.title}`);
      L.push('');
      L.push(f.detail);
      if (f.proposal && f.proposal.steps) {
        L.push('');
        L.push(`Vorschlag: nach Schritt ${f.proposal.insertAfter} einfügen —`);
        L.push('');
        L.push('```yaml');
        for (const s of f.proposal.steps) {
          L.push(`  - object: "${s.object}"`);
          L.push(`    description: "${s.description}"`);
          L.push(`    action: "${s.action}"`);
          if (s.input) L.push(`    input: "${s.input}"`);
        }
        L.push('```');
      }
      L.push('');
      L.push('⚠ Den erwarteten Text kennt dieses Werkzeug nicht. Er kommt aus dem Testfall bzw.');
      L.push('vom Fachbereich und muss von Hand eingetragen werden.');
      L.push('');
    }
  }

  // ---- 3
  L.push('## 3 Selektoren');
  L.push('');
  if (!res.objectFindings.length) {
    L.push('Kein statisch erkennbares Risiko in den verwendeten Objekten.');
  } else {
    for (const f of res.objectFindings) {
      L.push(`### \`${f.object}\` — ${f.title}`);
      L.push('');
      if (f.attribute) L.push(`Verwendetes Attribut: \`${f.attribute}\`${f.value ? ` = \`${f.value}\`` : ''}  `);
      L.push(`Betrifft Schritt(e): ${f.steps.join(', ')}${f.where ? `  ·  ${f.where}` : ''}`);
      L.push('');
      L.push(f.detail);
      if (f.proposal && f.proposal.text) {
        L.push('');
        L.push(`**Vorschlag:** ${f.proposal.text}`);
      }
      L.push('');
    }
  }
  L.push('### Offen: Eindeutigkeit');
  L.push('');
  L.push('Ob ein Selektor **genau ein** Element trifft, ist keine Eigenschaft der Datei, sondern von');
  L.push('(Selektor, Seitenzustand). Kein Lesen der Aufnahme kann das entscheiden — dieses Werkzeug');
  L.push('behauptet es deshalb auch nicht. Entschieden wird es, indem die echte Seite geöffnet wird:');
  L.push('');
  L.push('```powershell');
  for (const page of res.pagesInvolved) {
    L.push(`node tools\\selector-uniqueness.mjs --project "${projectArg || projectRoot}" \``);
    L.push(`     --page ${page} --url "<Adresse der Seite, auf der diese Objekte stehen>" \``);
    L.push('     --storage-state "<Playwright-Session der angemeldeten Sitzung>"');
  }
  L.push('```');
  L.push('');
  L.push('Bis das gelaufen ist, gilt die Eindeutigkeit als **ungeprüft** — nicht als in Ordnung.');
  L.push('');

  // ---- 4
  L.push('## 4 Testdaten');
  L.push('');
  if (!data.length) {
    L.push('Keine fest eingetragenen Werte gefunden.');
  } else {
    L.push('| Schritt | Objekt | Befund | Vorschlag für `input` |');
    L.push('|---:|---|---|---|');
    for (const f of data) {
      L.push(`| ${f.step} | \`${f.object}\` | ${f.title} | \`${f.proposal.value}\` |`);
    }
    L.push('');
    const cols = [...new Set(data.filter((f) => /^[^@]/.test(f.proposal.value) && f.proposal.value.includes(':')).map((f) => f.proposal.value.split(':')[1]))];
    if (cols.length) {
      L.push(`Dazu \`TestData/${tc.name}.csv\` anlegen bzw. ergänzen:`);
      L.push('');
      L.push('```csv');
      L.push(`Scenario,Flow,Scope,Iteration,SubIteration,${cols.join(',')}`);
      L.push(`${tc.scenario || '<Szenario>'},${tc.name},,1,1,${cols.map(() => '<Wert>').join(',')}`);
      L.push('```');
      L.push('');
      L.push('Danach läuft derselbe Testfall für jeden weiteren Kunden, ohne dass ein Schritt angefasst wird.');
    }
  }
  L.push('');

  L.push('---');
  L.push('');
  L.push('## Was dieses Werkzeug nicht kann');
  L.push('');
  L.push('- Es kennt die **fachliche Absicht** nicht. Es benennt, was passiert ist, nicht warum.');
  L.push('  Jeder erwartete Text ist ein Platzhalter.');
  L.push('- Namen, die der Rekorder aus dem Selektor gebildet hat (`even`, `contacthistory`,');
  L.push('  `uebernehmen`), sind technisch benennbar, fachlich aber leer. Die vorgeschlagene');
  L.push('  Beschreibung ist dann ein Anfang, kein fertiger Satz — bitte überschreiben.');
  L.push('- Es entscheidet die **Eindeutigkeit** von Selektoren nicht (siehe oben).');
  L.push('- Es weiß nicht, ob ein Schritt **fachlich überflüssig** war. Ein versehentlicher Klick');
  L.push('  sieht für das Werkzeug aus wie ein gewollter.');
  L.push('- Es fasst **wiederkehrende Anfänge** (Anmelden → Suchen → Partner öffnen) nicht zu einer');
  L.push('  wiederverwendbaren Komponente zusammen. Das ändert die Struktur des Laufs und lässt sich');
  L.push('  aus einer einzelnen Aufnahme nicht belegen — deshalb schlägt es das nicht vor.');
  L.push('');
  return L.join('\n');
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------
function fail(code, msg) {
  console.error(msg);
  process.exit(code);
}

function run(args) {
  const projectRoot = resolve(args.project);
  if (!existsSync(projectRoot)) return fail(EXIT.ARGS_REJECTED, `Projekt nicht gefunden: ${projectRoot}`);

  let casePath = args.testCase;
  if (!existsSync(casePath)) casePath = join(projectRoot, args.testCase);
  if (!existsSync(casePath)) {
    return fail(EXIT.ARGS_REJECTED, `Testfall nicht gefunden: ${args.testCase}`);
  }
  if (/\.csv$/i.test(casePath)) {
    console.error(
      'CANNOT TELL — das ist eine CSV. INGenious 3.1 migriert Projekte beim ersten Lauf nach\n' +
        'YAML und behandelt eine daneben liegende CSV als Konflikt. Geprüft wird das, was\n' +
        'die Engine wirklich ausführt — geben Sie die .yaml an.',
    );
    process.exit(EXIT.CANNOT_TELL);
  }

  let tc;
  try {
    tc = parseTestCaseYaml(casePath);
  } catch (e) {
    return fail(EXIT.CANNOT_TELL, `CANNOT TELL — Testfall nicht lesbar: ${e.message}`);
  }
  if (!tc.steps.length) {
    console.error(`CANNOT TELL — ${casePath} enthält keine Schritte. Es wurde nichts geprüft.`);
    process.exit(EXIT.CANNOT_TELL);
  }

  const or = loadObjectRepository(projectRoot);
  if (!or) {
    console.error(`CANNOT TELL — kein ObjectRepository/Web unter ${projectRoot}.`);
    process.exit(EXIT.CANNOT_TELL);
  }

  const res = review(tc, or, projectRoot, casePath);
  const md = renderReport(tc, res, projectRoot, casePath, args.project);

  if (!args.quiet) console.log(md);

  if (args.out) {
    mkdirSync(args.out, { recursive: true });
    const f = join(args.out, `${tc.name}.review.md`);
    writeFileSync(f, md);
    console.error(`\nVorschlag geschrieben: ${f}`);
  }
  if (args.json) {
    mkdirSync(dirname(resolve(args.json)), { recursive: true });
    writeFileSync(
      args.json,
      JSON.stringify(
        {
          project: args.project,
          testCase: tc.name,
          scenario: tc.scenario,
          steps: tc.steps.length,
          findings: res.findings,
          objectFindings: res.objectFindings,
          uniquenessDecided: false,
          pagesInvolved: res.pagesInvolved,
        },
        null,
        2,
      ),
    );
    console.error(`Befunde als JSON: ${args.json}`);
  }

  const total = res.findings.length + res.objectFindings.length;
  process.exit(total > 0 ? EXIT.PROPOSALS : EXIT.NOTHING_TO_PROPOSE);
}

// ---------------------------------------------------------------------------
// Selftest — a fixture whose problems are known by construction, written and read here
// so the check is not graded against a previous run of this tool.
// ---------------------------------------------------------------------------
function selftest() {
  const os = process.env.TEMP || process.env.TMPDIR || '.';
  const root = join(os, `review-recording-selftest-${process.pid}`);
  mkdirSync(join(root, 'ObjectRepository', 'Web'), { recursive: true });
  mkdirSync(join(root, 'TestPlan', 'Selbsttest'), { recursive: true });

  writeFileSync(
    join(root, 'ObjectRepository', 'Web', 'Probe.yaml'),
    [
      'page: Probe',
      'scope: PROJECT',
      'elements:',
      '  SuchenButton:',
      '    xpath: "//button[contains(text(),\'Suchen\')]"',
      '  ErgebnisZeile:',
      '    css: "table tr.even"',
      '  NummernFeld:',
      '    css: "#id10"',
      '    xpath: "//input[@name=\'sucheForm:suchfeld:suchbegriff\']"',
      '  AltesFeld:',
      '    css: "#id1f"',
      '  SpeichernButton:',
      '    css: "button[name=\'p::submit\']"',
      '',
    ].join('\n'),
  );

  writeFileSync(
    join(root, 'TestPlan', 'Selbsttest', 'Probe.yaml'),
    [
      'schemaVersion: 1',
      'testCase: Probe',
      'scenario: Selbsttest',
      'steps:',
      '  - step: 1',
      '    object: "Browser"',
      '    description: ""',
      '    action: "Open"',
      '    input: "@https://example.invalid/start"',
      '  - step: 2',
      '    object: "NummernFeld"',
      '    description: ""',
      '    action: "Fill"',
      '    input: "0000000000"',
      '  - step: 3',
      '    object: "SuchenButton"',
      '    description: ""',
      '    action: "Click"',
      '  - step: 4',
      '    object: "ErgebnisZeile"',
      '    description: ""',
      '    action: "Click"',
      '  - step: 5',
      '    object: "Refactor_Object_1"',
      '    description: ""',
      '    action: "Click"',
      '  - step: 6',
      '    object: "AltesFeld"',
      '    description: ""',
      '    action: "Fill"',
      '    input: "Bemerkung:Adresse geaendert"',
      '  - step: 7',
      '    object: "SpeichernButton"',
      '    description: ""',
      '    action: "Click"',
      '',
    ].join('\n'),
  );

  const tc = parseTestCaseYaml(join(root, 'TestPlan', 'Selbsttest', 'Probe.yaml'));
  const or = loadObjectRepository(root);
  const res = review(tc, or, root, 'TestPlan/Selbsttest/Probe.yaml');

  const problems = [];
  const naming = res.findings.filter((f) => f.check === 'BENENNUNG');
  const data = res.findings.filter((f) => f.check === 'TESTDATEN');
  const asserts = res.findings.filter((f) => f.check === 'PRUEFPUNKT');

  if (tc.steps.length !== 7) problems.push(`erwartet 7 Schritte, gelesen ${tc.steps.length}`);
  if (naming.length !== 7) problems.push(`erwartet 7 Benennungs-Befunde, erhalten ${naming.length}`);
  const unnameable = naming.filter((f) => f.humanNeeded);
  if (unnameable.length !== 1 || unnameable[0].step !== 5) {
    problems.push('erwartet genau Schritt 5 als nicht benennbar (Refactor_Object_1)');
  }
  const dataSteps = data.map((f) => f.step).sort((a, b) => a - b);
  if (JSON.stringify(dataSteps) !== JSON.stringify([2, 6])) {
    problems.push(`erwartet Testdaten-Befunde in Schritt 2 und 6, erhalten ${JSON.stringify(dataSteps)}`);
  }
  if (!data.some((f) => f.step === 6 && /Datenblatt-Verweis/.test(f.title))) {
    problems.push('„Hauptstrasse:12a" muss als versehentlicher Datenblatt-Verweis erkannt werden');
  }
  // No assertion anywhere -> 1 case-level finding; committing clicks 3 (Suchen) and 7 (Speichern).
  const stepwise = asserts.filter((f) => f.step !== null).map((f) => f.step).sort((a, b) => a - b);
  if (asserts.filter((f) => f.step === null).length !== 1) {
    problems.push('erwartet einen Befund „keine einzige Prüfung"');
  }
  if (JSON.stringify(stepwise) !== JSON.stringify([3, 7])) {
    problems.push(`erwartet fehlende Prüfungen nach Schritt 3 und 7, erhalten ${JSON.stringify(stepwise)}`);
  }

  if (!res.objectFindings.some((f) => f.object === 'ErgebnisZeile' && /Position/.test(f.title))) {
    problems.push('ErgebnisZeile (tr.even) muss als positionsabhängig erkannt werden');
  }
  if (!res.objectFindings.some((f) => f.object === 'SuchenButton' && /Text/.test(f.title))) {
    problems.push("SuchenButton (contains(text(),'Suchen')) muss als textabhängig erkannt werden");
  }
  if (!res.objectFindings.some((f) => f.object === 'AltesFeld' && /generierte ID/i.test(f.title))) {
    problems.push('AltesFeld (#id1f) muss als server-generierte ID erkannt werden');
  }
  // The engine order is Role, Text, Label, Placeholder, xpath, css — so on NummernFeld the
  // xpath wins and the css is dead weight, even though the css is the shorter one.
  const nf = res.objectFindings.find((f) => f.object === 'NummernFeld' && /ignoriert/i.test(f.title));
  if (!nf) problems.push('NummernFeld muss als beschattet gemeldet werden (xpath gewinnt, css ist tot)');
  else if (nf.attribute !== 'xpath') {
    problems.push(`NummernFeld: erwartet wirksames Attribut xpath, erhalten ${nf.attribute}`);
  }
  if (!res.objectFindings.some((f) => f.object === 'Refactor_Object_1' && /fehlt/i.test(f.title))) {
    problems.push('Refactor_Object_1 ist im Repository nicht vorhanden und muss gemeldet werden');
  }

  // Rendering must not throw and must mention the delegation to selector-uniqueness.
  let md = '';
  try {
    md = renderReport(tc, res, root, 'TestPlan/Selbsttest/Probe.yaml', root);
  } catch (e) {
    problems.push('Bericht konnte nicht gerendert werden: ' + e.message);
  }
  if (!/selector-uniqueness\.mjs/.test(md)) {
    problems.push('Der Bericht muss die Eindeutigkeitsprüfung an selector-uniqueness.mjs delegieren');
  }
  if (!/Dieses Dokument ändert nichts/.test(md)) {
    problems.push('Der Bericht muss ausdrücklich sagen, dass er nichts ändert');
  }
  if (process.argv.join(' ').includes('--apply')) {
    problems.push('--apply existiert nicht und darf nicht stillschweigend akzeptiert werden');
  }

  if (problems.length) {
    console.error('review-recording selftest: ROT\n  ' + problems.join('\n  '));
    process.exit(EXIT.PROPOSALS);
  }
  console.log(
    'review-recording selftest: GRÜN — 7 Schritte einer Fixtur-Aufnahme: 7 ohne Beschreibung ' +
      '(1 davon nicht benennbar), 2 Testdaten-Befunde (eine fest eingetragene Nummer und eine ' +
      'Eingabe, die die Engine als Datenblatt-Verweis missversteht), 2 Zustandsänderungen ohne ' +
      'Prüfung plus der Befund „keine einzige Prüfung", die 4 Selektor-Befunde ' +
      '(Position, sichtbarer Text, generierte ID, beschattetes Attribut) und 1 fehlendes Objekt. ' +
      'Eindeutigkeit bleibt ungeprüft und wird als offen gemeldet.',
  );
  process.exit(EXIT.NOTHING_TO_PROPOSE);
}

const args = parseArgs(process.argv.slice(2));
if (args.help) { console.log(USAGE); process.exit(EXIT.NOTHING_TO_PROPOSE); }
if (args.unknown) fail(EXIT.ARGS_REJECTED, `unbekanntes Argument: ${args.unknown}\n\n${USAGE}`);
if (args.selftest) selftest();
else if (!args.project || !args.testCase) fail(EXIT.ARGS_REJECTED, USAGE);
else run(args);
