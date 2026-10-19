'use strict';
/*
 * The three sessions scripts/measure-web-ui.js measures, built from the probe fixture
 * (`./gradlew :inspector-daemon:writeProbeFixture`) rather than committed, since one is 33 MB:
 *
 *   probe — the fixture as written: 16 calls, 19 signals, two markers.
 *   big   — plus 3000 calls after the probe's, 1200 to /v3/shop/bulk and 1800 to /v3/shop/other.
 *           Over the route's 1000-row clamp, and tall enough that every pane scrolls.
 *   huge  — plus 160,000 calls to /v3/shop/bulk. Past the ~110k-element limit where spreading a
 *           row array into Math.min/max throws (KIN-37), and slow enough to read in full that a
 *           first draw waiting for the whole session shows (KIN-42).
 *
 * These are the sessions the signals item's verify panels used, rebuilt the same way.
 */
const fs = require('fs');
const path = require('path');

function sessionDir(dataDir) {
  const sessions = path.join(dataDir, 'sessions');
  const names = fs.readdirSync(sessions).filter((n) => fs.statSync(path.join(sessions, n)).isDirectory());
  if (names.length !== 1) throw new Error(`${sessions} holds ${names.length} sessions; the probe fixture writes one`);
  return path.join(sessions, names[0]);
}

function readRows(file) {
  return fs.readFileSync(file, 'utf8').split('\n').filter(Boolean).map((line) => JSON.parse(line));
}

function tsAt(startedAt, mono) {
  return new Date(Date.parse(startedAt) + mono).toISOString();
}

function bigRow(i, startedAt) {
  const bulk = i < 1200;
  const mono = 3010 + i;
  const failed = !bulk && i % 10 === 9;
  const row = {
    v: 1, id: `bx${String(i).padStart(5, '0')}`, ts: tsAt(startedAt, mono), mono,
    method: bulk ? 'GET' : 'POST', scheme: 'https', host: 'api.example.invalid',
    path: bulk ? '/v3/shop/bulk' : '/v3/shop/other', status: failed ? 500 : 200, ms: 120,
    callId: `bc${String(i).padStart(5, '0')}`, resBytes: 32,
    reqHeaders: { Accept: ['application/json'] }, resHeaders: { 'Content-Type': ['application/json'] },
    resContentType: 'application/json',
  };
  return row;
}

function hugeRow(i, startedAt) {
  const mono = 10000 + i;
  return {
    v: 1, id: `hx${String(i).padStart(6, '0')}`, ts: tsAt(startedAt, mono), mono,
    method: 'GET', scheme: 'https', host: 'api.example.invalid', path: '/v3/shop/bulk',
    status: 200, ms: 5, callId: `hc${String(i).padStart(6, '0')}`, resBytes: 2,
  };
}

function writeVariant(probeDir, outDir, extraRows) {
  fs.cpSync(probeDir, outDir, { recursive: true, verbatimSymlinks: true });
  if (!extraRows) return outDir;
  const dir = sessionDir(outDir);
  const meta = JSON.parse(fs.readFileSync(path.join(dir, 'meta.json'), 'utf8'));
  const rows = readRows(path.join(dir, 'index.jsonl')).concat(extraRows(meta.startedAt));
  rows.sort((a, b) => a.mono - b.mono);
  const out = fs.openSync(path.join(dir, 'index.jsonl'), 'w');
  // Written in chunks: 160,000 rows joined into one string is fine, but there is no need to.
  for (let at = 0; at < rows.length; at += 5000) {
    fs.writeSync(out, rows.slice(at, at + 5000).map((r) => JSON.stringify(r)).join('\n') + '\n');
  }
  fs.closeSync(out);
  return outDir;
}

// Writes probe/, big/ and huge/ data directories under outDir and returns their paths.
function writeSessions(probeDir, outDir) {
  sessionDir(probeDir); // fails early, with a reason, on a directory that is not the fixture
  const range = (n, make) => (startedAt) => Array.from({ length: n }, (_, i) => make(i, startedAt));
  return {
    probe: writeVariant(probeDir, path.join(outDir, 'probe'), null),
    big: writeVariant(probeDir, path.join(outDir, 'big'), range(3000, bigRow)),
    huge: writeVariant(probeDir, path.join(outDir, 'huge'), range(160000, hugeRow)),
  };
}

module.exports = { writeSessions };
