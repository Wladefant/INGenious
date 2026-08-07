#!/usr/bin/env node
/**
 * handoff-pack — move a recorded INGenious project from a tester to an automation engineer.
 *
 *   pack     turn a tester's Projects/<Name> folder into one self-describing .zip
 *   inspect  read the manifest out of such a zip without unpacking it
 *   unpack   drop the project into an engineer's install without clobbering anything,
 *            verify every file against the manifest, and print the exact command to run it
 *
 * What travels and what does not is decided here, once. The package is an internal
 * hand-off between colleagues, so the whole run travels with it — reports, screenshots, videos, traces, logs. Evidence you do
 * not send is not evidence. Two things stay behind, and only two:
 *   - the saved browser session: `login.json`, `*storageState*.json`, `state.json` are LIVE
 *     credentials, not results. Whoever opens the zip would be signed in as the tester.
 *   - tooling caches: `.git` and `node_modules` are nobody's work and nobody's proof.
 * Everything left behind is listed in the manifest with the rule that dropped it.
 *
 * Plain `unzip` works on the result — the zip is a standard deflate archive whose single
 * root folder is the project. `unpack` only adds the collision check and the hash check.
 *
 * No dependencies: the zip reader/writer below is ~150 lines of zlib.
 */

import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import crypto from 'node:crypto';
import os from 'node:os';
import { execFileSync } from 'node:child_process';

const TOOL = 'handoff-pack';
const MANIFEST = 'handoff.json';
const SCHEMA = 'ing-qa/handoff@1';
/**
 * Where `selector-uniqueness.mjs` leaves its answer, inside the project.
 *
 * Inside rather than beside, so it travels in the package: the engineer who opens the zip can
 * see what was checked, against which page, and when — without a live application of their own.
 */
const RECEIPT = 'selector-uniqueness.json';

/* ------------------------------------------------------------------ what never travels */

/**
 * Directories left behind. Two, and neither of them is anybody's work.
 *
 * `Results/`, `Recording/`, `media/` and `.migration-backup/` used to be here, and that was
 * the defect: the report, the screenshots, the video and the trace are the only things in the
 * package a human can check the run against, and they were dropped on the floor while the
 * summary told the tester it was on purpose. They travel now. What that costs is measured, not
 * guessed — see the size line `pack` prints.
 */
const EXCLUDED_DIRS = new Map([
    // Reconstructible from the repository and the registry, bigger than everything else in the
    // project put together, and not produced by the tester. Not evidence of anything.
    ['.git', 'tooling'],
    ['node_modules', 'tooling'],
]);

/**
 * Files left behind. Live credentials, and nothing else.
 *
 * A saved browser session is not a result: it is a signed-in session, and whoever holds the
 * zip holds the session. That is a different question from whether the evidence travels, which
 * is why the run output is let through here and these three still are not.
 */
const EXCLUDED_FILE_PATTERNS = [
    { rule: 'session-state', re: /^login\.json$/i },
    { rule: 'session-state', re: /storagestate.*\.json$/i },
    { rule: 'session-state', re: /^state\.json$/i },
];

/** Top-level folders holding the record of a run, counted so the summary can name what is in. */
const RUN_EVIDENCE_DIRS = new Set(['Results', 'Recording', 'media']);

/** Already-compressed payloads: deflating a 50 MB trace at level 9 buys bytes nobody needs. */
const PRECOMPRESSED = /\.(zip|webm|mp4|png|jpe?g|gif|gz|woff2?)$/i;

/** Settings keys whose value is a credential the receiving side has to supply itself. */
const CREDENTIAL_KEYS = /^(password|userID|apiKey|token|secret|clientSecret|accessKey)$/i;

/** Text file extensions worth scanning for credentials and machine-local paths. */
const TEXT_EXT = /\.(csv|json|ya?ml|properties|txt|md|xml|java|js|html)$/i;

/* --------------------------------------------------------------------------- tiny zip */

const CRC_TABLE = (() => {
    const table = new Int32Array(256);
    for (let n = 0; n < 256; n++) {
        let c = n;
        for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
        table[n] = c;
    }
    return table;
})();

function crc32(buf) {
    let c = -1;
    for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
    return (c ^ -1) >>> 0;
}

