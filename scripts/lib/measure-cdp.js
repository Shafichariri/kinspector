'use strict';
/*
 * Headless Chrome over the DevTools protocol, with nothing to install: Node's own WebSocket and
 * fetch. Used by scripts/measure-web-ui.js, which measures what jsdom cannot see.
 */
const { spawn } = require('child_process');
const fs = require('fs');

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

// How long one DevTools command may go unanswered while Chrome is alive. Polling is done by
// `waitFor` in short commands, so no single command waits on the page for long: the slowest in a
// whole run, first rows on 160,000 calls included, took 718 ms on 2026-10-22 (KIN-99). Without a
// deadline a wedged renderer holds the run for ever, as a dead Chrome did before its exit was
// listened for.
const SEND_DEADLINE_MS = 30000;

function findChrome() {
  if (process.env.CHROME) return process.env.CHROME;
  const candidates = [
    '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    '/Applications/Chromium.app/Contents/MacOS/Chromium',
    '/usr/bin/google-chrome',
    '/usr/bin/google-chrome-stable',
    '/usr/bin/chromium',
    '/usr/bin/chromium-browser',
  ];
  const found = candidates.find((path) => fs.existsSync(path));
  if (!found) throw new Error('no Chrome found; set CHROME to its binary');
  return found;
}

// A fresh profile per run, so a pref one run saves (order, view) cannot leak into the next.
// Background throttling is off because the suite drives several tabs at once, and a throttled
// background tab runs its timers once a second, which turns every wait into a measurement error.
async function launchChrome(profileDir) {
  const proc = spawn(findChrome(), [
    '--headless=new', '--remote-debugging-port=0', `--user-data-dir=${profileDir}`,
    '--no-first-run', '--no-default-browser-check', '--disable-gpu',
    '--disable-background-timer-throttling', '--disable-renderer-backgrounding',
    '--disable-backgrounding-occluded-windows', 'about:blank',
  ], { stdio: ['ignore', 'ignore', 'pipe'] });
  // Every open page, so Chrome exiting after start-up fails each one's pending commands. Without
  // this a killed Chrome left every `send` waiting on a reply that could never come: the run hung,
  // read as a slow case, and was killed by hand with its daemons still running.
  const pages = new Set();
  let exited = null;
  let closing = false;
  proc.on('exit', (code, signal) => {
    exited = `Chrome exited (${signal ? `signal ${signal}` : `code ${code}`})`;
    if (!closing) for (const page of pages) page.lose(exited);
  });
  const port = await new Promise((resolve, reject) => {
    let seen = '';
    const timer = setTimeout(() => reject(new Error(`Chrome did not start:\n${seen}`)), 20000);
    proc.stderr.on('data', (chunk) => {
      seen += chunk;
      const match = /DevTools listening on ws:\/\/[^:]+:(\d+)\//.exec(seen);
      if (match) { clearTimeout(timer); resolve(Number(match[1])); }
    });
    proc.on('exit', (code) => reject(new Error(`Chrome exited with ${code}:\n${seen}`)));
  });
  const chrome = {
    port,
    exited: () => exited,
    // `label` names the case in every error the page raises, so a failed run says where it was.
    openPage: async (label) => {
      if (exited) throw new Error(`${label}: ${exited} before its page opened`);
      const page = await openPage(chrome, label);
      pages.add(page);
      page.closed.then(() => pages.delete(page));
      return page;
    },
    close: () => { closing = true; proc.kill(); },
  };
  return chrome;
}

