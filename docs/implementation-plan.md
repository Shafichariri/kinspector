# Inspector — Implementation Plan

A Wormholy-style network inspector for Compose Multiplatform apps using Ktor, with a host-side
daemon that archives sessions, a web UI, an in-app overlay + inspector, and AI-agent access
(files, CLI, MCP). This document is the executable spec: an AI agent should be able to build
the entire system from it, in order, without further product decisions.

Working name: **inspector** (rename is a find/replace; keep package `dev.inspector.*` until told otherwise).

---

## 1. Decisions already made (do not re-litigate)

| Topic | Decision |
|---|---|
| Capture scope | Ktor `HttpClient` only. No OkHttp interceptor, no NSURLProtocol, no WebView capture in v1. |
| Engines assumed | Android = OkHttp, iOS = Darwin, Desktop = OkHttp or CIO. Capture is engine-agnostic (plugin level); engines only matter for future native capture. |
| Devices | **Simulator / emulator only for v1.** No physical-device transport, no Bonjour, no IP-entry UI. |
| Daemon | Kotlin/JVM + Ktor server. Single fat jar. |
| Host retention | Auto-prune oldest sessions. Defaults: keep **100 sessions** max, **300 MB** total max. Both configurable. |
| Device retention | In-memory ring buffer only, byte-budgeted, default **8 MB**, configurable. Device keeps no history across launches. |
| History | Host daemon is the primary sink and system of record. Sessions saved as timestamped folders. |
| Modes | Internal-only (in-app UI), external-only (stream to daemon, zero UI in app), or both — chosen by Gradle dependency, not runtime flags. |
| Production safety | No-op artifact swapped in by Gradle property + CI canary-string guard on release binaries. Non-negotiable. |
| Web UI | Plain HTML/CSS/JS single-page app served from daemon resources. No framework, no JS build step. |
| Redaction | **Off by default** — capture is verbatim, credentials included. Opt in via `InspectorConfig(redaction = Redaction.On(...))`. When enabled it applies at capture time, on-device, before any sink; never at render time. |
| Filter grammar | One small grammar, parsed in `:inspector-model`, evaluated on device (in-app UI) and on daemon (web UI / CLI / MCP via REST). |

Non-goals for v1: physical devices, WebSocket/SSE traffic capture, request rewriting/replay,
HAR export (nice-to-have, Phase 3 stretch), charts/timelines in web UI, auth on the daemon
(binds to localhost only).

---

## 2. Repository layout

New standalone repo (not inside an app repo).

```
inspector/
  settings.gradle.kts
  gradle/libs.versions.toml
  inspector-model/        KMP: android, ios (arm64+simArm64+x64), jvm
  inspector-core/         KMP: same targets
  inspector-noop/         KMP: same targets
  inspector-stream/       KMP: same targets
  inspector-ui/           KMP + Compose Multiplatform: android, ios, jvm(desktop)
  inspector-daemon/       JVM only (Ktor server, web UI resources, CLI, MCP)
  sample/
    shared/               KMP app module using core+ui+stream
    androidApp/
    iosApp/               Xcode project
    test-server/          tiny Ktor JVM server with endpoints to demo everything
  scripts/
    check-release-clean.sh
  docs/
    schema.md             generated/maintained alongside model changes
```

### Version catalog (pin these; upgrade only if something is incompatible)

- Kotlin: latest stable 2.x
- Ktor: latest stable 3.x (client and server — same version everywhere)
- kotlinx-serialization-json, kotlinx-coroutines, kotlinx-datetime
- Compose Multiplatform: latest stable
- MCP Kotlin SDK: `io.modelcontextprotocol:kotlin-sdk` latest stable
- No other third-party dependencies without a stated reason. `:inspector-model` may depend
  **only** on kotlinx-serialization + kotlinx-datetime.

---

## 3. `:inspector-model` — schema and filter grammar

The contract between device, daemon, web UI, CLI, and MCP. Build and test this first.
Every serialized object carries `"v": 1`.

### 3.1 Core types (kotlinx-serialization, all `@Serializable`)