function dosTime(date) {
    const time = ((date.getHours() & 0x1f) << 11) | ((date.getMinutes() & 0x3f) << 5) | ((date.getSeconds() / 2) & 0x1f);
    const day = (((date.getFullYear() - 1980) & 0x7f) << 9) | (((date.getMonth() + 1) & 0x0f) << 5) | (date.getDate() & 0x1f);
    return { time, day };
}

/**
 * @param entries {{name: string, data: Buffer}[]}
 *
 * Plain zip, no ZIP64. Now that a whole run travels, the format's own ceilings are reachable,
 * so they are checked and said out loud: writing past them produces an archive that opens
 * wrong rather than not at all, which is the worst of the three outcomes.
 */
function writeZip(entries, now = new Date()) {
    const { time, day } = dosTime(now);
    const locals = [];
    const centrals = [];
    let offset = 0;

    if (entries.length > 0xffff) {
        throw new Error(
            `${entries.length} files — a plain zip holds 65535. Nothing was written; ` +
            `tell the test-automation team, and do not zip the folder by hand either.`
        );
    }
    for (const entry of entries) {
        const name = Buffer.from(entry.name, 'utf8');
        if (entry.data.length > 0xffffffff) {
            throw new Error(`${entry.name} is ${human(entry.data.length)} — a plain zip holds 4 GiB per file.`);
        }
        // Deflating an mp4 or a Playwright trace.zip costs minutes and saves nothing.
        const deflated = PRECOMPRESSED.test(entry.name) ? null : zlib.deflateRawSync(entry.data, { level: 9 });
        const stored = deflated !== null && deflated.length < entry.data.length;
        const payload = stored ? deflated : entry.data;
        const method = stored ? 8 : 0;
        const crc = crc32(entry.data);

        const local = Buffer.alloc(30);
        local.writeUInt32LE(0x04034b50, 0);
        local.writeUInt16LE(20, 4);
        local.writeUInt16LE(0x0800, 6); // UTF-8 names
        local.writeUInt16LE(method, 8);
        local.writeUInt16LE(time, 10);
        local.writeUInt16LE(day, 12);
        local.writeUInt32LE(crc, 14);
        local.writeUInt32LE(payload.length, 18);
        local.writeUInt32LE(entry.data.length, 22);
        local.writeUInt16LE(name.length, 26);
        locals.push(local, name, payload);

        const central = Buffer.alloc(46);
        central.writeUInt32LE(0x02014b50, 0);
        central.writeUInt16LE(20, 4);
        central.writeUInt16LE(20, 6);
        central.writeUInt16LE(0x0800, 8);
        central.writeUInt16LE(method, 10);
        central.writeUInt16LE(time, 12);
        central.writeUInt16LE(day, 14);
        central.writeUInt32LE(crc, 16);
        central.writeUInt32LE(payload.length, 20);
        central.writeUInt32LE(entry.data.length, 24);
        central.writeUInt16LE(name.length, 28);
        central.writeUInt32LE(offset, 42);
        centrals.push(central, name);

        offset += local.length + name.length + payload.length;
        if (offset > 0xffffffff) {
            throw new Error(`the package passed 4 GiB at ${entry.name} — a plain zip cannot address past it.`);
        }
    }

    const centralBuf = Buffer.concat(centrals);
    const end = Buffer.alloc(22);
    end.writeUInt32LE(0x06054b50, 0);
    end.writeUInt16LE(entries.length, 8);
    end.writeUInt16LE(entries.length, 10);
    end.writeUInt32LE(centralBuf.length, 12);
    end.writeUInt32LE(offset, 16);
    return Buffer.concat([...locals, centralBuf, end]);
}

/** @returns {{name: string, data: Buffer}[]} */
function readZip(buf) {
    let eocd = buf.length - 22;
    while (eocd >= 0 && buf.readUInt32LE(eocd) !== 0x06054b50) eocd--;
    if (eocd < 0) throw new Error('not a zip file (no end-of-central-directory record)');
    const count = buf.readUInt16LE(eocd + 10);
    let pointer = buf.readUInt32LE(eocd + 16);
    const entries = [];

    for (let i = 0; i < count; i++) {
        if (buf.readUInt32LE(pointer) !== 0x02014b50) throw new Error('corrupt central directory');
        const method = buf.readUInt16LE(pointer + 10);
        const crc = buf.readUInt32LE(pointer + 16);
        const csize = buf.readUInt32LE(pointer + 20);
        const usize = buf.readUInt32LE(pointer + 24);
        const nameLen = buf.readUInt16LE(pointer + 28);
        const extraLen = buf.readUInt16LE(pointer + 30);
        const commentLen = buf.readUInt16LE(pointer + 32);
        const localOffset = buf.readUInt32LE(pointer + 42);
        const name = buf.toString('utf8', pointer + 46, pointer + 46 + nameLen);

        const localNameLen = buf.readUInt16LE(localOffset + 26);
        const localExtraLen = buf.readUInt16LE(localOffset + 28);
        const start = localOffset + 30 + localNameLen + localExtraLen;
        const raw = buf.subarray(start, start + csize);
        const data = method === 8 ? zlib.inflateRawSync(raw) : Buffer.from(raw);
        if (data.length !== usize || crc32(data) !== crc) throw new Error(`corrupt entry: ${name}`);
        entries.push({ name, data });

        pointer += 46 + nameLen + extraLen + commentLen;
    }
    return entries;
}

