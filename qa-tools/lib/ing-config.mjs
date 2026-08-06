/**
 * ing-config.mjs — the one place that knows WHICH organisation these tools point at.
 *
 * Every value in here used to be a literal in the source: the Entra tenant, the Azure
 * DevOps organisation, the project, the test plan, the path of the example project.
 * That is fine while the source is private and fatal the moment it is not — a published
 * repository cannot be un-published, and a tenant id in its history stays there.
 *
 * So the tools no longer KNOW those values. They ask for them, and the answer travels
 * beside the tools instead of inside them:
 *
 *   1. an environment variable                       (a pipeline sets these)
 *   2. $ING_CONFIG, a full path to a JSON file       (an explicit override)
 *   3. ing-config.json next to the tools, or in any  (what the tester package ships)
 *      ancestor directory up to the repository root
 *   4. %LOCALAPPDATA%\IngQaAutopilot\ing-config.json (a machine-wide answer)
 *
 * Nothing is invented. A value that none of the four sources carries comes back empty,
 * and {@link requireValue} turns the first USE of an empty value into a German sentence
 * naming the key and the file to put it in. A tool that guesses an organisation is worse
 * than a tool that stops: it writes into somebody else's Azure DevOps.
 *
 * The file this reads is deliberately NOT tracked in a public repository — see
 * ing-config.example.json for its shape.
 */
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { homedir } from 'node:os';

const __dirname = dirname(fileURLToPath(import.meta.url));

/**
 * key -> environment variable that overrides it.
 *
 * The three ADO names are the ones ado-automark.mjs already honoured, kept byte-identical
 * so a pipeline that sets them today keeps working and the two markers cannot drift into
 * different rules.
 */
const ENV = {
  tenantId: 'ADO_TENANT_ID',
  org: 'ADO_ORG',
  project: 'ADO_PROJECT',
  plan: 'ADO_TEST_PLAN_ID',
  suite: 'ADO_TEST_SUITE_ID',
  projectLocation: 'ING_PROJECT_LOCATION',
  appUrl: 'ING_APP_URL',
  appReadySelector: 'ING_APP_READY_SELECTOR',
};

/** Where a human is told to put the value. Used in the error, so it must stay accurate. */
export const CONFIG_FILENAME = 'ing-config.json';

let cached = null;

/** Every candidate path for the config file, best first. */
function candidates() {
  const out = [];
  const explicit = String(process.env.ING_CONFIG || '').trim();
  if (explicit) out.push(explicit);
  // tools/lib -> tools -> repo root -> package root. Four levels covers the package
  // layout (<Paket>\repo\tools\lib) with the file at the package root.
  let dir = resolve(__dirname);
  for (let i = 0; i < 5; i++) {
    out.push(join(dir, CONFIG_FILENAME));
    const parent = dirname(dir);
    if (parent === dir) break;
    dir = parent;
  }
  const local = String(process.env.LOCALAPPDATA || '').trim();
  out.push(local
    ? join(local, 'IngQaAutopilot', CONFIG_FILENAME)
    : join(homedir(), '.IngQaAutopilot', CONFIG_FILENAME));
  return out;
}

/** The first readable config file, or an empty object. Never throws. */
function readFile() {
  for (const p of candidates()) {
    try {
      if (!existsSync(p)) continue;
      const parsed = JSON.parse(readFileSync(p, 'utf8'));
      if (parsed && typeof parsed === 'object') return { values: parsed, source: p };
    } catch {
      // A malformed file is treated as absent on purpose: the next candidate may be
      // good, and requireValue() will produce a far clearer sentence than a parse error
      // thrown from an import.
    }
  }
  return { values: {}, source: null };
}

/**
 * The resolved configuration. Empty strings for anything nobody supplied — never a guess.
 *
 * @param {NodeJS.ProcessEnv} env
 * @returns {{tenantId:string, org:string, project:string, plan:string, suite:string,
 *            projectLocation:string, source:string|null}}
 */
export function config(env = process.env) {
  if (cached && env === process.env) return cached;
  const { values, source } = readFile();
  const pick = (key) => {
    const fromEnv = String(env[ENV[key]] ?? '').trim();
    if (fromEnv) return fromEnv;
    return String(values[key] ?? '').trim();
  };
  const out = {
    tenantId: pick('tenantId'),
    org: pick('org'),
    project: pick('project'),
    plan: pick('plan'),
    suite: pick('suite'),
    projectLocation: pick('projectLocation'),
    appUrl: pick('appUrl'),
    appReadySelector: pick('appReadySelector'),
    source,
  };
  if (env === process.env) cached = out;
  return out;
}

/**
 * A configured value, or a German sentence explaining exactly what is missing.
 *
 * Thrown at the point of USE rather than at import time, so a tool whose selftest never
 * touches Azure DevOps still runs on a machine that has no config file at all.
 *
 * @throws {Error} when the value is empty
 */
export function requireValue(key, was) {
  const c = config();
  const v = c[key];
  if (v) return v;
  const wo = c.source ? ('"' + c.source + '"') : ('einer Datei ' + CONFIG_FILENAME);
  throw new Error(
    was + ' ist nicht konfiguriert. Bitte "' + key + '" in ' + wo + ' eintragen '
    + '(oder die Umgebungsvariable ' + ENV[key] + ' setzen). '
    + 'Die Vorlage steht in ing-config.example.json.',
  );
}

/** Forget the cached file. Only for tests that write a config and read it back. */
export function resetConfigCache() {
  cached = null;
}
