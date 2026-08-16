# Inspector — agent guide

Canonical context file for AI coding agents. `CLAUDE.md` is a symlink to this file and
`.cursor/rules/inspector.mdc` points here, so **there is exactly one copy to keep current** —
edit this file, never a duplicate.

---

## What this is

A network inspector for **Compose Multiplatform apps that use Ktor**. Think Wormholy (iOS), but
multiplatform, and with a host-side archive and first-class access for AI agents.

Three consumers of the same captured data:

1. **In-app** — a draggable overlay pill showing `GET /users · 200 · 143ms`, tapping into a full
   inspector. Works on Android, iOS and desktop from one Compose codebase.
2. **Host daemon** *(not built yet)* — archives every session to `~/.inspector/sessions/…` as
   readable folders, serves a web UI.
3. **Agents** *(not built yet)* — read `index.jsonl` directly, or query via CLI/MCP.

## What it is not

- Not a process-wide inspector. It sees **Ktor traffic only** — no third-party SDK traffic, no
  WebView, no native iOS `NSURLSession` calls. This was a deliberate v1 scope decision.
- Not wire-level. Bodies may already be decompressed; there is no DNS/TLS/TTFB breakdown.
- Not for WebSockets or SSE.
- Not shipped to production, ever. See **Production safety** below — this is enforced, not
  conventional.

---

## Where we are

| Phase | Scope | Status |
|---|---|---|
| **0** | Schema, filter grammar, API contract, release guard | ✅ done |
| **1** | Capture, ring buffer, redaction, overlay + inspector UI | ✅ done |
| **2** | Host daemon, session archive, stream sink, web UI | ✅ done |
| **3** | MCP server over the archive | ✅ done |
| **4** | Capture traffic from non-Ktor SDKs (Auth0, native) | ⬜ next |

**146 tests, 0 failures** across JVM, iOS simulator, Android host and the daemon.

### First real-app findings (2026-08-16, a consuming app on an Android emulator)

The first session recorded from a real app surfaced four defects. All are fixed; they are listed
because each was invisible to the suite at the time, and the shape of each gap matters more than
the fix.

1. **Live viewers saw every body as missing.** `SessionWriter.append` returned Unit and
   `SessionManager` broadcast the row *as received*, where `*BodyRef` is null by wire contract.
   The bodies were on disk the whole time. Nothing tested the live payload — `LiveTest` now does.
2. **The UI invented a reason.** Any null body rendered as "content type outside the capture
   allowlist", which was flatly wrong for the case above. Capture now records
   `reqBodyOmitted`/`resBodyOmitted` and both UIs render what was recorded, never a guess.
3. **`reqContentType` was null on every request.** It was read from the builder's headers, but
   Ktor keeps it on the `OutgoingContent`. This also silently disabled request-body redaction,
   which bails on anything not declared JSON — so `Redaction.On` was not redacting request bodies
   at all.
4. **Two dead daemons.** A failed bind left the JVM alive still printing "listening on …". The
   port is now probed before anything is printed.

The lesson worth keeping: **the live path and the REST path are two renderings of one row, and
only the REST one was tested.** Any new field on `NetworkTransaction` needs a check on both.

### Verified

- Redirect and retry each produce one row per attempt, sharing a `callId`.
- App receives byte-identical bodies with the inspector installed: 10 MB, gzip, chunked, empty,
  binary.
- `/secret` shows credentials verbatim by default, redacted under `Redaction.On()`.
- Ring buffer evicts by byte budget; a single oversized entry is still kept.
- `-Pinspector=off` yields a runtime classpath containing only noop modules.
- Canary guard fails when capture code is present and passes when it is not — **both directions
  proven**.
- `./gradlew :sample:desktop:run` launches and serves traffic.
- End-to-end: sample streams to the daemon, 40 transactions archived to
  `~/.inspector/sessions/<ts>_<app>_<device>_<build>/`, `latest` symlink correct, bodies written
  to `bodies/`, `index.jsonl` greppable from a terminal.
