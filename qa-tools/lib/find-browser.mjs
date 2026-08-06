/**
 * find-browser.mjs — launch a Playwright browser on a machine that cannot download one.
 *
 * WHY THIS EXISTS
 * ---------------
 * The browser build a `playwright` package bundles is pinned to its version. A device may get
 * its browsers from a DIFFERENT Playwright — INGenious ships Playwright for Java, which fills the
 * same `ms-playwright` cache — so usable builds sit there under revision names the Node
 * package never looks for, and `launch()` fails with "executable doesn't exist". A locked-down
 * corporate machine cannot run `npx playwright install`, so without this every browser-backed
 * check is unavailable on exactly the machines that have to run it.
 *
 * The substitution is always printed. A check that quietly answered about a different browser
 * than the one it claimed would be worse than one that could not run.
 */
import { existsSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { homedir } from 'node:os';

const BROWSER_DIR_PREFIX = {
  chromium: ['chromium_headless_shell-', 'chromium-'],
  firefox: ['firefox-'],
  webkit: ['webkit-'],
};

const BROWSER_EXE_NAMES = {
  chromium: ['chrome-headless-shell.exe', 'chrome-headless-shell', 'chrome.exe', 'chrome', 'Chromium'],
  firefox: ['firefox.exe', 'firefox'],
  webkit: ['Playwright.exe', 'pw_run.sh'],
};

export function browsersCacheDir() {
  if (process.env.PLAYWRIGHT_BROWSERS_PATH) return process.env.PLAYWRIGHT_BROWSERS_PATH;
  if (process.platform === 'win32') {
    return join(process.env.LOCALAPPDATA || join(homedir(), 'AppData', 'Local'), 'ms-playwright');
  }
  if (process.platform === 'darwin') return join(homedir(), 'Library', 'Caches', 'ms-playwright');
  return join(homedir(), '.cache', 'ms-playwright');
}

/** Depth-limited hunt for one of `names` under `dir`. The layouts differ per build. */
function findExecutable(dir, names, depth = 3) {
  if (depth < 0 || !existsSync(dir)) return null;
  let entries;
  try {
    entries = readdirSync(dir, { withFileTypes: true });
  } catch {
    return null;
  }
  for (const e of entries) {
    if (e.isFile() && names.includes(e.name)) return join(dir, e.name);
  }
  for (const e of entries) {
    if (!e.isDirectory()) continue;
    const hit = findExecutable(join(dir, e.name), names, depth - 1);
    if (hit) return hit;
  }
  return null;
}

/**
 * Newest cached build of `browserName`, or null. Headless shell wins — it starts faster.
 *
 * `headed: true` skips it: `chrome-headless-shell` has no UI at all, so a launch that must
 * put a window in front of a person (the SSO login) would start, render nothing, and wait
 * forever for a click nobody can make.
 */
export function findCachedBrowser(browserName, { headed = false } = {}) {
  const cache = browsersCacheDir();
  if (!existsSync(cache)) return null;
  const prefixes = (BROWSER_DIR_PREFIX[browserName] || [])
    .filter((p) => !(headed && p.includes('headless')));
  const names = BROWSER_EXE_NAMES[browserName] || [];
  let dirs;
  try {
    dirs = readdirSync(cache, { withFileTypes: true }).filter((d) => d.isDirectory());
  } catch {
    return null;
  }
  for (const prefix of prefixes) {
    const matching = dirs
      .filter((d) => d.name.startsWith(prefix))
      .sort((a, b) => Number(b.name.slice(prefix.length)) - Number(a.name.slice(prefix.length)));
    for (const d of matching) {
      const exe = findExecutable(join(cache, d.name), names);
      if (exe) return { exe, build: d.name };
    }
  }
  return null;
}

/**
 * Launch `engine`, falling back to an installed build if the bundled one is absent.
 * `opts.executablePath` short-circuits the search. `log` receives the substitution notice.
 */
export async function launchWithFallback(engine, browserName, opts = {}, log = console.log) {
  if (opts.executablePath) return engine.launch(opts);
  try {
    return await engine.launch(opts);
  } catch (e) {
    if (!/executable doesn't exist/i.test(e.message)) throw e;
    // headless:false means a person has to see this window, so the headless shell is not a
    // substitute — it would launch and show nothing.
    const found = findCachedBrowser(browserName, { headed: opts.headless === false });
    if (!found) throw e;
    log(`  (bundled ${browserName} is absent; using the installed build ${found.build})`);
    return engine.launch({ ...opts, executablePath: found.exe });
  }
}
