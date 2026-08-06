#!/usr/bin/env node
/**
 * redact-artifacts — pre-sharing gate over a collected artifact directory.
 *
 * Scans every text-bearing file of an artifact tree (as produced by
 * tools/collect-artifacts.mjs) for credentials and personal data, and reports
 * image/video/archive files as NOT machine-reviewable, requiring an explicit
 * human decision before anything is shared.
 *
 * Report-only by default (exit 1 on any finding, so it can gate a pipeline).
 * `--apply` writes a redacted COPY into a separate output directory; the input
 * tree is never modified.
 *
 * Node stdlib only, no network access. See tools/README-redact-artifacts.md.
 */

import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { pathToFileURL } from 'node:url';

// Reuse the distiller's credential vocabulary verbatim so the two tools cannot
// disagree about what counts as a secret field label (EN + DE banking terms).
import {
  CREDENTIAL_FIELD_HINT,
  SECRET_FIELD_HINT_TERMS,
  normalizeHintText,
} from './distill-trace.mjs';

const EXIT_CLEAN = 0;
const EXIT_FINDINGS = 1;
const EXIT_USAGE = 2;

/** Category id -> German label used in the human summary. */
const CATEGORY_LABELS = {
  zugangsdaten: 'Zugangsdaten (Kennwort / PIN / TAN / Token)',
  iban: 'IBAN',
  kontonummer: 'Kontonummer',
  kundennummer: 'Kunden- / Partnernummer',
  email: 'E-Mail-Adresse',
  telefon: 'Telefonnummer',
  geburtsdatum: 'Geburtsdatum',
  name: 'Name in bekanntem Feldlabel',
};

/**
 * Overlap precedence (lower wins). A basic-auth URL `//user:pass@host` also looks
 * like an e-mail address; the credential reading must win, otherwise a leaked
 * password is filed under "E-Mail" and the credential finding is lost.
 */
const CATEGORY_PRIORITY = {
  zugangsdaten: 0,
  iban: 1,
  kontonummer: 1,
  kundennummer: 1,
  geburtsdatum: 2,
  name: 2,
  email: 3,
  telefon: 3,
};

/** Stable output order for the summary. */
const CATEGORY_ORDER = [
  'zugangsdaten',
  'iban',
  'kontonummer',
  'kundennummer',
  'geburtsdatum',
  'name',
  'email',
  'telefon',
];

const TEXT_EXT = new Set([
  '.txt', '.log', '.json', '.jsonl', '.ndjson', '.js', '.mjs', '.cjs',
  '.html', '.htm', '.har', '.xml', '.csv', '.tsv', '.md', '.yaml', '.yml',
  '.css', '.svg', '.properties', '.ini', '.conf', '.cfg', '.vtt', '.srt',
]);
const IMAGE_EXT = new Set([
  '.png', '.jpg', '.jpeg', '.gif', '.bmp', '.webp', '.tif', '.tiff', '.ico', '.avif',
]);
const VIDEO_EXT = new Set(['.mp4', '.webm', '.mov', '.avi', '.mkv', '.ogv', '.m4v']);
const ARCHIVE_EXT = new Set(['.zip', '.gz', '.tgz', '.tar', '.7z', '.rar', '.jar', '.bz2']);

/** Directories skipped by default: report boilerplate and VCS/dependency noise. */
const DEFAULT_SKIP_DIRS = new Set(['media', '.git', 'node_modules']);

/** Files written by this tool itself; never re-scanned, never overwritten silently. */
const REPORT_FILE_NAME = 'REDAKTIONSBERICHT.json';

/**
 * Values that look like a placeholder / boolean / already-redacted marker and are
 * therefore NOT treated as a leaked secret.
 */
const PLACEHOLDER_VALUE =
  /^(?:%[^%]*%?|\$\{[^}]*\}?|\{\{[^}]*\}?\}?|\[[^\]]*\]?|<[^>]*>?|\*{2,}|x{3,}|\.{3,}|-{1,3}|_{2,}|(?:ja|nein|true|false|null|none|leer|empty|undefined|n\/a|na|redacted|redigiert|unknown|unbekannt)|\[REDACTED\]|\[REDIGIERT[^\]]*\])$/i;

/**
 * Mirror of the non-exported `SENSITIVE_KEY` constant in tools/distill-trace.mjs.
 * That module exports the German/English field-hint list but not this key regex,
 * and distill-trace.mjs must not be edited, so it is duplicated here verbatim.
 * DRIFT RISK: if SENSITIVE_KEY changes there, change it here too.
 */