- REST filters evaluate server-side with the shared grammar; a bad filter returns 400 carrying
  the parser's own message.
- Killing the daemon mid-session leaves the app unaffected; reconnecting resumes the same
  session folder; a relaunch gets a new one.
- Retention prunes by count and by bytes, never the active session.
- **The web UI renders.** `scripts/render-web-ui.js` runs the real `app.js` against the real
  `index.html` with fetch proxied to a live daemon and reports what actually rendered: rows,
  marker dividers, session picker, detail pane, attempt chain, console errors. Last run: 109
  rows, 2 marker dividers, 6 detail sections, 4 chain rows, zero errors.

### Not verified

- **The overlay has never been seen on a phone.** Verification so far is desktop-only. There is
  no Android app module and no Xcode project. UI code compiles for all targets.
- **Nobody has judged how the web UI *looks*.** It provably renders the right elements (see
  above), but no human has assessed spacing, colour or density. The browser pane is blocked from
  localhost by policy in this environment, so only a static snapshot has ever been produced.
- Redirect behaviour against engines other than CIO. Some engines follow redirects internally,
  which would collapse a chain into a single row. Unresolved for the target app's engine.

---

## What is next (Phase 4) — capturing what Ktor cannot see

**The problem, stated precisely.** Capture is a Ktor *client plugin*. It sees exactly the traffic
that flows through an `HttpClient` the app configured. It cannot see:

- SDKs that own their transport. Auth0's Android SDK builds its own OkHttp internally; on iOS it
  uses `URLSession`. Neither passes through Ktor, so neither appears — which is what the first
  real user hit, seeing app→backend calls but no Auth0 calls.
- Anything native: WebViews, Firebase, analytics SDKs, the platform image loaders.

Three mechanisms can close this, at increasing cost and increasing coverage. **They are not
alternatives to each other; 4a is worth doing regardless of whether 4c ever happens.**

**4a. `:inspector-okhttp` — an OkHttp `Interceptor` (small, Android only, partial coverage).**
Recording into the same `Recorder`, so rows are indistinguishable from Ktor's. Immediately covers
every OkHttp-based library where the app controls the client: Retrofit, Coil, and Ktor's own
OkHttp engine. For Auth0 specifically it needs an adapter, because `com.auth0.android` does not
accept an injected `OkHttpClient` — it accepts a `NetworkingClient`, so the app implements that
interface, delegates to an inspected OkHttp client, and passes it via `Auth0.networkingClient`.
That adapter is ~15 lines of app code and belongs in `docs/INTEGRATION.md`, not in the library:
`:inspector-okhttp` must not depend on Auth0.

Reuses `teeBody`, `Redactor` and `CallState` unchanged. The one genuinely new piece is that
OkHttp's `Interceptor` is blocking, so the body tee needs a blocking variant — do **not** make
the app wait on a coroutine.

**4b. Blocking the leak, cheaply: report what we know we cannot see.** Even with 4a, some traffic
is invisible, and the current failure mode is silence, which reads as "no traffic happened". Cheap
mitigation: a session-level note listing hosts seen in DNS/connection logs but never captured. Not
designed yet; may not be worth it.

**4c. Proxy capture (large, universal).** The daemon runs a local HTTP proxy; the emulator or
simulator is pointed at it. Catches everything including native SDKs and WebViews, and is the only
mechanism that works on iOS for non-Ktor traffic. Cost is real: HTTPS requires a generated CA
installed in the emulator's trust store, and on Android 7+ apps must additionally opt in via
`network_security_config.xml` — which is acceptable here, because that file is already a
debug-only artifact in this project's integration guide. This is the mitmproxy model and should be
scoped as its own phase, not bolted onto 4a.

**Recommendation:** do 4a next. It is a day's work, covers the common Android cases, and does not
foreclose 4c. Do not start 4c without the user explicitly choosing it — it changes the tool from a
library into a piece of network infrastructure.

### Also outstanding

