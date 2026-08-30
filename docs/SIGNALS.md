# Signals — app state on the same timeline as traffic

Status: **built and released in 0.3.0.** This was the handover spec for **Phase 4** of
[`implementation-plan.md`](implementation-plan.md); it is kept as the record of why the design is
what it is. Where the code and this document disagree, the code won — the decisions taken while
building are listed at the end of
[`SIGNALS-CHECKLIST.md`](SIGNALS-CHECKLIST.md).

The build order at the end is numbered in **stages**, not phases, because "Phase 1" in that
document means the capture work that already shipped. Stage numbers here are local to this
feature.

Building it? Keep [`SIGNALS-CHECKLIST.md`](SIGNALS-CHECKLIST.md) open alongside — the same
acceptance criteria as a flat list of assertions. This file owns the decisions and the rationale;
that one owns what must be true of a build.

Inspector today answers *what went over the wire*. It cannot answer *what the app was doing at the
time* — which screen was on top, what the presentation layer held, what was in the cache. Those are
the questions a person or an agent actually asks when handed a bug, and today they are reconstructed
by inference from request URLs.

This adds one generic primitive, the **Signal**, that carries app-defined observations on the same
clock as `NetworkTransaction`, through the same transport, into the same archive, readable through
the same MCP server.

---

## Scope

**In:** a `Signal` type, one new push frame, one new pull frame pair, device-side conflation, a
separate ring budget, a `signals.jsonl` stream in the archive, filter grammar v2, five MCP tools,
and a merged timeline.

**Out, deliberately:**

- **No app-domain knowledge in Inspector.** Inspector must not learn what a "screen" or a "view
  model" or a "cache" is. `tag` and `name` are free strings the app chooses. Inspector has
  *conventions* for a few tag names and *renders* them nicely; it never enumerates them in a type.
  Same reasoning as `SessionMeta.platform` being a plain string rather than an enum.
- **No adapter modules.** No `inspector-compose-nav`, no `inspector-mvi`. Consuming apps write eight
  lines of glue; the recipe lives in `INTEGRATION.md`. A library that ships adapters acquires a
  dependency on somebody's architecture, and this one has stayed free of that.
- **No overlay changes.** v1 is host-side only — daemon, web UI, CLI, MCP. The in-app pill and
  inspector screen stay traffic-only.
