'use strict';
// The producer emits exactly each fixture's frames. The same files are decoded by
// ProducerFramesTest in :inspector-model with unknown keys refused, so each frame is one the
// daemon's own serializer accepts and one the producer is known to send. Frames are compared
// parsed, so key order is free. The format is in fixtures/README.md.

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { harness } = require('./fake-ws.js');

const dir = path.join(__dirname, '..', 'fixtures', 'frames');
const files = fs.readdirSync(dir).filter((f) => f.endsWith('.json')).sort();

// A step's payload: `data` as given, or `dataText`, a string built from a short recipe so a
// payload past the 64 KB cap need not be written out in the scenario as well as in the frames.
function payloadOf(step) {
  if (step.dataText) return step.dataText.repeat.repeat(step.dataText.times) + (step.dataText.then || '');
  return step.data;
}

// Drives a fresh producer through the scenario and returns every frame it sent, parsed.
function run(scenario) {
  let now = 0;
  const h = harness({ now: () => now });
  assert.equal(h.p.start(scenario.start), true, 'the scenario starts');
  h.last.open();
  h.last.ack(scenario.ack || 's1');
  for (const step of scenario.steps || []) {
    now = step.now;
    if ('data' in step || step.dataText) h.p.signal(step.signal[0], step.signal[1], payloadOf(step));
    else h.p.signal(step.signal[0], step.signal[1]);
  }
  h.advance(0);
  return h.last.frames();
}

test('there are frame fixtures', () => {
  assert.ok(files.length > 0, `no fixtures in ${dir}`);
});

for (const file of files) {
  test(`${file}: the producer emits exactly its frames`, () => {
    const fixture = JSON.parse(fs.readFileSync(path.join(dir, file), 'utf8'));
    assert.deepEqual(Object.keys(fixture).sort(), ['frames', 'scenario']);
    assert.ok(fixture.frames.length > 0, `${file} holds no frames`);
    assert.deepEqual(run(fixture.scenario), fixture.frames);
  });
}