- **Someone should look at the web UI** and say what is ugly. It renders correctly; nobody has
  judged it.
- **Android + iOS sample shells**, to finally see the overlay on a device. Needs an Android app
  module and an Xcode project; the UI module already compiles for both.
- **Stretch:** HAR export (`GET /api/sessions/{id}/har`).

---

## Architecture

```
:inspector-model     KMP  Schema, wire protocol, filter grammar. Only kotlinx-serialization.
:inspector-core      KMP  Ktor plugin, recorder, ring buffer, redaction, sinks.
:inspector-noop      KMP  Identical public API, does nothing. Release builds.
:inspector-ui        KMP  Compose overlay pill + inspector screens.
:inspector-noop-ui   KMP  Passthrough overlay. Release builds.
:inspector-noop-stream KMP  No-op stream sink. Release builds.
:inspector-stream    KMP  WebSocket sink to the daemon.
:inspector-daemon    JVM  Archive, REST, live WS, web UI, CLI.   MCP is Phase 3.
sample/desktop       JVM  Runnable reference integration.
```

`:inspector-model` is shared between device and daemon **on purpose**: schema types and the
filter parser are then literally the same code on device, in the web UI backend, the CLI and the
MCP tools. A filter string typed in the app is a string you can hand an agent.

Targets: `android`, `iosArm64`, `iosSimulatorArm64`, `jvm`. **No `iosX64`** — Compose
Multiplatform 1.11+ dropped the Intel simulator, so no CMP app can target it.

---

## Non-obvious decisions — do not "fix" these

**Per-attempt rows, not per-call.** Redirects and retries each get a row. The bugs worth catching
hide in the attempts a "one row per call" view would collapse.

**`CallState` is shared by reference.** `HttpRequestRetry` rebuilds every attempt from the
*original* builder via `takeFrom`, which does `attributes.putAll` — entries copied, values shared
by reference. A plain `Int` attempt attribute resets to 1 on every retry. A mutable holder seeded
once in `SetupRequest` is the only thing that survives. `PerAttemptTest` caught this; keep it.

**Hand-rolled body tee.** Ktor's `split` is internal API. The tee forwards every byte unchanged
and copies a capped prefix aside; **past the cap it keeps reading and only counts**, because
stopping early would stall the app's side of the channel. `onComplete` fires in a `finally` so a
body the app abandons still produces a row.

**`rawContent` is `@InternalAPI`.** There is no public accessor for the undecoded body channel;
Ktor's own Logging plugin reads it the same way. Opted in explicitly, pinned to Ktor 3.5.0.
Revisit on upgrade.

**Redaction is OFF by default.** This is a debugging tool for debuggable builds whose capture
code cannot reach production. A debugger that hides the auth header is useless when the bug *is*
the auth header. Do not flip the default. `redacted[]` records what was stripped so consumers can
say "redacted" rather than "absent".

**Streaming request bodies are not buffered.** `ByteArrayContent`/`TextContent` are captured;
channel-backed uploads report `contentLength` only. Buffering a file upload to inspect it is the
exact cost the efficiency contract forbids.

**`path:` filters are dual-mode** — glob when the pattern contains `*`, substring otherwise. A
bare `path:/v2/users` typed in a hurry should find `/v2/users/me`.

**`since:marker("…")` with an unknown label matches nothing**, not everything. A typo silently
becoming "no filter" is the more dangerous failure while debugging.

**No eager `Redaction.Default`.** `On`'s default args read `DEFAULT_HEADERS` off the companion,
so constructing one during the companion's own init deadlocks `<clinit>`. Use `Redaction.On()`.

**Session resume is decided from `endedAt` on disk, not memory.** An in-memory "recently closed"
map is empty after a daemon restart — which is one of the very cases the grace period exists to
cover — so it silently refused every resume. The round-trip test caught it.

**Every real module needs a noop twin.** `core`→`noop`, `ui`→`noop-ui`, `stream`→`noop-stream`.
Adding a module the consuming app calls into, without its twin, breaks `-Pinspector=off`
compilation and pushes users into `if (BuildConfig.DEBUG)` guards around their own wiring —
which is the exact thing the noop artifacts exist to prevent.

