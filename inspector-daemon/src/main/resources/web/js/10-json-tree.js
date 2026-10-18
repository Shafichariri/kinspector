/* JSON tree: the source-preserving parser and the folding tree */
'use strict';

// @mirror-begin jsonParser
/*
 * JSON is parsed here rather than with `JSON.parse`, and that is not taste. `JSON.parse` turns
 * every number into a double, so a 64-bit id such as 1234567890123456789 comes back as
 * 1234567890123456800 — a different id, shown confidently in a debugger. It also moves every
 * integer-like key to the front of its object and keeps only the last of a duplicated key. All
 * three rewrite what the app received. This parser keeps each scalar's source text and each
 * object's entries in order, so the tree, the raw view and the copy are all the bytes that were
 * captured, only re-indented.
 *
 * Strict: anything `JSON.parse` would reject is rejected here too, and a caller falls back to the
 * text as it arrived — truncated and malformed bodies are exactly the ones worth seeing raw.
 */
const JSON_NUMBER = /-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?/y;

function parseJsonTree(text) {
  let i = 0;
  const n = text.length;
  const fail = () => {
    throw new SyntaxError(`not JSON at offset ${i}`);
  };
  const space = () => {
    while (i < n && (text[i] === ' ' || text[i] === '\n' || text[i] === '\r' || text[i] === '\t')) i++;
  };
  const string = () => {
    const start = i++;
    while (i < n) {
      const c = text[i];
      if (c === '"') {
        i++;
        const raw = text.slice(start, i);
        // Escapes are validated by the platform parser, on the string alone.
        return { raw, value: JSON.parse(raw) };
      }
      if (c === '\\') i += 2;
      else if (c < ' ') fail();
      else i++;
    }
    return fail();
  };
  const value = (depth) => {
    // Deep enough to be pathological; the raw view handles it without recursion.
    if (depth > 400) fail();
    space();
    const c = text[i];
    if (c === '{') {
      i++;
      const entries = [];
      space();
      if (text[i] === '}') { i++; return { t: 'obj', entries }; }
      for (;;) {
        space();
        if (text[i] !== '"') fail();
        const key = string().value;
        space();
        if (text[i] !== ':') fail();
        i++;
        entries.push([key, value(depth + 1)]);
        space();
        if (text[i] === ',') { i++; continue; }
        if (text[i] === '}') { i++; return { t: 'obj', entries }; }
        fail();
      }
    }
    if (c === '[') {
      i++;
      const items = [];
      space();
      if (text[i] === ']') { i++; return { t: 'arr', items }; }
      for (;;) {
        items.push(value(depth + 1));
        space();
        if (text[i] === ',') { i++; continue; }
        if (text[i] === ']') { i++; return { t: 'arr', items }; }
        fail();
      }
    }
    if (c === '"') return { t: 'str', raw: string().raw };
    for (const lit of ['true', 'false', 'null']) {
      if (text.startsWith(lit, i)) { i += lit.length; return { t: 'lit', raw: lit }; }
    }
    JSON_NUMBER.lastIndex = i;
    const m = JSON_NUMBER.exec(text);
    if (!m || !m[0].length) fail();
    i += m[0].length;
    return { t: 'num', raw: m[0] };
  };
  const root = value(0);
  space();
  if (i !== n) fail();
  return root;
}

/** The same layout `JSON.stringify(x, null, 2)` produces, from the lossless tree. */
function printJsonTree(root) {
  const out = [];
  const walk = (node, indent) => {
    if (node.t === 'obj' || node.t === 'arr') {
      const kids = node.t === 'obj' ? node.entries : node.items;
      const [open, close] = node.t === 'obj' ? ['{', '}'] : ['[', ']'];
      if (!kids.length) { out.push(open + close); return; }
      const inner = indent + '  ';
      out.push(open + '\n');
      kids.forEach((kid, idx) => {
        out.push(inner);
        if (node.t === 'obj') {
          out.push(JSON.stringify(kid[0]) + ': ');
          walk(kid[1], inner);
        } else {
          walk(kid, inner);
        }
        out.push(idx < kids.length - 1 ? ',\n' : '\n');
      });
      out.push(indent + close);
      return;
    }
    out.push(node.raw);
  };
  walk(root, '');
  return out.join('');
}
// @mirror-end jsonParser

// --- JSON tree ------------------------------------------------------------
//
// A body or payload that parses as a JSON object or array is drawn as a tree: containers fold,
// one can be focused (everything outside its branch folds), and any field can be hidden. All of
// that is *view* state. Copy, raw and every export read the body itself, never the tree — a
// hidden field that silently went missing from a copied body would be the viewer editing the
// evidence.

