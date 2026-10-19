'use strict';
/*
 * Headless Chrome over the DevTools protocol, with nothing to install: Node's own WebSocket and
 * fetch. Used by scripts/measure-web-ui.js, which measures what jsdom cannot see.
 */
const { spawn } = require('child_process');
const fs = require('fs');

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

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
  return {
    port,
    openPage: () => openPage(port),
    close: () => { proc.kill(); },
  };
}

// Each page gets a window of its own. Tabs in one window share it, and only the front tab paints:
// the others get no animation frames, so a follow that waits for a frame never runs, and a
// regression measured in a background tab reads as "stays". That hid KIN-38 in a first draft.
async function newWindowTarget(port) {
  const version = await (await fetch(`http://127.0.0.1:${port}/json/version`)).json();
  const browser = new WebSocket(version.webSocketDebuggerUrl);
  await new Promise((resolve, reject) => { browser.onopen = resolve; browser.onerror = reject; });
  const targetId = await new Promise((resolve, reject) => {
    browser.onmessage = (event) => {
      const data = JSON.parse(event.data);
      if (data.id !== 1) return;
      if (data.error) reject(new Error(data.error.message)); else resolve(data.result.targetId);
    };
    browser.send(JSON.stringify({ id: 1, method: 'Target.createTarget', params: { url: 'about:blank', newWindow: true } }));
  });
  browser.close();
  const targets = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
  const target = targets.find((t) => t.id === targetId);
  if (!target) throw new Error(`Chrome lists no target ${targetId}`);
  return target;
}

async function openPage(port) {
  const target = await newWindowTarget(port);
  const ws = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((resolve, reject) => { ws.onopen = resolve; ws.onerror = reject; });
  let nextId = 0;
  const pending = new Map();
  const exceptions = [];
  ws.onmessage = (event) => {
    const data = JSON.parse(event.data);
    if (data.method === 'Runtime.exceptionThrown') {
      const details = data.params.exceptionDetails;
      exceptions.push(String((details.exception && details.exception.description) || details.text).split('\n')[0]);
    }
    if (data.id && pending.has(data.id)) { pending.get(data.id)(data); pending.delete(data.id); }
  };
  const send = (method, params = {}) => new Promise((resolve) => {
    const id = ++nextId;
    pending.set(id, resolve);
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
    async setWidth(width) {
      await send('Emulation.setDeviceMetricsOverride', { width, height: 900, deviceScaleFactor: 1, mobile: false });
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
      try { await fetch(`http://127.0.0.1:${port}/json/close/${target.id}`); } catch (e) { /* Chrome already gone */ }
      ws.close();
    },
  };
  return page;
}

module.exports = { launchChrome, sleep };