**There are four surfaces, and only two are for users.** The in-app overlay and the browser web
UI are the products. The daemon is a headless CLI, and `sample/desktop` is a demo, not a tool.
Nobody should be told to "open the desktop app".

**Android blocks cleartext by default; the daemon connection is cleartext.** `ws://10.0.2.2:8099`
fails silently on API 28+ without a debug-only `network_security_config.xml`. This is the most
common reason an Android app records nothing, and the integration guide claiming "no manifest
entries, no permissions" was wrong until it was corrected — see `docs/INTEGRATION.md` 6d.

**StreamSink reports why it failed.** Connection errors used to be swallowed by `runCatching`,
leaving "the web UI is empty" undiagnosable from inside the app. Each distinct reason is now
printed once (not per retry) and exposed as `lastError`. Keep it that way.

**The daemon binds 127.0.0.1 only.** There is no auth and the archive holds unredacted
credentials by default. The loopback bind *is* the security boundary; do not widen it without
adding authentication first.

**Kotlin block comments nest.** A `/*` inside KDoc (e.g. the example `path:/v2/users/*`) swallows
the rest of the file. Write `&#42;` or avoid the sequence.

**A request's content type lives on the body, not the headers.** `HttpRequestBuilder.headers`
does not carry `Content-Type`; Ktor puts it on the rendered `OutgoingContent`. Read both, always.
Getting this wrong reports `reqContentType = null` *and* silently turns off request-body
redaction, because `Redactor.body` only touches bodies declared JSON.

**Never guess why a body is absent — read `reqBodyOmitted`/`resBodyOmitted`.** Those exist
precisely because a UI that guessed sent someone chasing a content-type bug that did not exist.
If a new code path can drop a body, give it a `BodyOmission` constant.

**The two module test frameworks differ.** `:inspector-core` and the KMP modules run **JUnit 4**
(`kotlin("test")` on JVM), where a test method must return `Unit` — `fun x() = runBlocking { … }`
fails at class-init with "should be void" if the block's last expression returns a value, and
`assertNotNull` returns its argument. `:inspector-daemon` runs **JUnit 5**
(`useJUnitPlatform()`), where a value-returning `@BeforeEach` is rejected instead.

**MCP tool failures are results, not JSON-RPC errors.** An agent can read `isError: true` with a
message and correct itself; a transport-level error just ends its turn. Reserve JSON-RPC errors
for protocol problems like an unknown method.

**`inspector mcp` owns stdout.** Anything printed there that is not a JSON-RPC frame
desynchronises the client. Diagnostics go to stderr.

---

## Efficiency contract

The app must never block on, wait for, or meaningfully allocate because of the inspector.

- Capture on the caller's coroutine does only a metadata snapshot plus `trySend` into a
  `Channel(256, DROP_OLDEST)`. Backpressure never reaches the app.
- One dedicated worker (`Dispatchers.Default.limitedParallelism(1)`) does ring accounting and
  sink fan-out. A sink that throws is caught and skipped.
- Body capture is the only real cost, gated by a 256 KB cap and a content-type allowlist.
- Release builds use the noop artifact: cost is *zero*, not *small*.

**DROP_OLDEST everywhere on the hot path. The app's traffic is sacred; the inspector's
completeness is not.**

---

## Production safety — two layers

1. **Artifact swap.** `-Pinspector=off` substitutes `:inspector-noop` / `:inspector-noop-ui`.
   Both expose an identical public surface, verified against `api/inspector-public-api.txt` by
   `ApiParityTest` running in *both* modules, so neither can drift silently.
2. **Canary guard.** `:inspector-core` carries `INSPECTOR_CANARY_…`;
   `scripts/check-release-clean.sh` fails if it appears in a release artifact. The script reads
   the canary from the Kotlin source rather than hardcoding it, and scans Kotlin/Native klib
   directories as well as jars/aars — an earlier version scanned only jars and so reported iOS
   builds clean purely because it never looked.

