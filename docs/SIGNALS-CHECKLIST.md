# Signals — development checklist

Working companion to [`SIGNALS.md`](SIGNALS.md). Keep this open while building; read the spec when
you need to know *why*. Stage numbers match its **Build order**.

**The split, so this does not become a second source of truth:** the spec owns every design
decision and its rationale. This file owns **assertions only** — things that are true or false about
a build. If you find yourself explaining something here, it belongs in the spec.

---

## Every PR, no exceptions

- [ ] `ApiParityTest` green; `api/inspector-public-api.txt` regenerated
      (`./gradlew :inspector-core:jvmTest -Dinspector.api.regenerate=true`), never hand-edited
- [ ] Every new public symbol has an identical-signature `:inspector-noop` twin
- [ ] **No-op `registerProvider` discards the lambda** — no signature check catches a retained
      closure
- [ ] `-Pinspector=off` → runtime classpath contains only no-op modules
- [ ] Canary guard proven in **both** directions on the new code
- [ ] Schema `v` is still `1` — needing a bump means a field was removed or repurposed by mistake
- [ ] `v` is **present in the serialized JSON** of an archived signal, not only as a Kotlin default
- [ ] Nothing committed names a client, employer, product or domain — see `AGENTS.md`
- [ ] `Signal.mono` comes from the same clock source as `NetworkTransaction.mono`, not a parallel
      one
- [ ] Any new `Signal` field asserted on **both** the live path and the REST path

---

## Stage gates

### 1 — Signal core

- [ ] 100 identical payloads for one `(tag, name)` → 1 row
- [ ] 100 differing payloads → the **last** one per conflation window
- [ ] A burst that **stops** mid-window still emits the held value (no timer ⇒ it is held forever)
- [ ] The payload appears **once** on the wire: `SignalMsg.data` set, `Signal.data` null
- [ ] Signal ring evicts under its own budget without touching the transaction ring
- [ ] Dropped-signal counter is visible, not silent
- [ ] `signal()` does nothing on the caller's coroutine but `trySend`
- [ ] Existing `InspectorSink` implementations compile untouched

### 2 — Daemon and archive

- [ ] Signals archived beside transactions; `signals.jsonl` greppable from a terminal
- [ ] **Live viewer receives `dataRef` populated** (`LiveTest`, not only the REST test)
- [ ] Retention prunes per tag
- [ ] Retention never prunes the active session
- [ ] An unknown `tag` is archived and served normally, never dropped

### 3 — Pull

- [ ] Host-initiated pull returns a row with `trigger = request` and the matching `requestId`
- [ ] A pull whose payload is **identical to the last pushed value** still returns a row —
      `dropUnchanged` must not swallow a reply somebody is awaiting
- [ ] A pull is not delayed by `minIntervalMs`
- [ ] Unregistered name → error naming what *is* registered
- [ ] Provider throws → error surfaces; the deferred does not hang
- [ ] App killed mid-pull → clean timeout, **no row in the archive**
- [ ] `POST …/signals/request` carries the same origin-header protection as the existing mutating
      routes

### 4 — Grammar v2 and MCP

- [ ] Acceptance question answered in three calls (see below), as a unit test **and** by piping
      JSON-RPC frames into the built binary
- [ ] `status:500 tag:screen` returns empty
- [ ] `status:500 | tag:screen` returns both kinds
- [ ] `text:` matches signal `tag`+`name`, never payloads
- [ ] Malformed filter returns the parser's own message
- [ ] **Every existing filter test passes unchanged** after the row-abstraction refactor
- [ ] `get_signal` / `list_signals` descriptions state what `trigger` means — an agent must not
      report an app-start snapshot as live state
- [ ] `session_summary` still around a kilobyte with signals included

### 5 — Web UI

- [ ] `scripts/render-web-ui.js` extended to assert signal lanes and their ordering
- [ ] Unknown-tag rows render in the generic lane
- [ ] Current-state panel renders, showing each observation's `trigger` and age
- [ ] No console errors

### 6 — Ship it

- [ ] `docs/schema.md` has the `Signal` section, carrying the unredacted-payload warning verbatim
- [ ] `get_signal` and `list_signals` MCP descriptions both state that payloads are unredacted
- [ ] `docs/INTEGRATION.md` at **v16**, §13 saying explicitly that a consumer must do **nothing**
- [ ] The consumer recipe is a numbered section of `INTEGRATION.md`, not an appendix
- [ ] Version **0.3.0** — new public API is not a patch — and tagged
- [ ] **0.3.0 resolved from GitHub Packages in a throwaway consumer and compiled for JVM and iOS
      simulator, before announcing.** A published version cannot be replaced; this is the check that
      caught 0.2.0 shipping with no licence in its POM

---

## The three that will bite

1. **`dataRef` before broadcast.** Write payload → set ref → append → *then* broadcast.
   Verify with `LiveTest`. A passing REST test proves nothing here. This is defect #1 reproducing.
2. **Trailing-edge conflation.** The burst test must assert the **last** value survives.
   Leading-edge passes a naive test and is useless in practice.
3. **Separate ring budgets.** Emit one oversized cache snapshot; assert the transaction history is
   untouched.
4. **Conflation must not touch pull replies.** The failure is a timeout the host reports as an
   unresponsive app, triggered by a cache that did not change — the least suspicious state there is.
5. **Trailing-edge needs a timer.** The worker is event-driven; a burst that stops emits nothing
   until something forces the window closed.

---

## The one end-to-end proof

> **"Why did the KYC submit fail?"**
>
> `timeline(since: marker("tapped submit"))` → `get_signal(<state id>)` → `get_body(<txn id>)`

Three calls, against a real recorded session, verified both ways. Four means something upstream is
under-summarizing.

---

## Known guesses

Not defects, but do not let them harden into recommendations unexamined:

- The 150 ms conflation window.
- The 2 MB signal ring budget.

Revisit both against one real session before `INTEGRATION.md` presents them as defaults worth
keeping.