// Each page gets a window of its own. Tabs in one window share it, and only the front tab paints:
// the others get no animation frames, so a follow that waits for a frame never runs, and a
// regression measured in a background tab reads as "stays". That hid KIN-38 in a first draft.
async function newWindowTarget(port) {
  const deadline = () => AbortSignal.timeout(SEND_DEADLINE_MS);
  const version = await (await fetch(`http://127.0.0.1:${port}/json/version`, { signal: deadline() })).json();
  const browser = new WebSocket(version.webSocketDebuggerUrl);
  await new Promise((resolve, reject) => {
    browser.onopen = resolve;
    browser.onerror = () => reject(new Error('the browser\'s DevTools socket did not open'));
  });
  const targetId = await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`Target.createTarget had no answer within ${SEND_DEADLINE_MS / 1000} s`)), SEND_DEADLINE_MS);
    browser.onclose = () => { clearTimeout(timer); reject(new Error('the browser\'s DevTools socket closed before a window opened')); };
    browser.onmessage = (event) => {
      const data = JSON.parse(event.data);
      if (data.id !== 1) return;
      clearTimeout(timer);
      if (data.error) reject(new Error(data.error.message)); else resolve(data.result.targetId);
    };
    browser.send(JSON.stringify({ id: 1, method: 'Target.createTarget', params: { url: 'about:blank', newWindow: true } }));
  });
  browser.onclose = null;
  browser.close();
  const targets = await (await fetch(`http://127.0.0.1:${port}/json/list`, { signal: deadline() })).json();
  const target = targets.find((t) => t.id === targetId);
  if (!target) throw new Error(`Chrome lists no target ${targetId}`);
  return target;
}