```kotlin
@Serializable
data class NetworkTransaction(
    val v: Int = 1,
    val id: String,                  // 8-char random hex, unique per session
    val ts: String,                  // ISO-8601 UTC with millis — device wall clock at request start
    val mono: Long,                  // device monotonic ms at request start (ordering authority)
    val method: String,              // uppercase
    val scheme: String,
    val host: String,
    val path: String,                // no query
    val query: String? = null,       // raw query string, post-redaction
    val status: Int? = null,         // null => failed before response
    val error: String? = null,       // exception class + message when status == null
    val ms: Long? = null,            // total duration; null if still in flight when serialized
    val attempt: Int = 1,            // 1-based; retries/redirects are separate rows sharing callId
    val callId: String,              // groups attempts of one logical call
    val reqBytes: Long = 0,          // actual body size (pre-truncation count)
    val resBytes: Long = 0,
    val reqHeaders: Map<String, List<String>> = emptyMap(),   // post-redaction
    val resHeaders: Map<String, List<String>> = emptyMap(),
    val reqBodyRef: String? = null,  // "bodies/{id}.req" on host; null if not captured
    val resBodyRef: String? = null,
    val reqBodyTruncated: Boolean = false,
    val resBodyTruncated: Boolean = false,
    val reqContentType: String? = null,
    val resContentType: String? = null,
    val redacted: List<String> = emptyList(), // e.g. ["header:authorization","body:$.token"]
)

@Serializable
data class Marker(
    val v: Int = 1,
    val ts: String,
    val mono: Long,
    val label: String,
    val source: String, // "app" | "agent" | "user"
)

@Serializable
data class SessionMeta(
    val v: Int = 1,
    val sessionId: String,          // daemon-assigned: "<ISO-ts-safe>_<app>_<device>_<build>"
    val appId: String,
    val appVersion: String,
    val platform: String,           // "ios-simulator" | "android-emulator" | "desktop"
    val device: String,             // e.g. "iPhone 16 Pro", "Pixel 8 API 35"
    val osVersion: String,
    val buildType: String,          // "debug" etc.
    val startedAt: String,
    val endedAt: String? = null,
    val txnCount: Int = 0,
    val errorCount: Int = 0,        // status>=400 or transport error
)
```

Body files are stored raw (exact captured bytes) — not JSON-wrapped. Metadata about them
lives on the transaction row.

### 3.2 Wire protocol (device → daemon, WebSocket, JSON text frames)

One `@Serializable sealed interface WireMsg` with a `type` discriminator:

- `hello`  — client info (everything in SessionMeta except sessionId/counts) + optional
  `resumeSessionId`. Daemon replies `helloAck { sessionId }`.
- `txn`    — a `NetworkTransaction` plus inline optional body payloads:
  `reqBody: String?` / `resBody: String?` (base64 when not valid UTF-8; flag `reqBodyB64: Boolean`).
  Bodies ride inline on the wire; the daemon writes them to files and rewrites `*BodyRef`.
- `marker` — a `Marker`.
- `bye`    — clean shutdown.

Rules: device never blocks on acks (fire-and-forget after hello). Reconnect with exponential
backoff (250ms → 5s cap). On reconnect within 5 minutes, send `resumeSessionId` to continue the
same session folder; otherwise it's a new session. Transactions recorded while disconnected are
NOT retroactively flushed from the ring buffer in v1 (keep it simple; note as v2 candidate).

### 3.3 Filter grammar

Terms separated by spaces AND together. `|` between two full terms ORs them. No parens, no
quotes except in `marker("...")`. Case-insensitive keys, case-insensitive substring semantics
for `:` matches. This grammar is frozen for v1 — reject scope creep.

```
status:404            exact         status>=400   status<500        (also > <=)
method:POST           exact (case-insensitive)
host:api.example.com  substring
path:/v2/users/*      glob (* only)
slower:500ms          duration >    (units: ms, s)
larger:10kb           max(reqBytes,resBytes) >   (units: b, kb, mb)
has:error             status>=400 OR error!=null
text:refund           substring in host+path+query (NOT bodies — bodies are never scanned by filters)
since:marker("tapped checkout")     mono >= mono of last marker with that label
attempt>1             retried/redirected rows only
```

Implementation: `FilterParser.parse(s: String): Result<Filter>` producing an AST;
`Filter.matches(txn, markers): Boolean`. Pure Kotlin, zero platform deps.
**Tests: table-driven, ≥40 cases, including malformed input producing helpful error strings**
(the error strings surface verbatim in both UIs and MCP responses).

