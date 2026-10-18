/* Live probes of the web UI under a filter, for `render-web-ui.js`.
 *
 * Kept out of that file because it sits at its line budget, and these are one subject: what the
 * page says, and what it still knows about the whole session, while a filter is set. Each probe
 * takes the page through `ctx` — `window`, `doc`, `ORIGIN`, `settle`, `sessionTransactions`,
 * `sessionSignals` and `lastSocket()` — and drives it through its real controls, as every probe
 * there does. Each returns `{ key: verdict }`; the caller prints one line per key.
 */

const pause = (ms) => new Promise((r) => setTimeout(r, ms));

/** Types into the real filter box and waits for the page to settle on the answer. */
async function typeFilter(ctx, text) {
  const input = ctx.doc.getElementById('filter');
  input.value = text;
  input.dispatchEvent(new ctx.window.Event('input', { bubbles: true }));
  await pause(400);
  await ctx.settle();
  await pause(300);
}

async function press(ctx, node) {
  node.dispatchEvent(new ctx.window.MouseEvent('click', { bubbles: true }));
  await pause(100);
}

/**
 * Under a filter the page caches its whole-session copies, and only the live socket keeps them
 * current. Whatever arrived while nothing was listening must still reach the tabs and the endpoint
 * chips once the page listens again — after the list was paused and resumed, and after the daemon
 * went away and came back.
 *
 * Rows "arrive" by rewriting the daemon's unfiltered answers from the moment the gap opens: one
 * signal under a tag the session never used, so a tab appears for it, and one more call to the
 * busiest endpoint, so its chip counts one more. The filter is `tag:screen`, which matches no call
 * and no tag but `screen`, so the daemon's filtered answers are right to leave both out and only
 * the unfiltered reads are touched. Nothing is written to the archive. The stop request is stubbed,
 * as the top bar probe does, so the daemon under test keeps running.
 */
async function probeWholeSessionRefresh(ctx) {
  const { window, doc, ORIGIN } = ctx;
  const na = (why) => ({ resumeRefresh: `n/a (${why})`, reconnectRefresh: `n/a (${why})` });
  const id = encodeURIComponent(doc.getElementById('session-picker').value);
  const calls = (await ctx.sessionTransactions(id)).items || [];
  const signals = await ctx.sessionSignals(id);
  const chip = doc.querySelector('#endpoint-chips .chip');
  if (!calls.length || !signals.length || !chip) return na('needs calls, signals and an endpoint chip');
  const segment = chip.firstChild.textContent;
  const template = calls.find((t) => t.path.split('/').pop() === segment);
  if (!template) return na(`no call ends in /${segment}`);
  const top = Math.max(...calls.map((t) => t.mono), ...signals.map((s) => s.mono));

  const realFetch = window.fetch;
  let arrived = null;
  window.fetch = async (path, init) => {
    const response = await realFetch(path, init);
    const url = new URL(String(path), ORIGIN);
    if (!arrived || url.searchParams.get('filter') || (init?.method || 'GET') !== 'GET') return response;
    if (/\/signals$/.test(url.pathname) && (url.searchParams.get('offset') || '0') === '0') {
      return new Response(JSON.stringify([arrived.signal, ...(await response.json())]));
    }
    if (/\/transactions$/.test(url.pathname) && (url.searchParams.get('offset') || '0') === '0') {
      const page = await response.json();
      return new Response(JSON.stringify({ ...page, items: [arrived.call, ...page.items], total: page.total + 1 }));
    }
    return response;
  };
  const arrive = (tag, n) => {
    arrived = {
      signal: { ...signals[0], id: `probe-${tag}`, tag, name: 'arrived', mono: top + n, dataRef: undefined },
      call: { ...template, id: `probe-${tag}-call`, mono: top + n, seq: undefined },
    };
  };
  const chipCount = () => Number(
    [...doc.querySelectorAll('#endpoint-chips .chip')].find((c) => c.firstChild.textContent === segment)
      ?.querySelector('.chip-count')?.textContent ?? NaN,
  );
  const seen = (tag, before) => {
    const tab = Boolean(doc.querySelector(`#tabs .tab[data-view="${tag}"]`));
    const count = chipCount();
    return tab && count === before + 1
      ? `yes (a tab for '${tag}', and /${segment} counts ${count}, under tag:screen)`
      : `NO - tab for '${tag}' ${tab}, /${segment} counts ${count} where ${before + 1} was due, under tag:screen`;
  };

  const result = {};
  try {
    await typeFilter(ctx, 'tag:screen');
    let before = chipCount();
    const live = doc.getElementById('live-btn');
    await press(ctx, live);
    arrive('probe-resumed', 1);
    await press(ctx, live);
    await ctx.settle();
    await pause(300);
    result.resumeRefresh = seen('probe-resumed', before);

    arrived = null;
    await typeFilter(ctx, '');
    await typeFilter(ctx, 'tag:screen');
    before = chipCount();
    const stop = doc.getElementById('server-stop');
    const stubbed = window.fetch;
    window.fetch = (path, init) => (String(path).includes('/api/server/stop')
      ? Promise.resolve(new Response('{}'))
      : stubbed(path, init));
    await press(ctx, stop);
    await press(ctx, stop);
    window.fetch = stubbed;
    arrive('probe-reconnected', 2);
    const socket = ctx.lastSocket();
    if (socket && socket.onopen) socket.onopen();
    await pause(300);
    await ctx.settle();
    await pause(300);
    result.reconnectRefresh = seen('probe-reconnected', before);
  } finally {
    window.fetch = realFetch;
    arrived = null;
    await typeFilter(ctx, '');
  }
  return result;
}

module.exports = { probeWholeSessionRefresh, typeFilter, press };
