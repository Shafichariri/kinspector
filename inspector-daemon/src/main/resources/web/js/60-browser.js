/* browser: tag browser rows, groups and facets */
'use strict';

// --- tag browser --------------------------------------------------------

/**
 * A key list beside one key's detail, for any tag.
 *
 * Inspector does not define what a signal payload contains — `tag` is app-defined and so is the
 * payload — so this reads a set of conventional field names and degrades rather than failing:
 * `storage`, `scopes` (or `scope`), `expired`, `key`, `value`, `payloadBytes`. A payload using
 * none of them still gets a key with its time and raw value; an app following the convention
 * gets the facets too. Documented in INTEGRATION.md §12d.
 *
 * Two payload shapes both feed this, because both are useful and apps emit both:
 *   - a **whole-cache snapshot**, `{ items: [ … ] }`, which expands to one key per entry;
 *   - a **single entry**, one observation, typically pushed as the entry changes.
 */

/**
 * Payloads for one tag, fetched once per signal.
 *
 * Only `cache` needs them up front: a cache key lives *inside* the payload, so the list cannot
 * be drawn without it. Every other tag names its key in `signal.name`, so the list draws from
 * the rows alone and a payload is fetched only when someone opens one.
 */
async function loadPayloadsFor(tag) {
  const missing = signalsForTag(tag).filter(
    (signal) => signal.dataRef && !state.payloads.has(signal.id),
  );
  if (!missing.length || !state.sessionId) return;
  await Promise.all(missing.map(loadPayload));
}

async function loadPayload(signal) {
  if (!signal || !signal.dataRef || state.payloads.has(signal.id)) return;
  const id = encodeURIComponent(state.sessionId);
  try {
    const res = await source.fetch(`/api/sessions/${id}/signals/${encodeURIComponent(signal.id)}/data`);
    state.payloads.set(signal.id, JSON.parse(await res.text()));
  } catch {
    // A payload that will not parse is still an observation; it just has nothing to show.
    // Recording the failure stops us retrying it on every render.
    state.payloads.set(signal.id, null);
  }
}

const asScopes = (o) => {
  if (Array.isArray(o.scopes)) return o.scopes.map(String);
  if (typeof o.scope === 'string') return [o.scope];
  return [];
};

function cacheFieldsOf(o) {
  return {
    storage: typeof o.storage === 'string' ? o.storage : '',
    scopes: asScopes(o),
    // Tri-state on purpose: `false` means the app said it is live, `null` means it said
    // nothing. Collapsing them would report an unknown as healthy.
    expired: typeof o.expired === 'boolean' ? o.expired : null,
    value: o.value === undefined ? null : o.value,
    bytes: typeof o.payloadBytes === 'number' ? o.payloadBytes : null,
    kind: typeof o.kind === 'string' ? o.kind : null,
    storageKey: typeof o.key === 'string' ? o.key : null,
  };
}

const baseRow = (signal) => ({
  ts: signal.ts,
  mono: signal.mono,
  signalId: signal.id,
  trigger: signal.trigger === 'request' ? 'request' : 'app',
});

/** Observations contributed by one signal — many, when it carries a whole-cache snapshot. */
function rowsForSignal(signal, tag) {
  const payload = state.payloads.get(signal.id);
  const base = baseRow(signal);

  if (tag === 'cache' && payload && typeof payload === 'object' && Array.isArray(payload.items)) {
    return payload.items.map((item) => ({
      ...base,
      ...cacheFieldsOf(item),
      // In a whole-cache snapshot the entry's own id is the only identity it has; the signal
      // name identifies the snapshot, not the entry.
      key: (typeof item.id === 'string' && item.id) || signal.name,
      fromSnapshot: true,
    }));
  }
  if (tag === 'cache' && payload && typeof payload === 'object') {
    return [{ ...base, ...cacheFieldsOf(payload), key: signal.name, fromSnapshot: false }];
  }
  return [{
    ...base,
    key: signal.name,
    storage: '',
    scopes: [],
    expired: null,
    kind: null,
    storageKey: null,
    bytes: typeof signal.bytes === 'number' && signal.bytes > 0 ? signal.bytes : null,
    // `undefined` is "not fetched yet" and `null` is "there is nothing"; the detail panel
    // fetches on the first and says so on the second.
    value: signal.dataRef ? (state.payloads.has(signal.id) ? payload : undefined) : null,
    fromSnapshot: false,
  }];
}

