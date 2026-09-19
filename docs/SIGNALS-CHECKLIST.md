# Signals — development checklist

Working companion to [`SIGNALS.md`](SIGNALS.md). Keep this open while building; read the spec when
you need to know *why*. Stage numbers match its **Build order**.

**The split, so this does not become a second source of truth:** the spec owns every design
decision and its rationale. This file owns **assertions only** — things that are true or false about
a build. If you find yourself explaining something here, it belongs in the spec.

---

## Every PR, no exceptions

- [x] `ApiParityTest` green; `api/inspector-public-api.txt` regenerated
      (`./gradlew :inspector-core:jvmTest -Dinspector.api.regenerate=true`), never hand-edited
- [x] Every new public symbol has an identical-signature `:inspector-noop` twin
- [x] **No-op `registerProvider` discards the lambda** — no signature check catches a retained
      closure
- [x] `-Pinspector=off` → runtime classpath contains only no-op modules
- [x] Canary guard proven in **both** directions on the new code
- [x] Schema `v` is still `1` — needing a bump means a field was removed or repurposed by mistake
- [x] `v` is **present in the serialized JSON** of an archived signal, not only as a Kotlin default
- [x] Nothing committed names a client, employer, product or domain — see `AGENTS.md`
- [x] `Signal.mono` comes from the same clock source as `NetworkTransaction.mono`, not a parallel
      one
- [x] Any new `Signal` field asserted on **both** the live path and the REST path

---

## Stage gates

### 1 — Signal core

- [x] 100 identical payloads for one `(tag, name)` → 1 row
- [x] 100 differing payloads → the **last** one per conflation window
- [x] A burst that **stops** mid-window still emits the held value (no timer ⇒ it is held forever)
- [x] The payload appears **once** on the wire: `SignalMsg.data` set, `Signal.data` null
- [x] Signal ring evicts under its own budget without touching the transaction ring
- [x] Dropped-signal counter is visible, not silent
- [x] `signal()` does nothing on the caller's coroutine but `trySend`
- [x] Existing `InspectorSink` implementations compile untouched

### 2 — Daemon and archive

- [x] Signals archived beside transactions; `signals.jsonl` greppable from a terminal
- [x] **Live viewer receives `dataRef` populated** (`LiveTest`, not only the REST test)
- [x] Retention prunes per tag
- [x] Retention never prunes the active session
- [x] An unknown `tag` is archived and served normally, never dropped

### 3 — Pull

- [x] Host-initiated pull returns a row with `trigger = request` and the matching `requestId`
- [x] A pull whose payload is **identical to the last pushed value** still returns a row —
      `dropUnchanged` must not swallow a reply somebody is awaiting
- [x] A pull is not delayed by `minIntervalMs`
- [x] Unregistered name → error naming what *is* registered
- [x] Provider throws → error surfaces; the deferred does not hang
- [x] App killed mid-pull → clean timeout, **no row in the archive**
- [x] `POST …/signals/request` carries the same origin-header protection as the existing mutating
      routes

### 4 — Grammar v2 and MCP

- [x] Acceptance question answered in three calls (see below), as a unit test **and** by piping
      JSON-RPC frames into the built binary
- [x] `status:500 tag:screen` returns empty
- [x] `status:500 | tag:screen` returns both kinds
- [x] `text:` matches signal `tag`+`name`, never payloads
- [x] Malformed filter returns the parser's own message
- [x] **Every existing filter test passes unchanged** after the row-abstraction refactor
- [x] `get_signal` / `list_signals` descriptions state what `trigger` means — an agent must not
      report an app-start snapshot as live state
- [x] `session_summary` still around a kilobyte with signals included

### 5 — Web UI

- [x] `scripts/render-web-ui.js` extended to assert signal lanes and their ordering
- [x] Unknown-tag rows render in the generic lane
- [x] Current-state panel renders, showing each observation's `trigger` and age
- [x] No console errors

### 7 — The overlay (added after 0.3.0)

The build order above stops at the host. These are the overlay's, numbered on from it rather than
restarting: `ROADMAP.md` counts the overlay work 1–4 and the collision has already been confusing
once.

- [x] Signals merge into the traffic list on one clock, on by default, one tap off *(0.8.0)*
- [x] Adjacent identical observations collapse, and expanding one loses nothing *(0.8.0)*
- [x] **Signals go through the same filter as the traffic.** They did not until stage 2: the
      overlay filtered the rows and let every observation through
- [x] A tag chip narrows the list to that tag, and the exclusion rule drops the traffic with it
- [x] Tag chips are built from the session's own tags, never from `SignalTags`
- [x] Tapping an observation opens it, and the screen draws the payload
- [x] A truncated payload is unwrapped before it is formatted, never re-quoted as one JSON string
- [x] **Provenance is drawn on every observation, not only the pulled ones**
- [x] History is every observation of `(tag, name)` — never of `name` alone — oldest-first
      regardless of the list's sort toggle, with the gap from the previous one
