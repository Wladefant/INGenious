#!/usr/bin/env node
/**
 * Rule-based failure classifier for one INGenious run directory.
 *
 * Reads the run's parsed report (parse-report.mjs) and its redacted distilled
 * Playwright traces (distill-trace.mjs), then labels every FAILing test case with
 * exactly one category:
 *
 *   selector-instabil | timing | testdaten | berechtigung | netzwerk |
 *   anwendungsfehler  | unklar
 *
 * Rules only. No LLM, no network, no heuristic scoring beyond the documented
 * ordered rule chain. Every verdict carries the concrete evidence that produced
 * it (which action, which error text, which signal). Node stdlib only.
 *
 * This tool ONLY orchestrates the two existing tools as child processes; it does
 * not reimplement parsing, distillation, or redaction. Redaction is always ON
 * (distill-trace.mjs redacts by default and this tool never passes --no-redact).
 *
 * See tools/README-triage-failure.md for the rule rationale.
 */

import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const parseReportPath = path.join(__dirname, 'parse-report.mjs');
const distillTracePath = path.join(__dirname, 'distill-trace.mjs');
const MAX_BUFFER = 64 * 1024 * 1024;

function die(msg) {
  console.error(msg);
  process.exit(1);
}

function parseArgs(argv) {
  const args = { runDir: null, json: null, explain: false, peerRuns: [] };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--run-dir') args.runDir = argv[++i];
    else if (a === '--json') args.json = argv[++i];
    else if (a === '--explain') args.explain = true;
    else if (a === '--peer-run') args.peerRuns.push(argv[++i]);
    else if (a === '--selftest') args.selftest = true;
    else if (a === '--help' || a === '-h') args.help = true;
  }
  return args;
}

// ---------------------------------------------------------------------------
// Child-process helpers (reuse the existing chain, never reimplement it)
// ---------------------------------------------------------------------------

function runParseReport(runDir) {
  let stdout;
  try {
    stdout = execFileSync('node', [parseReportPath, '--run-dir', runDir], {
      encoding: 'utf8',
      maxBuffer: MAX_BUFFER,
    });
  } catch (err) {
    die(`Error: parse-report failed for run dir: ${runDir}\n  ${err.message}`);
  }
  try {
    return JSON.parse(stdout);
  } catch (err) {
    die(`Error: could not parse parse-report output as JSON\n  ${err.message}`);
  }
}

function runDistillTrace(zipPath) {
  let stdout;
  try {
    stdout = execFileSync('node', [distillTracePath, '--trace', zipPath], {
      encoding: 'utf8',
      maxBuffer: MAX_BUFFER,
    });
  } catch (err) {
    die(`Error: distill-trace failed for trace: ${zipPath}\n  ${err.message}`);
  }
  try {
    return JSON.parse(stdout);
  } catch (err) {
    die(`Error: could not parse distill-trace output as JSON\n  ${err.message}`);
  }
}

function findTraceZips(dir) {
  const out = [];
  function walk(current) {
    let entries;
    try {
      entries = fs.readdirSync(current, { withFileTypes: true });
    } catch {
      return;
    }
    for (const e of entries) {
      const full = path.join(current, e.name);
      if (e.isDirectory()) walk(full);
      else if (e.isFile() && e.name.toLowerCase() === 'traces.zip') out.push(full);
    }
  }
  walk(dir);
  return out.sort();
}

/** Same normalisation postprocess-run.mjs uses to tie a trace to a test case. */
function normalizeKey(name) {
  return String(name ?? '')
    .replace(/_\d{2}-\d{2}-\d{2}$/, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '');
}

// ---------------------------------------------------------------------------
// Signal extraction
// ---------------------------------------------------------------------------

const errorMessage = (a) =>
  a && a.error && a.error.message != null ? String(a.error.message) : '';
const selectorOf = (a) =>
  a && a.params && a.params.selector != null ? String(a.params.selector) : null;
const urlOf = (a) => (a && a.params && a.params.url != null ? String(a.params.url) : null);
const callName = (a) => `${(a && a.class) || '?'}.${(a && a.method) || '?'}`;
const logText = (a) => (Array.isArray(a && a.logMessages) ? a.logMessages.join('\n') : '');

/** Browser/OS transport errors — the connection never produced an HTTP response. */
const TRANSPORT_ERROR =
  /net::ERR_|ECONNREFUSED|ECONNRESET|ENOTFOUND|EAI_AGAIN|ETIMEDOUT|socket hang up|ERR_NAME_NOT_RESOLVED|ERR_INTERNET_DISCONNECTED|ERR_PROXY_CONNECTION_FAILED|ERR_TUNNEL_CONNECTION_FAILED|ERR_SSL|ERR_CERT_/i;

/** Access / role / session verdicts from the server or the application text. */
const PERMISSION_TEXT =
  /\b(?:401|403)\b|forbidden|unauthorized|unauthorised|access denied|zugriff verweigert|keine berechtigung|nicht berechtigt|nicht autorisiert|anmeldung fehlgeschlagen|login failed|session (?:expired|abgelaufen)|sitzung abgelaufen/i;

