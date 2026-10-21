#!/usr/bin/env node
'use strict';
/*
 * Measures the web UI in headless Chrome: what jsdom cannot see. That covers layout (the scope bar on
 * top, path stations landing below the sticky box), scrolling (a run toggle or a live row leaving
 * the pane where it is), and scale (first rows on a 160,000-call session). Every defect it pins was
 * found by hand in a verify panel, after the build that caused it. `traffic` streams real rows into
 * a daemon of its own through `/ingest` and judges the page's filtered re-read
 * (`lib/live-traffic-cases.js`).
 *
 *   node scripts/measure-web-ui.js                       # this working tree's web/, judged
 *   node scripts/measure-web-ui.js --base "$P/base"      # ... and what moved against another tree
 *
 * Run it once per web fix round, not before every commit. With --base it measures both trees at
 * once and prints only the readings whose value moved, plus every failure. Copy a base with
 * `P=$(scripts/qa-snapshot.sh base=<rev>)`.
 *
 * It needs Chrome (or CHROME=<binary>), JDK 21 (JAVA_HOME), and a built daemon
 * (`./gradlew :inspector-daemon:installDist`). It does not rebuild the daemon for each tree: it
 * serves each tree's `inspector-daemon/src/main/resources/web/` ahead of the jar's on the
 * classpath, so an edit to web/ is measured without a build. The daemon's Kotlin is this tree's;
 * a base whose routes differ is measured against today's routes, which the output says.
 *
 * Options: --head <dir> (default: this repository), --base <dir>, --install <dir>,
 * --probe <dir> (the probe fixture; written with gradle when absent), --only layout,run,live,topbar,room,shell,traffic,first,
 * --first-rows-ms <n> (default 3000), --keep (keep the temp directory).
 * Exit status: 1 when any judged reading on head fails, 2 when the suite could not run.
 */
const { spawn, execFileSync } = require('child_process');
const fs = require('fs');
const net = require('net');
const os = require('os');
const path = require('path');
const { launchChrome, sleep } = require('./lib/measure-cdp');
const { writeSessions } = require('./lib/measure-fixtures');
const cases = require('./lib/measure-cases');
const { topBar } = require('./lib/topbar-cases');
const { shell, statsFirstDraw } = require('./lib/shell-cases');
const { room } = require('./lib/list-room-cases');
const { liveTraffic } = require('./lib/live-traffic-cases');

const REPO = path.resolve(__dirname, '..');
const WEB = 'inspector-daemon/src/main/resources';

function parseArgs(argv) {
  const opts = {
    head: REPO, base: null, install: path.join(REPO, 'inspector-daemon/build/install/inspector'),
    probe: null, only: ['layout', 'run', 'live', 'topbar', 'room', 'shell', 'traffic', 'first'], firstRowsMs: 3000, keep: false,
  };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    const next = () => { if (i + 1 >= argv.length) throw new Error(`${arg} needs a value`); return argv[++i]; };
    if (arg === '--head') opts.head = path.resolve(next());
    else if (arg === '--base') opts.base = path.resolve(next());
    else if (arg === '--install') opts.install = path.resolve(next());
    else if (arg === '--probe') opts.probe = path.resolve(next());
    else if (arg === '--only') opts.only = next().split(',');
    else if (arg === '--first-rows-ms') opts.firstRowsMs = Number(next());
    else if (arg === '--keep') opts.keep = true;
    else throw new Error(`unknown option ${arg}`);
  }
  for (const side of [opts.head, opts.base].filter(Boolean)) {
    if (!fs.existsSync(path.join(side, WEB, 'web/scripts.txt'))) throw new Error(`${side} has no ${WEB}/web/scripts.txt`);
  }
  if (!fs.existsSync(path.join(opts.install, 'lib'))) {
    throw new Error(`no daemon at ${opts.install}; run ./gradlew :inspector-daemon:installDist`);
  }
  return opts;
}

function probeFixture(opts, tmp) {
  if (opts.probe) return opts.probe;
  const dir = path.join(tmp, 'probe-fixture');
  console.log('writing the probe fixture with gradle…');
  // Written by the measured tree's own build, so the fixture is the one that tree's probes use.
  execFileSync(path.join(opts.head, 'gradlew'), ['-q', ':inspector-daemon:writeProbeFixture', `-Pinspector.probeDir=${dir}`],
    { cwd: opts.head, stdio: ['ignore', 'ignore', 'inherit'] });
  return dir;
}

function freePort() {
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.listen(0, '127.0.0.1', () => { const { port } = server.address(); server.close(() => resolve(port)); });
    server.on('error', reject);
  });
}

const children = [];

async function startDaemon(opts, webRoot, dataDir, logFile) {
  const port = await freePort();
  const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin/java') : 'java';
  // The tree's resources directory comes first, so `web/<file>` resolves to that tree's files.
  const classpath = [webRoot, path.join(opts.install, 'lib', '*')].join(path.delimiter);
  const log = fs.openSync(logFile, 'w');
  const child = spawn(java, ['-cp', classpath, 'dev.inspector.daemon.MainKt', 'serve', '--data', dataDir, '--port', String(port)],
    { stdio: ['ignore', log, log] });
  children.push(child);
  for (let i = 0; i < 120; i++) {
    try { if ((await fetch(`http://127.0.0.1:${port}/api/sessions`)).ok) return `http://127.0.0.1:${port}/`; } catch (e) { /* not up yet */ }
    if (child.exitCode !== null) break;
    await sleep(500);
  }
  throw new Error(`daemon on ${dataDir} did not come up:\n${fs.readFileSync(logFile, 'utf8').slice(-2000)}`);
}

