#!/usr/bin/env node
/**
 * Parse one INGenious test-run output directory into a normalized JSON results document.
 * Node.js stdlib only. See tools/README-parse-report.md.
 */

import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';

function die(msg) {
  console.error(msg);
  process.exit(1);
}

function parseArgs(argv) {
  const args = { runDir: null, json: null };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--run-dir') {
      args.runDir = argv[++i];
    } else if (a === '--json') {
      args.json = argv[++i];
    }
  }
  return args;
}

/** Normalize a screenshot link to a forward-slash relative path. */
function normalizeLink(link) {
  return String(link)
    .replace(/^[\\/]+/, '')
    .replace(/\\/g, '/');
}

/**
 * Walk STEPS tree; collect screenshot paths from leaf step nodes (in order).
 * Does not compute pass/fail counts.
 */
function collectScreenshots(steps) {
  const out = [];
  function walk(nodes) {
    if (!Array.isArray(nodes)) return;
    for (const node of nodes) {
      if (!node || typeof node !== 'object') continue;
      if (node.type === 'step' && node.data && !Array.isArray(node.data)) {
        if (node.data.link) {
          out.push(normalizeLink(node.data.link));
        }
      } else if (Array.isArray(node.data)) {
        walk(node.data);
      }
    }
  }
  walk(steps);
  return out;
}

/** Parse "HH:MM:SS" into seconds, or null if unparseable. */
function parseExeTime(exeTime) {
  if (exeTime == null || typeof exeTime !== 'string') return null;
  const m = exeTime.trim().match(/^(\d+):(\d{2}):(\d{2})$/);
  if (!m) return null;
  const h = Number(m[1]);
  const min = Number(m[2]);
  const s = Number(m[3]);
  if (![h, min, s].every(Number.isFinite)) return null;
  return h * 3600 + min * 60 + s;
}

/** Duration in seconds from startTime/endTime if both parseable as Date. */
function durationFromTimestamps(startTime, endTime) {
  if (startTime == null || endTime == null) return null;
  const start = Date.parse(startTime);
  const end = Date.parse(endTime);
  if (!Number.isFinite(start) || !Number.isFinite(end)) return null;
  return Math.round((end - start) / 1000);
}

function buildName(scenarioName, testcaseName) {
  const s = scenarioName != null && String(scenarioName).length ? String(scenarioName) : null;
  const t = testcaseName != null && String(testcaseName).length ? String(testcaseName) : null;
  if (s && t) return `${s}:${t}`;
  if (s) return s;
  if (t) return t;
  return 'unknown';
}

function normalizeStatus(status) {
  if (status === 'PASS') return 'PASS';
  if (status === 'FAIL') return 'FAIL';
  return 'unknown';
}

/**
 * Normalize one per-testcase DATA object into an output testCases entry.
 * @param {object} obj - EXECUTIONS entry or var DATA from a per-tc HTML file
 * @param {{ reportFile?: string|null, htmlFiles?: string[] }} opts
 */
function normalizeTestCase(obj, opts = {}) {
  const scenarioName = obj.scenarioName;
  const testcaseName = obj.testcaseName;

  let steps = null;
  if (obj.nopassTests !== undefined && obj.nofailTests !== undefined) {
    const passed = Number(obj.nopassTests);
    const failed = Number(obj.nofailTests);
    steps = {
      passed,
      failed,
      total: passed + failed,
    };
  }

  let durationSeconds = parseExeTime(obj.exeTime);
  if (durationSeconds === null) {
    durationSeconds = durationFromTimestamps(obj.startTime, obj.endTime);
  }

  let reportFile = opts.reportFile !== undefined ? opts.reportFile : null;
  if (reportFile === null && Array.isArray(opts.htmlFiles) && scenarioName && testcaseName) {
    const prefix = `${scenarioName}_${testcaseName}_`;
    const match = opts.htmlFiles.find((f) => f.startsWith(prefix));
    reportFile = match ?? null;
  }

  return {
    name: buildName(scenarioName, testcaseName),
    status: normalizeStatus(obj.status),
    steps,
    durationSeconds,
    screenshots: collectScreenshots(obj.STEPS),
    reportFile,
  };
}

/** Derive scenario/testcase name from a per-tc HTML filename (empty/missing DATA). */
function nameFromFilename(filename) {
  const base = filename.replace(/-v2\.html$/i, '').replace(/\.html$/i, '');
  const parts = base.split('_');
  const scenarioName = parts[0] || null;
  const testcaseName = parts.length > 1 ? parts[1] : null;
  return {
    scenarioName,
    testcaseName,
    name: buildName(scenarioName, testcaseName),
  };
}

/** Extract JSON object from a line containing `var DATA = {...};</script>`. */
function extractVarDataFromHtml(content) {
  const marker = 'var DATA = ';
  const idx = content.indexOf(marker);
  if (idx === -1) return null;

  // Work on the single line that holds the DATA assignment.
  const lineStart = content.lastIndexOf('\n', idx) + 1;
  let lineEnd = content.indexOf('\n', idx);
  if (lineEnd === -1) lineEnd = content.length;
  const line = content.slice(lineStart, lineEnd);

  const start = line.indexOf(marker);
  if (start === -1) return null;
  const endMarker = '};</script>';
  const end = line.lastIndexOf(endMarker);
  if (end === -1 || end < start) return null;

  const jsonStr = line.slice(start + marker.length, end + 1); // include closing }
  return JSON.parse(jsonStr);
}

