/* The JavaScript half of the shared mirror cases in `mirror-cases/`.
 *
 * The web UI mirrors rules whose authority is Kotlin — six in `:inspector-model`, one in the
 * daemon's `Replay.kt`. Each case file is read by `MirrorCasesTest` (or `HopByHopMirrorTest`) on the
 * Kotlin side and by this file against the functions lifted out of the real web UI by their
 * `@mirror-begin <name>` anchors, so a case is one fact asserted of both surfaces. See
 * `mirror-cases/README.md` for the format.
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
 * Each rule: the `@mirror-begin` blocks it needs, what it returns from them, the input keys a case
 * may use, and how a case's input becomes an answer. `urlOf` and `tagOf` are helpers some rules lean
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
const NON_RULE_BLOCKS = ['jsonParser', 'routePages'];

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

/**
 * The web UI's pagers, `signalPages` and `transactionPages` — one `routePages` under two answer
 * shapes — against stub routes rather than a daemon. Each stub answers like its route — a bare
 * array for signals, `{ total, matched, items }` for calls, newest first, sliced by `offset` and
 * `limit` — and then misbehaves in one way a real server can: a clamp below the limit asked for,
 * `offset` ignored, a live row arriving between two pages. Returns `[label, verdict]` pairs, one
 * per check, because each is a separate claim and a compound line hides which half failed. The
 * signal lines keep their labels; the call lines repeat them under `calls`.
 *
 * Every stub refuses a 100th request, so a pager that would loop forever fails here instead of
 * hanging the build.
 */
async function checkSignalPages(sources) {
  const pagers = lift(sources, ['routePages'], 'signalPages, transactionPages');
  const results = [];
  for (const [pager, prefix, wrap] of [
    [pagers.signalPages, 'pages', (rows) => rows],
    [pagers.transactionPages, 'calls', (rows, all) => ({ total: all.length, matched: all.length, items: rows })],
  ]) {
    results.push(...(await checkPager(pager, prefix, wrap)));
  }
  return results;
}

/** The four cases for one pager; `wrap(page, all)` shapes a page of rows as its route answers. */
async function checkPager(pager, prefix, wrap) {
  const total = 1001;
  const original = Array.from({ length: total }, (_, i) => ({ id: `s${String(i).padStart(4, '0')}`, mono: i }))
    .sort((a, b) => b.mono - a.mono);
  const want = original.map((s) => s.id).join(',');

  const stub = ({ clamp = Infinity, ignoreOffset = false, arriving = null } = {}) => {
    const rows = original.slice();
    const asked = [];
    const get = async (path) => {
      if (asked.length >= 100) throw new Error('more than 100 requests: the pager does not stop');
      const url = new URL(path, 'http://stub.invalid');
      asked.push(url);
      const offset = ignoreOffset ? 0 : Number(url.searchParams.get('offset')) || 0;
      const limit = Math.min(Number(url.searchParams.get('limit')) || 100, clamp);
      const page = wrap(rows.slice(offset, offset + limit), rows);
      // A row captured after the first page was served: everything older moves down by one.
      if (arriving && asked.length === 1) rows.unshift(arriving);
      return page;
    };
    return { get, asked };
  };
  const read = async (server, filter = '') => {
    try {
      return { ids: (await pager(server.get, 'probe session', filter)).map((s) => s.id) };
    } catch (e) {
      return { error: e.message };
    }
  };
  const verdict = (got, expected) => {
    if (got.error) return `NO - threw: ${got.error}`;
    const twice = got.ids.length - new Set(got.ids).size;
    if (twice) return `NO - ${twice} id(s) returned twice`;
    if (got.ids.join(',') !== expected) return `NO - ${got.ids.length} ids, not the ${expected.split(',').length} expected newest first`;
    return null;
  };

  const results = [];
  const plain = stub();
  const all = await read(plain, 'tag:cache');
  const filterSent = plain.asked.every((u) => u.searchParams.get('filter') === 'tag:cache');
  results.push([`${prefix} 1001 by 1000`, verdict(all, want) ||
    (filterSent ? `yes (${all.ids.length} ids in ${plain.asked.length} requests, newest first, the filter on each)` : 'NO - a request dropped the filter')]);

  const clamped = stub({ clamp: 500 });
  const underClamp = await read(clamped);
  results.push([`${prefix} under a 500 clamp`, verdict(underClamp, want) ||
    `yes (${underClamp.ids.length} ids in ${clamped.asked.length} requests)`]);

  const deaf = stub({ ignoreOffset: true });
  const ignored = await read(deaf);
  const firstPage = original.slice(0, 1000).map((s) => s.id).join(',');
  results.push([`${prefix} ignoring offset`, verdict(ignored, firstPage) ||
    `yes (stopped after ${deaf.asked.length} requests, ${ignored.ids.length} ids each once)`]);

  const live = stub({ arriving: { id: 'live', mono: total } });
  const seam = await read(live);
  results.push([`${prefix} repeat at a seam`, verdict(seam, want) ||
    `yes (${seam.ids.length} ids each once across ${live.asked.length} requests)`]);
  return results;
}

module.exports = { CASES, RULES, NON_RULE_BLOCKS, loadCaseFiles, runRule, checkJsonParser, checkSignalPages };
