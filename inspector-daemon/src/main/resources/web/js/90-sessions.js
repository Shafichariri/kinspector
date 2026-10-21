/* sessions: deleting, clearing and listing sessions */
'use strict';

// --- sessions -----------------------------------------------------------

/**
 * Two clicks, not a browser dialog.
 *
 * Deleting from the archive is permanent — there is no trash — so it needs a deliberate second
 * action. `confirm()` would do that, but it also blocks the event loop and cannot be driven by
 * the UI probe, so the second click lives on the button itself and expires on its own.
 */
function confirmThen(button, idle, armed, run) {
  if (button.dataset.armed === '1') {
    clearTimeout(Number(button.dataset.armTimer));
    button.dataset.armed = '0';
    button.textContent = idle;
    button.classList.remove('armed');
    run();
    return;
  }
  button.dataset.armed = '1';
  button.textContent = armed;
  button.classList.add('armed');
  button.dataset.armTimer = String(setTimeout(() => {
    button.dataset.armed = '0';
    button.textContent = idle;
    button.classList.remove('armed');
  }, 4000));
}

async function sessionApi(path, method) {
  const res = await source.fetch(path, { method, headers: { 'X-Inspector-Control': '1' } });
  const text = await res.text();
  if (!res.ok) {
    let message = text;
    try { message = JSON.parse(text).error || text; } catch { /* keep raw */ }
    throw new Error(message);
  }
  return JSON.parse(text);
}

function sessionStatus(text, isError) {
  const node = $('sessions-status');
  if (!node) return;
  node.textContent = text || '';
  node.classList.toggle('cache-status-error', !!isError);
}

async function deleteSession(id) {
  try {
    const result = await sessionApi(`/api/sessions/${encodeURIComponent(id)}`, 'DELETE');
    sessionStatus(`deleted ${id} · freed ${fmtBytes(result.freedBytes)}`, false);
    // The session on screen just stopped existing, so pick another before re-reading anything
    // that would ask the daemon about it.
    if (state.sessionId === id) {
      state.sessionId = null;
      state.selectedId = null;
      state.viewChosenForSession = false;
    }
    await afterSessionChange();
  } catch (e) {
    // The daemon's refusals name the reason — a live session, a bad id — so show them verbatim.
    sessionStatus(e.message, true);
  }
}

async function clearSessions() {
  try {
    const result = await sessionApi('/api/sessions/clear', 'POST');
    const kept = result.kept.length ? ` · kept ${result.kept.length} still recording` : '';
    sessionStatus(
      `deleted ${result.deleted.length} · freed ${fmtBytes(result.freedBytes)}${kept}`,
      false,
    );
    if (!result.kept.includes(state.sessionId)) {
      state.sessionId = null;
      state.selectedId = null;
      state.viewChosenForSession = false;
    }
    await afterSessionChange();
  } catch (e) {
    sessionStatus(e.message, true);
  }
}

/** Everything on screen was derived from a session list that has just changed. */
async function afterSessionChange() {
  state.payloads.clear();
  state.browserKey = null;
  state.browserObservation = null;
  // The session on screen may just have been deleted; its values must not stand while the list loads.
  followSession();
  await loadSessions();
  renderSessionRows();
  if (state.sessionId) {
    await loadTransactions();
  } else {
    // Through `forgetWholeSession`, so a load still in flight for the session just deleted is
    // dropped rather than drawing it back, and neither copy stays marked as a session's.
    forgetWholeSession();
    state.transactions = [];
    state.allTransactions = [];
    state.signals = [];
    state.allSignals = [];
    state.screens = [];
    state.current = [];
    state.markers = [];
    renderList();
    renderMarkers();
    renderCurrent();
    renderTabs();
    applyView();
  }
}

function renderSessionRows() {
  const box = $('session-rows');
  if (!box) return;
  box.innerHTML = '';
  if (!state.sessions.length) {
    box.appendChild(el('div', 'muted', 'no sessions'));
    return;
  }
  for (const meta of state.sessions) {
    const row = el('div', 'session-row');
    if (meta.sessionId === state.sessionId) row.classList.add('sel');

    const name = el('div', 'session-row-id mono', meta.sessionId);
    row.appendChild(name);

    const bits = [`${meta.txnCount ?? 0} calls`];
    if (meta.errorCount) bits.push(`${meta.errorCount} errors`);
    // `endedAt` is the only signal on this list of whether the app is still attached, and it
    // is exactly what decides whether the daemon will allow the delete.
    const live = !meta.endedAt;
    if (live) bits.push('recording');
    row.appendChild(el('div', 'session-row-meta muted', bits.join(' · ')));

    const remove = el('button', 'btn btn-sm btn-quiet', '✕');
    if (live) {
      remove.disabled = true;
      remove.title = 'still being written — disconnect the app first';
    } else {
      remove.title = `Delete ${meta.sessionId}. Permanent.`;
      remove.addEventListener('click', () => {
        confirmThen(remove, '✕', 'sure?', () => deleteSession(meta.sessionId));
      });
    }
    row.appendChild(remove);
    box.appendChild(row);
  }
}