/* ------------------------------------------------------------------------- reading a project */

/**
 * @param dir the folder to read
 * @param base the project root, so paths come out relative to it
 *
 * The one file skipped by path rather than by name is a `handoff.json` left in the project root
 * by an earlier pack: a second pack would write two entries under that name into the archive,
 * the stale one would land last on unpack, and the hash check would then fail against the fresh
 * manifest sitting inside the same zip. Packing twice is normal — a tester re-runs and hands
 * over again — so this has to be the packer's problem, not the engineer's.
 */
function walk(dir, base = dir, out = { files: [], excluded: [] }) {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name))) {
        const full = path.join(dir, entry.name);
        const rel = path.relative(base, full).split(path.sep).join('/');
        if (entry.isDirectory()) {
            if (EXCLUDED_DIRS.has(entry.name)) {
                out.excluded.push({ path: rel + '/', rule: EXCLUDED_DIRS.get(entry.name) });
                continue;
            }
            walk(full, base, out);
            continue;
        }
        if (!entry.isFile()) continue;
        if (rel === MANIFEST) {
            out.excluded.push({ path: rel, rule: 'previous-manifest' });
            continue;
        }
        const excluded = EXCLUDED_FILE_PATTERNS.find((p) => p.re.test(entry.name));
        if (excluded) {
            out.excluded.push({ path: rel, rule: excluded.rule });
            continue;
        }
        out.files.push(rel);
    }
    return out;
}

function sha256(buf) {
    return crypto.createHash('sha256').update(buf).digest('hex');
}

function parseCsv(text) {
    const rows = text
        .split(/\r?\n/)
        .filter((line) => line.trim() !== '')
        .map((line) => {
            const cells = [];
            let cell = '';
            let quoted = false;
            for (let i = 0; i < line.length; i++) {
                const c = line[i];
                if (quoted) {
                    if (c === '"' && line[i + 1] === '"') { cell += '"'; i++; }
                    else if (c === '"') quoted = false;
                    else cell += c;
                } else if (c === '"') quoted = true;
                else if (c === ',') { cells.push(cell); cell = ''; }
                else cell += c;
            }
            cells.push(cell);
            return cells;
        });
    return rows;
}

/** ADO id = the leading digit run of the test case name (AdoNaming.adoIdFromTestCaseName). */
function adoIdOf(testCaseName) {
    const match = /^(\d+)/.exec(testCaseName.trim());
    return match ? match[1] : null;
}

function readContents(projectDir) {
    const scenarios = [];
    const testPlan = path.join(projectDir, 'TestPlan');
    if (fs.existsSync(testPlan)) {
        for (const scenario of fs.readdirSync(testPlan, { withFileTypes: true })) {
            if (!scenario.isDirectory()) continue;
            const cases = fs
                .readdirSync(path.join(testPlan, scenario.name))
                .filter((f) => f.toLowerCase().endsWith('.csv'))
                .map((f) => f.replace(/\.csv$/i, ''))
                .map((name) => ({ testCase: name, adoId: adoIdOf(name) }));
            if (cases.length) scenarios.push({ scenario: scenario.name, testCases: cases });
        }
    }

    const releases = [];
    const testLab = path.join(projectDir, 'TestLab');
    if (fs.existsSync(testLab)) {
        for (const release of fs.readdirSync(testLab, { withFileTypes: true })) {
            if (!release.isDirectory()) continue;
            const sets = fs
                .readdirSync(path.join(testLab, release.name))
                .filter((f) => f.toLowerCase().endsWith('.csv'))
                .map((file) => {
                    const rows = parseCsv(fs.readFileSync(path.join(testLab, release.name, file), 'utf8'));
                    const header = rows[0] ?? [];
                    const column = (row, name) => {
                        const index = header.indexOf(name);
                        return index >= 0 ? row[index] : '';
                    };
                    return {
                        testSet: file.replace(/\.csv$/i, ''),
                        rows: rows.slice(1).map((row) => ({
                            execute: column(row, 'Execute'),
                            scenario: column(row, 'TestScenario'),
                            testCase: column(row, 'TestCase'),
                            browser: column(row, 'Browser'),
                        })),
                    };
                });
            if (sets.length) releases.push({ release: release.name, testSets: sets });
        }
    }
    return { scenarios, releases };
}