---

## 4. `:inspector-core` — capture

### 4.1 Public API (this exact surface, mirrored by `:inspector-noop`)

```kotlin
object Inspector {
    fun init(config: InspectorConfig = InspectorConfig())   // idempotent
    fun install(client: HttpClientConfig<*>)                 // call inside HttpClient { } block
    fun mark(label: String)
    fun addSink(sink: InspectorSink)
    val transactions: StateFlow<List<NetworkTransaction>>    // ring buffer contents, newest first
    val latest: StateFlow<NetworkTransaction?>               // feeds the overlay pill
    val markers: StateFlow<List<Marker>>
    fun clear()
}

data class InspectorConfig(
    val ringBufferMaxBytes: Long = 8 * 1024 * 1024,
    val bodyCaptureMaxBytes: Int = 256 * 1024,
    val captureContentTypes: List<String> =
        listOf("application/json", "text/", "application/xml",
               "application/x-www-form-urlencoded", "application/problem+json"),
    // Off by default: this is a debug-only tool and hiding the auth header defeats the point.
    val redaction: Redaction = Redaction.Off,
)

sealed interface Redaction {
    data object Off : Redaction
    data class On(
        val headers: List<String> = DEFAULT_HEADERS,          // exact, case-insensitive
        val bodyKeyPattern: String = DEFAULT_BODY_KEY_PATTERN, // JSON keys; compiled IGNORE_CASE
        val queryKeyPattern: String = DEFAULT_QUERY_KEY_PATTERN,
    ) : Redaction {
        val bodyKeys: Regex by lazy { Regex(bodyKeyPattern, RegexOption.IGNORE_CASE) }
        val queryKeys: Regex by lazy { Regex(queryKeyPattern, RegexOption.IGNORE_CASE) }
    }
    companion object {
        val DEFAULT_HEADERS: List<String> =
            listOf("authorization", "cookie", "set-cookie", "x-api-key", "proxy-authorization")
        const val DEFAULT_BODY_KEY_PATTERN =
            "(token|secret|password|passwd|api[-_]?key|authorization|bearer|session[-_]?id)"
        const val DEFAULT_QUERY_KEY_PATTERN = "(token|key|secret|password)"
        const val PLACEHOLDER = "‹redacted›"
        // Deliberately no eager `val Default = On()`: On's default args read DEFAULT_HEADERS off
        // this companion, so constructing one during the companion's own init deadlocks clinit.
    }
}

interface InspectorSink {                    // implemented by stream sink, in-app store, tests
    fun onTransaction(txn: NetworkTransaction, reqBody: ByteArray?, resBody: ByteArray?)
    fun onMarker(marker: Marker)
}
```

`:inspector-noop` ships the same file with empty bodies, `StateFlow`s of empty values, and
`install {}` doing nothing. Keep the two API files textually diffable; add a CI check that the
public API surfaces match (compare Kotlin metadata or simply a shared `expect`-style API test).

### 4.2 Ktor plugin — capture mechanics

Use `createClientPlugin("Inspector")`. Requirements, in order of importance:

1. **Per-attempt rows.** Redirects and retries must each produce a row (same `callId`,
   incrementing `attempt`). Preferred mechanism: the `on(Send)` hook inside
   `createClientPlugin`, which wraps each send. **Write the integration test first** (server
   that 302s then 200s; server that 500s twice then 200s with `HttpRequestRetry` installed)
   and verify the pinned Ktor version produces one row per attempt. If `on(Send)` observes
   only logical calls in that version, fall back to registering an interceptor on the
   `HttpSend` plugin from a `client.plugin(HttpSend)` post-install step. The test defines
   correctness; the mechanism is whatever passes it.
2. **Request body tee.** Wrap `OutgoingContent`:
   - `NoContent` → nothing.
   - `ByteArrayContent` / `TextContent` → copy up to cap, record true size.
   - `ReadChannelContent` / `WriteChannelContent` → wrap the channel with a tee that copies
     the first `bodyCaptureMaxBytes` bytes into a buffer and **counts** the remainder without
     storing. Never accumulate-then-truncate.
   - Content-type not in `captureContentTypes` → count bytes only, no buffer at all.