const SENSITIVE_KEY_MIRROR = /pass|pwd|secret|token|credential|authorization|apikey/i;

/**
 * Words that merely CONTAIN a hint substring but never mark a secret field.
 * Measured, not guessed: every INGenious run log ends with
 * "Total number of Steps Passed : 9", which the distiller's loose `pass` term
 * matches. Those words are stripped from the label before hint matching, so the
 * shared vocabulary stays intact while the false alarm disappears.
 */
const NON_SECRET_LABEL_WORDS =
  /\b(?:passed|passes|passing|passiv\w*|passung|passage|passagier\w*|passend\w*|passt|passier\w*|compass|bypass|passiv)\b/g;

/**
 * LOOSE label test — the distiller's vocabulary applied exactly as the distiller
 * applies it (substring match anywhere in the label). Used where the label is a
 * genuine UI field name, e.g. the INGenious `on '[Kennwort]'` log form.
 */
function isCredentialLabelLoose(label) {
  const normalized = normalizeHintText(label).replace(NON_SECRET_LABEL_WORDS, ' ');
  return CREDENTIAL_FIELD_HINT.test(normalized) || SENSITIVE_KEY_MIRROR.test(normalized);
}

/** Wrap a hint term in word boundaries unless it already carries its own. */
function asWholeWord(term) {
  return term.includes('\\b') ? `(?:${term})` : `\\b(?:${term})\\b`;
}

const CREDENTIAL_FIELD_HINT_WORD = new RegExp(
  `(?:${SECRET_FIELD_HINT_TERMS.map(asWholeWord).join('|')})`,
  'i',
);
const SENSITIVE_KEY_WORD = /\b(?:pass|pwd|secret|token|credential|authorization|apikey)\b/i;

/**
 * STRICT label test — used by the generic `label: value` rule, whose "label" is
 * just arbitrary text to the left of a colon and therefore hits report
 * scaffolding constantly. Measured on a real run: the loose test fired
 * on `nopassTests`, `passFailChart` and `pass.nodes` in the INGenious HTML/JS
 * report, i.e. 4 false alarms per run. Two narrowings remove them:
 *   1. the hint must match as a whole word, not glued inside an identifier;
 *   2. only the last dot-separated segment counts, so `pass.nodes` is a member
 *      expression, while `login.passwort` still matches.
 * Known false negative of this narrowing: glued identifiers such as `userPwd`
 * or `myPasswordField` are NOT flagged by this rule (README documents it).
 */
function isCredentialLabelStrict(label) {
  const normalized = normalizeHintText(label).replace(NON_SECRET_LABEL_WORDS, ' ');
  const segments = normalized.split('.');
  const last = segments[segments.length - 1];
  return CREDENTIAL_FIELD_HINT_WORD.test(last) || SENSITIVE_KEY_WORD.test(last);
}

/**
 * A one- or two-character value is never a usable credential, but it is very
 * often a JS/JSON scaffolding artefact (`"nopassTests":2`). Documented limit.
 */
const MIN_CREDENTIAL_VALUE_LENGTH = 3;

/**
 * Separator between a field label and its value, tolerant of JSON/HTML quoting:
 * `Kontonummer: 123`, `"kontonummer": "123"`, `kontonummer=123`, `Konto-Nr. #123`.
 */
const SEP = '["\'`]?\\s*[:=#]?\\s*["\'`]?';

/**
 * Detection rules. Every regex carries the `d` flag so the exact span of the
 * value group is known and can be replaced precisely on --apply.
 *
 * IMPORTANT design boundary: rules for Kontonummer / Kundennummer /
 * Geburtsdatum / Name / Telefon are LABEL-ANCHORED on purpose. A bare 10-digit
 * number or a bare date cannot be told apart from a timestamp, a step id or a
 * duration, and matching them unanchored floods the report with noise. This is
 * a deliberate false-negative trade-off, documented in the README.
 */
