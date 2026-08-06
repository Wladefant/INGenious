#!/usr/bin/env node
/**
 * Distill a Playwright trace.zip into a compact, redacted JSON summary.
 * Node.js stdlib only. See tools/README-distill-trace.md.
 */

import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import process from 'node:process';
import { pathToFileURL } from 'node:url';

const CAPS = {
  actions: 500,
  networkFailures: 100,
  screenshots: 50,
  logMessages: 20,
};

const SENSITIVE_KEY = /pass|pwd|secret|token|credential|authorization|apikey/i;
const BASIC_AUTH_IN_URL = /\/\/([^/@\s]+):([^/@\s]+)@/g;

/**
 * Field-name/label hints that mark a value as SECRET.
 * IMPORTANT: this list must be EXTENDED, never REPLACED - the English terms stay,
 * German banking terms are added. Matching runs against a normalised form of the
 * string (lowercased, umlauts folded, hyphens/underscores -> spaces). German
 * compounds use `\\s?` at each internal boundary so solid, hyphenated and spaced
 * spellings all match (e.g. "Kennwort", "Kenn-Wort", "Kenn wort").
 *
 * Short tokens pin / tan are word-boundary anchored so ordinary German words
 * (Bestand, Kontostand, Konstante, …) are not treated as secret fields.
 * TAN prefixes (photo/m/i/sms/push) also allow an optional separator before "tan".
 */
export const SECRET_FIELD_HINT_TERMS = [
  // English (retained)
  'pass',
  'pwd',
  'secret',
  'credential',
  'otp',
  '\\bpin\\b',
  // German banking UI wording (\\s? = solid / hyphenated / spaced after normalise)
  'kenn\\s?wort',
  'pass\\s?wort',
  'geheim\\s?zahl',
  'geheim\\s?nummer',
  'sicherheits\\s?code',
  'sicherheits\\s?nummer',
  'pin\\s?code',
  // TAN and variants (photoTAN, mTAN, iTAN, smsTAN, pushTAN, plain TAN;
  // also photo-TAN / m TAN / SMS-TAN after normalise → "photo tan" etc.)
  '\\b(?:(?:photo|m|i|sms|push)\\s?)?tan\\b',
  'zugangs\\s?nummer',
  'zugangs\\s?code',
  'legitimations\\s?code',
  'legitimation',
];

export const CREDENTIAL_FIELD_HINT = new RegExp(
  '(?:' + SECRET_FIELD_HINT_TERMS.join('|') + ')',
  'i',
);

/** Value that looks like a TAN/PIN code (4–8 digits). */
const LOOKS_LIKE_TAN_PIN = /^\d{4,8}$/;

/**
 * Sibling selector/label context for defense-in-depth trigger (c).
 * Word-boundary on short tokens so "Bestand" (contains "tan") does not match.
 */
const TAN_PIN_CONTEXT_HINT =
  /\b(?:(?:photo|m|i|sms|push)?tan|pin|code|nummer|zahl)\b/i;

const FILL_VALUE_KEY = /^(value|text|inputValue|values)$/i;

/**
 * Normalise a field name / selector / label before hint matching:
 * lowercase, fold umlauts (ä→ae, ö→oe, ü→ue, ß→ss), hyphens/underscores → space,
 * collapse whitespace runs.
 * @param {unknown} s
 * @returns {string}
 */
export function normalizeHintText(s) {
  return String(s ?? '')
    .toLowerCase()
    .replace(/ä/g, 'ae')
    .replace(/ö/g, 'oe')
    .replace(/ü/g, 'ue')
    .replace(/ß/g, 'ss')
    .replace(/[-_]+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

function die(msg) {
  console.error(msg);
  process.exit(1);
}

function parseArgs(argv) {
  const args = { trace: null, json: null, redact: true, secretsFile: null };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--trace') {
      args.trace = argv[++i];
    } else if (a === '--json') {
      args.json = argv[++i];
    } else if (a === '--no-redact') {
      args.redact = false;
    } else if (a === '--secrets-file') {
      args.secretsFile = argv[++i];
    }
  }
  return args;
}

