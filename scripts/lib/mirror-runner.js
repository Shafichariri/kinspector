/* The JavaScript half of the shared mirror cases in `mirror-cases/`.
 *
 * `app.js` mirrors rules whose authority is Kotlin — six in `:inspector-model`, one in the daemon's
 * `Replay.kt`. Each case file is read by `MirrorCasesTest` (or `HopByHopMirrorTest`) on the Kotlin
 * side and by this file against the functions lifted out of the real `app.js`, so a case is one fact
 * asserted of both surfaces. See `mirror-cases/README.md` for the format.
 *
 * The projections below turn each function's answer into the shape a case expects. They hold no
 * rules — grouping, ordering and thresholds all come from the lifted code — so a projection that
 * started deciding something would be a third copy of the rule, which is the drift this exists to
 * stop.
 */
const fs = require('fs');
const path = require('path');
const { HarnessError, liftBlock } = require('./web-audits');

const CASES = path.join(__dirname, '..', '..', 'mirror-cases');
const CASE_KEYS = ['name', 'from', 'input', 'expect'];

/**
 * Each rule: the `app.js` blocks it needs, what it returns from them, the input keys a case may
 * use, and how a case's input becomes an answer. `urlOf` and `tagOf` are helpers some rules lean
 * on; they are blocks so the lift is exact, and they have no case file of their own.
 */
const RULES = {
  timeline: {
    blocks: ['tagOf', 'visits'],
    exports: 'timelineEntries',
    inputs: ['transactions', 'markers', 'signals'],
    run: ({ timelineEntries }, input, d) => tokens(timelineEntries(txns(input, d), markers(input, d), signals(input, d))),
  },
  visits: {
    blocks: ['tagOf', 'visits'],
    exports: 'visits, visitIndexAt',
    inputs: ['screens', 'transactions', 'markers', 'signals', 'sessionStart', 'indexAt'],
    run: ({ visits, visitIndexAt }, input, d) => {
      const list = visits(
        signals(input, d, 'screens'), txns(input, d), markers(input, d), signals(input, d), input.sessionStart ?? null,
      );
      const out = { visits: list.map((v) => ({ screen: v.screen ? v.screen.name : null, entries: tokens(v.entries) })) };
      if (input.indexAt) out.indexAt = input.indexAt.map((mono) => visitIndexAt(list, mono));
      return out;
    },
  },
  pathScope: {
    blocks: ['pathScope'],
    exports: 'pathScope',
    inputs: ['transactions'],
    run: ({ pathScope }, input, d) => {
      const scope = pathScope(txns(input, d));
      return scope && { host: scope.host, prefix: scope.prefix, covered: scope.covered, label: scope.label };
    },
  },
  endpointShortcuts: {
    blocks: ['endpointShortcuts'],
    exports: 'endpointShortcuts',
    inputs: ['transactions', 'limit'],
    run: ({ endpointShortcuts }, input, d) => endpointShortcuts(txns(input, d), input.limit).map((s) => [s.segment, s.count]),
  },
  timelineRuns: {
    blocks: ['timelineRuns'],
    exports: 'timelineRuns, runIdOf',
    inputs: ['entries'],
    run: ({ timelineRuns, runIdOf }, input, d) => {
      const entries = input.entries.map((wrapped) => {
        const [[kind, row]] = Object.entries(wrapped);
        if (kind === 'T') { const txn = { ...d.txn, ...row }; return { kind: 'txn', mono: txn.mono, txn }; }
        if (kind === 'M') { const marker = { ...d.marker, ...row }; return { kind: 'marker', mono: marker.mono, marker }; }
        if (kind === 'S') { const signal = { ...d.signal, ...row }; return { kind: 'signal', mono: signal.mono, signal }; }
        throw new HarnessError(`unknown entry kind '${kind}'; expected T, M or S`);
      });
      return timelineRuns(entries).map((run) => {
        const out = { key: run.key, entries: tokens(run.entries) };
        if (run.key !== null) out.id = runIdOf(run);
        return out;
      });
    },
  },
  duplicates: {
    blocks: ['urlOf', 'duplicates'],
    exports: 'computeDuplicates',
    inputs: ['transactions', 'windowMs'],
    run: ({ computeDuplicates }, input, d) => {
      // Required in every case rather than defaulted here: a default in the harness would be a
      // second copy of DEFAULT_DUPLICATE_WINDOW_MS that nothing keeps equal to the first.
      if (typeof input.windowMs !== 'number') throw new Error('duplicates cases must give windowMs');
      const rows = txns(input, d);
      const marks = computeDuplicates(rows, input.windowMs);
      // The page keeps a map from row id to its group; regroup it by ordinal, members in the order
      // the map received them, and order the groups by their first member as the Kotlin list is.
      const groups = new Map();
      for (const [id, mark] of marks) {
        if (!groups.has(mark.ordinal)) groups.set(mark.ordinal, { ids: [], spanMs: mark.spanMs, callCount: mark.count });
        groups.get(mark.ordinal).ids.push(id);
      }
      const monoOf = (id) => rows.find((t) => t.id === id).mono;
      return [...groups.values()].sort((a, b) => monoOf(a.ids[0]) - monoOf(b.ids[0]));
    },
  },
  hopByHop: {
    blocks: ['hopByHop'],
    exports: 'HOP_BY_HOP, replayableHeaderLines',
    inputs: ['headers'],
    run: ({ HOP_BY_HOP, replayableHeaderLines }, input) => {
      if (!input.headers) return { names: [...HOP_BY_HOP].sort() };
      return { lines: replayableHeaderLines({ reqHeaders: input.headers }).split('\n').filter((l) => l.length) };
    },
  },
};