const RULES = [
  // --- Zugangsdaten -------------------------------------------------------
  {
    id: 'ingenious-entered-text',
    category: 'zugangsdaten',
    // INGenious run log: [DONE] | Entered Text 's3cret' on '[Password]'
    re: /Entered Text\s+'([^']*)'\s+on\s+'\[([^\]]*)\]'/gd,
    valueGroup: 1,
    guard: (m) => isCredentialLabelLoose(m[2]) && !PLACEHOLDER_VALUE.test(m[1]),
  },
  {
    id: 'credential-label-value',
    category: 'zugangsdaten',
    // Generic `Kennwort: geheim`, `"password": "s3cret"`, `pin=1234`, `tan = 998877`
    re: /["'`]?([A-Za-zÄÖÜäöüß][A-Za-zÄÖÜäöüß0-9 ._-]{1,39})["'`]?\s*[:=]\s*["'`]?([^\s"'`,;}\]]{1,120})/gd,
    valueGroup: 2,
    guard: (m) =>
      isCredentialLabelStrict(m[1]) &&
      m[2].length >= MIN_CREDENTIAL_VALUE_LENGTH &&
      !PLACEHOLDER_VALUE.test(m[2]),
  },
  {
    id: 'authorization-header',
    category: 'zugangsdaten',
    re: /(?:proxy-)?authorization["'`]?\s*[:=]\s*["'`]?((?:Bearer|Basic|Negotiate|NTLM)\s+[^\s"'`,;]+)/gid,
    valueGroup: 1,
  },
  {
    id: 'jwt',
    category: 'zugangsdaten',
    re: /\b(eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{4,})/gd,
    valueGroup: 1,
  },
  {
    id: 'basic-auth-url',
    category: 'zugangsdaten',
    // //user:pass@host — same shape the distiller redacts.
    re: /\/\/([^/@\s:]+:[^/@\s]+)@/gd,
    valueGroup: 1,
  },

  // --- Bankdaten ----------------------------------------------------------
  {
    id: 'iban',
    category: 'iban',
    re: /\b([A-Z]{2}\d{2}(?:[ ]?[A-Z0-9]{4}){2,7}(?:[ ]?[A-Z0-9]{1,3})?)\b/gd,
    valueGroup: 1,
    // mod-97 checksum: keeps random uppercase alphanumeric blobs out of the report.
    guard: (m) => isValidIban(m[1]),
  },
  {
    id: 'kontonummer-label',
    category: 'kontonummer',
    re: new RegExp(
      `(?:konto[ _-]?nummer|konto[ _-]?nr\\.?|kto\\.?[ _-]?nr\\.?|account[ _-]?number)\\b${SEP}(\\d[\\d .-]{4,16}\\d)`,
      'gid',
    ),
    valueGroup: 1,
  },
  {
    id: 'kundennummer-label',
    category: 'kundennummer',
    re: new RegExp(
      `(?:kunden[ _-]?nummer|kunden[ _-]?nr\\.?|partner[ _-]?nummer|partner[ _-]?nr\\.?|kd\\.?[ _-]?nr\\.?|customer[ _-]?(?:number|id)|partner[ _-]?id)\\b${SEP}([A-Za-z]?\\d[\\d .-]{2,16}\\d)`,
      'gid',
    ),
    valueGroup: 1,
  },

  // --- Personendaten ------------------------------------------------------
  {
    id: 'email',
    category: 'email',
    re: /\b([A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,24})\b/gd,
    valueGroup: 1,
  },
  {
    id: 'telefon-label',
    category: 'telefon',
    re: new RegExp(
      `(?:telefon[ _-]?nummer|telefon|tel\\.?|mobil(?:funk)?[ _-]?nummer|mobil|handy|rufnummer|phone[ _-]?number|phone)\\b${SEP}(\\+?\\d[\\d ()/-]{5,20}\\d)`,
      'gid',
    ),
    valueGroup: 1,
  },
  {
    id: 'telefon-international-de',
    category: 'telefon',
    re: /((?:\+49|0049)[ \/-]?\d[\d ()\/-]{4,18}\d)/gd,
    valueGroup: 1,
  },
  {
    id: 'geburtsdatum-label',
    category: 'geburtsdatum',
    re: new RegExp(
      `(?:geburts[ _-]?datum|geburts[ _-]?tag|geb\\.?[ _-]?(?:am|dat\\.?)|gebdat|date[ _-]?of[ _-]?birth|dob)\\b${SEP}(\\d{1,2}[./-]\\d{1,2}[./-]\\d{2,4}|\\d{4}-\\d{2}-\\d{2})`,
      'gid',
    ),
    valueGroup: 1,
  },
  {
    id: 'name-label',
    category: 'name',
    // Only explicit person-name labels. A bare `name:` is NOT matched — technical
    // keys (os.name, java.runtime.name, scenarioName, "name" in report JSON)
    // would otherwise dominate the report. Documented false negative.
    re: new RegExp(
      `(?<![\\w.])(?:vor[ _-]?name|nach[ _-]?name|familien[ _-]?name|nachnamen|voller[ _-]?name|vollst(?:ä|ae)ndiger[ _-]?name|konto[ _-]?inhaber(?:in)?|zahlungsempf(?:ä|ae)nger|empf(?:ä|ae)nger|beg(?:ü|ue)nstigter|auftraggeber|first[ _-]?name|last[ _-]?name|sur[ _-]?name)\\b${SEP}` +
        `([A-ZÄÖÜ][A-Za-zÄÖÜäöüß.'-]+(?:\\s+[A-ZÄÖÜ][A-Za-zÄÖÜäöüß.'-]+){0,3})`,
      'gid',
    ),
    valueGroup: 1,
  },
];

/** Byte-exact IBAN mod-97 check (ISO 13616). */
export function isValidIban(raw) {
  const compact = String(raw).replace(/[ \-]/g, '').toUpperCase();
  if (!/^[A-Z]{2}\d{2}[A-Z0-9]{11,30}$/.test(compact)) return false;
  if (compact.length < 15 || compact.length > 34) return false;
  const rearranged = compact.slice(4) + compact.slice(0, 4);
  let remainder = 0;
  for (const ch of rearranged) {
    const digits = ch >= 'A' && ch <= 'Z' ? String(ch.charCodeAt(0) - 55) : ch;
    for (const d of digits) {
      remainder = (remainder * 10 + Number(d)) % 97;
    }
  }
  return remainder === 1;
}

/**
 * Scan a single line and return every finding span, overlap-free.
 * @param {string} line
 * @returns {{ ruleId: string, category: string, start: number, end: number, length: number }[]}
 */
export function scanLine(line) {
  const hits = [];
  for (const rule of RULES) {
    rule.re.lastIndex = 0;
    let m;
    while ((m = rule.re.exec(line)) !== null) {
      if (m[0].length === 0) {
        rule.re.lastIndex += 1;
        continue;
      }
      if (rule.guard && !rule.guard(m)) continue;
      const span = m.indices && m.indices[rule.valueGroup];
      if (!span) continue;
      hits.push({
        ruleId: rule.id,
        category: rule.category,
        start: span[0],
        end: span[1],
        length: span[1] - span[0],
      });
    }
  }
  // Highest precedence first, then longest, then leftmost; drop anything
  // overlapping an already-kept span.
  hits.sort(
    (a, b) =>
      (CATEGORY_PRIORITY[a.category] ?? 9) - (CATEGORY_PRIORITY[b.category] ?? 9) ||
      b.length - a.length ||
      a.start - b.start,
  );
  const kept = [];
  for (const h of hits) {
    if (kept.some((k) => h.start < k.end && k.start < h.end)) continue;
    kept.push(h);
  }
  kept.sort((a, b) => a.start - b.start);
  return kept;
}

/** Replacement marker written into the redacted copy. */
function marker(category) {
  return `[REDIGIERT:${category.toUpperCase()}]`;
}

/**
 * Rewrite a line with every finding span replaced by its category marker.
 * @param {string} line
 * @param {ReturnType<typeof scanLine>} hits
 */
export function redactLine(line, hits) {
  if (hits.length === 0) return line;
  let out = '';
  let cursor = 0;
  for (const h of hits) {
    out += line.slice(cursor, h.start) + marker(h.category);
    cursor = h.end;
  }
  return out + line.slice(cursor);
}

/**
 * Excerpt for the report: a window of the source line with EVERY secret on that
 * line already removed, centred on `focus`. The report itself must be safe to
 * share, so the matched value is NEVER copied into it — only its length is
 * reported. Centring matters because report files (data.js, *-v2.html) hold the
 * whole payload on a single 100 kB line.
 */
function safeExcerpt(line, hits, focus, maxLen = 160) {
  const redacted = redactLine(line, hits);
  const label = marker(focus.category);
  // Offset shift caused by replacements that precede `focus` on this line.
  let delta = 0;
  for (const h of hits) {
    if (h === focus) break;
    delta += marker(h.category).length - (h.end - h.start);
  }
  const pos = focus.start + delta;
  const pad = Math.max(20, Math.floor((maxLen - label.length) / 2));
  const start = Math.max(0, pos - pad);
  const end = Math.min(redacted.length, pos + label.length + pad);
  let out = redacted.slice(start, end).replace(/\s+/g, ' ').trim();
  if (start > 0) out = `…${out}`;
  if (end < redacted.length) out = `${out}…`;
  return out;
}

function classify(filePath) {
  const ext = path.extname(filePath).toLowerCase();
  if (IMAGE_EXT.has(ext)) return 'bild';
  if (VIDEO_EXT.has(ext)) return 'video';
  if (ARCHIVE_EXT.has(ext)) return 'archiv';
  if (TEXT_EXT.has(ext)) return 'text';
  return 'unbekannt';
}

/** Sniff for NUL bytes to decide whether an unknown extension is text. */
function looksBinary(absPath) {
  let fd;
  try {
    fd = fs.openSync(absPath, 'r');
    const buf = Buffer.alloc(8192);
    const read = fs.readSync(fd, buf, 0, buf.length, 0);
    return buf.subarray(0, read).includes(0);
  } catch {
    return true;
  } finally {
    if (fd !== undefined) {
      try {
        fs.closeSync(fd);
      } catch {
        /* ignore */
      }
    }
  }
}

/** Recursively list regular files, honouring the skip-dir set. */
function listFiles(root, opts, rel = '', out = []) {
  let entries;
  try {
    entries = fs.readdirSync(path.join(root, rel), { withFileTypes: true });
  } catch (err) {
    out.push({ rel: rel || '.', unreadable: err.message });
    return out;
  }
  for (const ent of entries) {
    const childRel = rel ? `${rel}/${ent.name}` : ent.name;
    if (ent.isSymbolicLink()) continue;
    if (ent.isDirectory()) {
      // .git / node_modules are always skipped; media/ only unless --include-media.
      const alwaysSkip = ent.name === '.git' || ent.name === 'node_modules';
      if (alwaysSkip || (DEFAULT_SKIP_DIRS.has(ent.name) && !opts.includeMedia)) {
        out.push({ rel: childRel, skippedDir: true });
        continue;
      }
      listFiles(root, opts, childRel, out);
    } else if (ent.isFile()) {
      out.push({ rel: childRel });
    }
  }
  return out;
}

/**
 * Scan an artifact directory.
 * @param {string} dir
 * @param {{ includeMedia?: boolean, maxBytes?: number }} opts
 */
export function scanArtifacts(dir, opts = {}) {
  const includeMedia = opts.includeMedia === true;
  const maxBytes = Number.isFinite(opts.maxBytes) ? opts.maxBytes : 5 * 1024 * 1024;
  const root = path.resolve(dir);

  const findings = [];
  const unreviewedBinaries = [];
  const skipped = [];
  /** @type {{rel: string, hitsByLine: Map<number, ReturnType<typeof scanLine>>}[]} */
  const textFiles = [];
  let filesScanned = 0;

  for (const entry of listFiles(root, { includeMedia })) {
    if (entry.unreadable) {
      skipped.push({ file: entry.rel, art: 'ungeprueft', reason: `nicht lesbar: ${entry.unreadable}` });
      continue;
    }
    if (entry.skippedDir) {
      skipped.push({
        file: entry.rel,
        art: 'ausgeschlossen',
        reason: includeMedia
          ? 'Verzeichnis übersprungen (VCS/Abhängigkeiten)'
          : 'Verzeichnis übersprungen (Report-Boilerplate / VCS) — mit --include-media einbeziehen',
      });
      continue;
    }
    if (entry.rel === REPORT_FILE_NAME) continue;

    const abs = path.join(root, entry.rel);
    let stat;
    try {
      stat = fs.statSync(abs);
    } catch (err) {
      skipped.push({ file: entry.rel, art: 'ungeprueft', reason: `nicht lesbar: ${err.message}` });
      continue;
    }

    let kind = classify(entry.rel);
    if (kind === 'unbekannt') kind = looksBinary(abs) ? 'binaer' : 'text';

    if (kind !== 'text') {
      unreviewedBinaries.push({ file: entry.rel, kind, sizeBytes: stat.size });
      continue;
    }

    if (stat.size > maxBytes) {
      skipped.push({
        file: entry.rel,
        art: 'ungeprueft',
        reason: `größer als --max-bytes (${stat.size} > ${maxBytes}) — NICHT geprüft`,
      });
      continue;
    }

    let content;
    try {
      content = fs.readFileSync(abs, 'utf8');
    } catch (err) {
      skipped.push({ file: entry.rel, art: 'ungeprueft', reason: `nicht lesbar: ${err.message}` });
      continue;
    }

    filesScanned += 1;
    const lines = content.split('\n');
    const hitsByLine = new Map();
    for (let i = 0; i < lines.length; i++) {
      const line = lines[i];
      const hits = scanLine(line);
      if (hits.length === 0) continue;
      hitsByLine.set(i, hits);
      for (const h of hits) {
        findings.push({
          category: h.category,
          categoryLabel: CATEGORY_LABELS[h.category] ?? h.category,
          rule: h.ruleId,
          file: entry.rel,
          line: i + 1,
          column: h.start + 1,
          matchLength: h.length,
          excerpt: safeExcerpt(line, hits, h),
        });
      }
    }
    textFiles.push({ rel: entry.rel, hitsByLine });
  }

  findings.sort(
    (a, b) =>
      CATEGORY_ORDER.indexOf(a.category) - CATEGORY_ORDER.indexOf(b.category) ||
      (a.file < b.file ? -1 : a.file > b.file ? 1 : 0) ||
      a.line - b.line ||
      a.column - b.column,
  );
  unreviewedBinaries.sort((a, b) => (a.file < b.file ? -1 : a.file > b.file ? 1 : 0));
  skipped.sort((a, b) => (a.file < b.file ? -1 : a.file > b.file ? 1 : 0));

  const byCategory = {};
  for (const f of findings) byCategory[f.category] = (byCategory[f.category] ?? 0) + 1;

  return {
    tool: 'redact-artifacts',
    scannedDir: root,
    scannedAt: new Date().toISOString(),
    counts: {
      filesScanned,
      filesSkipped: skipped.length,
      textFindings: findings.length,
      binariesUnreviewed: unreviewedBinaries.length,
    },
    byCategory,
    findings,
    unreviewedBinaries,
    skipped,
    // Internal: not serialised to JSON output.
    _textFiles: textFiles,
  };
}

/**
 * Write a redacted copy of the tree into outDir. Text files are rewritten with
 * every finding replaced; binaries are NOT copied unless copyBinaries is set,
 * because their content cannot be cleared by a regex.
 */
function applyRedaction(root, outDir, result, opts) {
  fs.mkdirSync(outDir, { recursive: true });
  let written = 0;
  for (const tf of result._textFiles) {
    const src = path.join(root, tf.rel);
    const dest = path.join(outDir, tf.rel);
    fs.mkdirSync(path.dirname(dest), { recursive: true });
    const lines = fs.readFileSync(src, 'utf8').split('\n');
    for (const [idx, hits] of tf.hitsByLine) {
      lines[idx] = redactLine(lines[idx], hits);
    }
    fs.writeFileSync(dest, lines.join('\n'), 'utf8');
    written += 1;
  }

  let binariesCopied = 0;
  if (opts.copyBinaries) {
    for (const b of result.unreviewedBinaries) {
      const dest = path.join(outDir, b.file);
      fs.mkdirSync(path.dirname(dest), { recursive: true });
      fs.copyFileSync(path.join(root, b.file), dest);
      binariesCopied += 1;
    }
  }
  return { written, binariesCopied };
}

function helpText() {
  return `redact-artifacts — Freigabe-Prüfung für gesammelte Test-Artefakte

  Prüft ein Artefakt-Verzeichnis (Ausgabe von tools/collect-artifacts.mjs) auf
  Zugangsdaten und Personendaten in Textdateien und meldet Bild-, Video- und
  Archivdateien als NICHT maschinell prüfbar.

Aufruf:
  node tools/redact-artifacts.mjs --dir <artefakt-verzeichnis> [Optionen]

Optionen:
  --dir <pfad>            Zu prüfendes Verzeichnis (Pflicht)
  --apply                 Redigierte KOPIE schreiben (benötigt --out)
  --out <pfad>            Zielverzeichnis der Kopie (muss außerhalb von --dir liegen)
  --copy-binaries         Bilder/Videos/Archive unverändert mitkopieren (UNGEPRÜFT!)
  --binaries-reviewed     Bestätigt, dass ein Mensch die Binärdateien geprüft hat
  --include-media         Auch das Report-Boilerplate-Verzeichnis media/ prüfen
  --max-bytes <n>         Größenlimit pro Textdatei (Standard 5242880)
  --json                  Maschinenlesbare Ausgabe (JSON) statt deutscher Zusammenfassung
  --help, -h              Diese Hilfe

Exit-Codes:
  0  nichts gefunden (bzw. nur Binärdateien, und --binaries-reviewed gesetzt)
  1  Funde vorhanden ODER ungeprüfte Binärdateien -> NICHT freigeben
  2  Aufruf-/E-A-Fehler

Grenzen: siehe tools/README-redact-artifacts.md, Abschnitt
"Was dieses Werkzeug NICHT kann". Screenshots und Videos werden NIEMALS
inhaltlich geprüft.
`;
}

function parseArgs(argv) {
  const args = {
    dir: null,
    apply: false,
    out: null,
    copyBinaries: false,
    binariesReviewed: false,
    includeMedia: false,
    maxBytes: 5 * 1024 * 1024,
    json: false,
    help: false,
    unknown: [],
  };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--dir') args.dir = argv[++i];
    else if (a === '--out') args.out = argv[++i];
    else if (a === '--apply') args.apply = true;
    else if (a === '--copy-binaries') args.copyBinaries = true;
    else if (a === '--binaries-reviewed') args.binariesReviewed = true;
    else if (a === '--include-media') args.includeMedia = true;
    else if (a === '--max-bytes') args.maxBytes = Number(argv[++i]);
    else if (a === '--json') args.json = true;
    else if (a === '--help' || a === '-h') args.help = true;
    else args.unknown.push(a);
  }
  return args;
}

function die(msg) {
  console.error(msg);
  process.exit(EXIT_USAGE);
}

/** True when `child` is inside `parent` (or equal). */
function isInside(parent, child) {
  const rel = path.relative(parent, child);
  return rel === '' || (!rel.startsWith('..') && !path.isAbsolute(rel));
}

function printSummary(result, opts) {
  const c = result.counts;
  const out = [];
  out.push('');
  out.push('Redaktionsprüfung (Freigabe vor Weitergabe)');
  out.push('===========================================');
  out.push(`Verzeichnis            : ${result.scannedDir}`);
  out.push(`Modus                  : ${opts.apply ? 'KOPIE REDIGIEREN (--apply)' : 'nur Bericht'}`);
  out.push(`Textdateien geprüft    : ${c.filesScanned}`);
  out.push(`Übersprungen           : ${c.filesSkipped}`);
  out.push(`Textfunde              : ${c.textFindings}`);
  out.push(`Ungeprüfte Binärdateien: ${c.binariesUnreviewed}`);
  out.push('');

  if (c.textFindings === 0) {
    out.push('Keine Textfunde. (Das heißt NICHT, dass die Artefakte sauber sind —');
    out.push('siehe Abschnitt zu Bildern/Videos weiter unten.)');
  } else {
    out.push('Funde nach Kategorie');
    out.push('--------------------');
    for (const cat of CATEGORY_ORDER) {
      const items = result.findings.filter((f) => f.category === cat);
      if (items.length === 0) continue;
      out.push(`${CATEGORY_LABELS[cat]}  (${items.length})`);
      for (const f of items) {
        out.push(`  ${f.file}:${f.line}:${f.column}  [${f.rule}, ${f.matchLength} Zeichen]`);
        out.push(`      ${f.excerpt}`);
      }
      out.push('');
    }
  }

  out.push('Nicht maschinell prüfbar — menschliche Entscheidung erforderlich');
  out.push('----------------------------------------------------------------');
  if (c.binariesUnreviewed === 0) {
    out.push('  (keine Bild-, Video- oder Archivdateien gefunden)');
  } else {
    out.push('  Ein regulärer Ausdruck kann den Inhalt eines Screenshots nicht prüfen.');
    out.push('  Screenshots der echten Bankanwendung sind das höchste Risiko: sie zeigen');
    out.push('  Kontostände, Namen, IBANs und Kundennummern im Klartext als Pixel.');
    out.push('  Diese Dateien müssen VOR der Weitergabe von einem Menschen gesichtet und');
    out.push('  freigegeben oder gelöscht werden:');
    const byKind = {};
    for (const b of result.unreviewedBinaries) {
      (byKind[b.kind] ??= []).push(b);
    }
    const kindLabel = {
      bild: 'Bilder (Screenshots)',
      video: 'Videos / Aufzeichnungen',
      archiv: 'Archive (Inhalt ungeprüft, z. B. traces.zip)',
      binaer: 'sonstige Binärdateien',
    };
    for (const kind of ['bild', 'video', 'archiv', 'binaer']) {
      const items = byKind[kind];
      if (!items) continue;
      out.push('');
      out.push(`  ${kindLabel[kind]}  (${items.length})`);
      for (const b of items) out.push(`    ${b.file}  (${b.sizeBytes} Bytes)`);
    }
    if (opts.binariesReviewed) {
      out.push('');
      out.push('  --binaries-reviewed gesetzt: Sichtung wurde ausdrücklich bestätigt.');
    }
  }
  out.push('');

  if (result.skipped.length > 0) {
    out.push('Übersprungen (nicht geprüft)');
    out.push('----------------------------');
    for (const s of result.skipped) out.push(`  ${s.file}: ${s.reason}`);
    out.push('');
  }

  const blocked = c.textFindings > 0 || (c.binariesUnreviewed > 0 && !opts.binariesReviewed);
  out.push(
    blocked
      ? 'ERGEBNIS: NICHT FREIGEGEBEN — vor Weitergabe bereinigen bzw. Binärdateien sichten.'
      : 'ERGEBNIS: FREIGEGEBEN — keine offenen Funde.',
  );
  out.push('');
  console.log(out.join('\n'));
}

function main() {
  const args = parseArgs(process.argv.slice(2));

  if (args.help) {
    console.log(helpText());
    process.exit(EXIT_CLEAN);
  }
  if (args.unknown.length > 0) {
    die(`Fehler: unbekannte Option(en): ${args.unknown.join(' ')}\n\n${helpText()}`);
  }
  if (!args.dir) {
    die(`Fehler: --dir ist erforderlich.\n\n${helpText()}`);
  }
  if (!Number.isFinite(args.maxBytes) || args.maxBytes <= 0) {
    die('Fehler: --max-bytes muss eine positive Zahl sein.');
  }

  const root = path.resolve(args.dir);
  let st;
  try {
    st = fs.statSync(root);
  } catch {
    die(`Fehler: Verzeichnis nicht gefunden: ${root}`);
  }
  if (!st.isDirectory()) die(`Fehler: kein Verzeichnis: ${root}`);

  let outDir = null;
  if (args.apply) {
    if (!args.out) die('Fehler: --apply benötigt --out <zielverzeichnis>.');
    outDir = path.resolve(args.out);
    if (isInside(root, outDir) || isInside(outDir, root)) {
      die(
        `Fehler: --out darf nicht innerhalb von --dir liegen (und umgekehrt).\n` +
          `  --dir: ${root}\n  --out: ${outDir}\n` +
          `  Redigiert wird immer in eine getrennte Kopie, nie in-place.`,
      );
    }
  } else if (args.out) {
    die('Fehler: --out ist nur zusammen mit --apply sinnvoll.');
  }

  const result = scanArtifacts(root, {
    includeMedia: args.includeMedia,
    maxBytes: args.maxBytes,
  });

  let applied = null;
  if (args.apply) {
    applied = applyRedaction(root, outDir, result, { copyBinaries: args.copyBinaries });
  }

  const doc = {
    tool: result.tool,
    scannedDir: result.scannedDir,
    scannedAt: result.scannedAt,
    mode: args.apply ? 'apply' : 'report',
    outDir,
    binariesReviewed: args.binariesReviewed,
    counts: result.counts,
    byCategory: result.byCategory,
    findings: result.findings,
    unreviewedBinaries: result.unreviewedBinaries,
    skipped: result.skipped,
    applied,
  };

  if (args.apply) {
    fs.writeFileSync(
      path.join(outDir, REPORT_FILE_NAME),
      `${JSON.stringify(doc, null, 2)}\n`,
      'utf8',
    );
  }

  if (args.json) {
    process.stdout.write(`${JSON.stringify(doc, null, 2)}\n`);
  } else {
    printSummary(result, args);
    if (applied) {
      console.log(`Redigierte Kopie: ${outDir}`);
      console.log(`  Textdateien geschrieben : ${applied.written}`);
      console.log(
        `  Binärdateien kopiert    : ${applied.binariesCopied}` +
          (args.copyBinaries
            ? '  (UNGEPRÜFT mitkopiert — --copy-binaries)'
            : '  (bewusst NICHT kopiert; --copy-binaries erzwingt es)'),
      );
      console.log(`  Bericht                 : ${path.join(outDir, REPORT_FILE_NAME)}`);
      console.log('');
    }
  }

  const blocked =
    result.counts.textFindings > 0 ||
    (result.counts.binariesUnreviewed > 0 && !args.binariesReviewed);
  process.exit(blocked ? EXIT_FINDINGS : EXIT_CLEAN);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main();
}
