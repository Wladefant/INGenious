#!/usr/bin/env node
/**
 * Tests for handoff-pack.mjs — Node stdlib only, no network, no INGenious install needed.
 *
 *   node tools/test-handoff-pack.mjs
 *
 * The package travels from a tester to an automation engineer inside the organisation, and
 * the test-management system it names is internal too. So the only thing pack has to do is
 * pack: a project made of the identifiers this project scatters everywhere — ADO test-case
 * ids in file names, object-repository names and step rows, INGenious' own shipped API
 * sample, a Testkunde sheet — goes through, and so does one carrying a plain digit run.
 * There is nothing here for a tester to have done wrong.
 *
 * What is still absolute, and is what these tests guard: a **saved browser session** never
 * leaves the tester's machine, and neither does run output. `sessionStateNeverTravels` fails
 * if that exclusion is deleted or weakened — the fixture plants the files it must drop.
 *
 * All fixtures are built in a fresh temp folder outside the repository, from made-up values.
 */

import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import process from 'node:process';
import zlib from 'node:zlib';
import { fileURLToPath } from 'node:url';

const TOOL = path.resolve(path.dirname(fileURLToPath(import.meta.url)), 'handoff-pack.mjs');

/** A digit run in the shape of an account number. Made up — 4711/0815 are joke numbers. */
const SYNTHETIC_ACCOUNT = '4711000815';
/** An ADO test-case id, the kind our own naming writes into file names and titles. */
const ADO_ID = '10434440';

let failures = 0;
function test(name, fn) {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'handoff-pack-test-'));
    try {
        fn(dir);
        console.log(`  ok    ${name}`);
    } catch (error) {
        failures++;
        console.log(`  FAIL  ${name}`);
        console.log(`        ${(error?.message ?? error).toString().split('\n').join('\n        ')}`);
    } finally {
        fs.rmSync(dir, { recursive: true, force: true });
    }
}

function write(file, text) {
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, text, 'utf8');
}

/**
 * A project shaped like the ones we actually produce: every file name, every object name and
 * every step row carries the ADO id, and INGenious' own API sample is still in place.
 */
function makeProject(root, extras = {}) {
    const p = path.join(root, 'HandoffFixture');
    write(
        path.join(p, 'TestPlan', 'Listenarchiv', `${ADO_ID} - Aufruf und Bearbeitung.csv`),
        'Scenario,Flow,Iteration,SubIteration\nListenarchiv,' + `${ADO_ID} - Aufruf und Bearbeitung` + ',1,1\n'
    );
    write(
        path.join(p, 'TestPlan', 'Listenarchiv', `${ADO_ID} - Aufruf und Bearbeitung.yaml`),
        `testCase: "${ADO_ID} - Aufruf und Bearbeitung"\nsteps:\n` +
        `  - object: Feld${ADO_ID}\n    action: Set\n    input: "${ADO_ID}"\n`
    );
    write(
        path.join(p, 'ObjectRepository', 'Web', `${ADO_ID} - Aufruf und Bearbeitung.yaml`),
        `objects:\n  Feld${ADO_ID}:\n    testId: "feld-${ADO_ID}"\n`
    );
    // INGenious ships this in every new project. It must never be anything a tool complains about.
    write(
        path.join(p, 'api', 'collections', 'My_Collection.json'),
        JSON.stringify({ info: { _postman_id: 'a1b2', name: 'My_Collection' }, epoch: 1779862345551, account: '49997931' }, null, 2)
    );
    write(
        path.join(p, 'TestData', 'Testkunde.csv'),
        'Scenario,Flow,Iteration,SubIteration,Partnertyp,Produktvariante\n' +
        `Listenarchiv,${ADO_ID} - Aufruf und Bearbeitung,1,1,P,Extrakonto\n`
    );
    write(
        path.join(p, 'TestLab', 'Release1', 'FixtureSet.csv'),
        'Execute,TestScenario,TestCase,Browser\n' +
        `Y,Listenarchiv,${ADO_ID} - Aufruf und Bearbeitung,Chromium\n`
    );
    write(path.join(p, 'Settings', 'contextsettings.Properties'), 'userID=admin\npassword=admin\n');
    for (const [rel, text] of Object.entries(extras)) write(path.join(p, rel), text);
    return p;
}