/**
 * Parse a secrets file: one secret per line; blank lines and # comments ignored.
 * Never logs file contents.
 * @param {string} filePath
 * @returns {string[]}
 */
function loadSecretsFile(filePath) {
  const abs = path.resolve(filePath);
  if (!fs.existsSync(abs)) {
    die(`Error: secrets file not found: ${abs}`);
  }
  let text;
  try {
    text = fs.readFileSync(abs, 'utf8');
  } catch (err) {
    die(`Error: failed to read secrets file: ${abs}\n  ${err.message}`);
  }
  const secrets = [];
  for (const line of text.split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#')) continue;
    secrets.push(trimmed);
  }
  return secrets;
}

/**
 * The commands tried, in order, to unpack a trace zip. Node ships no zip reader, so this
 * is an external binary either way; what it must not be is a single one.
 *
 * Windows' own System32\tar.exe is bsdtar (libarchive), which reads zip, and so is macOS'
 * `tar`. **GNU tar cannot** — on Linux `tar -xf trace.zip` prints "This does not look like
 * a tar archive" and exits 2, so with tar as the only extractor every trace distil on a
 * Linux machine failed outright, and the message blamed the zip rather than the tool.
 * Measured, not assumed: GNU tar 1.35 exit 2, bsdtar exit 0, `unzip` exit 0, same archive.
 *
 * `unzip` is not guaranteed present either (a separate package on minimal Linux images),
 * which is why a total failure below names every command it tried instead of one.
 */
export function zipExtractors() {
  const tarExe =
    process.platform === 'win32'
      ? path.join(process.env.SystemRoot || 'C:\\Windows', 'System32', 'tar.exe')
      : 'tar';
  return [
    { exe: tarExe, args: (zip) => ['-xf', zip] },
    { exe: 'unzip', args: (zip) => ['-q', '-o', zip] },
  ];
}

/** First non-empty line of a child process failure, preferring what it wrote to stderr. */
function failureLine(err) {
  const stderr = err && err.stderr != null ? String(err.stderr) : '';
  const text = stderr.trim() || String((err && err.message) || err);
  return text.split(/\r?\n/).find((l) => l.trim()) || 'failed';
}

/**
 * Extract zip into tmpDir, trying each extractor until one succeeds. Dies with a message
 * naming every command tried, and what each said, when none does.
 *
 * @param {string} zipPath
 * @param {string} tmpDir
 * @param {ReturnType<typeof zipExtractors>} [extractors] injectable so the fallback can be
 *   exercised with the primary removed, rather than only on a machine where it happens to fail
 * @returns {string} the command that did the work
 */
export function extractZip(zipPath, tmpDir, extractors = zipExtractors()) {
  const absZipPath = path.resolve(zipPath);
  const tried = [];
  for (const ex of extractors) {
    try {
      execFileSync(ex.exe, ex.args(absZipPath), { cwd: tmpDir, stdio: 'pipe' });
      return ex.exe;
    } catch (err) {
      tried.push(`  ${ex.exe}: ${failureLine(err)}`);
    }
  }
  die(
    `Error: failed to extract trace zip: ${absZipPath}\n` +
      `  tried, in order:\n` +
      tried.join('\n') +
      `\n  GNU tar cannot read zip archives; install unzip (or run where tar is bsdtar).`,
  );
}

/** Parse NDJSON file; skip blank and malformed lines. */
function readNdjson(filePath) {
  if (!fs.existsSync(filePath)) return [];
  const text = fs.readFileSync(filePath, 'utf8');
  const out = [];
  for (const line of text.split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed) continue;
    try {
      out.push(JSON.parse(trimmed));
    } catch {
      // skip malformed lines silently
    }
  }
  return out;
}

/** List image file names under resources/ matching jpeg/png. */
function listScreenshots(resourcesDir) {
  if (!fs.existsSync(resourcesDir)) return [];
  let entries;
  try {
    entries = fs.readdirSync(resourcesDir);
  } catch {
    return [];
  }
  return entries
    .filter((name) => /\.(jpe?g|png)$/i.test(name))
    .sort();
}