/** Past this many rows a tree opens folded below depth 2, so a big array is not a wall. */
const JT_OPEN_LIMIT = 2000;

// Paths are JSON Pointers ('' is the root), so an ancestor test is a prefix test on a separator
// that cannot appear inside an escaped segment. Dotted paths could not tell `a.b` from `{"a.b"}`.
const jtChild = (path, seg) => `${path}/${String(seg).replace(/~/g, '~0').replace(/\//g, '~1')}`;
const jtParent = (path) => path.slice(0, path.lastIndexOf('/'));
const jtLastSeg = (path) => path.slice(path.lastIndexOf('/') + 1).replace(/~1/g, '/').replace(/~0/g, '~');
const jtWithin = (outer, inner) => outer === '' || inner === outer || inner.startsWith(`${outer}/`);
const jtKids = (node) =>
  node.t === 'obj' ? node.entries.map(([k, v]) => [k, v]) : node.t === 'arr' ? node.items.map((v, i) => [i, v]) : [];

/** Every non-empty container, with its depth — the things that can fold. */
function jtContainers(root) {
  const out = [];
  const walk = (node, path, depth) => {
    const kids = jtKids(node);
    if (!kids.length) return;
    out.push({ path, depth });
    for (const [seg, kid] of kids) walk(kid, jtChild(path, seg), depth + 1);
  };
  walk(root, '', 0);
  return out;
}

/** The view state for one body, created on first sight and kept for the page's life. */
function jtView(key, root) {
  let view = state.jsonViews.get(key);
  if (!view) {
    const containers = jtContainers(root);
    // Fully open, a tree draws one row per scalar plus two per container.
    let scalars = 0;
    const count = (node) => {
      const kids = jtKids(node);
      if (!kids.length) scalars++;
      for (const [, kid] of kids) count(kid);
    };
    count(root);
    const big = scalars + 2 * containers.length > JT_OPEN_LIMIT;
    view = {
      collapsed: new Set(big ? containers.filter((c) => c.depth >= 2).map((c) => c.path) : []),
      hidden: new Set(),
      focus: null,
      raw: false,
    };
    state.jsonViews.set(key, view);
  }
  return view;
}

/** The tree as the rows it currently draws. */
function jtRows(root, view) {
  const rows = [];
  const walk = (node, path, depth, key, comma) => {
    if (path !== '' && view.hidden.has(path)) {
      rows.push({ kind: 'stub', path, depth, key, comma });
      return;
    }
    const kids = jtKids(node);
    if (!kids.length) {
      rows.push({ kind: 'leaf', path, depth, key, node, comma });
      return;
    }
    if (view.collapsed.has(path)) {
      rows.push({ kind: 'summary', path, depth, key, node, comma });
      return;
    }
    rows.push({ kind: 'open', path, depth, key, node });
    kids.forEach(([seg, kid], idx) =>
      walk(kid, jtChild(path, seg), depth + 1, node.t === 'obj' ? seg : null, idx < kids.length - 1),
    );
    rows.push({ kind: 'close', path, depth, node, comma });
  };
  walk(root, '', 0, null, false);
  return rows;
}

const jtSummary = (node) => {
  const n = jtKids(node).length;
  return node.t === 'obj'
    ? `{ ${n} ${n === 1 ? 'field' : 'fields'} }`
    : `[ ${n} ${n === 1 ? 'item' : 'items'} ]`;
};

/** How a path is named where only a name fits: its key, or its index for an array item. */
const jtName = (path) => {
  const seg = jtLastSeg(path);
  return /^\d+$/.test(seg) ? `[${seg}]` : seg;
};

function jtButton(label, cls, onClick) {
  const button = el('button', cls, label);
  button.type = 'button';
  button.addEventListener('click', (event) => {
    event.stopPropagation();
    onClick();
  });
  return button;
}

/**
 * A tree for `text`, or null when it is not a JSON object or array — the caller then shows the
 * text as it did before. `key` names the body in `state.jsonViews`, so folding survives the
 * detail pane being rebuilt and coming back to the same row. `label` names the body to a screen
 * reader and in the copy tooltip; `caption` is what the toolbar shows, and is shorter wherever a
 * heading above the tree already says what it is.
 */