function runPack(projectDir, outDir, extraArgs = []) {
    return spawnSync(process.execPath, [TOOL, 'pack', '--project', projectDir, '--out', outDir, '--tester', 'fixture', ...extraArgs], {
        encoding: 'utf8',
    });
}

/** Entry names inside a zip, read from the central directory. */
function zipEntryNames(zipPath) {
    const buf = fs.readFileSync(zipPath);
    let eocd = -1;
    for (let i = buf.length - 22; i >= 0; i--) if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
    assert.notEqual(eocd, -1, 'no end-of-central-directory record — not a zip');
    const count = buf.readUInt16LE(eocd + 10);
    let at = buf.readUInt32LE(eocd + 16);
    const names = [];
    for (let i = 0; i < count; i++) {
        const nameLen = buf.readUInt16LE(at + 28);
        const extraLen = buf.readUInt16LE(at + 30);
        const commentLen = buf.readUInt16LE(at + 32);
        names.push(buf.toString('utf8', at + 46, at + 46 + nameLen));
        at += 46 + nameLen + extraLen + commentLen;
    }
    return names;
}

function manifestFrom(zipPath) {
    const buf = fs.readFileSync(zipPath);
    // Local header of the first entry, which pack writes as the manifest.
    assert.equal(buf.readUInt32LE(0), 0x04034b50, 'first record is not a local file header');
    const method = buf.readUInt16LE(8);
    const compressed = buf.readUInt32LE(18);
    const uncompressed = buf.readUInt32LE(22);
    const nameLen = buf.readUInt16LE(26);
    const extraLen = buf.readUInt16LE(28);
    const name = buf.toString('utf8', 30, 30 + nameLen);
    assert.ok(name.endsWith('/handoff.json'), `first zip entry is ${name}, expected the manifest`);
    const start = 30 + nameLen + extraLen;
    const raw = buf.subarray(start, start + (method === 8 ? compressed : uncompressed));
    return JSON.parse((method === 8 ? zlib.inflateRawSync(raw) : raw).toString('utf8'));
}

console.log('handoff-pack');

/* ------------------------------------------------------------------ it packs */

test('a project of ADO ids and shipped samples packs', (dir) => {
    const project = makeProject(dir);
    const result = runPack(project, path.join(dir, 'out'));
    assert.equal(result.status, 0, `exit ${result.status}\n${result.stderr}`);
    const zips = fs.readdirSync(path.join(dir, 'out'));
    assert.equal(zips.length, 1, `expected one package, got ${zips.join(', ')}`);
    const manifest = manifestFrom(path.join(dir, 'out', zips[0]));
    assert.deepEqual(manifest.adoTestCases, [ADO_ID], 'the ADO id should be read out of the test case name');
    assert.ok(manifest.files.length >= 7, `only ${manifest.files.length} files packed`);
});

test('a plain digit run in a step does not stop the package', (dir) => {
    const project = makeProject(dir, {
        [path.join('TestPlan', 'Listenarchiv', 'Weitere.yaml')]:
            `steps:\n  - action: Set\n    input: "${SYNTHETIC_ACCOUNT}"\n`,
    });
    const result = runPack(project, path.join(dir, 'out'));
    assert.equal(result.status, 0, `exit ${result.status}\n${result.stderr}`);
    assert.equal(result.stderr.trim(), '', `pack wrote to stderr: ${result.stderr}`);
    const zip = path.join(dir, 'out', fs.readdirSync(path.join(dir, 'out'))[0]);
    assert.ok(
        zipEntryNames(zip).some((n) => n.endsWith('TestPlan/Listenarchiv/Weitere.yaml')),
        'the file carrying the digit run should be in the package like any other'
    );
});

