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
 * Exit codes: 0 saved (or, with --check, a usable session exists) · 1 nothing was saved
 * · 2 the browser could not be opened at all.
 */
import { existsSync, mkdirSync, statSync } from 'node:fs';
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
 * Answers "is there a session, and how old is it" without opening anything.
 *
 * The panel calls this every time it paints, so it must be cheap and must never be able
 * to put a browser on somebody's screen as a side effect of drawing a label.
 */
function reportCheck(state) {
  const age = ageHours(state);
  if (age === null) {
    console.log('SESSION NONE');
    console.log('Es ist keine Anmeldung gespeichert.');
    return 1;
  }
  const hours = age.toFixed(1);
  console.log(`SESSION PRESENT ${hours} ${state}`);
  console.log(age > STALE_HOURS
    ? `Die gespeicherte Anmeldung ist ${hours} Stunden alt und vermutlich abgelaufen.`
    : `Es ist eine Anmeldung von vor ${hours} Stunden gespeichert.`);
  return age > STALE_HOURS ? 1 : 0;
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
      if (onTarget) {
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