function jsonTree(text, key, label, caption = label) {
  let root;
  try {
    root = parseJsonTree(text);
  } catch {
    return null;
  }
  // A scalar has nothing to fold, and a one-row tree is a worse `pre`.
  if (root.t !== 'obj' && root.t !== 'arr') return null;

  const view = jtView(key, root);
  const pretty = printJsonTree(root);
  const box = el('div', 'jt-view');
  box.dataset.jtKey = key;

  const render = (focusPath) => {
    box.replaceChildren(tools(), view.raw ? el('pre', 'body', pretty) : tree());
    if (focusPath !== undefined) {
      const target = [...box.querySelectorAll('.jt-row')].find((r) => r.dataset.path === focusPath);
      if (target) target.focus();
    }
  };
  // Re-render keeping the keyboard where it was, when it was in the tree at all.
  const update = () => {
    const active = document.activeElement;
    render(box.contains(active) && active.classList.contains('jt-row') ? active.dataset.path : undefined);
  };

  const containers = jtContainers(root);
  const act = {
    toggle(path) {
      if (view.collapsed.has(path)) view.collapsed.delete(path);
      else view.collapsed.add(path);
    },
    collapseAll() {
      view.collapsed = new Set(containers.filter((c) => c.path !== '').map((c) => c.path));
      view.focus = null;
    },
    expandAll() {
      view.collapsed = new Set();
      view.focus = null;
    },
    focus(path) {
      view.collapsed = new Set(
        containers.map((c) => c.path).filter((p) => !jtWithin(p, path) && !jtWithin(path, p)),
      );
      view.focus = path;
    },
    unfocus() {
      view.focus = null;
      view.collapsed = new Set();
    },
    hide(path) {
      if (path !== '') view.hidden.add(path);
    },
  };

  function tools() {
    const bar = el('div', 'jt-tools');
    bar.appendChild(el('span', 'jt-label', caption));
    bar.appendChild(el('span', 'spacer'));
    if (!view.raw) {
      bar.appendChild(jtButton('collapse all', 'btn btn-sm btn-quiet', () => { act.collapseAll(); update(); }));
      bar.appendChild(jtButton('expand all', 'btn btn-sm btn-quiet', () => { act.expandAll(); update(); }));
    }
    const raw = jtButton('raw', `btn btn-sm btn-quiet jt-raw${view.raw ? ' on' : ''}`, () => {
      view.raw = !view.raw;
      render();
    });
    raw.setAttribute('aria-pressed', String(view.raw));
    bar.appendChild(raw);
    // The whole body, always. Hidden fields are a view, not an edit.
    const copy = jtButton('⧉ copy', 'btn btn-sm btn-quiet jt-copy', () => {
      navigator.clipboard.writeText(pretty).then(
        () => {
          copy.textContent = '✓ copied';
          setTimeout(() => (copy.textContent = '⧉ copy'), 1200);
        },
        () => {
          copy.textContent = 'clipboard unavailable';
          copy.title = 'Open the UI on 127.0.0.1 rather than a LAN address.';
        },
      );
    });
    copy.title = `Copy the whole ${label.toLowerCase()}, including hidden fields`;
    bar.appendChild(copy);
    return bar;
  }

  function tree() {
    const wrap = el('div', 'jt');
    wrap.setAttribute('role', 'tree');
    wrap.setAttribute('aria-label', label);
    for (const row of jtRows(root, view)) wrap.appendChild(rowNode(row));
    if (view.hidden.size) wrap.appendChild(footer());
    return wrap;
  }

  function rowNode(row) {
    const node = el('div', 'jt-row');
    node.dataset.path = row.path;
    node.dataset.kind = row.kind;
    node.tabIndex = 0;
    node.setAttribute('role', 'treeitem');
    node.setAttribute('aria-level', String(row.depth + 1));
    node.style.paddingLeft = `${4 + row.depth * 14}px`;
    if (view.focus !== null && row.path === view.focus && row.kind !== 'close') node.classList.add('focus');

    const foldable = row.kind === 'open' || row.kind === 'summary';
    if (foldable) node.setAttribute('aria-expanded', String(row.kind === 'open'));
    const twisty = foldable
      ? jtButton(row.kind === 'open' ? '▾' : '▸', 'jt-tw', () => { act.toggle(row.path); update(); })
      : el('span', 'jt-tw leaf', '▾');
    twisty.tabIndex = -1;
    if (foldable) twisty.setAttribute('aria-label', row.kind === 'open' ? 'collapse' : 'expand');
    node.appendChild(twisty);

    if (row.kind !== 'close' && row.key !== null && row.key !== undefined) {
      node.appendChild(el('span', 'jt-key', JSON.stringify(row.key)));
      node.appendChild(el('span', 'jt-p', ': '));
    }
    const open = row.node && row.node.t === 'obj' ? '{' : '[';
    const close = row.node && row.node.t === 'obj' ? '}' : ']';
    switch (row.kind) {
      case 'open':
        node.appendChild(el('span', 'jt-p', open));
        break;
      case 'close':
        node.appendChild(el('span', 'jt-p', close));
        break;
      case 'summary': {
        const sum = el('span', 'jt-sum', jtSummary(row.node));
        sum.addEventListener('click', () => { act.toggle(row.path); update(); });
        node.appendChild(sum);
        break;
      }
      case 'stub': {
        // The key is already on the row for an object field; an array item has none to show.
        const stub = el('span', 'jt-hidden-stub', row.key !== null && row.key !== undefined
          ? '… hidden'
          : `… ${jtName(row.path)} hidden`);
        stub.title = 'Hidden from this view only — copy still includes it. Click to show.';
        stub.addEventListener('click', () => { view.hidden.delete(row.path); update(); });
        node.appendChild(stub);
        break;
      }
      default: {
        const leaf = row.node;
        if (leaf.t === 'obj' || leaf.t === 'arr') node.appendChild(el('span', 'jt-p', open + close));
        else node.appendChild(el('span', leaf.t === 'str' ? 'jt-str' : 'jt-num', leaf.raw));
      }
    }
    if (row.comma) node.appendChild(el('span', 'jt-p', ','));

    if (row.kind !== 'close' && row.kind !== 'stub') {
      const acts = el('span', 'jt-act');
      if (foldable) {
        const focused = view.focus === row.path;
        acts.appendChild(jtButton(focused ? 'unfocus' : 'focus', '', () => {
          if (focused) act.unfocus(); else act.focus(row.path);
          update();
        }));
      }
      if (row.path !== '') acts.appendChild(jtButton('hide', '', () => { act.hide(row.path); update(); }));
      if (acts.childNodes.length) {
        for (const b of acts.children) b.tabIndex = -1;
        node.appendChild(acts);
      }
    }

    node.addEventListener('keydown', (event) => {
      let handled = true;
      switch (event.key) {
        case 'ArrowLeft':
          if (row.kind === 'open' && row.path !== '') {
            act.toggle(row.path);
            update();
          } else if (row.path !== '') {
            const parent = [...box.querySelectorAll('.jt-row')]
              .find((r) => r.dataset.path === jtParent(row.path) && r.dataset.kind !== 'close');
            if (parent) parent.focus();
          }
          break;
        case 'ArrowRight':
          if (row.kind === 'summary') { act.toggle(row.path); update(); }
          break;
        case 'ArrowDown':
        case 'ArrowUp': {
          const all = [...box.querySelectorAll('.jt-row')];
          const next = all[all.indexOf(node) + (event.key === 'ArrowDown' ? 1 : -1)];
          if (next) next.focus();
          break;
        }
        case 'Enter':
        case ' ':
          if (foldable) { act.toggle(row.path); update(); }
          else if (row.kind === 'stub') { view.hidden.delete(row.path); update(); }
          break;
        case 'h':
          if (row.path !== '' && row.kind !== 'close') {
            // Keep the keyboard in the tree: on the stub that replaced this row.
            act.hide(row.path);
            update();
          }
          break;
        case 'f':
          if (foldable) {
            if (view.focus === row.path) act.unfocus(); else act.focus(row.path);
            update();
          }
          break;
        default:
          handled = false;
      }
      // `f`, `h` and the arrows mean something to the page as well — the filter box, row
      // navigation — and inside the tree the tree's meaning wins.
      if (handled) {
        event.preventDefault();
        event.stopPropagation();
      }
    });
    return node;
  }

  function footer() {
    const foot = el('div', 'jt-foot');
    foot.appendChild(el('span', null, `${view.hidden.size} hidden`));
    const list = el('span', 'jt-hidden-list');
    for (const path of view.hidden) {
      const chip = jtButton(`${jtName(path)} ×`, '', () => { view.hidden.delete(path); update(); });
      chip.title = `show ${path.slice(1).split('/').map((s) => s.replace(/~1/g, '/').replace(/~0/g, '~')).join(' › ')}`;
      list.appendChild(chip);
    }
    foot.appendChild(list);
    foot.appendChild(el('span', 'spacer'));
    foot.appendChild(jtButton('show all', 'btn-link', () => { view.hidden.clear(); update(); }));
    return foot;
  }

  render();
  return box;
}