/**
 * Server-side or application-level defect signatures.
 *
 * A bare three-digit number in the 500s used to count as a 5xx status. It must not:
 * the application under test is Apache Wicket and every page URL carries a per-session
 * page counter (`…/suche?44`, `?45`, `?47` — measured 03.08.2026). Playwright writes
 * that URL into its own call log (`navigated to "…"`), so after a few hundred page loads
 * in one session ANY failure would read as `anwendungsfehler` — "Kandidat für einen echten
 * Defect, Entwicklung einbeziehen" — purely because the counter passed 500. R3 sits above
 * the selector and timing rules, so the misread wins.
 *
 * A real 5xx is already caught by the network branch, which reads the actual response
 * status. The text branch therefore only accepts wording that names a status as a status.
 */
const APP_ERROR_TEXT =
  /internal server error|bad gateway|service unavailable|gateway timeout|unhandled exception|nullpointerexception|stack trace|technischer fehler|es ist ein fehler aufgetreten|\bhttp\/?\s*5\d{2}\b|\bstatus(?:\s*code)?\s*[:=]?\s*5\d{2}\b/i;

/** Value/content-based locators: they address DATA on the page, not structure. */
const CONTENT_LOCATOR =
  /^(?:internal:text=|internal:label=|internal:role=[^\]]*\[name=|internal:attr=\[(?:placeholder|title|alt)=|text=)|has-text\(|:has-text\(/i;

/**
 * "The value simply is not there" phrasings, EN + DE.
 *
 * `kein ergebnis` in the singular is the wording of the application under test, measured on
 * 03.08.2026: a hit-less search renders "Die Suche nach […] lieferte kein Ergebnis." in a
 * Wicket feedback panel. The plural `keine ergebnisse` does not match
 * that sentence, so the one no-data message this application actually produces was the one
 * the no-data rule could not see.
 */
const NO_DATA_TEXT =
  /kein treffer|keine treffer|nicht gefunden|keine daten|kein ergebnis|keine ergebnisse|leere ergebnisliste|no results|not found|no matching records|keine eintraege|keine einträge/i;

/** Unambiguous locator defects reported by Playwright itself. */
const SELECTOR_DEFECT_TEXT =
  /strict mode violation|resolved to \d+ elements|is not attached to the DOM|element is not attached|is not a valid selector|unknown engine|failed to find element matching selector|selector .* did not match/i;

/** Structurally brittle locators: position-, index- or hash-class-based. */
function brittleSelector(selector) {
  if (!selector) return null;
  const s = String(selector);
  if (/^(?:xpath=)?\/html(?:\[\d+\])?\//i.test(s)) return 'absoluter XPath ab /html';
  if (/nth-child\(|nth-of-type\(|>>\s*nth=|\bnth=\d+/i.test(s)) return 'positionsabhängiger Index (nth)';
  // A generated class name is a hash: its suffix carries digits. A hyphen alone does not
  // make one — measured 03.08.2026, the old pattern called `table.tabelle-produktliste`
  // and `div.hinweis-meldung` generated hashes. Both are hand-written class names in the
  // application under test, and one of them is a selector this repository deliberately
  // introduced. A German UI is full of hyphenated compound words; calling them all
  // fragile turns the brittleness signal into noise exactly where it is meant to help.
  // Known limit, stated rather than hidden: an all-letter generated name (`.css-abcdef`)
  // is not detected. Under-reporting is the safer error — a false "fragile" verdict sends
  // a tester to rewrite a perfectly good locator.
  if (/\.[A-Za-z][A-Za-z0-9]*[-_](?=[A-Za-z0-9]{5,}\b)[A-Za-z]*\d[A-Za-z0-9]*\b/.test(s))
    return 'generierter Klassen-Hash';
  const combinators = (s.match(/\s*>\s*/g) || []).length;
  if (combinators >= 4) return `tiefe Struktur-Kette (${combinators} Ebenen)`;
  return null;
}

/** Playwright timeout wording. */
const TIMEOUT_TEXT = /Timeout\s+\d+ms exceeded|exceeded while waiting|timeouterror/i;

/** Call-log wording that proves the element existed but was not ready yet. */
const NOT_READY_TEXT =
  /waiting for element to be (?:visible|enabled|stable)|element is not (?:visible|stable|enabled)|intercepts pointer events|waiting for navigation|waiting until "(?:load|networkidle|domcontentloaded)"|scrolling into view|retrying click|locator resolved to (?:hidden|disabled)/i;

/**
 * The two halves of the "did the locator ever match anything?" question.
 *
 * Playwright writes `waiting for locator(...)` as soon as it starts waiting, and adds
 * `N × locator resolved to <tag …>` the moment the selector matches an element — it does
 * so even when the match is hidden ("locator resolved to hidden <button …>"). So the
 * ABSENCE of a resolved-line next to a present waiting-line is positive evidence that the
 * selector addressed nothing at all, which is a statement about the locator and not about
 * timing. Measured, not assumed — see the `--selftest` counter-examples, which are the
 * real call logs of an absent and a present-but-hidden element.
 */
const WAITING_FOR_LOCATOR = /waiting for locator\(/i;
const LOCATOR_RESOLVED = /locator resolved to/i;

// ---------------------------------------------------------------------------
// The rule chain — ORDER IS THE DESIGN. First match wins.
// Rationale for each rule lives in tools/README-triage-failure.md.
// ---------------------------------------------------------------------------

const RULES = [
  {
    id: 'R1-transport',
    category: 'netzwerk',
    kurz: 'Transportfehler des Browsers (keine HTTP-Antwort)',
    evaluate(ctx) {
      const signals = [];
      const msg = errorMessage(ctx.failedAction);
      const m = msg.match(TRANSPORT_ERROR);
      if (m) {
        signals.push(
          `Fehlertext der ersten fehlgeschlagenen Aktion enthält Transportfehler "${m[0]}": ${msg}`,
        );
      }
      const deadRequests = ctx.networkFailures.filter(
        (n) => n.error === 'no response' || String(n.error || '').startsWith('time:-'),
      );
      if (deadRequests.length > 0) {
        signals.push(
          `${deadRequests.length} Request(s) ohne jede Antwort: ` +
            deadRequests.map((n) => `${n.method} ${n.url} (${n.error})`).join(', '),
        );
      }
      // Only a real transport error decides; a dead request alone is corroboration.
      return m ? { signals } : null;
    },
  },
  {
    id: 'R2-berechtigung',
    category: 'berechtigung',
    kurz: 'Server oder Anwendung verweigert den Zugriff (401/403)',
    evaluate(ctx) {
      const signals = [];
      const authStatus = ctx.networkFailures.filter(
        (n) => n.status === 401 || n.status === 403,
      );
      for (const n of authStatus) {
        signals.push(`HTTP ${n.status} auf ${n.method} ${n.url}`);
      }
      const hay = `${errorMessage(ctx.failedAction)}\n${logText(ctx.failedAction)}`;
      const m = hay.match(PERMISSION_TEXT);
      if (m) signals.push(`Fehler-/Logtext nennt Zugriffsproblem: "${m[0]}"`);
      return signals.length > 0 ? { signals } : null;
    },
  },
  {
    id: 'R3-anwendungsfehler',
    category: 'anwendungsfehler',
    kurz: 'Server- oder Anwendungsfehler (5xx bzw. falscher Wert)',
    evaluate(ctx) {
      const signals = [];
      const serverErrors = ctx.networkFailures.filter(
        (n) => typeof n.status === 'number' && n.status >= 500,
      );
      for (const n of serverErrors) {
        signals.push(`HTTP ${n.status} auf ${n.method} ${n.url}`);
      }
      const hay = `${errorMessage(ctx.failedAction)}\n${logText(ctx.failedAction)}`;
      const m = hay.match(APP_ERROR_TEXT);
      if (m) signals.push(`Fehlertext nennt Anwendungsfehler: "${m[0]}"`);
      // Assertion mismatch: the app produced a value, it was simply the wrong one.
      if (
        /\.expect$/i.test(callName(ctx.failedAction)) &&
        /expected|erwartet/i.test(hay) &&
        !TIMEOUT_TEXT.test(hay)
      ) {
        signals.push(
          `Assertion (${callName(ctx.failedAction)}) meldet Soll/Ist-Abweichung ohne Timeout`,
        );
      }
      return signals.length > 0 ? { signals } : null;
    },
  },
  {
    id: 'R4-testdaten',
    category: 'testdaten',
    kurz: 'Seite reagiert, aber der gesuchte Datenwert fehlt',
    evaluate(ctx) {
      const signals = [];
      const hay = `${errorMessage(ctx.failedAction)}\n${logText(ctx.failedAction)}`;
      const noData = hay.match(NO_DATA_TEXT);
      if (noData) signals.push(`Fehler-/Logtext meldet fehlende Daten: "${noData[0]}"`);

      const sel = selectorOf(ctx.failedAction);
      const isContentLocator = sel != null && CONTENT_LOCATOR.test(sel);
      const navigationOk = !ctx.actions.some(
        (a) => /\.goto$/i.test(callName(a)) && a.error,
      );
      const anyEarlierSuccess = ctx.actionsBeforeFirstFailure.some((a) => !a.error);
      if (
        isContentLocator &&
        navigationOk &&
        anyEarlierSuccess &&
        ctx.networkFailures.length === 0
      ) {
        signals.push(
          `Erste fehlgeschlagene Aktion adressiert einen Inhalt, keine Struktur: ${sel}`,
        );
        signals.push('Navigation zur Anwendung war erfolgreich, kein Netzwerkfehler im Trace');
        signals.push(
          `${ctx.actionsBeforeFirstFailure.filter((a) => !a.error).length} Aktion(en) davor liefen erfolgreich — die Seite war also bedienbar`,
        );
      }
      return signals.length > 0 ? { signals } : null;
    },
  },
  {
    id: 'R5-selector',
    category: 'selector-instabil',
    kurz: 'Der Locator selbst ist defekt oder mehrdeutig',
    evaluate(ctx) {
      const signals = [];
      const hay = `${errorMessage(ctx.failedAction)}\n${logText(ctx.failedAction)}`;
      const m = hay.match(SELECTOR_DEFECT_TEXT);
      if (m) signals.push(`Playwright meldet Locator-Defekt: "${m[0]}"`);
      const sel = selectorOf(ctx.failedAction);
      const brittle = brittleSelector(sel);
      if (brittle) signals.push(`Locator ist strukturell fragil (${brittle}): ${sel}`);

      // A timeout whose call log waited on a locator and never resolved it. The element
      // does not exist on the page at all — typically because the application renamed or
      // removed the control since the recording. Guarded by WAITING_FOR_LOCATOR so an
      // empty call log abstains instead of being read as absence.
      const log = logText(ctx.failedAction);
      let massnahme = null;
      if (
        TIMEOUT_TEXT.test(hay) &&
        WAITING_FOR_LOCATOR.test(log) &&
        !LOCATOR_RESOLVED.test(log)
      ) {
        signals.push(
          'Call-Log hat auf den Locator gewartet und ihn nie aufgelöst — das Element ' +
            `existiert in diesem Seitenzustand nicht: ${sel}`,
        );
        // "Locator auf ein stabiles Merkmal umstellen" ist hier die falsche Auskunft und
        // richtet Schaden an: gemessen am 03.08.2026 stand eine ganze Suite genau
        // deshalb grün, weil ein nicht auffindbares Element durch einen Selektor ersetzt
        // worden war, den schon die Suchseite erfüllt (Issue #191). Ein abwesendes Element
        // hat drei mögliche Ursachen, und nur bei einer davon hilft ein neuer Selektor.
        massnahme =
          'Das Element ist in diesem Seitenzustand gar nicht vorhanden. Vor jedem ' +
          'Selektor-Tausch ist zu klären, welche der drei Ursachen vorliegt: das ' +
          'Bedienelement wurde in der Anwendung entfernt oder umbenannt, der Test steht ' +
          'auf einem anderen Seitenzustand als erwartet, oder der Schritt davor hat keine ' +
          'Daten geliefert. Nur im ersten Fall ist ein neuer Locator die Antwort — in den ' +
          'beiden anderen macht er den Test grün, ohne dass er etwas prüft.';
      }
      return signals.length > 0 ? { signals, massnahme } : null;
    },
  },
  {
    id: 'R6-timing',
    category: 'timing',
    kurz: 'Element vorhanden, Test war zu früh',
    evaluate(ctx) {
      const signals = [];
      const hay = `${errorMessage(ctx.failedAction)}\n${logText(ctx.failedAction)}`;
      const timedOut = TIMEOUT_TEXT.test(hay);
      if (!timedOut) return null;
      signals.push(`Aktion lief in einen Timeout: ${errorMessage(ctx.failedAction)}`);
      const notReady = hay.match(NOT_READY_TEXT);
      if (notReady) {
        signals.push(`Call-Log zeigt Warten auf Zustand, nicht auf Existenz: "${notReady[0]}"`);
        return { signals };
      }
      if (ctx.flakeSignal) {
        signals.push(ctx.flakeSignal);
        return { signals };
      }
      return null;
    },
  },
];

const CATEGORY_TEXT = {
  netzwerk: {
    titel: 'Netzwerk / Umgebung',
    satz: 'Der Browser hat die Anwendung überhaupt nicht erreicht — es kam keine HTTP-Antwort zurück.',
    massnahme:
      'Umgebung prüfen (läuft die Anwendung, stimmt die URL, ist Proxy/VPN aktiv). Der Test selbst ist unverdächtig.',
  },
  berechtigung: {
    titel: 'Berechtigung',
    satz: 'Der Server bzw. die Anwendung hat den Zugriff aktiv verweigert.',
    massnahme:
      'Rolle, Rechte oder Session des Testbenutzers prüfen. Kein Selektor- und kein Anwendungsfehler.',
  },
  anwendungsfehler: {
    titel: 'Anwendungsfehler',
    satz: 'Die Anwendung hat einen Fehler geliefert oder einen falschen Wert erzeugt.',
    massnahme: 'Das ist ein Kandidat für einen echten Defect — Entwicklung einbeziehen.',
  },
  testdaten: {
    titel: 'Testdaten',
    satz: 'Die Seite war bedienbar, der gesuchte Datenwert stand aber nicht darauf.',
    massnahme:
      'Testkunde / Konto / Datensatz prüfen, nicht den Selektor. Das ist der häufigste Fall bei geteilten Testdaten.',
  },
  'selector-instabil': {
    titel: 'Instabiler Selektor',
    satz: 'Nicht die Anwendung, sondern der Locator im Test ist das Problem.',
    massnahme: 'Locator auf ein stabiles Merkmal umstellen (data-test, Label, Rolle).',
  },
  timing: {
    titel: 'Timing',
    satz: 'Das Element war grundsätzlich vorhanden, der Test hat nur zu früh zugegriffen.',
    massnahme: 'Gezielte Wartebedingung ergänzen statt den Selektor zu tauschen.',
  },
  unklar: {
    titel: 'Unklar',
    satz: 'Keine der Regeln greift eindeutig.',
    massnahme:
      'Die gesammelten Signale unten reichen für eine manuelle Entscheidung — hier wird bewusst nicht geraten.',
  },
};

/** Build the German one-paragraph explanation strictly from the collected evidence. */
function buildExplanation(finding) {
  const t = CATEGORY_TEXT[finding.category];
  const fa = finding.evidence.failedAction;
  const parts = [];
  parts.push(
    `${t.titel}: ${t.satz} Ausgelöst hat es Aktion #${fa.index} (${fa.call}${
      fa.selector ? `, Selektor ${fa.selector}` : fa.url ? `, URL ${fa.url}` : ''
    }) mit dem Fehler "${fa.error}".`,
  );
  if (finding.cascade.followUpFailures > 0) {
    parts.push(
      `Die weiteren ${finding.cascade.followUpFailures} fehlgeschlagenen Aktionen sind Folgefehler derselben Ursache (erste davon: ${finding.cascade.firstFollowUp}) — sie dürfen nicht einzeln bewertet werden.`,
    );
  }
  if (finding.evidence.networkFailures.length > 0) {
    const n = finding.evidence.networkFailures[0];
    parts.push(
      `Im Trace stehen ${finding.evidence.networkFailures.length} fehlgeschlagene Request(s), z. B. ${n.method} ${n.url} (${n.error ?? n.status}).`,
    );
  }
  if (finding.peerRuns && finding.peerRuns.length > 0) {
    const green = finding.peerRuns.filter((p) => p.status === 'PASS');
    if (green.length > 0) {
      parts.push(
        `Derselbe Testfall lief in ${green.length} Vergleichslauf/-läufen grün (${green
          .map((p) => p.run)
          .join(', ')}) — die Ursache liegt also nicht dauerhaft im Testfall.`,
      );
    }
  }
  parts.push(`Maßnahme: ${finding.massnahme ?? t.massnahme}`);
  return parts.join(' ');
}

/** Classify one failing test case. Returns the finding object. */
function classify(tc, distilled, peerStatuses, ruleLog) {
  const actions = Array.isArray(distilled.actions) ? distilled.actions : [];
  const networkFailures = Array.isArray(distilled.networkFailures)
    ? distilled.networkFailures
    : [];
  const failedIndexes = actions
    .map((a, i) => (a.error ? i : -1))
    .filter((i) => i >= 0);
  const firstFailIndex = failedIndexes.length > 0 ? failedIndexes[0] : -1;
  // distill-trace already reports the FIRST failing call as failedAction; prefer it
  // so the verdict is always anchored on the root cause, never on a follow-up.
  const failedAction =
    distilled.failedAction ?? (firstFailIndex >= 0 ? actions[firstFailIndex] : null);

  const peers = (peerStatuses.get(tc.name) || []).map((p) => ({ ...p }));
  const greenPeer = peers.find((p) => p.status === 'PASS');
  const flakeSignal = greenPeer
    ? `Derselbe Testfall ist im Vergleichslauf ${greenPeer.run} grün gelaufen (Flake-Indiz)`
    : null;

  const ctx = {
    testCase: tc,
    actions,
    actionsBeforeFirstFailure: firstFailIndex > 0 ? actions.slice(0, firstFailIndex) : [],
    failedAction,
    networkFailures,
    flakeSignal,
  };

  let category = 'unklar';
  let ruleId = 'R7-fallback';
  let signals = [];
  // A rule may replace the category's standard measure when the signal that actually
  // fired calls for a different next step. Null means "use the category's own".
  let massnahme = null;

  if (!failedAction) {
    signals = [
      'Der Report meldet FAIL, im Trace steht aber keine fehlgeschlagene Aktion — es wird bewusst nicht geraten.',
    ];
    ruleLog.push({
      testCase: tc.name,
      rule: 'R0-preflight',
      fired: true,
      why: 'kein failedAction im Trace vorhanden',
    });
  } else {
    for (const rule of RULES) {
      const res = rule.evaluate(ctx);
      ruleLog.push({
        testCase: tc.name,
        rule: rule.id,
        category: rule.category,
        kurz: rule.kurz,
        fired: Boolean(res),
        why: res ? res.signals.join(' | ') : 'kein Signal getroffen',
      });
      if (res && category === 'unklar' && ruleId === 'R7-fallback') {
        category = rule.category;
        ruleId = rule.id;
        signals = res.signals;
        massnahme = res.massnahme ?? null;
        // Keep evaluating so --explain shows the whole chain, but do not overwrite.
      }
    }
    if (category === 'unklar') {
      signals = [
        `Erste fehlgeschlagene Aktion: ${callName(failedAction)} — "${errorMessage(failedAction)}"`,
        `${networkFailures.length} Netzwerkfehler im Trace`,
      ];
    }
  }

  const followUps = failedIndexes.filter((i) => i !== firstFailIndex);
  const firstFollowUp =
    followUps.length > 0
      ? `${callName(actions[followUps[0]])} ${selectorOf(actions[followUps[0]]) ?? ''} — ${errorMessage(actions[followUps[0]])}`.trim()
      : null;

  const finding = {
    testCase: tc.name,
    status: tc.status,
    durationSeconds: tc.durationSeconds,
    steps: tc.steps,
    category,
    rule: ruleId,
    massnahme,
    evidence: {
      failedAction: failedAction
        ? {
            index: firstFailIndex >= 0 ? firstFailIndex : null,
            call: callName(failedAction),
            selector: selectorOf(failedAction),
            url: urlOf(failedAction),
            error: errorMessage(failedAction),
            logMessages: Array.isArray(failedAction.logMessages)
              ? failedAction.logMessages
              : [],
            durationMs: failedAction.durationMs ?? null,
          }
        : null,
      networkFailures,
      screenshotsCount: Array.isArray(distilled.screenshots)
        ? distilled.screenshots.length
        : 0,
      actionCount: actions.length,
      failedActionCount: failedIndexes.length,
      signals,
    },
    cascade: {
      followUpFailures: followUps.length,
      firstFollowUp,
    },
    peerRuns: peers,
    traceSource: distilled.source ?? null,
  };
  finding.erklaerung = buildExplanation(finding);
  return finding;
}

/**
 * Counter-examples for the "locator never resolved" signal.
 *
 * Every call log below is INVENTED. Each one is modelled line by line on a real failure
 * that was observed against an internal web application, and reproduces that failure's
 * shape exactly — same Playwright call-log grammar, same selector forms, same wording of
 * the application's own messages — with the host, the test-case names and the selectors
 * replaced by made-up equivalents. What is being asserted is the parsing behaviour, and
 * that survives the substitution unchanged; the identifying detail does not travel.
 *
 *  - `absent` models a failing action whose CSS selector matches 0 elements, because the
 *    control had been removed from the application since the recording.
 *  - `hidden` models Playwright's own log for an element that exists but is display:none,
 *    so the two cases are provably distinguishable.
 *
 * If a future change makes the absent case read as `timing` (or the hidden case read as
 * `selector-instabil`), this selftest fails — that is the whole point of keeping them.
 */
const SELFTEST_CASES = [
  {
    name: 'absent — locator never resolved (invented Bestaetigen-Button)',
    action: {
      class: 'Frame',
      method: 'waitForSelector',
      params: { selector: 'css=button[name="bestaetigenAktion"]' },
      error: { message: 'Timeout 30000ms exceeded.' },
      logMessages: ['waiting for locator("button[name=\\"bestaetigenAktion\\"]") to be visible'],
    },
    expectCategory: 'selector-instabil',
    expectRule: 'R5-selector',
    // The category is right and its standard measure is wrong: telling a tester to swap
    // the locator for an element that is not on the page is how this suite went green
    // without checking anything (#191). The absent case must carry its own measure.
    expectMassnahmeContains: 'nicht vorhanden',
  },
  {
    name: 'present but hidden — locator resolved, only not ready',
    action: {
      class: 'Frame',
      method: 'waitForSelector',
      params: { selector: 'css=button[name="present_hidden"]' },
      error: { message: 'Timeout 3000ms exceeded.' },
      logMessages: [
        'waiting for locator("button[name=\\"present_hidden\\"]") to be visible',
        '11 × locator resolved to hidden <button name="present_hidden">B</button>',
      ],
    },
    expectCategory: 'timing',
    expectRule: 'R6-timing',
  },
  /**
   * The Wicket page counter must not be read as an HTTP 5xx.
   *
   * Provenance, stated exactly: the URL is invented, the `?<n>` page counter is not — a
   * Wicket application appends one to every page URL, and it was measured at 44, 45 and 47
   * on 03.08.2026 within a single session. `navigated to "…"` is Playwright's own call-log
   * wording. The counter value 512 stands for that same session a few hundred page loads
   * later; it was not sat through.
   *
   * Before the fix this classified as `anwendungsfehler` (R3) — "Kandidat für einen echten
   * Defect, Entwicklung einbeziehen" — for a test whose control simply is not on the page.
   */
  {
    name: 'Wicket page counter in the 500s is not an HTTP 5xx',
    action: {
      class: 'Frame',
      method: 'waitForSelector',
      params: { selector: 'css=table.tabelle-produktliste' },
      error: { message: 'Timeout 30000ms exceeded.' },
      logMessages: [
        'navigated to "https://beispiel-anwendung.intern/suche?512"',
        'waiting for locator("table.tabelle-produktliste") to be visible',
      ],
    },
    expectCategory: 'selector-instabil',
    expectRule: 'R5-selector',
  },
  {
    name: 'a status named as a status still counts as an application error',
    action: {
      class: 'Frame',
      method: 'waitForSelector',
      params: { selector: 'css=table.tabelle-produktliste' },
      error: { message: 'Timeout 30000ms exceeded.' },
      logMessages: ['navigated to "https://beispiel-anwendung.intern/suche"', 'HTTP 503'],
    },
    expectCategory: 'anwendungsfehler',
    expectRule: 'R3-anwendungsfehler',
  },
  /**
   * A no-hit message from the application, not from Playwright.
   *
   * Invented sentence, built to the same grammar as the one a Wicket application renders
   * into its feedback panel (`li.hinweisPanelINFO … p`) when a search finds nothing: the
   * singular "kein Ergebnis", which the plural `keine ergebnisse` in the old pattern did
   * not match. Before the fix this read as `selector-instabil`: the tester would have been
   * sent to repair a locator over a record that simply is not in the system.
   */
  {
    name: 'the app says "lieferte kein Ergebnis" — that is a data verdict, not a locator one',
    action: {
      class: 'Frame',
      method: 'waitForSelector',
      params: { selector: 'css=table.trefferListe tr.even' },
      error: { message: 'Timeout 30000ms exceeded.' },
      logMessages: [
        'waiting for locator("table.trefferListe tr.even") to be visible',
        'Die Suche nach Konto- oder Depotnummer [1000000001] lieferte kein Ergebnis.',
      ],
    },
    expectCategory: 'testdaten',
    expectRule: 'R4-testdaten',
  },
  /**
   * A hyphenated German class name is not a generated hash.
   *
   * Invented, modelled on a real failing action of a "table must not be visible on the
   * search page" counter-test. There the verdict was right and one of its two signals was
   * wrong: the old brittleness pattern read `table.tabelle-produktliste` — a class this
   * repository deliberately chose because it is meaningful and stable — as a generated
   * hash. The same pattern also flagged `div.hinweis-meldung`. Both are hand-written names.
   */
  {
    name: 'a hyphenated German class name is not a generated hash',
    action: {
      class: 'Frame',
      method: 'waitForSelector',
      params: { selector: 'css=table.tabelle-produktliste' },
      error: { message: 'Timeout 30000ms exceeded.' },
      logMessages: ['waiting for locator("table.tabelle-produktliste") to be visible'],
    },
    expectCategory: 'selector-instabil',
    expectRule: 'R5-selector',
    forbidSignalContains: 'generierter Klassen-Hash',
  },
  {
    name: 'a real generated hash is still called one',
    action: {
      class: 'Frame',
      method: 'waitForSelector',
      params: { selector: 'css=.css-1a2b3c4' },
      error: { message: 'Timeout 30000ms exceeded.' },
      logMessages: ['waiting for locator(".css-1a2b3c4") to be visible'],
    },
    expectCategory: 'selector-instabil',
    expectRule: 'R5-selector',
    expectSignalContains: 'generierter Klassen-Hash',
  },
];

function selftest() {
  const problems = [];
  for (const c of SELFTEST_CASES) {
    const ctx = {
      testCase: { name: c.name },
      actions: [c.action],
      actionsBeforeFirstFailure: [],
      failedAction: c.action,
      networkFailures: [],
      flakeSignal: null,
    };
    let category = 'unklar';
    let ruleId = 'R7-fallback';
    let massnahme = null;
    let signals = [];
    for (const rule of RULES) {
      const res = rule.evaluate(ctx);
      if (res && category === 'unklar' && ruleId === 'R7-fallback') {
        category = rule.category;
        ruleId = rule.id;
        massnahme = res.massnahme ?? null;
        signals = res.signals;
      }
    }
    const signalText = signals.join(' | ');
    if (category !== c.expectCategory || ruleId !== c.expectRule) {
      problems.push(
        `${c.name}\n      expected ${c.expectCategory} (${c.expectRule}), got ${category} (${ruleId})`,
      );
    } else if (c.expectMassnahmeContains && !String(massnahme ?? '').includes(c.expectMassnahmeContains)) {
      problems.push(
        `${c.name}\n      expected a rule-specific measure containing ` +
          `"${c.expectMassnahmeContains}", got ${massnahme === null ? 'the category default' : `"${massnahme}"`}`,
      );
    } else if (c.forbidSignalContains && signalText.includes(c.forbidSignalContains)) {
      problems.push(
        `${c.name}\n      the signal "${c.forbidSignalContains}" must NOT fire here, but it did: ${signalText}`,
      );
    } else if (c.expectSignalContains && !signalText.includes(c.expectSignalContains)) {
      problems.push(
        `${c.name}\n      expected a signal containing "${c.expectSignalContains}", got: ${signalText}`,
      );
    } else {
      console.log(`  OK  ${c.name}\n        -> ${category} (${ruleId})`);
    }
  }
  if (problems.length) {
    console.error('triage-failure selftest: RED\n  ' + problems.join('\n  '));
    process.exit(1);
  }
  console.log(
    `triage-failure selftest: GREEN — ${SELFTEST_CASES.length} counter-examples, ` +
      'an absent locator is a selector verdict and a hidden one is a timing verdict.',
  );
  process.exit(0);
}

function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.selftest) return selftest();
  if (args.help || !args.runDir) {
    console.log(
      'Usage: node tools/triage-failure.mjs --run-dir <dir> [--json <out>] [--explain] [--peer-run <dir> ...]',
    );
    process.exit(args.help ? 0 : 1);
  }

  const runDir = path.resolve(args.runDir);
  let st;
  try {
    st = fs.statSync(runDir);
  } catch {
    die(`Error: run dir not found: ${args.runDir}`);
  }
  if (!st.isDirectory()) die(`Error: run dir is not a directory: ${args.runDir}`);

  const parsed = runParseReport(runDir);

  // Peer runs supply the cross-run "same test case was green elsewhere" signal.
  const peerStatuses = new Map();
  for (const peer of args.peerRuns) {
    const peerDir = path.resolve(peer);
    if (!fs.existsSync(peerDir)) {
      console.error(`Warning: peer run not found, skipping: ${peerDir}`);
      continue;
    }
    const peerDoc = runParseReport(peerDir);
    for (const tc of peerDoc.testCases || []) {
      const list = peerStatuses.get(tc.name) || [];
      list.push({ run: path.basename(peerDir), status: tc.status, durationSeconds: tc.durationSeconds });
      peerStatuses.set(tc.name, list);
    }
  }

  const zips = findTraceZips(runDir);
  const distilledByKey = new Map();
  for (const zip of zips) {
    const d = runDistillTrace(zip);
    const key = normalizeKey(d.testCase);
    if (key && !distilledByKey.has(key)) distilledByKey.set(key, d);
  }

  const ruleLog = [];
  const findings = [];
  const passing = [];

  for (const tc of parsed.testCases || []) {
    if (tc.status !== 'FAIL') {
      passing.push({ testCase: tc.name, status: tc.status });
      continue;
    }
    const d = distilledByKey.get(normalizeKey(tc.name));
    if (!d) {
      findings.push({
        testCase: tc.name,
        status: tc.status,
        durationSeconds: tc.durationSeconds,
        steps: tc.steps,
        category: 'unklar',
        rule: 'R0-preflight',
        evidence: {
          failedAction: null,
          networkFailures: [],
          screenshotsCount: 0,
          actionCount: 0,
          failedActionCount: 0,
          signals: [
            'Zu diesem Testfall wurde kein passender Trace gefunden — ohne Trace wird nicht klassifiziert.',
          ],
        },
        cascade: { followUpFailures: 0, firstFollowUp: null },
        peerRuns: peerStatuses.get(tc.name) || [],
        traceSource: null,
        erklaerung:
          'Unklar: Der Report meldet FAIL, es liegt aber kein zugehöriger Trace vor. Ohne Trace-Beleg wird hier bewusst keine Ursache behauptet.',
      });
      continue;
    }
    findings.push(classify(tc, d, peerStatuses, ruleLog));
  }

  const byCategory = {};
  for (const f of findings) byCategory[f.category] = (byCategory[f.category] || 0) + 1;

  const doc = {
    runDir,
    triagedAt: new Date().toISOString(),
    redacted: true,
    totals: {
      cases: (parsed.testCases || []).length,
      failed: findings.length,
      passed: passing.length,
      byCategory,
    },
    findings,
    passing,
  };
  if (args.explain) doc.ruleChain = ruleLog;

  const pretty = JSON.stringify(doc, null, 2);
  if (args.json) {
    const outPath = path.resolve(args.json);
    fs.mkdirSync(path.dirname(outPath), { recursive: true });
    fs.writeFileSync(outPath, pretty + '\n', 'utf8');
    console.log(`Wrote ${outPath}`);
    console.log(
      `Cases: ${doc.totals.cases}  Failed: ${doc.totals.failed}  ` +
        `Kategorien: ${Object.entries(byCategory).map(([k, v]) => `${k}=${v}`).join(', ') || '—'}`,
    );
    for (const f of findings) {
      console.log(`  [${f.category}] ${f.testCase}  (Regel ${f.rule})`);
      console.log(`    ${f.erklaerung}`);
    }
  } else {
    console.log(pretty);
  }

  if (args.explain) {
    console.log('');
    console.log('Regelkette (--explain):');
    let current = null;
    for (const entry of ruleLog) {
      if (entry.testCase !== current) {
        current = entry.testCase;
        console.log(`  ── ${current}`);
      }
      console.log(
        `     ${entry.fired ? 'GREIFT ' : 'greift nicht'} ${entry.rule}` +
          (entry.category ? ` -> ${entry.category}` : '') +
          `\n        ${entry.why}`,
      );
    }
  }
}

main();