/** The Testkunde sheet: which kind of customer each test case needs. Settings columns only. */
function readCustomerProfiles(projectDir) {
    const sheet = path.join(projectDir, 'TestData', 'Testkunde.csv');
    if (!fs.existsSync(sheet)) return { present: false, profiles: [] };
    const rows = parseCsv(fs.readFileSync(sheet, 'utf8'));
    if (rows.length < 2) return { present: true, profiles: [] };
    const header = rows[0];
    const keyColumns = new Set(['Scenario', 'Flow', 'Iteration', 'SubIteration']);
    const profiles = rows.slice(1).map((row) => {
        const settings = {};
        header.forEach((name, index) => {
            const value = (row[index] ?? '').trim();
            if (!name || keyColumns.has(name) || value === '') return;
            settings[name] = value;
        });
        return {
            scenario: row[header.indexOf('Scenario')] ?? '',
            testCase: row[header.indexOf('Flow')] ?? '',
            settings,
        };
    });
    return { present: true, profiles };
}

/**
 * What `selector-uniqueness.mjs` last found for this project, if anything.
 *
 * THIS IS A RECEIPT, NOT A GATE — and the difference is the whole design. The tempting wiring
 * is to have `pack` refuse a recording whose selectors are ambiguous. That would make packaging
 * require a reachable, authenticated application, and hand-off would then stop working in
 * exactly the situation it exists for: a Fachbereich colleague, at the end of the day, whose
 * session has expired or who is no longer on the network. Packaging is offline work and must
 * stay offline work.
 *
 * So the manifest records what is known. `{ checked: false }` is an honest answer and the
 * common one; what it replaces is silence, which an engineer opening the package would read as
 * "fine". A missing field says nothing; this field always says something.
 *
 * Nothing is recomputed here. The receipt is whatever the probe itself wrote — the summary and
 * the ambiguous entries, so the engineer can see which steps and how many matches without
 * unpacking and re-probing, plus the page it was measured against, because uniqueness is a
 * property of (selector, page state) and a count without its page is not a fact.
 *
 * @returns the receipt, or `null` when there is none to trust
 */
function readReceiptIfPresent(projectDir) {
    const file = path.join(projectDir, RECEIPT);
    if (!fs.existsSync(file)) return null;
    let receipt;
    try {
        receipt = JSON.parse(fs.readFileSync(file, 'utf8'));
    } catch (error) {
        // A receipt that cannot be read is not a check that was run. Saying so beats both
        // crashing the packaging and quietly reporting `checked: false`, which would hide a
        // corrupt file behind the same words as an honest absence.
        return { checked: false, unreadable: `${RECEIPT}: ${error.message}` };
    }
    if (!receipt || typeof receipt !== 'object' || !receipt.summary) return null;
    const ambiguous = (Array.isArray(receipt.results) ? receipt.results : [])
        .filter((r) => r.verdict === 'AMBIGUOUS' || r.verdict === 'AMBIGUOUS_FRAME')
        .map((r) => ({
            page: r.page,
            element: r.element,
            attribute: r.attribute,
            frame: r.frame || undefined,
            matched: r.count,
            // The one the engine will NOT raise at replay: css inside a frame is resolved with
            // a trailing .first(), so that run goes green on the wrong element
            // (https://github.com/ing-bank/INGenious/issues/320). An engineer re-running this
            // package will never see it, which is why it has to be in the manifest.
            silentAtReplay: r.silentAtReplay === true || undefined,
        }));
    return {
        checked: true,
        // When, so a stale receipt cannot pass for a fresh one. A check from before the last
        // recording describes a project that has since changed; `recordedAt` sits beside this
        // field in the manifest precisely so the two can be compared.
        checkedAt: fs.statSync(file).mtime.toISOString(),
        url: receipt.url ?? null,
        summary: receipt.summary,
        ambiguous,
    };
}