/** One entry per key, carrying every observation of it in causal order. */
function browserGroups(tag) {
  const groups = new Map();
  for (const signal of signalsForTag(tag)) {
    for (const row of rowsForSignal(signal, tag)) {
      const group = groups.get(row.key) || { key: row.key, rows: [] };
      group.rows.push(row);
      groups.set(row.key, group);
    }
  }
  for (const group of groups.values()) {
    group.rows.sort((a, b) => a.mono - b.mono);
    group.latest = group.rows[group.rows.length - 1];
  }
  const ordered = [...groups.values()].sort((a, b) => a.latest.mono - b.latest.mono);
  return state.newestFirst ? ordered.reverse() : ordered;
}

/**
 * Percent-decoded for display only.
 *
 * A storage key is usually escaped by whatever scheme the app uses to make it a safe filename,
 * so it arrives full of `%2F`. Reading past that is work the eye should not have to do; the
 * undecoded original stays on the row's title.
 */
const readableKey = (key) => {
  try {
    return decodeURIComponent(String(key));
  } catch {
    return String(key);
  }
};

/**
 * The distinctive tail of a key.
 *
 * Cache keys share their head — a namespace, a method, a version prefix — so a list truncated
 * from the left is a list of identical rows. The last two segments are what tells them apart;
 * the whole key stays on the line below and in the title.
 */
function shortKey(key) {
  const readable = readableKey(key).replace(/\/+$/, '');
  const parts = readable.split('/').filter(Boolean);
  if (parts.length <= 2) return readable;
  return parts.slice(-2).join('/');
}

// --- facets ---------------------------------------------------------------

/**
 * Filter chips built from the rows themselves.
 *
 * `storage` and `scope` are the app's words, so a hardcoded set would be wrong for every app
 * but the one it was written against. A facet with nothing to choose between — one value, or
 * none — is not drawn: a chip you can only leave on is not a filter.
 */
function facetsFor(groups) {
  const storages = [...new Set(groups.map((g) => g.latest.storage).filter(Boolean))].sort();
  const scopes = [...new Set(groups.flatMap((g) => g.latest.scopes))].sort();
  const expiries = [...new Set(groups.map((g) => g.latest.expired).filter((e) => e !== null))];
  const kinds = [...new Set(groups.map((g) => g.latest.kind).filter(Boolean))]
    .map((k) => k.toLowerCase()).sort();
  const facets = [];
  if (kinds.length > 1) facets.push({ field: 'kind', label: 'last change', values: kinds });
  if (storages.length > 1) facets.push({ field: 'storage', label: 'storage', values: storages });
  if (scopes.length > 1) facets.push({ field: 'scope', label: 'scope', values: scopes });
  if (expiries.length) {
    facets.push({
      field: 'expired',
      label: 'state',
      values: ['live', 'expired'].filter(
        (v) => expiries.includes(v === 'expired'),
      ),
    });
  }
  return facets.filter((facet) => facet.values.length > 1 || facet.field !== 'expired');
}

function renderFacets(facets) {
  const box = $('browser-facets');
  box.innerHTML = '';
  for (const facet of facets) {
    const group = el('span', 'facet');
    group.appendChild(el('span', 'facet-label muted', facet.label));
    for (const value of facet.values) {
      const chip = el('button', 'chip chip-facet', value);
      chip.classList.toggle('on', state.browserFacets[facet.field] === value);
      chip.addEventListener('click', () => {
        // Clicking the chip that is already on clears the facet, so every filter can be
        // undone with the control that set it.
        state.browserFacets[facet.field] =
          state.browserFacets[facet.field] === value ? '' : value;
        renderBrowser();
      });
      group.appendChild(chip);
    }
    box.appendChild(group);
  }
}