/** Redact basic-auth userinfo in URL strings: //user:pass@host -> //[REDACTED]@host */
function redactUrlString(s) {
  return String(s).replace(BASIC_AUTH_IN_URL, '//[REDACTED]@');
}

/**
 * True when any own string value (selector, label, …) matches the credential hint list
 * after normalisation. Covers defense-in-depth triggers (a) and (b).
 * @param {Record<string, unknown>} obj
 */
function objectHintsCredential(obj) {
  return Object.values(obj).some(
    (v) => typeof v === 'string' && CREDENTIAL_FIELD_HINT.test(normalizeHintText(v)),
  );
}

/**
 * True when any own string value matches TAN/PIN context (trigger c sibling side).
 * @param {Record<string, unknown>} obj
 */
function objectHintsTanPinContext(obj) {
  return Object.values(obj).some(
    (v) => typeof v === 'string' && TAN_PIN_CONTEXT_HINT.test(normalizeHintText(v)),
  );
}

/**
 * Recursive redaction: sensitive keys -> "[REDACTED]"; httpCredentials wholesale;
 * basic-auth in URL strings; fill value/text keys when:
 *   (a)(b) a sibling string names a credential field (EN + DE hints), or
 *   (c) the value looks like a TAN/PIN (4–8 digits) and a sibling hints tan/pin/code/…
 */
function redact(value) {
  if (value == null) return value;
  if (typeof value === 'string') return redactUrlString(value);
  if (typeof value !== 'object') return value;
  if (Array.isArray(value)) return value.map((v) => redact(v));

  const siblingHintsCredential = objectHintsCredential(value);
  const siblingHintsTanPinContext = objectHintsTanPinContext(value);

  const out = {};
  for (const [key, val] of Object.entries(value)) {
    if (key === 'httpCredentials' || SENSITIVE_KEY.test(key)) {
      out[key] = '[REDACTED]';
    } else if (FILL_VALUE_KEY.test(key)) {
      const valStr = val == null ? '' : String(val);
      const looksLikeTanPin = LOOKS_LIKE_TAN_PIN.test(valStr);
      if (siblingHintsCredential || (looksLikeTanPin && siblingHintsTanPinContext)) {
        out[key] = '[REDACTED]';
      } else {
        out[key] = redact(val);
      }
    } else {
      out[key] = redact(val);
    }
  }
  return out;
}

/** Maybe apply structural redaction based on flag. */
function maybeRedact(value, doRedact) {
  return doRedact ? redact(value) : value;
}

/**
 * Replace every exact occurrence of each known secret in any string in the tree.
 * Longest-secret-first to avoid partial overlaps. Empty secrets ignored.
 * Never logs secret values.
 * @param {unknown} value
 * @param {string[]} secrets
 * @returns {unknown}
 */
function applySecretsRedaction(value, secrets) {
  const list = (secrets || [])
    .filter((s) => typeof s === 'string' && s.length > 0)
    .slice()
    .sort((a, b) => b.length - a.length);
  if (list.length === 0) return value;

  function walk(v) {
    if (v == null) return v;
    if (typeof v === 'string') {
      let out = v;
      for (const secret of list) {
        if (!secret) continue;
        // Exact substring replacement (all occurrences)
        if (out.includes(secret)) {
          out = out.split(secret).join('[REDACTED]');
        }
      }
      return out;
    }
    if (typeof v !== 'object') return v;
    if (Array.isArray(v)) return v.map(walk);
    const out = {};
    for (const [k, val] of Object.entries(v)) {
      out[k] = walk(val);
    }
    return out;
  }

  return walk(value);
}

/**
 * Distill an already-extracted Playwright trace directory.
 * Reads trace.trace, trace.network, resources/ under rootDir.
 *
 * @param {string} rootDir
 * @param {{
 *   redact?: boolean,
 *   secrets?: string[],
 *   sourcePath?: string,
 *   testCase?: string | null,
 * }} opts
 */