test('a Kontonummer column in the Testkunde sheet does not stop the package', (dir) => {
    const project = makeProject(dir, {
        [path.join('TestData', 'Testkunde.csv')]:
            'Scenario,Flow,Iteration,SubIteration,Kontonummer,Partnertyp\n' +
            `Listenarchiv,${ADO_ID} - Aufruf und Bearbeitung,1,1,${SYNTHETIC_ACCOUNT},P\n`,
    });
    const result = runPack(project, path.join(dir, 'out'));
    assert.equal(result.status, 0, `exit ${result.status}\n${result.stderr}`);
});

test('nothing in the output or the manifest calls the project’s own data a problem', (dir) => {
    const project = makeProject(dir, {
        [path.join('TestData', 'Testkunde.csv')]:
            'Scenario,Flow,Iteration,SubIteration,Kontonummer\n' +
            `Listenarchiv,${ADO_ID} - Aufruf und Bearbeitung,1,1,${SYNTHETIC_ACCOUNT}\n`,
    });
    const result = runPack(project, path.join(dir, 'out'));
    const said = `${result.stdout}${result.stderr}`;
    assert.doesNotMatch(said, /account|Konto|refus/i, `pack still talks about account data:\n${said}`);
    const manifest = manifestFrom(path.join(dir, 'out', fs.readdirSync(path.join(dir, 'out'))[0]));
    assert.doesNotMatch(
        JSON.stringify(manifest.warnings),
        /account|konto/i,
        `the manifest still warns about account data: ${JSON.stringify(manifest.warnings)}`
    );
});

/* ------------------------------------------- what is still absolute: sessions and output */

test('sessionStateNeverTravels — a saved session and run output are left behind', (dir) => {
    const project = makeProject(dir, {
        'login.json': '{"cookies":[{"name":"SESSION","value":"fixture-not-a-real-token"}]}',
        [path.join('Results', 'TestExecution', 'report.html')]: '<html>past run</html>',
        [path.join('Recording', 'Scratch.java')]: 'class Scratch {}',
        [path.join('TestData', 'sheet.csv.bak')]: 'old\n',
    });
    const result = runPack(project, path.join(dir, 'out'));
    assert.equal(result.status, 0, `exit ${result.status}\n${result.stderr}`);
    const zip = path.join(dir, 'out', fs.readdirSync(path.join(dir, 'out'))[0]);
    const names = zipEntryNames(zip).join('\n');
    for (const forbidden of ['login.json', 'Results/', 'Recording/', '.bak']) {
        assert.ok(!names.includes(forbidden), `${forbidden} reached the package:\n${names}`);
    }
    const manifest = manifestFrom(zip);
    assert.ok(
        manifest.excluded.some((e) => e.rule === 'session-state' && e.path.endsWith('login.json')),
        `the manifest does not record the session file as excluded: ${JSON.stringify(manifest.excluded)}`
    );
});

/* ------------------------------------------------------------------------ the round trip */

test('unpack restores the project and refuses to clobber an existing one', (dir) => {
    const project = makeProject(dir);
    assert.equal(runPack(project, path.join(dir, 'out')).status, 0);
    const zip = path.join(dir, 'out', fs.readdirSync(path.join(dir, 'out'))[0]);
    const into = path.join(dir, 'engineer', 'Projects');
    fs.mkdirSync(into, { recursive: true });

    const first = spawnSync(process.execPath, [TOOL, 'unpack', zip, '--into', into], { encoding: 'utf8' });
    assert.equal(first.status, 0, `exit ${first.status}\n${first.stderr}`);
    assert.ok(
        fs.existsSync(path.join(into, 'HandoffFixture', 'ObjectRepository', 'Web', `${ADO_ID} - Aufruf und Bearbeitung.yaml`)),
        'the object repository did not arrive'
    );

    const second = spawnSync(process.execPath, [TOOL, 'unpack', zip, '--into', into], { encoding: 'utf8' });
    assert.notEqual(second.status, 0, 'unpacking twice should not overwrite the first copy');
});

console.log(failures ? `\n${failures} failing` : '\nall green');
process.exit(failures ? 1 : 0);
