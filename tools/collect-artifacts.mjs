#!/usr/bin/env node
/**
 * Collect an INGenious test-run's output artifacts into a versioned tree:
 *   <out>/TC-<adoTestCaseId>/run-<timestamp>/
 * plus a manifest.json describing every regular file that was copied.
 *
 * Node stdlib only. See tools/README-collect-artifacts.md.
 */

import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';

function parseArgs(argv) {
  const args = { runDir: null, tc: null, out: './artifacts' };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--run-dir') {
      args.runDir = argv[++i];
    } else if (a === '--tc') {
      args.tc = argv[++i];
    } else if (a === '--out') {
      args.out = argv[++i];
    } else if (a === '--help' || a === '-h') {
      args.help = true;
    } else {
      // Unknown flag / bare value — ignore for simplicity; validation catches missing requireds.
    }
  }
  return args;
}

function die(msg) {
  console.error(msg);
  process.exit(1);
}

/** UTC timestamp as yyyymmdd-HHMMss (e.g. 20260720-142530). */
function utcRunTimestamp(date = new Date()) {
  const p = (n, w = 2) => String(n).padStart(w, '0');
  return (
    `${date.getUTCFullYear()}` +
    `${p(date.getUTCMonth() + 1)}` +
    `${p(date.getUTCDate())}` +
    `-${p(date.getUTCHours())}` +
    `${p(date.getUTCMinutes())}` +
    `${p(date.getUTCSeconds())}`
  );
}

/**
 * Recursively copy regular files and directories from srcDir into destDir.
 * Skips symbolic links (does not follow, does not error).
 * Appends { path, sizeBytes } for every regular file copied into `files`.
 * `relBase` is the forward-slash path relative to the run-<timestamp> root.
 */
function copyRecursive(srcDir, destDir, files, relBase = '') {
  fs.mkdirSync(destDir, { recursive: true });

  let entries;
  try {
    entries = fs.readdirSync(srcDir, { withFileTypes: true });
  } catch (err) {
    die(`Error: cannot read directory ${srcDir}: ${err.message}`);
  }

  for (const ent of entries) {
    const srcPath = path.join(srcDir, ent.name);
    const destPath = path.join(destDir, ent.name);
    const relPath = relBase ? `${relBase}/${ent.name}` : ent.name;

    let stat;
    try {
      stat = fs.lstatSync(srcPath);
    } catch {
      // Unreadable entry — skip rather than crash mid-copy.
      continue;
    }

    // Skip symlinks entirely (do not follow, do not copy the link).
    if (stat.isSymbolicLink()) {
      continue;
    }

    if (stat.isDirectory()) {
      copyRecursive(srcPath, destPath, files, relPath);
    } else if (stat.isFile()) {
      fs.copyFileSync(srcPath, destPath);
      files.push({
        path: relPath.replace(/\\/g, '/'),
        sizeBytes: stat.size,
      });
    }
    // Other types (FIFO, socket, device) are skipped silently.
  }
}

function main() {
  const args = parseArgs(process.argv.slice(2));

  if (args.help) {
    console.log(
      'Usage: node tools/collect-artifacts.mjs --run-dir <path> --tc <adoTestCaseId> [--out <artifacts-root>]',
    );
    process.exit(0);
  }

  if (args.tc === null || args.tc === undefined || String(args.tc).trim() === '') {
    die('Error: --tc is required and must be a non-empty string/number.');
  }
  if (args.runDir === null || args.runDir === undefined || String(args.runDir).trim() === '') {
    die('Error: --run-dir is required.');
  }

  const runDir = path.resolve(String(args.runDir));
  if (!fs.existsSync(runDir)) {
    die(`Error: --run-dir does not exist: ${runDir}`);
  }

  let runStat;
  try {
    runStat = fs.statSync(runDir);
  } catch (err) {
    die(`Error: cannot access --run-dir: ${runDir} (${err.message})`);
  }
  if (!runStat.isDirectory()) {
    die(`Error: --run-dir is not a directory: ${runDir}`);
  }

  let listing;
  try {
    listing = fs.readdirSync(runDir);
  } catch (err) {
    die(`Error: cannot read --run-dir: ${runDir} (${err.message})`);
  }
  if (listing.length === 0) {
    die(`Error: --run-dir is empty: ${runDir}`);
  }

  const tc = String(args.tc).trim();
  const outRoot = path.resolve(String(args.out ?? './artifacts'));
  const now = new Date();
  const timestamp = utcRunTimestamp(now);
  const collectedAt = now.toISOString();
  const destRun = path.join(outRoot, `TC-${tc}`, `run-${timestamp}`);

  fs.mkdirSync(destRun, { recursive: true });

  const files = [];
  copyRecursive(runDir, destRun, files);
  files.sort((a, b) => (a.path < b.path ? -1 : a.path > b.path ? 1 : 0));

  const manifest = {
    tcId: tc,
    sourceDir: runDir,
    collectedAt,
    files,
  };

  fs.writeFileSync(
    path.join(destRun, 'manifest.json'),
    `${JSON.stringify(manifest, null, 2)}\n`,
    'utf8',
  );

  console.log(`Collected ${files.length} files from ${runDir} into ${destRun}`);
}

main();