export function distillFromDir(rootDir, opts = {}) {
  const doRedact = opts.redact !== false;
  const secrets = Array.isArray(opts.secrets) ? opts.secrets : [];
  const sourcePath = opts.sourcePath != null ? opts.sourcePath : rootDir;
  const testCase = opts.testCase !== undefined ? opts.testCase : null;

  const events = readNdjson(path.join(rootDir, 'trace.trace'));
  const network = readNdjson(path.join(rootDir, 'trace.network'));
  const screenshotNames = listScreenshots(path.join(rootDir, 'resources'));

  let playwrightVersion = null;
  let browser = null;
  let sdkLanguage = null;
  let startedAt = null;

  /** @type {Map<string, { before?: object, after?: object, logs: string[] }>} */
  const byCall = new Map();

  for (const ev of events) {
    if (!ev || typeof ev !== 'object') continue;
    const t = ev.type;

    if (t === 'context-options') {
      playwrightVersion = ev.playwrightVersion ?? null;
      browser = ev.browserName ?? null;
      sdkLanguage = ev.sdkLanguage ?? null;
      if (ev.wallTime != null && Number.isFinite(Number(ev.wallTime))) {
        startedAt = new Date(Number(ev.wallTime)).toISOString();
      }
      continue;
    }

    if (t === 'before') {
      const id = ev.callId;
      if (!id) continue;
      let entry = byCall.get(id);
      if (!entry) {
        entry = { logs: [] };
        byCall.set(id, entry);
      }
      entry.before = ev;
      continue;
    }

    if (t === 'after') {
      const id = ev.callId;
      if (!id) continue;
      let entry = byCall.get(id);
      if (!entry) {
        entry = { logs: [] };
        byCall.set(id, entry);
      }
      entry.after = ev;
      continue;
    }

    if (t === 'log') {
      const id = ev.callId;
      if (!id) continue;
      let entry = byCall.get(id);
      if (!entry) {
        entry = { logs: [] };
        byCall.set(id, entry);
      }
      if (ev.message != null) entry.logs.push(String(ev.message));
      continue;
    }

    // ignored: event, frame-snapshot, console, screencast-frame (screenshots from resources/)
  }

  // Build actions in first-seen (Map insertion) order
  const actionsAll = [];
  let failedAction = null;

  for (const [callId, entry] of byCall) {
    const before = entry.before;
    if (!before) continue; // need a before to form an action

    const after = entry.after;
    const startTime = before.startTime;
    const endTime = after != null ? after.endTime : undefined;
    let durationMs = null;
    if (
      startTime != null &&
      endTime != null &&
      Number.isFinite(Number(startTime)) &&
      Number.isFinite(Number(endTime))
    ) {
      durationMs = Math.round((Number(endTime) - Number(startTime)) * 1000) / 1000;
    }

    const action = {
      callId,
      class: before.class ?? null,
      method: before.method ?? null,
      params: maybeRedact(before.params ?? {}, doRedact),
      startTime,
      endTime,
      durationMs,
    };

    if (after && after.error) {
      action.error = maybeRedact(after.error, doRedact);
      if (failedAction === null) {
        const logMessages = entry.logs.slice(0, CAPS.logMessages);
        failedAction = {
          ...action,
          logMessages,
        };
      }
    }

    actionsAll.push(action);
  }

  // Network failures
  const networkAll = [];
  for (const line of network) {
    if (!line || line.type !== 'resource-snapshot') continue;
    const snap = line.snapshot;
    if (!snap || typeof snap !== 'object') continue;
    const req = snap.request || {};
    const res = snap.response;
    const method = req.method ?? null;
    let url = req.url ?? null;
    const time = snap.time;
    const status = res && res.status != null ? res.status : undefined;

    let error = null;
    let isFailure = false;

    if (res == null) {
      isFailure = true;
      error = 'no response';
    } else if (time != null && Number(time) < 0) {
      isFailure = true;
      error = `time:${time}`;
    } else if (status != null && Number(status) >= 400) {
      isFailure = true;
      error = `status:${status}`;
    }

    if (!isFailure) continue;

    if (doRedact && typeof url === 'string') {
      url = redactUrlString(url);
    }

    const nf = { method, url };
    if (status !== undefined) nf.status = status;
    if (error != null) nf.error = error;
    networkAll.push(nf);
  }

  // Caps + truncated counts
  const truncated = {
    actions: Math.max(0, actionsAll.length - CAPS.actions),
    networkFailures: Math.max(0, networkAll.length - CAPS.networkFailures),
    screenshots: Math.max(0, screenshotNames.length - CAPS.screenshots),
    logLines: 0,
  };

  // logLines: count of dropped log messages across the failed action only
  // (per-action cap). Track excess for the first failed action's logs.
  if (failedAction) {
    const callId = failedAction.callId;
    const entry = byCall.get(callId);
    if (entry && entry.logs.length > CAPS.logMessages) {
      truncated.logLines = entry.logs.length - CAPS.logMessages;
    }
  }

  const actions = actionsAll.slice(0, CAPS.actions);
  const networkFailures = networkAll.slice(0, CAPS.networkFailures);
  const screenshots = screenshotNames.slice(0, CAPS.screenshots);

  let result = {
    source: sourcePath,
    playwrightVersion,
    browser,
    sdkLanguage,
    startedAt,
    testCase,
    redacted: doRedact,
    actions,
    failedAction,
    networkFailures,
    screenshots,
    truncated,
  };

  // Defense-in-depth (d): exact known secrets anywhere in output strings
  if (secrets.length > 0) {
    result = /** @type {typeof result} */ (applySecretsRedaction(result, secrets));
  }

  return result;
}