- **No redaction of signal payloads.** See [Redaction](#redaction-is-not-applied-in-v1). Deliberate,
  and it must be stated loudly rather than left to be discovered.

---

## The primitive

One type covers a navigation event, a view-model state observation and a cache snapshot, because all
three are *a named thing, under a category, at a moment, optionally with a payload*.

```kotlin
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Signal(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val v: Int = SCHEMA_VERSION,
    val id: String,
    val ts: String,
    val mono: Long,
    val tag: String,
    val name: String,
    val data: JsonElement? = null,
    val dataRef: String? = null,
    val dataTruncated: Boolean = false,
    val bytes: Long = 0,
    val redacted: List<String> = emptyList(),
    val trigger: SignalTrigger = SignalTrigger.App,
    val requestId: String? = null,
)

@Serializable
enum class SignalTrigger {
    @SerialName("app") App,
    @SerialName("request") Request,
}
```

| Field | Type | Notes |
|---|---|---|
| `v` | int | Always present — and that needs `@EncodeDefault(ALWAYS)`, exactly as `NetworkTransaction.v` does. Without it kotlinx omits a value equal to its default, so every archived row silently loses its version marker and version gating has nothing to gate on. Additive fields do not bump it; this whole feature is additive, so **`v` stays 1**. |
| `id` | string | 8-char lowercase hex, unique within a session. Same generator as `NetworkTransaction.id`. |
| `ts` | string | ISO-8601 UTC with millis, device wall clock. **Display only.** |
| `mono` | long | Device monotonic ms — **the same clock `NetworkTransaction.mono` uses**. This is what makes the merged timeline possible; it is not optional and must not be sourced separately. |
| `tag` | string | App-defined category. Lowercase by convention. See [Tag conventions](#tag-conventions). |
| `name` | string | App-defined identity within the tag. `(tag, name)` is the grouping key for last-wins queries. |
| `data` | JsonElement? | **In-memory only.** Populated in the device ring and the `signals` StateFlow; **null on the wire and null in the archive**. The payload travels beside the row in `SignalMsg.data` and lands on disk at `dataRef` — the same split `Txn` uses for bodies. Carrying it on the row as well would ship every payload twice. Non-JSON payloads (a `toString()` dump) are held as `JsonPrimitive(String)`. |
| `dataRef` | string? | Host-relative, e.g. `signals/7f3a.json`. Null on the wire; the daemon fills it in. **See the broadcast rule below — this is a known trap.** |
| `dataTruncated` | bool | Payload exceeded `SignalPolicy.maxPayloadBytes`. |
| `bytes` | long | **True** payload size, counted even when the payload was truncated or dropped. Mirrors `reqBytes`/`resBytes`. |
| `redacted` | list<string> | Reserved. **Always empty in v1.** |
| `trigger` | enum | `app` — the app emitted this on its own. `request` — the host asked for it. |
| `requestId` | string? | Set when `trigger == request`, correlating to the `SignalRequest` that caused it. |

### Why `trigger` is load-bearing

It is the same field, for the same reason, as `redacted` on `NetworkTransaction`.

An agent handed a cache snapshot with no provenance will report it as the current state of the
cache. If that snapshot was pushed at app start and the session is now twenty minutes old, the agent
has just given a confidently wrong answer about live state — the exact failure `redacted` exists to
prevent for credentials. `trigger` lets every consumer say "recorded at app start, not re-read
since" instead of guessing.

Consumers must render it. The MCP tool descriptions must mention it.

### Point observations and interval claims

A `screen` signal is true *at an instant*. A `cache` snapshot claims to be true *from its `mono`
until the next observation of the same `(tag, name)`*.

That difference is **not** in the schema, because it is derivable: consecutive last-wins rows for a
key define the intervals. It is a rendering and query rule, and it belongs in the web UI (draw
`cache` as spans, `screen` as a lane of points) and in the MCP `current` tool (which reports the
observation *and* how old it is).

---

## Tag conventions

`tag` is an open set. Inspector ships **conventions**, not an enum:

| Tag | Meaning | `name` is | `data` is |
|---|---|---|---|
| `screen` | The active destination changed | The destination/route name | Its arguments, when the app can serialize them |
| `state` | A presentation-layer state observation | The state holder's name | The state, structured or as text |
| `cache` | A cache observation or mutation | The cache or entry name | Contents, or a summary |
| `session` | Auth/session lifecycle | The event name | Whatever the app has |

An app may emit `tag = "featureflags"` or `tag = "bluetooth"` and everything works: the row is
archived, filterable, timeline-merged and MCP-readable. It simply renders in the generic lane rather
than a bespoke one.

The daemon and UI must treat an unknown tag as ordinary. A tag arriving that the UI has no styling
for is not an error and must never be dropped.

---

## Push and pull

Both produce the same row. The transport already supports both directions — `SignRequest` /
`SignResponse` prove it, and `LiveApps` already holds the correlated-deferred bookkeeping.

| Signal | Mode | Why |
|---|---|---|
| `screen` | Push | Only the app knows when navigation happened. |
| `state` | Push, conflated | Same, and it is a firehose without conflation. |
| `cache` mutation | Push | A tiny row on write/evict/invalidate. Lets a reader reconstruct history without asking. |
| `cache` contents | **Push at ready, pull on demand** | The push guarantees a session that is never pulled still carries something. The pull answers "what is in there *now*". `trigger` distinguishes them. |

### Pull frames

```kotlin
@Serializable
@SerialName("signalReq")
data class SignalRequest(
    val requestId: String,
    val tag: String,
    val name: String,
) : WireMsg

@Serializable
@SerialName("signalErr")
data class SignalError(
    val requestId: String,
    val error: String,
) : WireMsg
```

The device replies with an ordinary `SignalMsg` carrying `trigger = request` and the `requestId`, or
with `SignalError` when no provider is registered or the provider threw.

`name` is **required**, so one request yields exactly one reply and there is no completion ambiguity
to resolve. There is no provider advertisement in v1 — instead, `SignalError` must name what *is*
registered:

```
no provider for cache/portfolios; registered: cache/response, cache/prefs
```

That is self-documenting and costs one frame instead of a second mechanism, at the price of
discovery being an error path rather than a listing. Acceptable for v1; revisit if it annoys.

Errors are replies, never archived rows. A failed pull must not leave a `Signal` in the archive.

The reply bypasses conflation entirely — see
[Recorder](#recorder-conflation-belongs-here-not-in-app-code). A pull answered from an unchanged
cache still produces a row.

---

## Wire additions

```kotlin
@Serializable
@SerialName("signal")
data class SignalMsg(
    val signal: Signal,
    val data: String? = null,
    val dataB64: Boolean = false,
) : WireMsg
```

Deliberately shaped exactly like `Txn`: the payload rides inline as a string (UTF-8, or base64 with
`dataB64` set), the device never touches the filesystem, and the daemon writes it to disk and
rewrites `dataRef`.

All existing wire rules carry over unchanged — fire-and-forget after `Hello`, exponential backoff,
resume within `SESSION_RESUME_GRACE_MS`, signals recorded while disconnected are not replayed in v1.

---

## Public API

Five additions. The surface is deliberately small because every symbol costs a `:inspector-noop`
twin and a line in `api/inspector-public-api.txt`, and that pressure is doing useful work here.

```kotlin
object Inspector {
    fun signal(tag: String, name: String, data: JsonElement? = null)
    fun signal(tag: String, name: String, text: String)

    fun registerProvider(tag: String, name: String, provider: suspend () -> JsonElement?)
    fun unregisterProvider(tag: String, name: String)

    val signals: StateFlow<List<Signal>>
}
```

`registerProvider` deliberately does **not** follow the `ReplaySigner` pattern. `StreamSink` takes
its signer as a constructor parameter, added last so existing call sites kept compiling; providers
cannot work that way, because an app registers and unregisters them at runtime as caches and
repositories come and go. So the registry lives on the `Inspector` facade, and `:inspector-stream`
reads it rather than being handed it — a new core→stream seam, and an intentional one. Say so at
the seam, or stage 3 opens with somebody trying to reconcile the two shapes.

`signal(text:)` exists because the most common consumer payload is a `toString()` of a data class —
a KMP app has no reflection on Native, so structured serialization of arbitrary state is not free.
Text is captured as `JsonPrimitive(String)`, so there is one payload type on disk, not two.

`InspectorConfig` gains one field:

```kotlin
data class InspectorConfig(
    /* … existing five … */
    val signals: SignalPolicy = SignalPolicy(),
)

data class SignalPolicy(
    val minIntervalMs: Long = 150,
    val dropUnchanged: Boolean = true,
    val maxPayloadBytes: Int = 64 * 1024,
    val ringBufferMaxBytes: Long = 2L * 1024 * 1024,
)
```

`InspectorSink` gains one method:

```kotlin
interface InspectorSink {
    fun onTransaction(txn: NetworkTransaction, reqBody: ByteArray?, resBody: ByteArray?)
    fun onMarker(marker: Marker)
    fun onSignal(signal: Signal, data: ByteArray?)
}
```

Give it a default no-op implementation so existing sinks — including anything a consumer wrote —
compile unchanged.

---

## Recorder: conflation belongs here, not in app code

An app emitting one `state` signal per keystroke across dozens of state holders will saturate the
wire and the archive. If conflation lives in app code, every consumer reinvents it, and most will
get it wrong in the same way.

So the app spams `signal()` freely and the **Recorder** conflates, on the worker, per `(tag, name)`:

- Within `minIntervalMs` of the last emission for that key, hold the newest and emit it when the
  window closes.
- With `dropUnchanged`, drop a signal whose payload is byte-identical to the last one emitted for
  that key.

**Conflation must be trailing-edge.** Dropping everything after the first in a burst is the wrong
choice: in a rapid sequence of state changes the one you always want is the *last*, because that is
where the state settled. A leading-edge implementation will look correct in tests and be useless in
practice.

**That needs a clock the worker does not currently have.** `Recorder`'s worker is `for (event in
queue)` — purely event-driven, with no notion of time passing. Trailing-edge conflation means a
held value must be emitted when its window closes *even though no further event arrives*, so a
burst that simply stops still yields its last value. Without a timer the final value of every burst
is held forever, which loses precisely the row this rule exists to keep. Use a per-key delayed
flush launched on the recorder's own single-parallelism scope, or `select` with `onTimeout`; both
serialize on the worker, so neither reintroduces a lock. `clear()` must discard held values, and a
value still held at process death is simply lost — acceptable, and worth saying so.

**Conflation applies to `trigger = app` only.** A pull reply must reach the host unconditionally:
it is a reply to a `requestId` that somebody is awaiting. Route it around both rules, because both
break it. `dropUnchanged` would swallow a reply whose payload is byte-identical to the last push —
that is, one taken when the cache had *not* changed — and the host would then time out and report
the app as unresponsive at the moment it was behaving most predictably. `minIntervalMs` would add
up to a window's latency to a synchronous round trip. Neither failure looks like conflation from
the outside, which is why this is a rule and not an optimization.

`signal()` itself keeps the existing efficiency contract — `trySend` into the bounded channel on the
caller's coroutine, nothing more. A dead or slow daemon costs dropped signals, never backpressure.

### A separate ring budget

Signals get their **own** byte-budgeted buffer, sized by `SignalPolicy.ringBufferMaxBytes`, not a
share of `ringBufferMaxBytes`.

Sharing one budget means a single large cache snapshot evicts the entire network history — at
exactly the moment somebody needs both to sit side by side. This requires generalizing `RingBuffer`
to take an entry-size function rather than being typed to `NetworkTransaction`; that is the intended
change, not a second copy of the eviction logic.

Keep the existing `dropped` counter behaviour: signals dropped by a full queue must be visible, not
silent.

---

## Archive layout

```
~/.inspector/sessions/<session>/
  meta.json
  index.jsonl          one NetworkTransaction per line, append-only   (unchanged)
  markers.jsonl                                                        (unchanged)
  bodies/7f3a.req                                                      (unchanged)
  bodies/7f3a.res
  signals.jsonl        one Signal per line, append-only                (new)
  signals/7f3a.json    payload as captured                             (new)
```

Separate files rather than a merged stream, so every existing grep, REST route, UI query and MCP
tool keeps working untouched. Merging happens on read, by `mono`.

Retention is **per tag**. A session may reasonably keep every `screen` row for its whole life while
holding only the last few `cache` snapshots, because the size distribution across tags spans orders
of magnitude. Extend `Retention` with a per-tag cap; the existing never-prune-the-active-session
rule stands.

---

## Daemon contract

Ingest, on `SignalMsg`:

1. Write the payload to `signals/<id>.json`.
2. Set `dataRef`, clear `data`.
3. Append to `signals.jsonl`.
4. **Then** broadcast to live viewers.

> **Read this before implementing step 4.** This is defect #1 from the first real-app session,
> reproduced exactly: `SessionWriter.append` returned `Unit`, `SessionManager` broadcast the row
> *as received*, and every live viewer saw every body as missing while the bodies sat on disk the
> whole time. The signal path has the identical shape and will reproduce the identical bug. The
> live payload needs its own test — extend `LiveTest`, do not rely on the REST path proving it.
>
> The standing rule from `AGENTS.md` applies verbatim: **the live path and the REST path are two
> renderings of one row.** Any new field on `Signal` needs a check on both.

New REST routes, following the existing shapes:

```
GET  /api/sessions/{id}/signals?filter=&limit=&offset=
GET  /api/sessions/{id}/signals/{signalId}
GET  /api/sessions/{id}/signals/{signalId}/data
GET  /api/sessions/{id}/current?tag=
GET  /api/sessions/{id}/timeline?filter=&since=&until=&limit=
POST /api/sessions/{id}/signals/request        {"tag":"…","name":"…"}   live session only
```

`POST …/signals/request` generalizes what `POST /api/replay` already does with `LiveApps`: allocate
a `requestId`, park a `CompletableDeferred`, send `SignalRequest`, complete on `SignalMsg` or
`SignalError`, time out cleanly. It carries the same origin-header protection the existing mutating
routes have.

---

## Filter grammar v2

The v1 grammar is frozen and HTTP-shaped. Two terms are added:

```
tag:screen                  exact, case-insensitive
name:portfolios             substring, case-insensitive
```

and one rule that makes the whole thing work across row types:

> **A term whose field does not exist on a row type excludes that row type.**

| Expression | Matches |
|---|---|
| `status>=400` | transactions only |
| `tag:screen` | signals only |
| `since:marker("checkout")` | both |
| `text:refund` | both — transactions on host+path+query, signals on tag+name. **Never payloads**, mirroring the existing "never bodies" rule. |
| `has:error` | transactions only |

The consequence must be documented rather than discovered: because terms are ANDed,
`status:500 tag:screen` matches **nothing at all** — the `status` term excludes signals and the
`tag` term excludes transactions. That is correct and consistent, not a bug. Use `|` to span types:

```
status:500 | tag:screen
```

`FilterParser` and `Filter` are extended; there is no parallel signal-only grammar. One grammar
across both streams is what makes a unified timeline query expressible at all.

**Budget for a refactor, not two new terms.** `Filter.matches` is declared
`matches(txn: NetworkTransaction, ctx: FilterContext)` on the sealed interface, and every term
implements that signature — so the exclusion rule requires each existing term to answer for a row
type it was never written against. Introduce a small row abstraction in `:inspector-model` that
both `NetworkTransaction` and `Signal` satisfy, rather than adding a second `matches` overload
across the hierarchy. Nothing outside `:inspector-model` breaks: the overlay stays traffic-only in
v1, and `api/inspector-public-api.txt` covers the `Inspector` facade, not the filter types. The
gate is that **every existing filter test still passes unchanged** — this refactor touches the one
component with the most existing coverage, and that coverage is the safety net.

---

## MCP tools

Five new, one extended. Sized for a context window, same discipline as the existing set: summaries
before rows, rows before payloads, payloads truncated unless asked.

| Tool | Args | Returns |
|---|---|---|
| `current` | `session`, `tag?` | Latest observation per `(tag, name)`, with each one's `trigger` and age. One call answers "what screen, what's cached". |
| `list_signals` | `session`, `filter`, `limit`, `offset` | Matching rows, newest first. Payloads **not** included. |
| `get_signal` | `session`, `id`, `maxBytes` | One signal in full, payload truncated by default. |
| `timeline` | `session`, `filter`, `since`, `until`, `limit` | Transactions, signals and markers merged by `mono`, one compact line each. |
| `request_signal` | `tag`, `name`, `session?` | Pulls fresh from the live app. Explicit, actionable error when no app is connected or no provider is registered. |

`session_summary` gains: signal counts by tag, the current `screen`, and the registered providers
seen this session. It stays around a kilobyte — that is the whole point of it.

`timeline` is the tool that earns this feature. Everything else is retrieval; `timeline` is what
turns "a 500 happened" into "the user was on the KYC page, the state holder said
`isSubmitting=true`, the portfolios cache was 40 seconds stale, and *then* the 500 happened".

### Acceptance bar

The existing bar is *"the MCP server answers the acceptance question in three calls"*, proven both
as a unit test and by piping JSON-RPC frames into the built binary. Hold the new work to the same
standard, with a signals-shaped question:

> **"Why did the KYC submit fail?"**
> `timeline(since: marker("tapped submit"))` → `get_signal(<state id>)` → `get_body(<txn id>)`

Three calls, against a real recorded session, verified the same two ways.

---

## Redaction is not applied in v1

**Decided: out of scope for v1.** Stated here rather than left implicit, because the gap is not
obvious and the failure mode is an agent being confidently wrong.

- `Signal.redacted` exists in the schema and is **always empty**.
- `Redaction.On` covers headers, query parameters and JSON body keys. It does **not** touch
  `Signal.data`.
- Therefore a session recorded with `Redaction.On` still archives signal payloads **verbatim**. A
  cache snapshot or a state dump carries whatever the app put in it — customer records, form
  contents, tokens held in state.

This is consistent with the project's stance that capture is verbatim and cannot reach production,
and the archive has always been readable by any agent pointed at it. What is new is that signals
carry *domain* data rather than *wire* data, so the blast radius is larger.

Two things must say so explicitly, or an agent asked "were there any credentials in this session"
will answer from a redacted transaction set and miss an unredacted state dump sitting beside it:

1. `docs/schema.md`, in the Signal section.
2. The `get_signal` and `list_signals` MCP tool descriptions.

Extending `Redactor` to `Signal.data` with the existing `bodyKeyPattern` is the obvious v2 move and
should be cheap when wanted — the schema field is already reserved for it.

---

## Production safety checklist

The existing mechanism is unchanged; this is what the new surface had to do to stay inside it. All
of it holds as of 0.3.0 — the live copy, the one to update when the bar moves, is
[`SIGNALS-CHECKLIST.md`](SIGNALS-CHECKLIST.md). This list is kept here because the reasoning under
each item is the reason it is on the list at all.

- [x] Every new public symbol added to `api/inspector-public-api.txt`
      (`./gradlew :inspector-core:jvmTest -Dinspector.api.regenerate=true`).
- [x] Identical signatures in `:inspector-noop` — `signal`, `registerProvider`,
      `unregisterProvider`, `signals`, `SignalPolicy`, and the widened `InspectorConfig` and
      `InspectorSink`.
- [x] **`registerProvider` in the no-op must discard the lambda, not store it.** A retained closure
      in a release build holds a reference to whatever the provider closes over — a cache, a
      repository, a whole graph. That is the one way a no-op stops being free, and it will not show
      up in any signature check.
- [x] `signals` in the no-op is a permanently empty `MutableStateFlow`, matching how `_markers` is
      already handled there.
- [x] `:inspector-noop-stream` mirrors any `StreamSink` surface change.
- [x] No new canary. The new code lives in existing modules, so `scripts/check-release-clean.sh`
      already covers it — confirm this rather than assuming it.
- [x] `ApiParityTest` passing is the gate. It is not advisory.
- [x] Prove the guard in **both** directions on the new code, as was done originally: it must fail
      when signal capture is present in a release artifact, and pass when it is not.

---

## Build order

Each stage ends somewhere shippable, and stages 1–2 plus the nav recipe are already useful on their
own.

### Stage 1 — Signal core (4–5 d)

`Signal`, `SignalTrigger`, `SignalMsg` in `:inspector-model`. `Recorder` signal path with
trailing-edge conflation and `dropUnchanged`. Generalized `RingBuffer` with its own signal budget.
Public API, no-op twins, golden file, `ApiParityTest`.

**Done when:** a signal emitted on a burst of 100 identical payloads produces one row; a burst of
100 differing payloads produces the last one per window; the signal ring evicts without touching the
transaction ring; `ApiParityTest` is green; `-Pinspector=off` yields a runtime classpath with only
no-op modules, as it does today.

### Stage 2 — Daemon and archive (3–4 d)

`signals.jsonl`, `signals/`, `dataRef` rewrite, per-tag retention, the five read routes.

**Done when:** an end-to-end run archives signals beside transactions; `signals.jsonl` is greppable
from a terminal; **a live viewer receives `dataRef` populated** (`LiveTest`, not only the REST
test); retention prunes signals per tag and never prunes the active session.

### Stage 3 — Pull (2–3 d)

`SignalRequest` / `SignalError`, the device-side provider registry, `LiveApps` generalization,
`POST …/signals/request`.

**Done when:** a host-initiated pull against a live app returns a fresh row with
`trigger = request`; an unregistered name returns an error naming what *is* registered; a provider
that throws surfaces the error rather than hanging the deferred; killing the app mid-pull times out
cleanly and leaves no row in the archive.

### Stage 4 — Grammar v2 and MCP (3–4 d)

`tag:` / `name:`, the exclusion rule, `current`, `list_signals`, `get_signal`, `timeline`,
`request_signal`, extended `session_summary`.

**Done when:** the acceptance question is answered in three calls against a real recorded session,
verified both as a unit test and by piping JSON-RPC frames into the built binary;
`status:500 tag:screen` returns empty and is documented as doing so; a malformed filter returns the
parser's own message, as it does today.

### Stage 5 — Web UI (4–6 d)

Merged timeline as the default view, `screen` as a lane of points, `cache` as spans, generic lane
for unknown tags, a current-state panel, payload viewer.

**Done when:** `scripts/render-web-ui.js` is extended to drive and assert the signal lanes the same
way it already drives sort order and endpoint chips — rendered rows, lane assignment, unknown-tag
fallback, and the current-state panel, with no console errors.

### Stage 6 — Ship it to consumers (1–2 d)

The glue an app writes, from the appendix — plus the release mechanics, which the rest of this
document predates. Inspector is published now, so a feature is not delivered when it compiles:

- `docs/INTEGRATION.md` → **v16**, with a §13 entry saying what a consumer must *do*. That answer
  is "nothing": signals are opt-in and every existing call site compiles untouched. Say so
  explicitly rather than leaving a reader to work it out from a list of new symbols.
- The appendix recipe below becomes a numbered section of that document, not an appendix.
- `docs/schema.md` gains the `Signal` section, carrying the unredacted-payload warning verbatim.
- Bump to **0.3.0** — new public API is not a patch — and tag. `release.yml` does the rest.
- Verify before announcing: resolve `0.3.0` from GitHub Packages in a throwaway consumer and
  compile for JVM and iOS simulator. That check is what caught 0.2.0 shipping with no licence
  declared in its POM, and a published version cannot be replaced.

**Total: 17–24 days.** The thinnest end-to-end useful slice is stages 1, 2 and the nav recipe —
about **8–10 days** — and it already answers "which screen was the user on when this 500 happened".

---

## Appendix: what a consuming app writes

This belongs in `INTEGRATION.md`, not in the library. It is written against one real consuming
app — a Compose Multiplatform banking client — to prove the API is sufficient without Inspector
learning anything about that app.

The app is deliberately not named, here or anywhere else in this repository. See the naming rule
in `AGENTS.md`; the recipe holds regardless of whose app it came from.

**Screen.** Its navigation destinations are already `@Serializable`, so the arguments come free:

```kotlin
LaunchedEffect(backStack) {
    snapshotFlow { backStack.lastOrNull() }
        .filterNotNull()
        .collect { dest ->
            Inspector.signal(
                tag = "screen",
                name = dest::class.simpleName.orEmpty(),
                data = Json.encodeToJsonElement(dest),
            )
        }
}
```

**State.** Every state holder in that app extends one base class exposing `state: StateFlow<S>`, so
one hook covers all of them. The states are plain data classes, not `@Serializable`, and
Kotlin/Native has no reflection — hence `toString()` and hence the `signal(text:)` overload:

```kotlin
scope.launch {
    viewModel.state.collect { Inspector.signal("state", viewModelName, text = it.toString()) }
}
```

No throttling in this code. The Recorder handles it — that is why conflation lives there.

**Cache.** Push a snapshot when the cache is ready, and register a provider so the host can pull a
fresh one later:

```kotlin
Inspector.signal("cache", "response", data = cache.debugDump())

Inspector.registerProvider("cache", "response") { cache.debugDump() }
```

The app owns `debugDump()`. Inspector never learns what a cache is.

**Where it goes.** All of this lives in the app's composition root, behind whatever gate that app
uses for internal builds, and compiles unchanged against `:inspector-noop`. No production module
depends on Inspector; the app declares its own no-op-default port and installs an Inspector-backed
implementation only where capture is wanted. That keeps the consuming app's own architecture rules
intact and is the pattern worth documenting for everyone.

---

## Still open

Not blocking stage 1, but decide before stage 5:

- **Default web UI view** — merged timeline, or does traffic stay the landing view with signals as a
  second tab? (Leaning merged; the merge is the value.)
- **Per-tag retention defaults** — what `cache` keeps versus what `screen` keeps.
- **Does `timeline` displace `session_summary`** as the recommended MCP entry point, or do they sit
  side by side with `session_summary` still first?

## Not verified, and will not be until somebody runs it

Stated up front in the tradition of the rest of these docs. Nothing in this file has been built.
The conflation window default of 150 ms is a guess. The 2 MB signal ring budget is a guess. Both
should be revisited against one real session before they are written into `INTEGRATION.md` as
recommendations.