- [x] A **now** panel above the list: the latest observation of each `(tag, name)`, collapsed to
      one line that says how much there is **and how stale the oldest of it is**
- [x] It lists one row per key, never one per observation, and sorts stably rather than by recency
- [x] Every row carries its provenance and its age — "now" is a claim, and most rows are pushed
- [x] An unreadable timestamp is skipped from the staleness rather than counted as fresh
- [x] **Freezing holds the ages still along with the rows**, and this is proved rather than
      commented: `InspectorList` takes an injectable clock because a test cannot move the real one
- [x] A **pull** control on an observation whose key has a registered provider — and **only**
      there, because in-process the registry is readable rather than inferred
- [x] A provider that has never answered is reachable, in the now strip, with a pull of its own
- [x] A local pull records `trigger = request` with a **null** `requestId`: nothing asked over a
      wire, so there is no request to correlate to
- [x] A failed pull shows the app's own message verbatim and leaves no row in the ring
- [ ] Seen on a device. The screens have been rendered at 360dp and driven in tests; no build of
      them has run on a phone

### 6 — Ship it

- [x] `docs/schema.md` has the `Signal` section, carrying the unredacted-payload warning verbatim
- [x] `get_signal` and `list_signals` MCP descriptions both state that payloads are unredacted
- [x] `docs/INTEGRATION.md` at **v16**, changelog saying explicitly a consumer must do **nothing**
- [x] The consumer recipe is a numbered section of `INTEGRATION.md`, not an appendix
- [x] Version **0.3.0** — new public API is not a patch — and tagged
- [x] **0.3.0 resolved from GitHub Packages in a throwaway consumer and compiled for JVM and iOS
      simulator, before announcing.** A published version cannot be replaced; this is the check that
      caught 0.2.0 shipping with no licence in its POM

---

## The ones that will bite

1. **`dataRef` before broadcast.** Write payload → set ref → append → *then* broadcast.
   Verify with `LiveTest`. A passing REST test proves nothing here. This is defect #1 reproducing.
2. **Trailing-edge conflation.** The burst test must assert the **last** value survives.
   Leading-edge passes a naive test and is useless in practice.
3. **A leak test must capture a `val` in a frame that ends.** A lambda closing over a local
   `var` compiles to a `Ref.ObjectRef`; nulling the var clears the box the closure points at, so
   the referent is collected whether or not the lambda was retained — the test passes against the
   exact mistake it exists to catch. Register from a helper that returns only the `WeakReference`.
4. **`DROP_OLDEST` never fails a send.** `trySend(...).isSuccess` cannot observe a dropped
   element, so any counter guarded by it stays at zero. Report drops through the channel's
   `onUndeliveredElement` instead. This was live in `Recorder` and `StreamSink` for the whole
   transaction path before signals existed.
5. **Separate ring budgets.** Emit one oversized cache snapshot; assert the transaction history is
   untouched.
6. **Conflation must not touch pull replies.** The failure is a timeout the host reports as an
   unresponsive app, triggered by a cache that did not change — the least suspicious state there is.
7. **Trailing-edge needs a timer.** The worker is event-driven; a burst that stops emits nothing
   until something forces the window closed.

---

## The one end-to-end proof

> **"Why did the KYC submit fail?"**
>
> `timeline(since: marker("tapped submit"))` → `get_signal(<state id>)` → `get_body(<txn id>)`

Three calls, against a real recorded session, verified both ways. Four means something upstream is
under-summarizing.

---

## Decisions taken while building

Recorded here because `SIGNALS.md` left them open:

- **Default web view.** The merged timeline, but only for a session that *has* signals; traffic
  stays the landing view otherwise. A session recorded before signals existed therefore behaves
  exactly as it always did, and nobody lands on an empty timeline.
- **`timeline` vs `session_summary` as the MCP entry point.** They sit side by side, with
  `session_summary` still first: it is the cheaper orienting call, and it now reports
  `currentScreen` and signal counts, which is often enough to decide whether the timeline is
  worth reading at all.
- **Per-tag retention defaults.** `cache` and `state` 500, everything else uncapped, and
  overridable per tag from `config.json`. `cache` was 20 while a cache signal meant one whole-cache
  snapshot; the per-entry recipe in `INTEGRATION.md` v18 made 20 bind routinely — below the number
  of distinct *keys*, not just the depth of their history — so it now matches `state`. Per-tag
  caps are a runaway guard for one chatty session; `maxSessions` and `maxTotalMb` bound the disk.

## Known guesses

Not defects, but do not let them harden into recommendations unexamined:

- The 150 ms conflation window.
- The 2 MB signal ring budget.

Revisit both against one real session before `INTEGRATION.md` presents them as defaults worth
keeping.

The per-tag retention caps have left this list: `cache` 20 was measured against real sessions and
found to bind routinely, and the caps are configurable now rather than only being a constant. The
remaining judgement in them is where "chatty enough to be a runaway" sits, which only an archive
that actually fills will settle.
