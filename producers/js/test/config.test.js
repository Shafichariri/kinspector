'use strict';
// start(): what it accepts, what it refuses and the exact line it prints, and the URL it dials.
// A refusal returns false, opens no socket and throws nothing.

const test = require('node:test');
const assert = require('node:assert/strict');
const { harness, CONFIG, producer } = require('./fake-ws.js');

function refused(config, line, options) {
  const h = harness(options);
  let result;
  assert.doesNotThrow(() => { result = h.p.start(config); });
  assert.equal(result, false);
  assert.equal(h.sockets.length, 0, 'a refusal installs nothing');
  assert.equal(h.timers.length, 0);
  assert.deepEqual(h.errors(), [line]);
  assert.deepEqual(h.infos(), []);
  assert.equal(h.p.stats().state, 'stopped');
}

for (const host of ['192.168.1.20', 'example.test', '0.0.0.0', '10.0.2.3', '']) {
  test(`host ${JSON.stringify(host)} is refused`, () => {
    refused({ ...CONFIG, host },
      `[inspector] not started: host "${host}" is not allowed. Use 127.0.0.1, localhost, ::1 or 10.0.2.2.`);
  });
}

test('iOS without platform is refused', () => {
  const reactNative = { Platform: { OS: 'ios', constants: {} } };
  refused({ appId: 'a', appVersion: '1', reactNative },
    "[inspector] not started: on iOS pass platform: 'ios-simulator' to start(). It cannot be detected without a native module.");
});

test('neither reactNative nor platform is refused', () => {
  refused({ appId: 'a', appVersion: '1' },
    "[inspector] not started: pass reactNative: require('react-native') or platform");
});

test('Android without platform is refused until emulator detection lands', () => {
  const reactNative = { Platform: { OS: 'android', constants: { Model: 'sdk_gphone64_arm64' } } };
  refused({ appId: 'a', appVersion: '1', reactNative },
    "[inspector] not started: pass reactNative: require('react-native') or platform");
});

test('a missing XMLHttpRequest refuses', () => {
  refused(CONFIG, '[inspector] not started: XMLHttpRequest is not available here', { XMLHttpRequest: undefined });
});

test('nonsense config never throws', () => {
  for (const config of [undefined, null, 42, 'x', [], { platform: 'ios-simulator' }, { ...CONFIG, port: 'eighty' }]) {
    const h = harness();
    let result;
    assert.doesNotThrow(() => { result = h.p.start(config); });
    assert.equal(result, false, JSON.stringify(config));
    assert.equal(h.sockets.length, 0);
  }
});

for (const [host, url] of [
  ['127.0.0.1', 'ws://127.0.0.1:8099/ingest'],
  ['localhost', 'ws://localhost:8099/ingest'],
  ['::1', 'ws://[::1]:8099/ingest'],
  ['[::1]', 'ws://[::1]:8099/ingest'],
  ['10.0.2.2', 'ws://10.0.2.2:8099/ingest'],
]) {
  test(`host ${host} dials ${url}`, () => {
    const h = harness();
    assert.equal(h.p.start({ ...CONFIG, host }), true);
    assert.equal(h.last.url, url);
    assert.equal(h.p.stats().url, url);
  });
}

test('the default host is 10.0.2.2 on an Android emulator and 127.0.0.1 otherwise', () => {
  for (const [platform, url] of [
    ['android-emulator', 'ws://10.0.2.2:8099/ingest'],
    ['android-device', 'ws://127.0.0.1:8099/ingest'],
    ['ios-simulator', 'ws://127.0.0.1:8099/ingest'],
  ]) {
    const h = harness();
    h.p.start({ appId: 'a', appVersion: '1', platform, port: 9001 });
    assert.equal(h.last.url, url.replace('8099', '9001'));
  }
});

test('the canary is a used reference at module top level', () => {
  assert.match(globalThis.__INSPECTOR_PRODUCER__.canary, /^INSPECTOR_JS_CANARY_[0-9a-f]{16}$/);
  assert.equal(globalThis.__INSPECTOR_PRODUCER__.canary, producer._test.CANARY);
  assert.equal(typeof globalThis.__INSPECTOR_PRODUCER__.version, 'string');
});

test('the module surface is start, signal, stats and stop, and stats() works before start', () => {
  assert.deepEqual(Object.keys(producer).filter((k) => !k.startsWith('_')), ['start', 'signal', 'stats', 'stop']);
  assert.deepEqual(producer.stats(), { state: 'stopped', url: null, sessionId: null, dropped: 0, lastError: null });
  assert.doesNotThrow(() => producer.signal('screen', 'Home'));
});
