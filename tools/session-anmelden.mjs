/**
 * session-anmelden.mjs — the tester signs in to the application, in a real browser,
 * and the session is kept so the checks can reach pages behind that login.
 *
 * WHY THIS EXISTS
 * ---------------
 * Studio's "Aufnahme prüfen" opens the recorded page and counts how many elements each
 * step matches. Against a login-protected application it has been opening the login screen
 * and counting the
 * elements on THAT — a check that answers confidently about the wrong page. The missing
 * piece was never the probe; it was a signed-in browser profile for it to reuse.
 *
 * `ing-qa-recorder/mvp/refresh-session.mjs` has done this since June, from a terminal,
 * over SSH, typed by an engineer. That is the MVP and it is not the product: a tester
 * has no terminal. This is the same capability with a contract a panel can drive — and
 * the panel (de.ing.qa.panel.SessionSignIn) is what a tester actually presses.
 *
 * WHAT THE TESTER DOES
 * --------------------
 * They log in. That is the whole interaction, and it is allowed to be exactly that: a
 * real browser window, the real ING sign-in, their own credentials. What they must never
 * do is open a console to make the window appear.
 *
 * WHAT IS SAVED, AND WHAT THAT MEANS
 * ----------------------------------
 * Playwright's storageState: cookies and local storage for the site that was signed in
 * to. That is a live session — treat the file as a credential. It is written outside the
 * repository, next to the Azure DevOps token cache, and never into a recording, a
 * hand-off package or a problem report.
 *
 *   node tools/session-anmelden.mjs --check
 *   node tools/session-anmelden.mjs [--url <adresse>] [--out <datei>] [--ready <selektor>]
 *
 * Exit codes: 0 saved (or, with --check, the saved session is PROVABLY still valid) · 1 nothing
 * was saved, or nothing in the file proves the saved session is still good · 2 the browser could
 * not be opened at all.
 */
import { existsSync, mkdirSync, readFileSync, statSync } from 'node:fs';
import { homedir } from 'node:os';
import { dirname, join } from 'node:path';
import { pathToFileURL } from 'node:url';
// The device has no pinned Playwright revision and cannot install one; INGenious' own
// Playwright-for-Java fills the same cache under names this package never looks for.
// Without this the window simply never opens on the one machine that matters.
import { launchWithFallback } from './lib/find-browser.mjs';
import { config } from './lib/ing-config.mjs';

/**
 * Where a saved session lives by default: beside the ADO token cache, never in the repo.
 *
 * Two candidates, the current name first. Testers who signed in before this file was renamed
 * still have the old `callimero-state.json` lying there, and silently ignoring it would send
 * every one of them through a login again for no reason. Nothing is copied and nothing is
 * deleted — whichever of the two exists is simply read. The old name can be dropped from this
 * list once nobody has such a file any more.
 */
export function defaultStatePath() {
  const local = process.env.LOCALAPPDATA?.trim();
  const dir = local ? join(local, 'IngQaAutopilot') : join(homedir(), '.IngQaAutopilot');
  const aktuell = join(dir, 'session-state.json');
  const alt = join(dir, 'callimero-state.json');
  return !existsSync(aktuell) && existsSync(alt) ? alt : aktuell;
}

/**
 * The application this signs in to. There is no built-in address: a tool that ships one
 * names somebody's internal host in its source. It comes from ing-config.json / $ING_APP_URL
 * beside the tools, or from --url, and an empty one is refused with a sentence saying so.
 */
const DEFAULT_URL = config().appUrl;

/**
 * The element that only exists once somebody is through the login. Precise, and therefore
 * specific to one application — which is why it is configured rather than built in, and why
 * there is a second, generic signal below it that needs no selector at all.
 */
const DEFAULT_READY = config().appReadySelector;

/** Anything whose URL looks like an identity provider is, by definition, not "signed in yet". */
const IDP = /microsoftonline|login\.|\/login|adfs|saml|sso|auth/i;

