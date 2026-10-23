#!/usr/bin/env node
'use strict';
/*
 * Measures the web UI in headless Chrome: what jsdom cannot see. That covers layout (the scope bar on
 * top, path stations landing below the sticky box), scrolling (a run toggle or a live row leaving
 * the pane where it is), and scale (first rows on a 160,000-call session). Every defect it pins was
 * found by hand in a verify panel, after the build that caused it. `traffic` streams real rows into
 * a daemon of its own through `/ingest` and judges the page's filtered re-read
 * (`lib/live-traffic-cases.js`). `polish` reads the icon controls' targets and the page documents' buttons, prose, density select
 * and Sessions rows, and the key legend `?` opens on Settings (`lib/polish-cases.js`).
 * `site` opens the landing page, `site/index.html`, from disk and judges that it fits a 400 px window,
 * and every width from 360 to 1440 px in 5 px steps (`lib/site-cases.js`); the one group with no daemon, so `--only site` needs no build and no JDK.
 * `sheet`, opt-in, judges nothing: it writes a PNG of every page and view at four widths, dark and
 * light, served and exported, and an `index.html` grid of them to `--out` (`lib/sheet-cases.js`),
 * for a person to look at.
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
 * --probe <dir> (the probe fixture; written with gradle when absent), --only layout,run,live,topbar,room,polish,shell,traffic,first,site,sheet,
 * --first-rows-ms <n> (default 3000), --keep (keep the temp directory), --out <dir> (where `--only sheet`
 * writes; with --base, each tree in a folder of its own).
 * Exit status: 1 when any judged reading on head fails, 2 when the suite could not run, 130 or 143
 * when SIGINT or SIGTERM stopped it, after it stopped Chrome and its daemons and removed its temp
 * directory (kept with --keep), with the exported sessions the cases wrote inside it.
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
const { shell, findingsFits, statsFirstDraw, statsRouteBreaks } = require('./lib/shell-cases');
const { room } = require('./lib/list-room-cases');
const { liveTraffic } = require('./lib/live-traffic-cases');
const { polish } = require('./lib/polish-cases');
const { sheet } = require('./lib/sheet-cases');
const { setScratchRoot } = require('./lib/measure-scratch');
const { landingFits } = require('./lib/site-cases');

const REPO = path.resolve(__dirname, '..');
const WEB = 'inspector-daemon/src/main/resources';
// Groups that read only files from the tree. A run of these alone starts no daemon.
const NO_DAEMON = ['site'];
const needsDaemon = (opts) => opts.only.some((group) => !NO_DAEMON.includes(group));

function parseArgs(argv) {
  const opts = {
    head: REPO, base: null, install: path.join(REPO, 'inspector-daemon/build/install/inspector'),
    probe: null, only: ['layout', 'run', 'live', 'topbar', 'room', 'polish', 'shell', 'traffic', 'first', 'site'], firstRowsMs: 3000, keep: false, out: null,
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
    else if (arg === '--out') opts.out = path.resolve(next());
    else throw new Error(`unknown option ${arg}`);
  }
  if (opts.only.includes('sheet') && !opts.out) throw new Error('--only sheet needs --out <dir>');
  for (const side of [opts.head, opts.base].filter(Boolean)) {
    if (!fs.existsSync(path.join(side, WEB, 'web/scripts.txt'))) throw new Error(`${side} has no ${WEB}/web/scripts.txt`);
  }
  if (needsDaemon(opts) && !fs.existsSync(path.join(opts.install, 'lib'))) {
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

// Every process the run starts, Chrome included, and what it needs to stop them and remove its
// temp directory. Module state because a signal handler, not only `main`'s `finally`, must reach it.
const children = [];
const run = { tmp: null, keep: false, chrome: null, cleaning: null, interrupted: null };

// Asked to stop, a daemon has this long to exit before it is killed: it may be writing to the temp
// directory, which is removed next.
const STOP_MS = 5000;

async function stopChild(child) {
  const gone = () => child.exitCode !== null || child.signalCode !== null;
  if (gone()) return;
  const exited = new Promise((resolve) => child.once('exit', resolve));
  child.kill();
  if (await Promise.race([exited.then(() => true), sleep(STOP_MS).then(() => false)])) return;
  child.kill('SIGKILL');
  await Promise.race([exited, sleep(1000)]);
}

// Once per run, whichever asks first: the end of `main`, or a signal.
function cleanUp() {
  if (!run.cleaning) {
    run.cleaning = (async () => {
      if (run.chrome) run.chrome.close();
      await Promise.all(children.map(stopChild));
      if (!run.tmp) return;
      if (run.keep) { console.log(`kept ${run.tmp}`); return; }
      await sleep(500); // Chrome is still writing its profile as it exits
      try { fs.rmSync(run.tmp, { recursive: true, force: true }); } catch (e) { console.error(`left ${run.tmp}: ${e.message}`); }
    })();
  }
  return run.cleaning;
}

// Node ends on SIGINT or SIGTERM without running any `finally`, so Ctrl-C used to leave every daemon
// serving a copy of the fixtures, the 160,016-call session among them, from a temp directory never
// removed (KIN-109). A second signal while cleaning waits for the first: the cleanup is bounded by
// STOP_MS. A signal during the probe fixture's gradle write is handled once gradle returns.
for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => {
    if (run.interrupted) return;
    run.interrupted = signal;
    console.error(`measure-web-ui: interrupted by ${signal}; stopping the ${children.length} processes it started. Nothing measured is a result.`);
    cleanUp().finally(() => process.exit(128 + os.constants.signals[signal]));
  });
}

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

// `name` is the case's, so a run that fails because Chrome went away says which case it was in. A
// lost page's readings are refused even if the case returned some: none may print as passed.
async function onPage(chrome, name, run) {
  const page = await chrome.openPage(name);
  try {
    const rows = await run(page);
    if (page.lost) throw page.lost;
    return rows;
  } finally { await page.close(); }
}

async function measureSide(chrome, side, opts) {
  const groups = [];
  if (opts.only.includes('layout')) {
    groups.push(['layout probe', (p) => cases.layout(p, side.urls.probe, 'probe')]);
    groups.push(['layout big', (p) => cases.layout(p, side.urls.big, 'big')]);
  }
  if (opts.only.includes('run')) groups.push(['run', (p) => cases.runToggle(p, side.urls.big, 'big')]);
  if (opts.only.includes('live')) groups.push(['live', (p) => cases.liveArrival(p, side.urls.big, 'big')]);
  if (opts.only.includes('topbar')) groups.push(['topbar', (p) => topBar(p, side.urls.big, 'long')]);
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
  run.tmp = tmp;
  // The cases' exported sessions go inside it, so cleanUp removes them on a signal too, which skips
  // the `finally` each case removes its own with (lib/measure-scratch.js).
  setScratchRoot(tmp);
  run.keep = opts.keep;
  try {
    const trees = [['head', opts.head], ...(opts.base ? [['base', opts.base]] : [])];
    let sides = trees.map(([name, tree]) => ({ name, tree, urls: {} }));
    if (needsDaemon(opts)) {
      const probe = probeFixture(opts, tmp);
      sides = [];
      for (const [name, tree] of trees) sides.push(await prepareSide(opts, name, tree, probe, tmp));
    }
    const chrome = await launchChrome(path.join(tmp, 'chrome-profile'), (proc) => children.push(proc));
    run.chrome = chrome;

    // Layout, run and live cases run in parallel tabs, both trees at once. Timing cases run alone
    // afterwards, one at a time, so a busy CPU does not count against either tree.
    const results = Object.fromEntries(sides.map((s) => [s.name, []]));
    const jobs = [];
    for (const side of sides) {
      for (const [name, group] of await measureSide(chrome, side, opts)) {
        jobs.push(onPage(chrome, `${side.name} ${name}`, group).then((rows) => results[side.name].push(...rows)));
      }
    }
    await Promise.all(jobs);
    // After the parallel batch, one window per tree: twelve windows opened at once made Chrome's
    // DevTools endpoint drop a request, failing the whole run before anything was measured.
    if (opts.only.includes('room')) {
      await Promise.all(sides.map((side) => onPage(chrome, `${side.name} room`, (p) => room(p, side.urls))
        .then((rows) => results[side.name].push(...rows))));
    }
    if (opts.only.includes('polish')) {
      await Promise.all(sides.map((side) => onPage(chrome, `${side.name} polish`, (p) => polish(p, side.urls))
        .then((rows) => results[side.name].push(...rows))));
    }
    if (opts.only.includes('shell')) {
      await Promise.all(sides.map((side) => onPage(chrome, `${side.name} shell`, (p) => shell(p, side.urls.big, 'long'))
        .then((rows) => results[side.name].push(...rows))));
      await Promise.all(sides.map((side) => onPage(chrome, `${side.name} stats routes`, (p) => statsRouteBreaks(p, side.urls.routes))
        .then((rows) => results[side.name].push(...rows))));
      await Promise.all(sides.map((side) => onPage(chrome, `${side.name} findings fits`, (p) => findingsFits(p, side.urls.probe))
        .then((rows) => results[side.name].push(...rows))));
      await Promise.all(sides.map((side) => onPage(chrome, `${side.name} findings sensitive`, (p) => cases.findingsSensitive(p, side.urls.probe))
        .then((rows) => results[side.name].push(...rows))));
    }
    // Against a daemon per tree with an empty data directory, so the streamed sessions are the only
    // ones it holds; one tree at a time, because the burst's bound is counted against a timer.
    if (opts.only.includes('traffic')) {
      for (const side of sides) {
        const data = path.join(tmp, `${side.name}-traffic-data`);
        fs.mkdirSync(data);
        const origin = await startDaemon(opts, path.join(side.tree, WEB), data, path.join(tmp, `${side.name}-traffic.log`));
        results[side.name].push(...await onPage(chrome, `${side.name} traffic`, (p) => liveTraffic(p, origin)));
      }
    }
    if (opts.only.includes('first')) {
      for (const session of ['huge', 'big']) {
        for (const side of sides) {
          results[side.name].push(...await onPage(chrome, `${side.name} first rows ${session}`, (p) => cases.firstRows(p, side.urls[session], session, opts.firstRowsMs)));
          if (session === 'huge') results[side.name].push(...await onPage(chrome, `${side.name} stats first draw ${session}`, (p) => statsFirstDraw(p, side.urls.huge, session)));
        }
      }
    }

    if (opts.only.includes('site')) {
      await Promise.all(sides.map((side) => onPage(chrome, `${side.name} site`, (p) => landingFits(p, side.tree))
        .then((rows) => results[side.name].push(...rows))));
    }

    // Readings cut short by a signal are not a result, whatever they say.
    if (run.interrupted) return 2;

    // Last and one tree at a time: about a hundred loads each, and nothing in it is a timing.
    if (opts.only.includes('sheet')) {
      for (const side of sides) {
        const dir = opts.base ? path.join(opts.out, side.name) : opts.out;
        results[side.name].push(...await onPage(chrome, `${side.name} sheet`, (p) => sheet(p, side.urls.probe, dir, side.tree)));
      }
    }
    if (run.interrupted) return 2;

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
    // Interrupted, a case fails because its Chrome was stopped under it; that is not the news.
    if (!run.interrupted) console.error(`measure-web-ui: ${e.stack || e.message}`);
    return 2;
  } finally {
    await cleanUp();
  }
}

// Interrupted, the signal's handler exits, with 128 + the signal's number, once the cleanup is done.
main().then((code) => { if (!run.interrupted) process.exit(code); });