function listRootHtmlFiles(runDir) {
  let entries;
  try {
    entries = fs.readdirSync(runDir, { withFileTypes: true });
  } catch (err) {
    die(`Error: cannot read run dir: ${runDir}: ${err.message}`);
  }
  return entries
    .filter((e) => e.isFile() && e.name.toLowerCase().endsWith('.html'))
    .map((e) => e.name);
}

/** Per-tc report HTML files (exclude set-level detailed/summary reports). */
function listPerTcHtmlFiles(htmlFiles) {
  return htmlFiles.filter((name) => {
    const lower = name.toLowerCase();
    if (lower === 'detailed-v2.html' || lower === 'summary-v2.html') return false;
    if (lower === 'detailed.html' || lower === 'summary.html') return false;
    // Per-tc names look like Scenario_TestCase_Iteration_Browser-v2.html
    return name.includes('_') && lower.endsWith('-v2.html');
  });
}

function parseDataJs(runDir, htmlFiles) {
  const dataPath = path.join(runDir, 'data.js');
  const raw = fs.readFileSync(dataPath, 'utf8');
  let text = raw.trim();
  if (text.startsWith('var DATA=')) {
    text = text.slice('var DATA='.length);
  } else if (text.startsWith('var DATA =')) {
    text = text.slice('var DATA ='.length);
  }
  if (text.endsWith(';')) text = text.slice(0, -1);
  const data = JSON.parse(text);
  const executions = Array.isArray(data.EXECUTIONS) ? data.EXECUTIONS : [];
  const testCases = executions.map((exec) =>
    normalizeTestCase(exec, { htmlFiles }),
  );
  return { shape: 'data-js', testCases };
}

function parsePerTcHtml(runDir, htmlFiles) {
  const perTc = listPerTcHtmlFiles(htmlFiles);
  const testCases = [];

  for (const filename of perTc) {
    const full = path.join(runDir, filename);
    let stat;
    try {
      stat = fs.statSync(full);
    } catch {
      const derived = nameFromFilename(filename);
      testCases.push({
        name: derived.name,
        status: 'unknown',
        steps: null,
        durationSeconds: null,
        screenshots: [],
        reportFile: filename,
      });
      continue;
    }

    if (stat.size === 0) {
      const derived = nameFromFilename(filename);
      testCases.push({
        name: derived.name,
        status: 'unknown',
        steps: null,
        durationSeconds: null,
        screenshots: [],
        reportFile: filename,
      });
      continue;
    }

    let content;
    try {
      content = fs.readFileSync(full, 'utf8');
    } catch {
      const derived = nameFromFilename(filename);
      testCases.push({
        name: derived.name,
        status: 'unknown',
        steps: null,
        durationSeconds: null,
        screenshots: [],
        reportFile: filename,
      });
      continue;
    }

    let obj;
    try {
      obj = extractVarDataFromHtml(content);
    } catch {
      obj = null;
    }

    if (!obj) {
      const derived = nameFromFilename(filename);
      testCases.push({
        name: derived.name,
        status: 'unknown',
        steps: null,
        durationSeconds: null,
        screenshots: [],
        reportFile: filename,
      });
      continue;
    }

    testCases.push(normalizeTestCase(obj, { reportFile: filename }));
  }

  return { shape: 'per-tc-html', testCases };
}

function main() {
  const args = parseArgs(process.argv.slice(2));

  if (!args.runDir) {
    console.error(
      'Usage: node tools/parse-report.mjs --run-dir <dir> [--json <out-path>]',
    );
    process.exit(1);
  }

  const runDir = path.resolve(args.runDir);

  let st;
  try {
    st = fs.statSync(runDir);
  } catch {
    die(`Error: run dir not found: ${args.runDir}`);
  }
  if (!st.isDirectory()) {
    die(`Error: run dir not found: ${args.runDir}`);
  }

  const dataJsPath = path.join(runDir, 'data.js');
  const hasDataJs = fs.existsSync(dataJsPath) && fs.statSync(dataJsPath).isFile();
  const htmlFiles = listRootHtmlFiles(runDir);

  const { shape, testCases } = hasDataJs
    ? parseDataJs(runDir, htmlFiles)
    : parsePerTcHtml(runDir, htmlFiles);

  const doc = {
    runDir,
    parsedAt: new Date().toISOString(),
    shape,
    testCases,
    totals: {
      cases: testCases.length,
      passed: testCases.filter((tc) => tc.status === 'PASS').length,
      failed: testCases.filter((tc) => tc.status === 'FAIL').length,
    },
  };

  const pretty = JSON.stringify(doc, null, 2);

  if (args.json) {
    const outPath = path.resolve(args.json);
    fs.mkdirSync(path.dirname(outPath), { recursive: true });
    fs.writeFileSync(outPath, pretty + '\n', 'utf8');
    console.log(`Wrote ${outPath}`);
  } else {
    console.log(pretty);
  }
}

main();