/** A session older than this is offered, but the panel is told how old it is. */
const STALE_HOURS = 8;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function parseArgs(argv) {
  const out = {
    url: DEFAULT_URL,
    state: defaultStatePath(),
    ready: DEFAULT_READY,
    timeoutSeconds: 8 * 60,
    check: false,
  };
  for (let i = 0; i < argv.length; i++) {
    const next = () => {
      const v = argv[++i];
      if (v === undefined) throw new Error(`${argv[i - 1]} braucht einen Wert`);
      return v;
    };
    switch (argv[i]) {
      case '--url': out.url = next(); break;
      case '--out': case '--state': out.state = next(); break;
      case '--ready': out.ready = next(); break;
      case '--timeout-seconds': out.timeoutSeconds = Number(next()); break;
      case '--check': out.check = true; break;
      case '--help': case '-h': out.help = true; break;
      default: throw new Error(`unbekanntes Argument: ${argv[i]}`);
    }
  }
  return out;
}

/** How old a saved session is, in hours, or null when there is none. */
function ageHours(file) {
  if (!existsSync(file)) return null;
  try {
    return (Date.now() - statSync(file).mtimeMs) / 3_600_000;
  } catch {
    return null;
  }
}

/**
 * Whether a cookie belongs to the application rather than to the login that guards it.
 *
 * The host from the configured address decides when there is one — that is the application,
 * by definition. Without it the {@link IDP} pattern is the fallback: everything that is
 * recognisably an identity provider is not the application. The fallback is the weaker of
 * the two (an application whose own host contains "auth" would be discarded by it), which is
 * exactly why the configured address is preferred whenever it exists.
 */
function isAppCookie(cookie, appHost) {
  const domain = String(cookie.domain || '').replace(/^\./, '').toLowerCase();
  if (!domain) return false;
  if (appHost) return domain === appHost || appHost.endsWith(`.${domain}`);
  return !IDP.test(domain);
}