3. **Response body tee.** Split the response body channel (the approach Ktor's own `Logging`
   plugin and `ResponseObserver` use — read their source before implementing). Same cap and
   content-type rules. **The app must always receive the complete, unmodified body** — this is
   the single most important integration test in the project: large bodies (10 MB), chunked,
   gzip'd, and empty bodies, all byte-identical with and without the plugin installed.
4. **Failure rows.** Connect errors, timeouts, cancellation → row with `status=null`,
   `error="SocketTimeoutException: ..."`, duration up to failure.
5. **Redaction at capture, when enabled.** With `Redaction.Off` (the default) this stage is
   skipped entirely — no scanning cost, no allocation. With `Redaction.On`: headers by
   denylist (values → `‹redacted›`), query params by `queryKeys`, JSON body values whose key
   matches `bodyKeys` (parse leniently; if the body isn't parseable JSON, apply a regex pass
   for `"key"\s*:\s*"value"` patterns only — never fail capture because redaction failed).
   Record every redaction in `redacted[]`.
6. **Threading/perf.** The capture path on the caller's coroutine does only: metadata snapshot
   + handing buffers to a `Channel(capacity = 256, onBufferOverflow = DROP_OLDEST)`. One
   dedicated single-thread dispatcher drains the channel: builds the `NetworkTransaction`,
   updates the ring buffer, fans out to sinks. Sinks that throw are caught + logged, never
   propagate.

### 4.3 Ring buffer

Deque of (txn, reqBody?, resBody?) with a running byte total (serialized-txn estimate + body
lengths). Evict oldest until under `ringBufferMaxBytes`. Exposed via the `transactions`
StateFlow. `clear()` empties it and emits.

### 4.4 Monotonic time

`expect fun monoMs(): Long` — Android/JVM: `System.nanoTime()/1e6`; iOS:
`clock_gettime_nsec_np(CLOCK_UPTIME_RAW)/1e6` (or `NSProcessInfo.systemUptime*1000`).

---

## 5. `:inspector-stream` — device → daemon sink

- `StreamSink(host: String? = null, port: Int = 8099) : InspectorSink`
- Default host per platform (expect/actual): iOS simulator → `localhost`; Android emulator →
  `10.0.2.2`; desktop → `localhost`. (Simulator/emulator only in v1 — no discovery code.)
