'use strict';
// The defaults the producer embeds, held to fixtures/defaults.json, which ProducerDefaultsTest
// holds to InspectorConfig. The core embeds only the signal payload cap; the capture file's
// allowlist and body cap are checked here once that file exists.

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { producer } = require('./fake-ws.js');

const defaults = JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'fixtures', 'defaults.json'), 'utf8'));

test("the core's signal payload cap is Kotlin's", () => {
  assert.equal(typeof defaults.signals.maxPayloadBytes, 'number');
  assert.equal(producer._test.SIGNAL_PAYLOAD_CAP, defaults.signals.maxPayloadBytes);
});