/** A moment a tester reads on a panel, in the notation they write dates in. */
function moment(epochSeconds) {
  const d = new Date(epochSeconds * 1000);
  const pad = (n) => String(n).padStart(2, '0');
  return `${pad(d.getDate())}.${pad(d.getMonth() + 1)}.${d.getFullYear()} `
    + `${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/**
 * What the saved file itself can prove about the session — read out of it, not guessed from
 * how recently it was written.
 *
 * WHY THIS REPLACED THE AGE
 * -------------------------
 * "Saved 3.3 hours ago" was being reported as "signed in", and the two are not the same
 * sentence. Measured against the real application on 2026-08-07, a saved state held eighteen
 * cookies: seventeen for the identity provider — one of them good until November — and
 * exactly ONE for the application itself, a JSESSIONID carrying no expiry at all. A servlet
 * container drops that handle after its idle timeout, typically far inside the eight hours
 * this file used to call "angemeldet". So the panel was green over a session the server had
 * already forgotten, which is the one failure mode worse than saying nothing.
 *
 * The file can prove three different things, and they get three different answers:
 *   GUELTIG          an application cookie carries an expiry, and it is in the future
 *   ABGELAUFEN       every application cookie carries an expiry, and all of them have passed
 *   SITZUNGSKENNUNG  the application cookie carries no expiry — the SERVER decides, and this
 *                    file cannot answer the question. Age is reported; validity is not claimed
 *   KEINE            nothing for the application at all: the sign-in never reached it
 *   UNLESBAR         not a storage state we can read — fall back to the age alone
 *
 * <p>Exported because a second tool has to reach the same verdict about the same file:
 * {@code anmeldung-mitnehmen.mjs} decides from it whether a saved session may be handed to
 * a recording or a run. Two implementations of "is this session worth anything" would drift,
 * and the drift would show up as a panel and a run disagreeing about the same file.
 *
 * @returns {{kind: string, until: number|null, host: string}}
 */
export function cookieVerdict(file, appUrl) {
  let appHost = '';
  try {
    // hostname, NOT host: host carries the port and a cookie domain never does. Against a
    // demo on 127.0.0.1:8731 the comparison then failed for every cookie in the file and
    // reported "ohne Sitzung" over a session that was there and working — a false alarm that
    // sends a tester back through a login they do not need. Caught by that demo run, which is
    // the only reason this line reads hostname today.
    appHost = appUrl ? new URL(appUrl).hostname.toLowerCase() : '';
  } catch {
    // A malformed address is not worth refusing a check over: the fallback still classifies.
  }
  let cookies;
  try {
    cookies = JSON.parse(readFileSync(file, 'utf8')).cookies;
    if (!Array.isArray(cookies)) throw new Error('keine cookies');
  } catch {
    return { kind: 'UNLESBAR', until: null, host: appHost };
  }
  const mine = cookies.filter((c) => isAppCookie(c, appHost));
  const host = appHost || (mine.length
    ? String(mine[0].domain || '').replace(/^\./, '') : '');
  if (!mine.length) return { kind: 'KEINE', until: null, host };
  // A cookie without an expiry outlives every dated one in the file: it lasts exactly as long
  // as the server keeps the session, so no date in here may be presented as its deadline.
  if (mine.some((c) => !(Number(c.expires) > 0))) {
    return { kind: 'SITZUNGSKENNUNG', until: null, host };
  }
  const latest = Math.max(...mine.map((c) => Number(c.expires)));
  return { kind: latest * 1000 > Date.now() ? 'GUELTIG' : 'ABGELAUFEN', until: latest, host };
}

/**
 * Answers "is there a session, and what does it prove" without opening anything.
 *
 * The panel calls this every time it paints, so it must be cheap and must never be able
 * to put a browser on somebody's screen as a side effect of drawing a label.
 *
 * The `SESSION PRESENT` line keeps its shape — the panel and its harness both read it — and
 * the verdict arrives on a line of its own, so a plugin older than this file loses the detail
 * and never the answer. Exit 0 now means "still valid, and here is why", not "written
 * recently": only GUELTIG earns it.
 */
function reportCheck(state) {
  const age = ageHours(state);
  if (age === null) {
    console.log('SESSION NONE');
    console.log('Es ist keine Anmeldung gespeichert.');
    return 1;
  }
  const hours = age.toFixed(1);
  const verdict = cookieVerdict(state, DEFAULT_URL);
  const fuer = verdict.host ? ` fuer ${verdict.host}` : '';
  console.log(`SESSION PRESENT ${hours} ${state}`);
  // Host before date, and the date last: it is the only field containing a space, so putting
  // it at the end is what keeps the line splittable at all.
  console.log(`SESSION COOKIES ${verdict.kind} ${verdict.host || '-'}`
    + ` ${verdict.until ? moment(verdict.until) : '-'}`);
  switch (verdict.kind) {
    case 'GUELTIG':
      console.log(`Es ist eine Anmeldung${fuer} gespeichert, gueltig bis `
        + `${moment(verdict.until)}.`);
      return 0;
    case 'ABGELAUFEN':
      console.log(`Die gespeicherte Anmeldung${fuer} ist seit ${moment(verdict.until)} `
        + 'abgelaufen.');
      return 1;
    case 'SITZUNGSKENNUNG':
      console.log(`Gespeichert vor ${hours} Stunden. Die Sitzungskennung${fuer} traegt kein `
        + 'Ablaufdatum — wie lange sie gilt, entscheidet die Anwendung.');
      return 1;
    case 'KEINE':
      console.log(`Gespeichert vor ${hours} Stunden, aber ohne Sitzung${fuer} — die Anmeldung `
        + 'hat die Anwendung nicht erreicht.');
      return 1;
    default:
      console.log(age > STALE_HOURS
        ? `Die gespeicherte Anmeldung ist ${hours} Stunden alt und vermutlich abgelaufen.`
        : `Es ist eine Anmeldung von vor ${hours} Stunden gespeichert.`);
      return age > STALE_HOURS ? 1 : 0;
  }
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.help) {
    console.log('  node tools/session-anmelden.mjs [--check] [--url <adresse>] [--out <datei>]'
      + ' [--ready <selektor>] [--timeout-seconds <n>]');
    return 0;
  }
  if (args.check) return reportCheck(args.state);

  // No built-in address means the unconfigured case has to be caught HERE rather than
  // discovered by page.goto(''), which fails with a Playwright message about an invalid
  // URL and tells a tester nothing about what to do.
  if (!args.url || !args.url.trim()) {
    console.log('SESSION KEINE-ADRESSE');
    console.log('Es ist keine Adresse konfiguriert. Bitte "appUrl" in ing-config.json '
      + 'eintragen, die Umgebungsvariable ING_APP_URL setzen oder --url <adresse> angeben.');
    return 2;
  }

  mkdirSync(dirname(args.state), { recursive: true });

  let browser;
  try {
    // Imported here rather than at the top on purpose: --check is called every time the panel
    // paints its state line, and a machine without Playwright would then fail to load this
    // module at all — turning "is a session saved?" into a crash instead of an answer.
    const { chromium } = await import('playwright');
    // headless:false is not a preference here — a person has to see this window and type
    // into it. launchWithFallback knows that and refuses to substitute the headless shell.
    browser = await launchWithFallback(chromium, 'chromium', { headless: false, slowMo: 30 });
  } catch (e) {
    console.log('SESSION NO-BROWSER');
    console.log(`Es konnte kein Browser geoeffnet werden: ${e.message}`);
    return 2;
  }

  // A previous session is loaded first: usually the tester is then already signed in and
  // this closes by itself in seconds, which is the difference between "log in again every
  // morning" and "log in again when it has actually expired".
  const context = await browser.newContext(
    existsSync(args.state) ? { storageState: args.state } : {});
  const page = await context.newPage();
  console.log('SESSION WAITING');
  console.log('  Ein Browserfenster ist geoeffnet. Bitte dort ganz normal anmelden.');
  console.log('  Sobald die Anwendung erscheint, wird die Anmeldung gespeichert und das');
  console.log('  Fenster schliesst sich von selbst. Sonst ist nichts zu tun.');
  await page.goto(args.url, { waitUntil: 'domcontentloaded' }).catch(() => {});

  const target = new URL(args.url).host;
  const deadline = Date.now() + args.timeoutSeconds * 1000;
  let why = null;
  let settledSince = 0;
  while (Date.now() < deadline && !why) {
    try {
      const here = page.url();
      const onTarget = here.includes(target) && !IDP.test(here.replace(target, ''));
      // The precise signal: an element that only exists past the login.
      if (onTarget && await page.locator(args.ready).count() > 0) {
        why = 'die Anwendung ist erreicht';
        break;
      }
      // The generic one, for any application with no configured ready-selector: on the target host,
      // away from the identity provider, and still there ten seconds later. The dwell is
      // what keeps a redirect passing through from counting as an arrival.
      //
      // ONLY when no selector is configured, and that condition is the whole point. Without
      // it the dwell also fired for an application that HAS one — measured against a demo
      // whose login lives on the same host as the application: ten seconds on the login page
      // counted as "die Anwendung wird seit einigen Sekunden angezeigt", the state was saved
      // with zero cookies in it, and the tester was told "✔ Angemeldet" about a sign-in that
      // had not happened. A configured selector is a precise statement of what arriving means;
      // when it never appears, the honest answer is the timeout, not a saved file.
      if (onTarget && !args.ready) {
        if (!settledSince) settledSince = Date.now();
        else if (Date.now() - settledSince > 10_000) {
          why = 'die Anwendung wird seit einigen Sekunden angezeigt';
          break;
        }
      } else {
        settledSince = 0;
      }
    } catch {
      // A navigation in flight makes every one of the questions above throw. That is not
      // an answer of "no" — it is no answer, and the next round asks again.
    }
    if (page.isClosed()) break;
    await sleep(2000);
  }

  let code = 1;
  if (why) {
    await context.storageState({ path: args.state });
    console.log(`SESSION SAVED ${args.state}`);
    console.log(`  Die Anmeldung wurde gespeichert (${why}).`);
    code = 0;
  } else {
    console.log('SESSION TIMEOUT');
    console.log('  Es wurde keine Anmeldung gespeichert — die Anwendung wurde nicht erreicht.');
  }
  await browser.close().catch(() => {});
  return code;
}

// Only when RUN, never when imported — and "run" has to be decided exactly.
//
// This used to test `process.argv[1].endsWith('session-anmelden.mjs')`, which is true for
// anything whose first argument merely NAMES this file: `node -e "import('…/session-anmelden.mjs')"`
// imported the module to read defaultStatePath() and got a browser window instead. The
// harness beside this file caught it doing exactly that. The comparison below is the only
// one that means "this module is the entry point": its own URL against the entry point's.
const invoked = process.argv[1] ? pathToFileURL(process.argv[1]).href : '';
if (invoked === import.meta.url) {
  main().then((code) => process.exit(code)).catch((e) => {
    console.log('SESSION FAILED');
    console.log(`  ${e.stack || e.message}`);
    process.exit(1);
  });
}