function lastChangeUnder(dir) {
    if (!fs.existsSync(dir)) return null;
    let newest = 0;
    const visit = (current) => {
        for (const entry of fs.readdirSync(current, { withFileTypes: true })) {
            const full = path.join(current, entry.name);
            if (entry.isDirectory()) visit(full);
            else newest = Math.max(newest, fs.statSync(full).mtimeMs);
        }
    };
    visit(dir);
    return newest ? new Date(newest).toISOString() : null;
}

/**
 * A browsable commit link built from the checkout's own remote, or null.
 *
 * Handles the two shapes a remote actually has — `https://host/owner/repo(.git)` and
 * `git@host:owner/repo.git` — and any credentials embedded in the URL are dropped, because
 * this string is written into a manifest that travels to another person.
 */
export function commitUrlFrom(remote, commit) {
    if (!remote || !commit) return null;
    const m = String(remote).trim()
        .match(/^(?:https?:\/\/(?:[^@/]*@)?|git@|ssh:\/\/(?:[^@/]*@)?)([^/:]+)[/:](.+?)(?:\.git)?$/);
    if (!m) return null;
    return `https://${m[1]}/${m[2]}/commit/${commit}`;
}

function gitInfo(repoRoot) {
    const run = (args) => execFileSync('git', args, { cwd: repoRoot, encoding: 'utf8' }).trim();
    try {
        return {
            commit: run(['rev-parse', 'HEAD']),
            branch: run(['rev-parse', '--abbrev-ref', 'HEAD']),
            dirty: run(['status', '--porcelain']) !== '',
            // Built from the checkout's OWN remote rather than a baked-in address. The
            // hard-coded one named a private repository, was written into every manifest
            // this tool produced, and would have been wrong for anybody who cloned from
            // anywhere else. No remote, or an unparsable one, yields null — the commit id
            // above still identifies the build.
            url: commitUrlFrom(run(['remote', 'get-url', 'origin']), run(['rev-parse', 'HEAD'])),
        };
    } catch {
        return { commit: null, branch: null, dirty: null, url: null };
    }
}

/** The INGenious build the tester recorded against, as far as the install will admit to it. */
function ingeniousVersion(installDir) {
    if (!installDir) return { install: null, version: 'unknown' };
    const versionFile = path.join(installDir, 'INSTALL-VERSION.txt');
    if (fs.existsSync(versionFile)) {
        const text = fs.readFileSync(versionFile, 'utf8').replace(/^﻿/, '');
        const field = (name) => (new RegExp(`^${name}\\s*:\\s*(.+)$`, 'm').exec(text)?.[1] ?? '').trim();
        return {
            install: installDir,
            version: field('describe') || field('commit') || 'unknown',
            commit: field('commit') || null,
            source: field('source') || null,
        };
    }
    const jar = fs.existsSync(installDir)
        ? fs.readdirSync(installDir).find((f) => /^ingenious-ide-.*\.jar$/i.test(f))
        : null;
    return { install: installDir, version: jar ? jar.replace(/^ingenious-ide-|\.jar$/gi, '') : 'unknown' };
}

/* -------------------------------------------------------------------------------- pack */