**Always run the self-test alongside the real check.** A detector that cannot detect would report
every release build clean:

```bash
./scripts/check-release-clean.sh --self-test   # must FIND the canary
./scripts/check-release-clean.sh               # must find NOTHING
```

---

## Commands

```bash
./gradlew build                                   # everything, all targets
./gradlew :inspector-daemon:installDist           # then: inspector-daemon/build/install/inspector/bin/inspector serve
./gradlew :sample:desktop:run -Dinspector.sample.autofire=true   # scripted traffic, no clicking
npm install jsdom && node scripts/render-web-ui.js > /tmp/ui.html # web UI smoke test + snapshot
./gradlew :sample:desktop:run                     # runnable reference app
./gradlew :inspector-core:jvmTest                 # capture integration tests
./gradlew :inspector-model:iosSimulatorArm64Test  # iOS
./gradlew :inspector-model:testAndroidHostTest    # Android host
./gradlew build -Pinspector=off                   # release swap
```

Driving the MCP server by hand, which is the fastest way to check a tool change:

```bash
printf '%s\n' \
  '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{}}}' \
  '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"session_summary","arguments":{}}}' \
  | inspector-daemon/build/install/inspector/bin/inspector mcp
```

Changing the public API is deliberately high-friction, because it is a contract:

```bash
# 1. change BOTH :inspector-core and :inspector-noop
# 2. regenerate:
./gradlew :inspector-core:jvmTest -Dinspector.api.regenerate=true
# 3. confirm both modules pass:
./gradlew :inspector-core:jvmTest :inspector-noop:jvmTest
```

---

## Conventions

- **Pinned versions.** Kotlin 2.3.21, Ktor 3.5.0, Compose MP 1.11.1, AGP 9.3.1, Gradle 9.5.1,
  JDK 21. All were verified present in the local Gradle cache before pinning. Do not bump
  casually.
- **Trust the pinned source over blog posts.** Ktor's plugin surface drifted across 2.x/3.x.
  Sources are in the Gradle cache; read them.
- **Tests define correctness, not APIs.** Where Ktor behaviour is uncertain, write the assertion
  first and pick whichever hook makes it pass.
- **Prove guards in both directions.** A guard only ever seen passing is not a guard.
- Comments explain *why*, especially where the obvious approach is wrong.
- `:inspector-model` may depend only on kotlinx-serialization. Keep it that way.

---

## Reproducing the full stack locally

```bash
./gradlew :inspector-daemon:installDist
inspector-daemon/build/install/inspector/bin/inspector serve --data /tmp/demo &
./gradlew :sample:desktop:run -Dinspector.sample.autofire=true
open http://127.0.0.1:8099
```

Sessions land in `/tmp/demo/sessions/`; `/tmp/demo/latest` symlinks the newest.

---

## Key files

| Path | Why it matters |
|---|---|
| `docs/schema.md` | The data contract. Read before touching `:inspector-model`. |
| `docs/INTEGRATION.md` | Self-contained guide for integrating into a consuming CMP app. |
| `docs/implementation-plan.md` | Full build order, phases, acceptance criteria. |
| `api/inspector-public-api.txt` | Golden public API surface, asserted by both modules. |
| `inspector-core/.../InspectorPlugin.kt` | Capture hooks, `CallState`, per-attempt logic. |
| `inspector-core/.../BodyCapture.kt` | The tee. The byte-identical guarantee lives here. |
| `inspector-model/.../Filter.kt` | Filter grammar, frozen for v1. |
| `scripts/check-release-clean.sh` | Production-safety enforcement. |
| `scripts/render-web-ui.js` | The only check the web UI has; run it after touching `web/`. |
| `inspector-daemon/.../SessionRepository.kt` | Every archive read. The MCP tools wrap this. |
| `inspector-daemon/src/main/resources/web/` | The web UI. No build step, no dependencies. |
