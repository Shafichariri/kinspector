'use strict';
// The Android emulator rule, run over the profiles EmulatorDetectionTest also reads. Each profile
// goes through start() as React Native would hand it in, so what is checked is the platform the
// hello carries and the address dialled, not a helper's return value.

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { harness } = require('./fake-ws.js');

const { profiles } = JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'fixtures', 'emulator-profiles.json'), 'utf8'));

test('there are profiles to run', () => {
  assert.ok(Array.isArray(profiles) && profiles.length > 0, 'emulator-profiles.json holds no profiles');
});

for (const profile of profiles) {
  test(`${profile.name}: ${profile.expect.js ? 'an emulator' : 'a device'} in JS`, () => {
    const { js, kotlin, jsReason } = profile.expect;
    assert.equal(typeof js, 'boolean');
    if (js !== kotlin) assert.ok(jsReason, 'a profile where the runners differ says why');
    // Only what Platform.constants carries on Android; the other Build fields never reach JS.
    const constants = { Fingerprint: profile.build.fingerprint, Model: profile.build.model, Brand: profile.build.brand };
    const h = harness();
    const reactNative = { Platform: { OS: 'android', constants } };
    assert.equal(h.p.start({ appId: 'a', appVersion: '1', reactNative }), true);
    h.last.open();
    assert.equal(h.last.frames()[0].client.platform, js ? 'android-emulator' : 'android-device');
    assert.equal(h.last.url, js ? 'ws://10.0.2.2:8099/ingest' : 'ws://127.0.0.1:8099/ingest');
  });
}

test('Android with no constants at all is a device, not a refusal', () => {
  const h = harness();
  assert.equal(h.p.start({ appId: 'a', appVersion: '1', reactNative: { Platform: { OS: 'android' } } }), true);
  h.last.open();
  assert.equal(h.last.frames()[0].client.platform, 'android-device');
});