function groupMatches(group) {
  const text = state.browserFilter.trim().toLowerCase();
  if (text) {
    // Both forms, because both are things a person types here: the readable key is what the
    // list shows, and the raw one is what you paste out of a log or a filename.
    const haystacks = [readableKey(group.key), group.key, group.latest.storageKey || '']
      .map((k) => String(k).toLowerCase());
    if (!haystacks.some((k) => k.includes(text))) return false;
  }
  const facets = state.browserFacets;
  if (facets.kind && String(group.latest.kind).toLowerCase() !== facets.kind) return false;
  if (facets.storage && group.latest.storage !== facets.storage) return false;
  if (facets.scope && !group.latest.scopes.includes(facets.scope)) return false;
  if (facets.expired === 'expired' && group.latest.expired !== true) return false;
  if (facets.expired === 'live' && group.latest.expired !== false) return false;
  return true;
}

// --- key list -------------------------------------------------------------

/** `cleared` and `removed` mean the value is gone; the dot says so before you read the row. */
const isGone = (row) => row.kind === 'Cleared' || row.kind === 'Removed';

function keyItem(group, tag) {
  const latest = group.latest;
  const item = el('li', 'bkey');
  item.dataset.key = group.key;
  item.classList.toggle('sel', group.key === state.browserKey);

  // Only where the app said something. A tag that reports no freshness at all — `state`, say —
  // would otherwise get a column of identical hollow dots, which reads as a status and is not
  // one. The column collapses instead.
  const dot = el('span', 'bkey-dot');
  if (isGone(latest)) {
    dot.classList.add('gone');
    dot.title = `${latest.kind.toLowerCase()} — the value is gone`;
  } else if (latest.expired === true) {
    dot.classList.add('expired');
    dot.title = 'expired';
  } else if (latest.expired === false) {
    dot.classList.add('live');
    dot.title = 'live';
  } else if (latest.kind) {
    dot.title = 'the app did not say whether this is still live';
  } else {
    dot.classList.add('bkey-dot-none');
  }
  item.appendChild(dot);

  const text = el('span', 'bkey-text');
  const name = el('span', 'bkey-name', shortKey(group.key));
  name.title = latest.storageKey || group.key;
  text.appendChild(name);

  const meta = [];
  if (latest.storage) meta.push(latest.storage);
  if (latest.scopes.length) meta.push(latest.scopes.join(', '));
  if (group.rows.length > 1) meta.push(`${group.rows.length}×`);
  if (meta.length) text.appendChild(el('span', 'bkey-meta muted', meta.join(' · ')));
  item.appendChild(text);

  item.appendChild(ageNode(latest.ts, 'bkey-age muted mono'));

  item.addEventListener('click', () => {
    state.browserKey = group.key;
    state.browserObservation = null;
    renderBrowser();
  });
  return item;
}

function renderBrowser() {
  const tag = state.view;
  if (tag === 'all' || tag === 'network') return;

  const groups = browserGroups(tag);
  renderFacets(facetsFor(groups));

  const shown = groups.filter(groupMatches);
  const list = $('browser-list');
  list.innerHTML = '';
  for (const group of shown) list.appendChild(keyItem(group, tag));

  const empty = $('browser-list-empty');
  empty.hidden = shown.length > 0;
  empty.textContent = groups.length
    ? 'nothing matches these filters'
    : `no ${tag} signals in this session`;

  const observations = groups.reduce((n, g) => n + g.rows.length, 0);
  $('browser-count').textContent = groups.length
    ? `${shown.length}/${groups.length} keys · ${observations} observations`
    : '';

  // A pull is only offered where a provider has been proven to exist, so the button never
  // promises something the app cannot answer.
  const providers = providerNames(tag);
  $('browser-pull').hidden = providers.length === 0;

  const selected = shown.find((g) => g.key === state.browserKey)
    || shown.find((g) => g.key === state.browserKey)
    || null;
  renderBrowserDetail(
    selected || (state.browserKey ? groups.find((g) => g.key === state.browserKey) : null),
    groups,
    tag,
  );
}