- Own Ktor client (CIO or platform engine) with WebSockets — **do not install the Inspector
  plugin on this client** (guard against self-capture: tag the client with an attribute the
  plugin checks, so even a copy-paste mistake can't create a feedback loop).
- Owns its outbound `Channel(512, DROP_OLDEST)`. Serializes and sends on its own dispatcher.
  Connection state machine: connecting → open → backoff. Expose `val state: StateFlow<StreamState>`
  so the in-app UI can show a "● host" indicator.
- On `helloAck`, remember `sessionId` in memory for resume.
- App lifecycle: on app background/termination, best-effort `bye` (don't block).

---

## 6. `:inspector-ui` — overlay + in-app inspector (Compose Multiplatform)

### 6.1 Integration surface

```kotlin
@Composable fun InspectorOverlay(content: @Composable () -> Unit)
// App wraps its root once. Noop artifact: passthrough that just calls content().
```

### 6.2 Overlay pill

- Draggable pill, snaps to nearest screen edge on release; position remembered in-memory.
- Shows latest transaction: status dot + `METHOD /path-tail · status · ms` in monospace.
  Colors: 2xx green, 3xx blue, 4xx amber, 5xx red, transport-error grey. In-flight: pulsing dot
  + count badge when >1 in flight.
- Idle 60% opacity, 100% on activity, fades back after 2s.
- Tap → full-screen inspector (modal sheet). Long-press → collapse to a 24dp dot; tap dot to restore.
- Must not intercept touches outside its own bounds.

### 6.3 Inspector screens

- **List**: pinned filter bar (TextField, parse-on-type, inline error text from FilterParser),
  `LazyColumn` of rows (status chip, method, path, duration, size, relative time). Markers render
  as full-width dividers with the label. Toolbar: clear, pause/resume autoscroll, marker button
  (prompts for label → `Inspector.mark`), host-connection indicator (from StreamSink.state when present).
- **Detail**: tabs Overview / Request / Response.
  - Overview: full URL, timing, sizes, attempt chain (all rows sharing `callId`, tappable),
    `redacted[]` list shown explicitly ("authorization header was redacted at capture").
  - Request/Response: headers table (long-press to copy value), body view — pretty-printed
    collapsible JSON tree when content-type is JSON, plain monospace text otherwise,
    "truncated at 256 KB" banner when applicable, byte count for uncaptured bodies.
  - Actions: copy as cURL (build from method/URL/headers/body; redacted headers appear as
    `Authorization: ‹redacted›`), copy URL, copy body.
- Dark and light themes following system. Keep the design plain, dense, monospace-forward.

State comes only from `Inspector.transactions` / `markers` — the UI module has zero capture logic.

---

## 7. `:inspector-daemon` — host daemon, archive, web UI, CLI, MCP

Single fat jar (`./gradlew :inspector-daemon:shadowJar` or install-dist). Subcommands:

```
inspector serve   [--port 8099] [--data ~/.inspector] [--max-sessions 100] [--max-mb 300]
inspector sessions                       # list, newest first
inspector summary  [--session latest]
inspector query    '<filter>' [--session latest] [--limit 50] [--offset 0] [--json]
inspector body     <txnId> --side req|res [--session latest]
inspector mark     '<label>' [--session latest]      # appends marker with source:"user"
inspector mcp                                        # stdio MCP server
inspector prune                                      # apply retention now
```

CLI subcommands talk to a running daemon over REST when reachable, else read the files
directly (both paths through the same repository class — see 7.2).

### 7.1 Disk layout (exactly as specified)

```
~/.inspector/
  config.json                      # optional overrides: port, maxSessions, maxTotalMb, dataDir
  sessions/
    2026-08-16T10-14-02_ProjectX_iPhone16Pro_debug/
      meta.json
      index.jsonl                  # one NetworkTransaction per line, append-only
      markers.jsonl
      bodies/7f3a.req              # raw bytes as captured
      bodies/7f3a.res
  latest -> sessions/<newest>      # symlink, updated on session creation
```

Folder name = sessionId: `<yyyy-MM-dd'T'HH-mm-ss>_<appId-last-segment>_<device-slug>_<buildType>`
(slugs: alphanumeric + dashes). Writer: append + `flush` per line (no fsync per line);
`meta.json` rewritten atomically (tmp + rename) on session start, every 30s, and on close.

**Retention**: after each session close and on daemon start — delete oldest session folders
until `count <= maxSessions` AND `totalBytes <= maxTotalMb`. Never delete the active session.
Log every pruned folder.

### 7.2 Server (Ktor, binds 127.0.0.1 only)

- `WS /ingest` — wire protocol from §3.2. Rewrites inline bodies to `bodies/` files and
  fills `*BodyRef` before appending to `index.jsonl`. Updates meta counts.
- `WS /api/live` — fan-out to web UI: every appended txn/marker for the active session.
- REST (all JSON):
  - `GET /api/sessions` → `[SessionMeta]`
  - `GET /api/sessions/{id}/summary`
  - `GET /api/sessions/{id}/transactions?filter=<grammar>&offset&limit` →
    `{ total, matched, items:[NetworkTransaction] }` (400 with the parser's error string on bad filter)
  - `GET /api/sessions/{id}/transactions/{txnId}`
  - `GET /api/sessions/{id}/transactions/{txnId}/body/{req|res}` → raw bytes with stored content-type
  - `POST /api/sessions/{id}/markers` `{label, source}`
  - `{id}` accepts the literal `latest`
- One `SessionRepository` class does all file reads; REST handlers and CLI-direct mode share it.
  Filter evaluation happens here, server-side, via `:inspector-model`'s parser.

`session_summary` shape (also the MCP tool result — keep it ≤ ~1 KB):

```json
{ "sessionId": "...", "startedAt": "...", "txnCount": 412, "byStatusClass": {"2xx": 371, "4xx": 24, "5xx": 9, "error": 8},
  "byHost": {"api.example.com": 380, "cdn.example.com": 32},
  "slowest": [{"id":"7f3a","path":"/v2/checkout","ms":2140}],
  "errors":  [{"id":"9c1d","method":"POST","path":"/v2/pay","status":500,"ms":340}],
  "markers": ["login","tapped checkout"] }
```
(`slowest` top 5; `errors` capped at 20 with a `moreErrors: n` overflow count.)

### 7.3 Web UI (`GET /` from resources: index.html + app.js + style.css, no build step)

Three-pane layout per the agreed sketch:

- **Header**: app/device from meta, session picker (dropdown, newest first), live-tail toggle
  (auto-on when viewing the active session), transaction count.
- **Left rail**: filter input (debounced 150ms, server-side eval; parser errors inline),
  quick-filter chips (`has:error`, `slower:500ms`, per-method), marker list — clicking a marker
  scrolls the list to it.
- **Center**: virtualized list (windowed rendering — target smoothness at 10k rows), rows =
  status dot/method/path/status/duration/size; markers as inline dividers; click selects.
- **Right**: detail — Overview/Request/Response sections, collapsible JSON tree (small
  hand-rolled renderer, ~100 lines), copy-as-cURL button, body fetch is lazy (only on select).
- **Keyboard**: `j`/`k` move, `Enter`/click select, `/` focus filter, `f` also focus filter,
  `c` copy cURL, `Esc` clear selection, `g`/`G` top/bottom.
- Dark theme default, light via `prefers-color-scheme`. Monospace numerics. No external fonts,
  no CDN anything — fully offline.

### 7.4 MCP server (`inspector mcp`, stdio, MCP Kotlin SDK)

Tools (thin wrappers over `SessionRepository` — same filter grammar, same shapes as REST):

- `list_sessions()` — id, app, device, startedAt, txnCount, errorCount
- `session_summary(sessionId = "latest")`
- `list_transactions(filter = "", sessionId = "latest", limit = 50, offset = 0)` — index rows only, never bodies
- `get_transaction(txnId, sessionId = "latest")`
- `get_body(txnId, side, sessionId = "latest", maxBytes = 65536)` — truncates with a note
- `add_marker(label, sessionId = "latest")` — source `"agent"`

Tool descriptions must steer the model: "Call `session_summary` first; it answers most
questions in ~1 KB. Use `list_transactions` with a filter rather than reading everything.
Fetch bodies only for specific transactions." Document the filter grammar inside the
`list_transactions` description.

---

## 8. Production-safety mechanism

1. **Artifact swap** (documented in README for consuming apps):

```kotlin
// consuming app's shared/build.gradle.kts
val inspectorOff = providers.gradleProperty("inspector").orNull == "off"
commonMain.dependencies {
    if (inspectorOff) implementation("dev.inspector:inspector-noop:<v>")
    else {
        implementation("dev.inspector:inspector-core:<v>")
        implementation("dev.inspector:inspector-ui:<v>")      // internal mode
        implementation("dev.inspector:inspector-stream:<v>")  // external mode
    }
}
```
Release CI builds pass `-Pinspector=off`. Android consumers may additionally use
`debugImplementation`/`releaseImplementation` for the same effect.

2. **Canary guard**: `:inspector-core` contains
   `internal const val CANARY = "INSPECTOR_CANARY_7f3a9b_DO_NOT_SHIP"` referenced from `init()`
   so it can't be dead-code-eliminated. `scripts/check-release-clean.sh` builds the sample's
   release APK + iOS release framework with `-Pinspector=off` and fails if
   `strings`/`grep` finds the canary in any output. Wire into CI in Phase 0. The noop artifact
   contains no canary.

---

## 9. Sample app + test server

- `sample/test-server`: Ktor JVM server on :9090 with endpoints exercising every capture path:
  `/json` (pretty JSON), `/slow?ms=`, `/status/{code}`, `/redirect` (302→`/json`),
  `/flaky` (500,500,200 in sequence per session — exercises retry), `/large?kb=` (streams N KB),
  `/gzip`, `/binary` (octet-stream), `/echo` (POST echo), `/secret` (response containing
  `{"token":"...","password":"..."}` — proves redaction end-to-end).
- `sample/shared`: buttons for each endpoint + a "burst 50" button + a "mark" button.
  Root wrapped in `InspectorOverlay`. Ktor client with `Inspector.install`, `HttpRequestRetry`,
  and `StreamSink` added.
- The sample is the integration-test vehicle and the demo. Keep it ugly-but-functional.

---

## 10. Build order, tasks, acceptance criteria

Dependencies flow downward; within a phase, tasks are ordered. Do not start a phase until the
previous phase's acceptance criteria pass.

### Phase 0 — contract (est. 2 days)
1. Repo scaffold, version catalog, all modules compiling empty for all targets.
2. `:inspector-model`: types §3.1, wire protocol §3.2, filter parser + evaluator §3.3.
3. `:inspector-noop` full API surface; API-parity test vs a stub of core's surface.
4. `scripts/check-release-clean.sh` + CI wiring (canary in a placeholder core `init()`).
5. `docs/schema.md` written from §3.

**Accept**: filter test table (≥40 cases) green on JVM + iOS sim + Android unit tests;
serialization round-trip tests green; CI runs the release-clean script and passes with
`-Pinspector=off`, fails when canary is force-included (prove both directions once).

### Phase 1 — capture + device (est. 1 week)
1. `:inspector-core`: plugin skeleton, metadata capture, failure rows, hand-off channel + dispatcher.
2. Per-attempt integration tests (redirect, retry) — then make them pass (§4.2.1).
3. Request/response body tees with cap + content-type gating; byte-identical-body tests
   including 10 MB, gzip, chunked, empty.
4. Redaction (headers, query, JSON bodies) + tests incl. non-JSON body fallback.
5. Ring buffer with byte budget + eviction tests.
6. `:inspector-ui`: overlay pill, list, detail, cURL copy; wire into sample; manual pass on
   iOS simulator + Android emulator + desktop.

**Accept**: sample app on both simulators shows correct rows for every test-server endpoint;
`/secret` shows token and password **verbatim** under the default `Redaction.Off`, and shows
`‹redacted›` plus a populated `redacted[]` when re-run with `Redaction.On()`; burst-50 causes no dropped frames in the app
UI and no ANR; app receives byte-identical bodies (automated); release-clean script still green.

### Phase 2 — daemon + archive + web UI (est. 1 week)
1. Daemon skeleton: `serve`, config file + flags, data dir, session folders, `latest` symlink.
2. `WS /ingest` + disk writer + meta updates + retention pruning (+ unit tests with tmp dirs:
   prune-by-count, prune-by-bytes, never-prune-active).
3. `:inspector-stream` sink + reconnect/resume; kill-daemon-mid-session test (app unaffected,
   reconnect resumes same session within 5 min).
4. REST API + `SessionRepository` + server-side filter eval + tests.
5. Web UI: layout, list + live tail, detail, filter, markers, keyboard nav.

**Accept**: full flow — start daemon, run sample on simulator, browse live traffic at
`localhost:8099`, filter `status>=400` works, marker dividers render, kill+relaunch app creates
a second session, session picker switches, retention prunes correctly when limits are lowered;
grep of `~/.inspector/latest/index.jsonl` from a terminal answers "which calls 500'd".

### Phase 3 — AI access + polish (est. 3 days)
1. CLI subcommands (REST-or-files via shared repository).
2. MCP server + tool descriptions; verify by registering with Claude Code
   (`claude mcp add inspector -- inspector mcp`) and running a real debugging prompt against
   a recorded session ("why did checkout fail?" must be answerable in ≤4 tool calls).
3. `session_summary` shaping; `add_marker` from agent.
4. Polish: in-app connection indicator, web UI quick-filter chips, README with consuming-app
   setup (both modes), copy-as-cURL edge cases.
5. Stretch (only if time remains): HAR export on daemon (`GET /api/sessions/{id}/har`).

**Accept**: an agent with only the MCP server answers "what failed after the 'tapped checkout'
marker and what did the server return?" correctly on the sample session; CLI `query` works with
daemon stopped (file mode); README dogfooded by integrating inspector into a fresh empty CMP app.

### Phase 4 — signals (est. 17–24 days) — **not started**

App state on the same timeline as traffic: which screen was up, what the presentation layer held,
what was in the cache, merged with the network rows by `mono`. One generic primitive — the
`Signal` — carried over the existing transport into the existing archive.

`docs/SIGNALS.md` is the executable spec for this phase, the way this document was for Phases 0–3.
The numbered items below are its stages; every type, wire frame, filter rule and trap lives there.

1. **Signal core.** `Signal`, `SignalTrigger`, `SignalMsg` in `:inspector-model`. `Recorder`
   signal path with **trailing-edge** conflation and `dropUnchanged`. `RingBuffer` generalized to
   an entry-size function, with a signal budget separate from the transaction budget. Public API,
   no-op twins, golden file, `ApiParityTest`.
2. **Daemon and archive.** `signals.jsonl`, `signals/`, `dataRef` rewrite on ingest, per-tag
   retention, the five read routes.
3. **Pull.** `SignalRequest` / `SignalError`, device-side provider registry, `LiveApps`
   generalization, `POST /api/sessions/{id}/signals/request`.
4. **Grammar v2 and MCP.** `tag:` and `name:` terms plus the rule that a term excludes row types
   lacking its field. Tools `current`, `list_signals`, `get_signal`, `timeline`, `request_signal`;
   extended `session_summary`.
5. **Web UI.** Merged timeline as the default view, `screen` as a lane of points, `cache` as
   spans, a generic lane for unknown tags, a current-state panel.
6. **Ship it.** `INTEGRATION.md` v16 + §13 entry, `schema.md` `Signal` section, bump to 0.3.0,
   tag, then verify the published artifact through a throwaway consumer before announcing.

**Accept**: "why did the KYC submit fail?" answered in three MCP calls against a real recorded
session — `timeline(since: marker(…))` → `get_signal` → `get_body` — proven both as a unit test
and by piping JSON-RPC frames into the built binary; a live viewer receives `dataRef` **populated**
(`LiveTest`, not only the REST test); a burst of 100 identical payloads yields one row and 100
differing ones yield the last per window; the signal ring evicts without touching the transaction
ring; `status:500 tag:screen` returns empty and is documented as doing so; `ApiParityTest` green;
`-Pinspector=off` still yields a runtime classpath of no-op modules only.

Stages 1–2 plus the navigation recipe are the thinnest useful slice — about 8–10 days — and
already answer "which screen was the user on when this 500 happened".

---

Total for Phases 0–3: ~3 weeks single-agent full-time equivalent.

---

## 11. Known traps (read before Phase 1)

- **Body channels are one-shot.** Any naive read consumes the body and breaks the app. Only
  the tee patterns in §4.2. Study `io.ktor.client.plugins.logging.Logging` and
  `ResponseObserver` source for the split mechanics in the pinned Ktor version.
- **Ktor 3 API drift.** Plugin APIs moved between 2.x and 3.x; trust the pinned version's
  source over blog posts. The per-attempt test defines correctness, not the API's name.
- **Kotlin/Native**: no runtime reflection; keep everything kotlinx-serialization-friendly.
  `Dispatchers.IO` exists on Native in current coroutines versions, but the single capture
  dispatcher should be created explicitly (`newSingleThreadContext`) and shared.
- **Self-capture loop**: stream sink's client must never carry the plugin (attribute guard, §5).
- **Clock skew**: order by `mono`, display `ts`. Never mix them.
- **DROP_OLDEST everywhere** on the hot path. The app's traffic is sacred; the inspector's
  completeness is not.
- **JSON pretty-print on big bodies** in UIs: parse lazily/on-demand; a 256 KB body must not
  jank the list.
- **Emulator networking**: Android emulator reaches host via `10.0.2.2`; iOS simulator shares
  the host network (`localhost`). Both baked into `:inspector-stream` defaults — no user config
  needed in v1.

---

## 12. Configuration reference (defaults)

| Setting | Where | Default |
|---|---|---|
| Ring buffer budget | `InspectorConfig.ringBufferMaxBytes` | 8 MB |
| Body capture cap | `InspectorConfig.bodyCaptureMaxBytes` | 256 KB |
| Captured content types | `InspectorConfig.captureContentTypes` | json, text/*, xml, form |
| Redaction | `InspectorConfig.redaction` | **`Redaction.Off`** — verbatim capture |
| Redacted headers (when on) | `Redaction.On.headers` | auth, cookie, set-cookie, x-api-key, proxy-auth |
| Daemon port | flag / `config.json` | 8099 |
| Data dir | flag / `config.json` | `~/.inspector` |
| Max sessions kept | flag / `config.json` | 100 |
| Max total size | flag / `config.json` | 300 MB |
| Session resume grace | constant | 5 min |
| Stream queue | constant | 512, DROP_OLDEST |