// One tree: its own copies of the sessions (the daemon writes to them), one daemon per session.
async function prepareSide(opts, name, tree, probe, tmp) {
  const sessions = writeSessions(probe, path.join(tmp, `${name}-data`));
  const urls = {};
  for (const [session, dir] of Object.entries(sessions)) {
    urls[session] = await startDaemon(opts, path.join(tree, WEB), dir, path.join(tmp, `${name}-${session}.log`));
  }
  return { name, tree, urls };
}

async function onPage(chrome, run) {
  const page = await chrome.openPage();
  try { return await run(page); } finally { await page.close(); }
}

async function measureSide(chrome, side, opts) {
  const groups = [];
  if (opts.only.includes('layout')) {
    groups.push((p) => cases.layout(p, side.urls.probe, 'probe'));
    groups.push((p) => cases.layout(p, side.urls.big, 'big'));
  }
  if (opts.only.includes('run')) groups.push((p) => cases.runToggle(p, side.urls.big, 'big'));
  if (opts.only.includes('live')) groups.push((p) => cases.liveArrival(p, side.urls.big, 'big'));
  if (opts.only.includes('topbar')) groups.push((p) => topBar(p, side.urls.big, 'long'));
  return groups;
}

function line(row) {
  const mark = row.ok === true ? 'ok  ' : row.ok === false ? 'FAIL' : '--  ';
  return `${mark} ${row.key}: ${row.value}${row.detail ? ` (${row.detail})` : ''}`;
}

async function main() {
  let opts;
  try { opts = parseArgs(process.argv.slice(2)); } catch (e) { console.error(`measure-web-ui: ${e.message}`); return 2; }
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'measure-web-ui-'));
  let chrome = null;
  try {
    const probe = probeFixture(opts, tmp);
    const sides = [await prepareSide(opts, 'head', opts.head, probe, tmp)];
    if (opts.base) sides.push(await prepareSide(opts, 'base', opts.base, probe, tmp));
    chrome = await launchChrome(path.join(tmp, 'chrome-profile'));

    // Layout, run and live cases run in parallel tabs, both trees at once. Timing cases run alone
    // afterwards, one at a time, so a busy CPU does not count against either tree.
    const results = Object.fromEntries(sides.map((s) => [s.name, []]));
    const jobs = [];
    for (const side of sides) {
      for (const group of await measureSide(chrome, side, opts)) {
        jobs.push(onPage(chrome, group).then((rows) => results[side.name].push(...rows)));
      }
    }
    await Promise.all(jobs);
    // After the parallel batch, one window per tree: twelve windows opened at once made Chrome's
    // DevTools endpoint drop a request, failing the whole run before anything was measured.
    if (opts.only.includes('room')) {
      await Promise.all(sides.map((side) => onPage(chrome, (p) => room(p, side.urls))
        .then((rows) => results[side.name].push(...rows))));
    }
    if (opts.only.includes('shell')) {
      await Promise.all(sides.map((side) => onPage(chrome, (p) => shell(p, side.urls.big, 'long'))
        .then((rows) => results[side.name].push(...rows))));
    }
    // Against a daemon per tree with an empty data directory, so the streamed sessions are the only
    // ones it holds; one tree at a time, because the burst's bound is counted against a timer.
    if (opts.only.includes('traffic')) {
      for (const side of sides) {
        const data = path.join(tmp, `${side.name}-traffic-data`);
        fs.mkdirSync(data);
        const origin = await startDaemon(opts, path.join(side.tree, WEB), data, path.join(tmp, `${side.name}-traffic.log`));
        results[side.name].push(...await onPage(chrome, (p) => liveTraffic(p, origin)));
      }
    }
    if (opts.only.includes('first')) {
      for (const session of ['huge', 'big']) {
        for (const side of sides) {
          results[side.name].push(...await onPage(chrome, (p) => cases.firstRows(p, side.urls[session], session, opts.firstRowsMs)));
          if (session === 'huge') results[side.name].push(...await onPage(chrome, (p) => statsFirstDraw(p, side.urls.huge, session)));
        }
      }
    }

    const head = results.head;
    console.log(`head: ${opts.head}`);
    for (const row of head) console.log(line(row));
    const failed = head.filter((r) => r.ok === false).length;
    if (opts.base) {
      const base = new Map(results.base.map((r) => [r.key, r]));
      const moved = head.filter((r) => !base.has(r.key) || base.get(r.key).value !== r.value);
      const gone = results.base.filter((r) => !head.some((h) => h.key === r.key));
      console.log(`\nmoved against base (${opts.base}); the daemon's Kotlin is this tree's for both:`);
      for (const r of moved) console.log(`  ${r.key}: ${base.has(r.key) ? base.get(r.key).value : '(not measured)'} -> ${r.value}`);
      for (const r of gone) console.log(`  ${r.key}: ${r.value} -> (not measured)`);
      if (!moved.length && !gone.length) console.log('  nothing');
      const baseFailed = results.base.filter((r) => r.ok === false).length;
      console.log(`base: ${results.base.length} readings, ${baseFailed} failed`);
    }
    console.log(`head: ${head.length} readings, ${failed} failed`);
    return failed ? 1 : 0;
  } catch (e) {
    console.error(`measure-web-ui: ${e.stack || e.message}`);
    return 2;
  } finally {
    if (chrome) chrome.close();
    for (const child of children) child.kill();
    if (opts.keep) console.log(`kept ${tmp}`);
    else {
      await sleep(500); // Chrome is still writing its profile as it exits
      try { fs.rmSync(tmp, { recursive: true, force: true }); } catch (e) { console.error(`left ${tmp}: ${e.message}`); }
    }
  }
}

main().then((code) => process.exit(code));