/** Blocks that are checked by something other than a case file. */
const NON_RULE_BLOCKS = ['jsonParser'];

const txns = (input, d) => (input.transactions || []).map((row) => ({ ...d.txn, ...row }));
const markers = (input, d) => (input.markers || []).map((row) => ({ ...d.marker, ...row }));
const signals = (input, d, key = 'signals') => (input[key] || []).map((row) => ({ ...d.signal, ...row }));
const tokens = (entries) => entries.map((e) =>
  e.kind === 'txn' ? `T:${e.txn.id}` : e.kind === 'marker' ? `M:${e.marker.label}` : `S:${e.signal.tag}/${e.signal.name}`);

/** JSON with keys sorted, so two answers compare by content rather than by key order. */
function canonical(value) {
  if (Array.isArray(value)) return `[${value.map(canonical).join(',')}]`;
  if (value && typeof value === 'object') {
    return `{${Object.keys(value).sort().map((k) => `${JSON.stringify(k)}:${canonical(value[k])}`).join(',')}}`;
  }
  return JSON.stringify(value === undefined ? null : value);
}

function readJson(file) {
  try {
    return JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch (e) {
    throw new HarnessError(`${path.basename(file)}: ${e.message}`);
  }
}

/** Every case file, validated for shape. A file naming an unknown rule is a failure, not a skip. */
function loadCaseFiles(dir = CASES) {
  if (!fs.existsSync(dir)) throw new HarnessError(`no mirror-cases directory at ${dir}`);
  const defaults = readJson(path.join(dir, '_defaults.json'));
  const files = fs.readdirSync(dir).filter((f) => f.endsWith('.json') && !f.startsWith('_')).sort();
  const problems = [];
  const byRule = {};
  for (const file of files) {
    const root = readJson(path.join(dir, file));
    const keys = Object.keys(root).sort().join(',');
    if (keys !== 'cases,rule') problems.push(`${file}: keys [${keys}], expected [rule, cases]`);
    else if (!RULES[root.rule]) problems.push(`${file}: unknown rule '${root.rule}'`);
    else if (!Array.isArray(root.cases) || root.cases.length === 0) problems.push(`${file}: no cases`);
    else if (file !== `${root.rule}.json`) problems.push(`${file}: holds rule '${root.rule}'; name the file after it`);
    else byRule[root.rule] = root.cases;
  }
  return { defaults, byRule, problems };
}

/** Lifts [blocks] out of [sources] and returns the named [exports] from them. */
function lift(sources, blocks, exports) {
  const text = blocks.map((name) => liftBlock(sources, name)).join('\n');
  try {
    return new Function(`${text}\nreturn { ${exports} };`)();
  } catch (e) {
    throw new HarnessError(`lifting [${blocks.join(', ')}] failed: ${e.message}`);
  }
}

/** Runs one rule's cases: `{ total, failures: [string] }`. */
function runRule(sources, rule, cases, defaults) {
  const spec = RULES[rule];
  const fns = lift(sources, spec.blocks, spec.exports);
  const failures = [];
  for (const c of cases) {
    const name = c.name || '(unnamed)';
    const extra = Object.keys(c).filter((k) => !CASE_KEYS.includes(k));
    if (extra.length) { failures.push(`${name}: unknown case keys ${extra.join(', ')}`); continue; }
    if (!c.input || !('expect' in c)) { failures.push(`${name}: needs input and expect`); continue; }
    const badInput = Object.keys(c.input).filter((k) => !spec.inputs.includes(k));
    if (badInput.length) { failures.push(`${name}: unknown input keys ${badInput.join(', ')}`); continue; }
    let actual;
    try {
      actual = spec.run(fns, c.input, defaults);
    } catch (e) {
      if (e instanceof HarnessError) throw e;
      failures.push(`${name}: threw ${e.message}`);
      continue;
    }
    if (canonical(actual) !== canonical(c.expect)) {
      failures.push(`${name}\n      expected ${canonical(c.expect)}\n      actual   ${canonical(actual)}`);
    }
  }
  return { total: cases.length, failures };
}

/**
 * The parser under the JSON tree, which exists because `JSON.parse` rewrites what the app received:
 * doubles for every number, integer-like keys hoisted, duplicate keys dropped. Moved here from
 * `render-web-ui.js` unchanged; it lifts the real code by its anchors.
 */
function checkJsonParser(sources) {
  const { parseJsonTree, printJsonTree } = lift(sources, ['jsonParser'], 'parseJsonTree, printJsonTree');
  const pretty = (t) => printJsonTree(parseJsonTree(t));
  const failures = [];
  const expect = (name, got, want) => { if (got !== want) failures.push(name); };
  expect('big id', pretty('{"id":1234567890123456789}'), '{\n  "id": 1234567890123456789\n}');
  expect('key order', pretty('{"b":1,"2":2}'), '{\n  "b": 1,\n  "2": 2\n}');
  expect('duplicate keys', pretty('{"a":1,"a":2}'), '{\n  "a": 1,\n  "a": 2\n}');
  expect('escapes', pretty('["\\u00e9"]'), '[\n  "\\u00e9"\n]');
  for (const t of ['{}', '[]', '{"a":{"b":[1,{"c":"d"}],"e":[]},"f":{}}', ' [ 1 , "x" ] ']) {
    expect(`layout ${t}`, pretty(t), JSON.stringify(JSON.parse(t), null, 2));
  }
  for (const bad of ['{"a":1', '{"a":1,}', '[01]', '{a:1}', '[1] x', '', '"\\q"']) {
    let threw = false;
    try { parseJsonTree(bad); } catch { threw = true; }
    if (!threw) failures.push(`accepted ${JSON.stringify(bad)}`);
  }
  return failures.length
    ? `NO - ${failures.join(', ')}`
    : 'yes (ids, key order, duplicates, escapes, layout, rejection)';
}

module.exports = { CASES, RULES, NON_RULE_BLOCKS, loadCaseFiles, runRule, checkJsonParser };