async function openPage(chrome, label) {
  const { port } = chrome;
  let target;
  let ws;
  try {
    target = await newWindowTarget(port);
    ws = new WebSocket(target.webSocketDebuggerUrl);
    await new Promise((resolve, reject) => {
      ws.onopen = resolve;
      ws.onerror = () => reject(new Error('the page\'s DevTools socket did not open'));
    });
  } catch (e) {
    const cause = chrome.exited() ? ` (${chrome.exited()})` : '';
    throw new Error(`${label}: could not open a window${cause}: ${e.message}`);
  }
  let nextId = 0;
  const pending = new Map(); // id -> { resolve, reject, method, timer }
  const exceptions = [];
  let lost = null;
  let closing = false;
  let markClosed;
  const closed = new Promise((resolve) => { markClosed = resolve; });
  // Fails every pending command, and every later one at once. Chrome's exit and the socket's close
  // both arrive when Chrome dies; the first names the cause and the second changes nothing.
  const lose = (cause) => {
    if (lost || closing) return;
    lost = new Error(`${label}: ${cause}`);
    for (const call of pending.values()) {
      clearTimeout(call.timer);
      call.reject(new Error(`${label}: ${cause} while ${call.method} waited for its answer`));
    }
    pending.clear();
    markClosed();
  };
  // The socket closes a moment before Chrome's exit is reaped, so a close waits briefly for the
  // exit: "Chrome exited (signal SIGKILL)" says more than "its DevTools socket closed".
  ws.onclose = (event) => {
    if (closing) return;
    setTimeout(() => lose(chrome.exited() || `its DevTools socket closed (code ${event.code})`), 250);
  };
  ws.onerror = () => { /* the close that follows says what happened */ };
  ws.onmessage = (event) => {
    const data = JSON.parse(event.data);
    if (data.method === 'Runtime.exceptionThrown') {
      const details = data.params.exceptionDetails;
      exceptions.push(String((details.exception && details.exception.description) || details.text).split('\n')[0]);
    }
    const call = data.id && pending.get(data.id);
    if (call) { clearTimeout(call.timer); pending.delete(data.id); call.resolve(data); }
  };
  // A command sent once the page is lost fails at once: a closed socket silently drops what it is
  // sent, so it would otherwise wait out its deadline, once for every poll of a `waitFor`.
  const send = (method, params = {}) => new Promise((resolve, reject) => {
    if (lost) { reject(new Error(`${lost.message} before ${method} was sent`)); return; }
    const id = ++nextId;
    const timer = setTimeout(() => {
      pending.delete(id);
      reject(new Error(`${label}: ${method} had no answer from Chrome within ${SEND_DEADLINE_MS / 1000} s`));
    }, SEND_DEADLINE_MS);
    pending.set(id, { resolve, reject, method, timer });
    ws.send(JSON.stringify({ id, method, params }));
  });
  // Evaluates in the page and returns the value. An exception in the expression comes back as
  // `{ evalError }` rather than throwing, so one broken case reports itself and the run goes on.
  const ev = async (expression) => {
    const reply = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
    if (reply.result && reply.result.exceptionDetails) {
      const d = reply.result.exceptionDetails;
      return { evalError: String((d.exception && d.exception.description) || d.text).split('\n')[0] };
    }
    return reply.result && reply.result.result ? reply.result.result.value : undefined;
  };
  await send('Page.enable');
  await send('Runtime.enable');
  // Keeps every WebSocket the page opens, so a case can hand a message to the page's own
  // `ws.onmessage` — the real live path, not a call to a render function the page would never make.
  await send('Page.addScriptToEvaluateOnNewDocument', { source: `(() => {
    const Native = window.WebSocket; window.__sockets = [];
    window.WebSocket = function (url, protocols) { const s = new Native(url, protocols); window.__sockets.push(s); return s; };
    window.WebSocket.prototype = Native.prototype;
    Object.assign(window.WebSocket, { CONNECTING: 0, OPEN: 1, CLOSING: 2, CLOSED: 3 });
  })()` });

  const page = {
    exceptions,
    ev,
    lose,
    closed,
    // Set once Chrome or this page's socket went away. The runner refuses the case's readings when
    // it is set, so a case that caught the failure still cannot have a reading printed as passed.
    get lost() { return lost; },
    // Runs `source` in every document this page loads from now on, before any page script, as the
    // socket wrapper above does. A case that records or holds the page's requests installs its
    // wrapper here (`lib/live-traffic-cases.js`), so this stays the one way a page is opened.
    async onNewDocument(source) {
      await send('Page.addScriptToEvaluateOnNewDocument', { source });
    },
    async setWidth(width) {
      await send('Emulation.setDeviceMetricsOverride', { width, height: 900, deviceScaleFactor: 1, mobile: false });
    },
    // A key pressed as the browser receives one, so Tab moves focus as it does for a person; a
    // synthetic `KeyboardEvent` in the page moves nothing.
    async key(key, code = key, keyCode = 0) {
      for (const type of ['rawKeyDown', 'keyUp']) {
        await send('Input.dispatchKeyEvent', { type, key, code, windowsVirtualKeyCode: keyCode, nativeVirtualKeyCode: keyCode });
      }
    },
    // A drag, as a mouse makes one: press at the start, moves in steps, release at the end. A
    // synthetic `PointerEvent` in the page would skip what the browser does between them.
    async drag(fromX, y, toX, steps = 10) {
      const at = (type, x, extra = {}) => send('Input.dispatchMouseEvent', { type, x, y, button: 'left', ...extra });
      await at('mouseMoved', fromX, { button: 'none' });
      await at('mousePressed', fromX, { buttons: 1, clickCount: 1 });
      for (let i = 1; i <= steps; i++) await at('mouseMoved', fromX + ((toX - fromX) * i) / steps, { buttons: 1 });
      await at('mouseReleased', toX, { buttons: 0, clickCount: 1 });
    },
    // `null` puts the system's scheme back.
    async colorScheme(value) {
      await send('Emulation.setEmulatedMedia', { features: value ? [{ name: 'prefers-color-scheme', value }] : [] });
    },
    async navigate(url) {
      exceptions.length = 0;
      await send('Page.navigate', { url });
    },
    // Polls an expression until it is truthy; returns the elapsed milliseconds, or null on timeout.
    async waitFor(expression, timeoutMs = 20000, everyMs = 100) {
      const start = Date.now();
      while (Date.now() - start < timeoutMs) {
        const value = await ev(`(() => { try { return !!(${expression}); } catch (e) { return false; } })()`);
        if (value === true) return Date.now() - start;
        await sleep(everyMs);
      }
      return null;
    },
    async close() {
      closing = true;
      if (!lost) {
        try { await fetch(`http://127.0.0.1:${port}/json/close/${target.id}`, { signal: AbortSignal.timeout(5000) }); } catch (e) { /* Chrome already gone */ }
      }
      ws.close();
      markClosed();
    },
  };
  return page;
}

module.exports = { launchChrome, sleep };