/**
 * Distill a Playwright traces.zip into a compact JSON summary.
 * Extracts to a temp dir, delegates to distillFromDir, cleans up.
 * @param {string} zipPath
 * @param {{ redact?: boolean, secrets?: string[] }} opts
 */
export function distillTrace(zipPath, opts = {}) {
  const doRedact = opts.redact !== false;
  const secrets = Array.isArray(opts.secrets) ? opts.secrets : [];
  const absSource = path.resolve(zipPath);
  if (!fs.existsSync(absSource)) {
    die(`Error: trace zip not found: ${absSource}`);
  }

  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'distill-trace-'));
  try {
    extractZip(absSource, tmpDir);

    // Playwright zips sometimes nest contents one level deep
    let root = tmpDir;
    const topEntries = fs.readdirSync(tmpDir);
    if (!fs.existsSync(path.join(tmpDir, 'trace.trace'))) {
      for (const name of topEntries) {
        const candidate = path.join(tmpDir, name);
        if (
          fs.statSync(candidate).isDirectory() &&
          fs.existsSync(path.join(candidate, 'trace.trace'))
        ) {
          root = candidate;
          break;
        }
      }
    }

    // testCase from parent dir basename of the zip (same as before the split)
    let testCase = null;
    try {
      const parent = path.basename(path.dirname(absSource));
      if (parent && parent !== '.' && parent !== path.parse(absSource).root) {
        testCase = parent;
      }
    } catch {
      testCase = null;
    }

    return distillFromDir(root, {
      redact: doRedact,
      secrets,
      sourcePath: absSource,
      testCase,
    });
  } finally {
    fs.rmSync(tmpDir, { recursive: true, force: true });
  }
}

function main() {
  const args = parseArgs(process.argv.slice(2));
  if (!args.trace) {
    die(
      'Usage: node tools/distill-trace.mjs --trace <path to traces.zip> [--json <out>] [--no-redact] [--secrets-file <path>]',
    );
  }

  if (!args.redact) {
    console.error('========================================');
    console.error('  WARNING');
    console.error('  Redaction is DISABLED (--no-redact).');
    console.error('  Trace credentials may be written in cleartext.');
    console.error('========================================');
  }

  /** @type {string[]} */
  let secrets = [];
  if (args.secretsFile) {
    secrets = loadSecretsFile(args.secretsFile);
  }

  const result = distillTrace(args.trace, { redact: args.redact, secrets });
  const pretty = JSON.stringify(result, null, 2);

  if (args.json) {
    fs.writeFileSync(args.json, pretty + '\n', 'utf8');
  } else {
    process.stdout.write(pretty + '\n');
  }
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  main();
}