function pack(args) {
    const projectDir = path.resolve(required(args, '--project'));
    if (!fs.existsSync(projectDir)) fail(`no such project folder: ${projectDir}`);
    const projectName = path.basename(projectDir);
    if (!fs.existsSync(path.join(projectDir, 'TestPlan'))) {
        fail(`${projectDir} has no TestPlan folder — that is not an INGenious project`);
    }

    const outDir = path.resolve(args['--out'] ?? process.cwd());
    const tester = args['--tester'] ?? os.userInfo().username;
    const installDir = args['--install'] ? path.resolve(args['--install']) : guessInstall(projectDir);
    const repoRoot = path.resolve(path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')), '..');

    const { files, excluded } = walk(projectDir);
    const packedAt = new Date();

    const entries = [];
    const fileRecords = [];
    const credentialFindings = [];
    const absolutePathFindings = [];
    let totalBytes = 0;
    const runEvidence = { files: 0, bytes: 0 };

    for (const rel of files) {
        const data = fs.readFileSync(path.join(projectDir, rel));
        totalBytes += data.length;
        fileRecords.push({ path: rel, sizeBytes: data.length, sha256: sha256(data) });
        entries.push({ name: `${projectName}/${rel}`, data });

        const isRunEvidence = RUN_EVIDENCE_DIRS.has(rel.split('/')[0]);
        if (isRunEvidence) {
            runEvidence.files++;
            runEvidence.bytes += data.length;
        }
        // The warnings are about the project, not about the run. A report names the machine it
        // ran on in every second line; scanning it would bury the handful of absolute paths that
        // actually matter under thousands that are simply what a report looks like.
        if (isRunEvidence || !TEXT_EXT.test(rel)) continue;
        const text = data.toString('utf8');
        if (rel.startsWith('Settings/')) {
            for (const line of text.split(/\r?\n/)) {
                const match = /^([A-Za-z][\w.]*)\s*=\s*(.+)$/.exec(line.trim());
                if (match && CREDENTIAL_KEYS.test(match[1])) credentialFindings.push({ path: rel, key: match[1] });
            }
        }
        for (const match of text.matchAll(/(?:^|[\s="'])([A-Za-z]:[\\/][^\s"',]+)/g)) {
            absolutePathFindings.push({ path: rel, value: match[1] });
        }
    }

    const customers = readCustomerProfiles(projectDir);
    const contents = readContents(projectDir);
    const manifest = {
        schema: SCHEMA,
        tool: TOOL,
        toolCommit: gitInfo(repoRoot),
        packedAt: packedAt.toISOString(),
        // When the test steps were last written — the closest thing on disk to "when this was
        // recorded", and the field that tells an engineer whether the package is stale.
        recordedAt: lastChangeUnder(path.join(projectDir, 'TestPlan')),
        // Whether anybody counted how many elements these selectors actually match, and what
        // they found. A receipt, never a gate — see readReceiptIfPresent.
        selectorUniqueness: readReceiptIfPresent(projectDir) ?? { checked: false },
        packedBy: tester,
        packedOn: os.hostname(),
        project: { name: projectName, sourcePath: projectDir },
        ingenious: ingeniousVersion(installDir),
        adoTestCases: [
            ...new Set(contents.scenarios.flatMap((s) => s.testCases.map((c) => c.adoId).filter(Boolean))),
        ],
        contents,
        customerProfiles: customers,
        files: fileRecords,
        totalBytes,
        // The run itself, counted separately so the tester's summary can say what is in the
        // package rather than what it hopes is in there, and so a package with no run at all
        // says zero instead of implying one.
        runEvidence,
        excluded,
        warnings: {
            credentialsInSettings: credentialFindings,
            absolutePaths: absolutePathFindings,
        },
        note: args['--note'] ?? null,
        // The legacy argument form, which both 3.0.x and 3.1 accept. Kept as arguments rather
        // than a full command line because the launcher and the jar name belong to whichever
        // install receives the package; `unpack` fills those in from the install it unpacks into.
        runs: contents.releases.flatMap((release) =>
            release.testSets.map((set) => ({
                testSet: `${release.release}/${set.testSet}`,
                args:
                    `-project_location "Projects\\${projectName}" -release ${release.release} ` +
                    `-testset ${set.testSet} -browser Chromium -run -quit -dont_launch_report`,
            }))
        ),
    };

    entries.unshift({ name: `${projectName}/${MANIFEST}`, data: Buffer.from(JSON.stringify(manifest, null, 2) + '\n', 'utf8') });

    const stamp = packedAt.toISOString().replace(/[-:]/g, '').replace(/\..+/, '').replace('T', '-');
    const zipName = args['--name'] ?? `${projectName}_${sanitize(tester)}_${stamp}.zip`;
    fs.mkdirSync(outDir, { recursive: true });
    const zipPath = path.join(outDir, zipName);
    let zip;
    try {
        zip = writeZip(entries, packedAt);
    } catch (error) {
        // A ceiling of the zip format, not a filter: nothing was dropped to stay under it, and
        // nothing was written. Said plainly so the size is a decision somebody makes, not one
        // this tool made quietly.
        fail(error.message);
    }
    fs.writeFileSync(zipPath, zip);

    console.log(`${TOOL}: packed ${projectName}`);
    console.log(`  zip        ${zipPath}`);
    console.log(`  size       ${human(zip.length)} zipped, ${human(totalBytes)} of project files`);
    console.log(`  files      ${fileRecords.length} packed, ${excluded.length} paths left behind`);
    console.log(
        runEvidence.files
            ? `  results    ${runEvidence.files} file(s), ${human(runEvidence.bytes)} — reports, screenshots, videos, traces, logs`
            : `  results    0 file(s) — this project holds no run output to send`
    );
    // Counted by the tool that read the TestPlan, so the Studio panel can say "there was
    // nothing to hand over" instead of "✔ Fertig" over a package that holds only the project
    // skeleton. A tester who presses abgeben before recording anything gets a valid zip and a
    // sentence that means they are done — see the panel's showHandoff.
    console.log(`  testcases  ${manifest.contents.scenarios.reduce((n, s) => n + s.testCases.length, 0)} recorded in TestPlan`);
    console.log(`  sha256     ${sha256(zip)}`);
    console.log(`  ADO cases  ${manifest.adoTestCases.join(', ') || '(none named in test case titles)'}`);
    console.log(`  INGenious  ${manifest.ingenious.version}`);
    console.log(`  tooling    ${manifest.toolCommit.commit ?? 'unknown'}${manifest.toolCommit.dirty ? ' (dirty)' : ''}`);
    if (credentialFindings.length) {
        console.log(`  ! ${credentialFindings.length} credential setting(s) travel with the project — see warnings in ${MANIFEST}`);
    }
    if (absolutePathFindings.length) {
        console.log(`  ! ${absolutePathFindings.length} absolute path(s) inside the project — they will not resolve elsewhere`);
    }
    return zipPath;
}

/* ------------------------------------------------------------------------ inspect / unpack */

function manifestOf(zipPath) {
    const entries = readZip(fs.readFileSync(zipPath));
    const entry = entries.find((e) => e.name.endsWith(`/${MANIFEST}`) || e.name === MANIFEST);
    if (!entry) fail(`${zipPath} carries no ${MANIFEST} — not a hand-off package`);
    return { manifest: JSON.parse(entry.data.toString('utf8')), entries };
}

function inspect(args) {
    const { manifest } = manifestOf(path.resolve(args._[0] ?? required(args, '--zip')));
    console.log(JSON.stringify(manifest, null, 2));
}

function unpack(args) {
    const zipPath = path.resolve(args._[0] ?? required(args, '--zip'));
    const into = path.resolve(required(args, '--into'));
    const { manifest, entries } = manifestOf(zipPath);

    const name = args['--name'] ?? manifest.project.name;
    const target = path.join(into, name);
    if (fs.existsSync(target)) {
        fail(
            `${target} already exists — refusing to overwrite an engineer's own project.\n` +
            `  Unpack it beside the existing one:  --name ${name}_${sanitize(manifest.packedBy)}_${manifest.packedAt.slice(0, 10)}`
        );
    }

    const prefix = `${manifest.project.name}/`;
    let written = 0;
    for (const entry of entries) {
        if (!entry.name.startsWith(prefix)) fail(`unexpected entry outside the project folder: ${entry.name}`);
        const rel = entry.name.slice(prefix.length);
        if (rel.includes('..')) fail(`refusing path traversal: ${entry.name}`);
        const dest = path.join(target, rel);
        fs.mkdirSync(path.dirname(dest), { recursive: true });
        fs.writeFileSync(dest, entry.data);
        written++;
    }

    const mismatches = [];
    for (const record of manifest.files) {
        const dest = path.join(target, record.path);
        if (!fs.existsSync(dest)) { mismatches.push(`${record.path}: missing`); continue; }
        const actual = sha256(fs.readFileSync(dest));
        if (actual !== record.sha256) mismatches.push(`${record.path}: sha256 differs`);
    }

    console.log(`${TOOL}: unpacked ${manifest.project.name} -> ${target}`);
    console.log(`  packed     ${manifest.packedAt} by ${manifest.packedBy} on ${manifest.packedOn}`);
    console.log(`  recorded   ${manifest.recordedAt ?? 'unknown'}`);
    console.log(`  INGenious  ${manifest.ingenious.version}`);
    console.log(`  tooling    ${manifest.toolCommit.commit ?? 'unknown'}`);
    console.log(`  ADO cases  ${manifest.adoTestCases.join(', ') || '(none)'}`);
    console.log(`  files      ${written} written, ${manifest.files.length} verified against the manifest`);
    const evidence = manifest.runEvidence;
    if (evidence) {
        console.log(
            evidence.files
                ? `  results    ${evidence.files} file(s), ${human(evidence.bytes)} — the tester's run travelled with it`
                : `  results    none — the tester sent no run output with this project`
        );
    }
    if (mismatches.length) {
        console.error(`  FAILED     ${mismatches.length} file(s) do not match the manifest:`);
        for (const line of mismatches.slice(0, 20)) console.error(`    ${line}`);
        process.exit(3);
    }
    console.log(`  integrity  OK — every file matches the sha256 recorded when it was packed`);

    for (const profile of manifest.customerProfiles.profiles ?? []) {
        const settings = Object.entries(profile.settings).map(([k, v]) => `${k}=${v}`).join(', ');
        console.log(`  customer   ${profile.scenario} / ${profile.testCase}: ${settings || '(no profile recorded)'}`);
    }
    if (manifest.warnings.credentialsInSettings.length) {
        console.log(`  ! settings carry credential keys — check them before running:`);
        for (const finding of manifest.warnings.credentialsInSettings) console.log(`      ${finding.path}  ${finding.key}`);
    }
    if (manifest.warnings.absolutePaths.length) {
        console.log(`  ! absolute paths recorded on the tester's machine:`);
        for (const finding of manifest.warnings.absolutePaths.slice(0, 10)) console.log(`      ${finding.path}  ${finding.value}`);
    }

    const install = path.basename(into) === 'Projects' ? path.dirname(into) : null;
    const launcher = install && fs.existsSync(path.join(install, 'ingenious.bat')) ? 'ingenious.bat' : null;
    console.log(`\nRun it${install ? ` (from ${install})` : ''}:`);
    for (const run of manifest.runs ?? []) {
        const args = run.args.replace(`Projects\\${manifest.project.name}`, `Projects\\${name}`);
        console.log(`  # ${run.testSet}`);
        console.log(`  ${launcher ? `${launcher} ${args}` : `<ingenious launcher> ${args}`}`);
    }
    return target;
}

/* --------------------------------------------------------------------------------- plumbing */

function guessInstall(projectDir) {
    const parent = path.dirname(projectDir);
    if (path.basename(parent) !== 'Projects') return null;
    const install = path.dirname(parent);
    return fs.existsSync(path.join(install, 'lib')) ? install : null;
}

function sanitize(value) {
    return String(value).replace(/[^\w.-]+/g, '-');
}

/** Bytes in the unit a human would use to decide whether a package is sendable. */
function human(bytes) {
    if (bytes < 1024) return `${bytes} B`;
    const units = ['KiB', 'MiB', 'GiB'];
    let value = bytes / 1024;
    let unit = 0;
    while (value >= 1024 && unit < units.length - 1) { value /= 1024; unit++; }
    return `${value.toFixed(1)} ${units[unit]}`;
}

function required(args, flag) {
    if (!args[flag]) fail(`${flag} is required`);
    return args[flag];
}

function fail(message) {
    console.error(`${TOOL}: ${message}`);
    process.exit(1);
}

function parseArgs(argv) {
    const args = { _: [] };
    for (let i = 0; i < argv.length; i++) {
        const token = argv[i];
        if (token.startsWith('--')) {
            const next = argv[i + 1];
            if (next === undefined || next.startsWith('--')) args[token] = true;
            else { args[token] = next; i++; }
        } else args._.push(token);
    }
    return args;
}

const USAGE = `
${TOOL} — hand a recorded INGenious project from a tester to an automation engineer

  node tools/handoff-pack.mjs pack   --project <Projects\\Name> [--out <dir>] [--tester <name>]
                                     [--install <ingenious install>] [--note <text>]
  node tools/handoff-pack.mjs inspect <package.zip>
  node tools/handoff-pack.mjs unpack  <package.zip> --into <install>\\Projects [--name <NewName>]

The whole run travels: Results, Recording, media, reports, screenshots, videos, traces, logs.
Left behind are the saved browser session (login.json, *storageState*.json, state.json —
live credentials, not results) and the tooling caches .git and node_modules. Every path left
behind is named in the manifest with the rule that dropped it.
`;

const [command, ...rest] = process.argv.slice(2);
const args = parseArgs(rest);
switch (command) {
    case 'pack': pack(args); break;
    case 'inspect': inspect(args); break;
    case 'unpack': unpack(args); break;
    default: console.log(USAGE); process.exit(command ? 1 : 0);
}
