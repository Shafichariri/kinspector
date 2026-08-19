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
2. **Host daemon** — archives every session to `~/.inspector/sessions/…` as readable folders and
   serves a web UI at `http://127.0.0.1:8099`. Operations are in `docs/DAEMON.md`.
3. **Agents** — read `index.jsonl` directly, or query via the CLI or the MCP server.

## What it is not

- Not a process-wide inspector. It sees **Ktor traffic, plus any OkHttp client the app hands the
  interceptor to** — so Auth0/Retrofit/Coil are reachable on Android and JVM with a line of app
  code, but WebViews and native iOS `NSURLSession` calls are not. Deliberate scope; the universal
  answer is proxy capture (4c), which is not started.
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
| **4a** | OkHttp capture, for SDKs that own their transport | ✅ done |
| **4c** | Proxy capture — iOS `URLSession`, WebViews, opaque SDKs | ⬜ not started |

**259 tests, 0 failures** across JVM, iOS simulator, Android host and the daemon.

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
  marker dividers, session picker, detail pane, attempt chain, method classes, console errors. It
  also drives the sort toggle and asserts the rendered order reverses, restores, and that the
  row/divider sequence is an exact mirror. It clicks an endpoint chip and asserts three things:
  the row count narrows, the chip list *survives* (proving chips come from the unfiltered
  session), and exactly one chip goes active. It then drives the settings input to prove the cap
  applies and `0` hides them. `INSPECTOR_UI_SESSION=<id or substring>` targets a session other
  than the newest, which is otherwise whatever ran last on the machine.
- **Daemon stop and restart, against a live daemon** — not only the unit tests. An unheadered POST
  is refused 403 and the daemon survives; restart swapped one pid for another in about a second
  with the whole archive still served; stop released the port and left no `serve` process.
- **A live viewer receives body refs**, so bodies are fetchable without a page reload
  (`LiveTest`). This is the defect the first real user hit.
- **OkHttp capture, end to end.** The sample makes one call through a plain `OkHttpClient` with no
  Ktor anywhere in its path; it reaches the archive as
  `GET /json?via=okhttp → 200` with its body on disk and header case preserved. The app receives
  byte-identical bodies with and without the interceptor across json, gzip, binary, 4 MB chunked
  and empty.
- **The MCP server answers the acceptance question in three calls**, verified both as a unit test
  and by piping JSON-RPC frames into the built binary against the real recorded session.

### Not verified

- **The Auth0 adapter in `docs/INTEGRATION.md` §11 compiles but has never run.** It is now
  written against the real `auth0-android` 3.12.0 sources (read out of the Gradle cache) and
  compile-checked against that jar plus OkHttp 4.12.0 and Gson, so the types and the
  body-construction logic are right. What is unproven is behaviour against a live tenant.

- **The overlay has now been seen on a phone once**, and every one of the four things that came
  back was a real defect: it drew under the status bar and camera cutout, there was no way back to
  the app, and nothing but cURL could be copied. All four are fixed; **the fixes themselves have
  only been compiled and exercised on desktop**, where insets are zero and there is no back
  gesture, so the parts that matter most are still unverified on a device.
- There is still no Android app module and no Xcode project. UI code compiles for all targets.
- **Nobody has judged how the web UI *looks*.** It provably renders the right elements (see
  above), but no human has assessed spacing, colour or density. The browser pane is blocked from
  localhost by policy in this environment, so only a static snapshot has ever been produced.
- Redirect behaviour against engines other than CIO. Some engines follow redirects internally,
  which would collapse a chain into a single row. Unresolved for the target app's engine.

---

## Capturing what Ktor cannot see

Capture is a Ktor *client plugin*: it sees exactly the traffic flowing through an `HttpClient`
the app configured. SDKs that own their transport do not appear, and their absence reads as "no
traffic happened" — which is what the first real user hit, seeing app→backend calls but no Auth0
calls.

**4a — OkHttp capture. Done.** `Inspector.okHttpInterceptor()` in `:inspector-core`'s
`jvmAndAndroidMain`, recording into the same `Recorder` so rows are indistinguishable from Ktor's.
Covers every OkHttp-based library where the app controls the client. Verified end to end: the
sample app makes one non-Ktor call and it lands in the archive with its body.

It deliberately does **not** live in its own `:inspector-okhttp` module, which is what the
original plan said. A separate module cannot see `Recorder` or `Redactor` — they are `internal` —
so it would have forced either a permanent public recording API into existence or a second copy of
the redaction logic, and a second copy is how two capture paths quietly stop agreeing. The cost of
keeping it in core is a `compileOnly` OkHttp dependency on the jvm and android source sets, which
consumers never inherit.

Auth0 still needs a small adapter in *app* code, because `com.auth0.android` accepts a
`NetworkingClient` rather than an `OkHttpClient`. That belongs in `docs/INTEGRATION.md` §11, not
in the library — nothing here may depend on Auth0.

What reading `auth0-android` 3.12.0 actually established, so nobody has to re-derive it:

- `Auth0.networkingClient` is a public `var`; `NetworkingClient` is one method,
  `load(url: String, options: RequestOptions): ServerResponse`.
- `DefaultClient` keeps its `OkHttpClient` in an `internal` field, so there is no way to add an
  interceptor to the client Auth0 builds. Replacing `NetworkingClient` is the only seam.
- Replacing it **drops DPoP nonce retry**. `RetryInterceptor` and `DPoP.storeNonce` are both
  `internal`, so no external adapter can reproduce them. Harmless for bearer tokens, fatal for a
  DPoP tenant — which is why §11 says debug builds only, in bold, twice.
- `AuthenticationAPIClient`, `UsersAPIClient` and `MyAccountAPIClient` each read
  `auth0.networkingClient` **once, at construction**. Set it before building any of them.
- Auth0 declares OkHttp and Gson at compile scope, so the adapter needs no new dependency.

The sources jar is in the Gradle cache (`~/.gradle/caches/modules-2/files-2.1/com.auth0.android/`)
whenever the consuming app has resolved Auth0 — read it rather than guessing at the API.

**4b — report what we know we cannot see.** Even with 4a, some traffic is invisible and the
failure mode is silence. A session-level note listing hosts seen in connection logs but never
captured would fix that. Not designed; may not be worth it.

**4c — proxy capture (large, universal). Not started.** The daemon runs a local HTTP proxy and the
emulator or simulator is pointed at it. Catches everything, including native SDKs and WebViews,
and is the only mechanism that reaches iOS `URLSession`. HTTPS needs a generated CA in the
emulator's trust store, plus a `network_security_config.xml` opt-in on Android 7+ — acceptable,
since that file is already a debug-only artifact here. This is the mitmproxy model. **Do not start
it without the user explicitly choosing it**: it turns the tool from a library into a piece of
network infrastructure.

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

**`teeBody` also reports from `job.invokeOnCompletion`, and that is not belt-and-braces.** The
`finally` is not sufficient: `teeBody` launches into the *response's* scope, and in Ktor an
`HttpResponse` is a `CoroutineScope` tied to its call. Ktor discards intermediate responses — every
redirect hop, every retried attempt — by cancelling that scope, and a `launch` cancelled before it
is dispatched never runs its body, so the `finally` never runs either and **the hop produces no row
at all**. A unit test that cancels the scope around 200 tees saw 6 rows without the completion
handler and 200 with it. This was live for months and hid on a fast machine, surfacing only on a
2-core CI runner: the last hop always survived (the app reads it) while earlier ones vanished, so
`redirect_chain_produces_a_row_for_every_hop` failed with attempts 2 and 3 present and 1 missing.
Silent loss of exactly the rows "one row per attempt" exists to show. `TeeCancellationTest` guards
both directions; do not "simplify" the handler away.

The row that survives cancellation carries `BodyOmission.DISCARDED` and zero bytes. Both UIs check
the recorded reason **before** the byte count, because a discarded hop reports zero bytes and the
`totalBytes == 0` shortcut would otherwise render "empty" — asserting the one thing capture could
not determine.

**Saved responses are read, not teed — the `isSaved` branch in the `Send` hook is load-bearing.**
Ktor's `SaveBody` plugin lives in the *receive* pipeline, and the receive pipeline runs **inside**
`proceed` (`HttpClient` intercepts `HttpSendPipeline.Receive` to execute it). So by the time the hook
regains control, every non-streaming response is already a `ByteArray` and `rawContent` yields a
fresh reader per access. Capture reads one of those readers and hands the call back untouched.

Teeing there was a real bug. `HttpRequestRetry` sits **outside** this hook — `HttpSend` builds its
chain from `interceptors.reversed()`, and Inspector installs last, so it ends up innermost — and it
probes whatever call comes back with
`isSaved && rawContent.run { try { awaitContent() } finally { cancel() } }`. Against Ktor's saved
response that cancel discards a throwaway reader, which is what its own comment says it is for.
Against `replaceResponse { appChannel }`, which returned **one** captured channel on every access, it
destroyed the only channel the caller had left, and `HttpStatement.fetchResponse`'s own `call.save()`
then died with `ClosedByteChannelException`. Load-dependent, because it only did damage when the
cancel beat the tee to the bytes — so it surfaced as an intermittent CI failure in the burst test.
`SavedBodyTest` reproduces it deterministically with a 2 MB body; do not collapse the branch back
into a single tee.

The tee is still correct for streaming responses, and still necessary: there is nothing buffered to
re-read, and buffering a stream to inspect it is the cost the efficiency contract forbids. `isSaved`
being false is also exactly what short-circuits the cancel above, so the tee is safe there.

**Known limitation of the `isSaved` branch:** if the app registers a download progress listener
(`onDownload`), `BodyProgress` wraps `rawContent` in an observable channel, and capture reading a
second view makes that listener fire for the whole body twice. Ktor exposes no way to reach the
underlying saved channel — `DelegatedResponse.origin` and `DownloadProgressListenerAttributeKey` are
both internal — so this is not currently avoidable. It affects only apps using progress listeners,
and it doubles reported progress rather than corrupting the body.

**Replay applies edits before asking the app to sign.** A signing scheme typically covers the
method and path, so signing first and editing after produces a signature for a request that was
never sent. Doing it in the wrong order works fine for unedited replays, so it would pass every
test that did not specifically edit a path — the worst possible failure distribution.
`ReplayTest.an edited path is what gets signed` pins it.

**The signing hook asks for headers, not for a signature over bytes.** `ReplaySigner` takes a
method and a URL and returns headers. Inspector therefore never learns the canonical-string format,
the algorithm, or which headers are involved, which means replay works for any scheme and no part
of this codebase becomes a signing oracle. `:inspector-noop-stream` declares the same type and
parameter so a consumer's call site compiles under `-Pinspector=off`; the sample passes one
specifically to keep that parity honest at compile time.

**Capture records the port, and `url` omits it only when it is the default.** It used to be dropped
entirely: `RequestSnapshot` took `url.host`, which excludes the port, so a capture of
`127.0.0.1:8080` rendered as `http://127.0.0.1`. Every copied cURL for a non-default port was
silently wrong, and it went unnoticed for the whole life of the feature because the host was right
and only the port was missing. Found when replay could not connect to the sample's own demo server.
`port` is nullable so archives written before it existed still read.

**A guard that scans nothing must fail, not pass.** `check-release-clean.sh` used to treat a
missing `--paths` target as `skip (missing)` and still print `PASSED: no capture code found in 1
artifact(s)` with exit 0. It also `cd`s to its own repo root, so a *relative* path from a consumer's
build directory resolved against Inspector and missed every time — the two combined into a release
guard that green-lit a build it had never looked at. Relative `--paths` now resolve against the
caller's directory, and any missing target is a hard exit 2. Reported from a consuming app. This
is the same principle the `--self-test` mode exists for: a broken detector reports everything
clean.

**MCP tools reject arguments they do not declare.** They used to ignore them. `list_sessions`
reports the field as `sessionId`, so calling another tool with `sessionId` left `session` absent,
defaulted to `latest`, and answered about a *different session* with no sign anything was dropped —
a confidently wrong answer to an agent that cannot tell. The allowlist is derived from
`descriptors()` so a new parameter cannot drift out of it, and the error names `session` explicitly
when it sees `sessionId`. Reported from a consuming app.

**Repeated-request detection lives in `:inspector-model`, and the web UI mirrors it.** Both rules
in `duplicateGroups` are load-bearing. A **different `callId` is required**, because redirect hops
and retry attempts share one and are a single logical call already shown as an attempt chain —
without that rule every retry lights up, which is noise *and* a false description. And **headers are
excluded from the key**, because a signed app puts a fresh nonce and signature on every request, so
a key including them would find nothing on exactly the apps this helps most. Timing uses `mono`,
never `ts`, per `docs/schema.md`. The key is method + URL + status + both byte counts, which
identifies rows that are *indistinguishable at row level* — a weaker claim than byte-identical
bodies, and the doc says so rather than overselling it. `app.js` carries a mirror of the same
algorithm; keep them in step.

**The web UI keeps an unfiltered copy of the session, and two features depend on it.**
`state.transactions` holds the rows the *daemon* matched against the current filter, so it is the
wrong source for anything describing the session as a whole. `state.allTransactions` is the whole
session; endpoint chips and duplicate detection both read it. Endpoint chips built from the
filtered view would collapse to the one chip you just clicked, making every other endpoint a dead
end, and duplicate highlighting would vanish under exactly the filter you applied to investigate
it. It costs one extra request per *session* — not per keystroke — and nothing at all when no
filter is set, because then the rows already are the whole session.

**Endpoint chips filter with an anchored glob, not a substring.** A chip labelled `profile` emits
`path:` + `*/profile`, because a bare `path:profile` term is a substring match and would also match
`/v3/accounts/profile/status` — a chip that does not mean what its label says. `globMatches`
anchors the trailing literal with `endsWith`, so the term matches only paths ending in `/profile`.
Chips are ordered by call count rather than recency on purpose: recency reshuffles the whole row on
every
request during live tail. Ties break on the most recent call, then alphabetically, so the order is
deterministic and `scripts/render-web-ui.js` can assert it.

**Backtick test names in multiplatform `commonTest` cannot contain commas.** Kotlin/Native rejects
them with `Name contains illegal characters: ","` while the JVM accepts them, so a targeted
`:inspector-model:jvmTest` passes and the full build fails on iOS. `:inspector-daemon` is JVM-only,
so its tests are exempt — do not churn them.

**There is no "start the MCP server" endpoint, and there should not be.** The MCP server speaks
stdio — `McpServer.run` blocks on `input.readLine()` and stops at EOF — so a process the daemon
spawned would have no client on its pipes and would exit at once or hang holding an empty one. The
editor owns that lifecycle. The two operations that mean something are `POST /api/mcp/probe`, which
spawns a throwaway server, handshakes, reports the tool count and stops it, and killing a wedged one
through the ordinary peer kill so the editor respawns it. `McpControlTest` asserts the probe leaves
nothing behind.

**Peers are identified by argv token, never by command-line substring.** `PeerArgv.MAIN_CLASS`
is matched against `ProcessHandle` arguments by exact equality. Substring matching is what makes
the manual alternative dangerous: `pkill -f inspector` also kills the editor's MCP connection, and
`pkill -f "inspector.*serve"` kills it too, because the classpath contains `ktor-server-cio-*.jar`
and "server" contains "serve". Both traps are documented in `docs/DAEMON.md`; the token match is
what makes them unreachable from the UI. `PeerRegistryTest` asserts an `mcp` process whose
classpath carries that jar is still reported as `mcp`.

The kill endpoint **re-verifies process identity at kill time** rather than trusting the pid the
client sends back. A process can exit between listing and killing and have its pid reused, so a pid
alone is not evidence of what it identifies. Without that re-check the endpoint would be a
"kill any pid" facility on an unauthenticated loopback port. It also refuses the daemon's own pid —
`/api/server/stop` is the path that replies before shutting down.

**`rawContent` is `@InternalAPI`.** There is no public accessor for the undecoded body channel;
Ktor's own Logging plugin reads it the same way. Opted in explicitly, pinned to Ktor 3.5.0.
Revisit on upgrade.

**`InspectorBackHandler` is `expect`/`actual`, not Compose Multiplatform's `BackHandler`.** Do not
"simplify" it back. CMP's common `BackHandler` resolves a dispatcher owner from the composition
and calls `error(...)` when there is none — so on any host that does not provide one, installing
the overlay crashes the app at startup. That is an unacceptable failure mode for a debugging aid
that wraps somebody's entire root composable. It is also `@Deprecated` in 1.11.1, pointing at
`NavigationEventHandler` in navigationevent-compose, which is at `1.1.0-beta01` — this module does
not push a beta transitive dependency onto consumers. So: Android uses `androidx.activity`'s
handler (guaranteed by `ComponentActivity`), desktop and iOS no-op, because neither has a system
back gesture for a full-screen overlay.

**The daemon's control endpoints require `X-Inspector-Control: 1`.** Not ceremony. The daemon
listens on loopback with no authentication, so any page the developer has open can POST to
`127.0.0.1:8099`; a form post or a `no-cors` fetch would otherwise be an off switch for anyone's
web page. A custom header forces a CORS preflight, and this server answers none, so only its own
page gets through. Read-only endpoints are deliberately left open — `HttpMarkerPoster` and the
MCP tools post markers without it, and a marker is not a weapon.

**`ServerControl` is an interface because the real one ends the JVM.** The daemon tests run a
daemon inside the test JVM; a hardcoded `exitProcess` in the stop handler would kill the test
runner rather than fail a test. On restart the port is released *before* the replacement spawns —
the other order has the new process fail its own pre-flight bind and exit, leaving no daemon at
all.

**Method colours avoid the status hue family.** Green/amber/red belong to 2xx/4xx/5xx. An amber
PUT next to an amber 404 reads as "this row failed", which is the one thing a traffic list must
not get wrong. So reads are blue, POST green (it is not a status), PUT purple, PATCH amber only
because it is rare, DELETE red because destructive is what red should mean. The web UI's `--m-*`
tokens and `InspectorColors.forMethod` are the same palette on purpose — keep them in step.

The method renders as a **tinted badge**, not coloured text: `MethodBadge` in the overlay, `.method`
plus `.m-*` on the web. Both tint at 0.18 alpha of the text colour rather than filling solid with
white text — solid is fine for GET's blue and unreadable for PATCH's amber in the light theme, and
one rule that holds for all five beats five exceptions. The web tokens are therefore bare RGB
components (`106 169 255`), so `rgb(var(--m-get) / 0.18)` yields the tint from the same token;
`color-mix(… currentColor …)` was the first attempt and was dropped because a silent failure on an
older engine drops the whole `background` declaration and the badge with it.

**The list's display order is `orderedRows()`, and both rendering and `j`/`k` go through it.** If
navigation computes its own sort, `j` moves up the screen the moment newest-first is on. Marker
dividers are interleaved oldest-first and the whole sequence is then reversed, so a divider stays
attached to the same rows; `scripts/render-web-ui.js` asserts the sequence is an exact mirror.

**Overlay screens inset themselves; the host is not asked to.** `Modifier.inspectorScreen` applies
`background` *before* `windowInsetsPadding(safeDrawing)`, so colour bleeds edge to edge while
content stays clear of the status bar, cutout, nav bar and keyboard. Reversing that order leaves a
strip of the host app visible behind the status bar, which reads as a rendering bug. Non
edge-to-edge hosts report zero insets, so this is a no-op there rather than a double margin.

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

**OkHttp capture tees, it does not peek.** `Response.peekBody` looks like the obvious way to copy
a response body, and it blocks until the requested count arrives — on a `text/event-stream` or any
long-lived response that stalls the app until the cap fills. `TeeingResponseBody` forwards lazily
as the app reads instead. Observing a stream must not consume or delay it.

**`okhttp3.Headers.toMultimap()` lowercases every name.** Use the local `asMultimap()`, which
preserves the case as sent — otherwise an OkHttp row displays `authorization` where the Ktor row
for the same header displays `Authorization`, and the two paths feed one list.

**An OkHttp application interceptor does not see OkHttp's own headers.** `User-Agent`,
`Accept-Encoding`, `Host` and `Connection` are attached by `BridgeInterceptor`, which runs below
it. Do not write a test that identifies an OkHttp-originated row by its `User-Agent` — there
isn't one.

**`OkHttpClient` must be shut down in tests.** Each one owns a dispatcher thread pool and a
connection pool that outlive the test. Leaking them made *unrelated* Ktor test classes fail
intermittently: a 1 MB response arriving empty, a redirect chain losing a hop. Call
`dispatcher.executorService.shutdown()` and `connectionPool.evictAll()` in teardown.

**`ApiSurface` only sees classes it is named.** It reflects over an explicit `CONTRACT_CLASSES`
list, so a new public declaration is unguarded until it is added there. Top-level *extension*
functions need the Java-reflection fallback as well — Kotlin's `declaredMemberFunctions` and
`staticFunctions` both miss them, and the guard reports a file facade as having no API at all.
Prove any change to this file catches divergence in both directions before trusting it.

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
INSPECTOR_UI_SESSION=<id> node scripts/render-web-ui.js           # ... against a chosen session
./gradlew :sample:desktop:run                     # runnable reference app
./gradlew :inspector-core:jvmTest                 # capture integration tests
./gradlew :inspector-model:iosSimulatorArm64Test  # iOS
./gradlew :inspector-model:testAndroidHostTest    # Android host
./gradlew build -Pinspector=off                   # release swap
./gradlew :inspector-daemon:distZip -Pinspector.version=0.2.0   # the release zip, as CI builds it
```

Cutting a daemon release — the tag is the trigger, and the only thing that ships this way:

```bash
git tag v0.2.0 && git push origin v0.2.0
```

`.github/workflows/release.yml` builds the zip, unzips it, starts it and checks the web UI and the
MCP server both answer, and only then publishes. The library modules are *not* released — they are
consumed as a composite build, so a consumer needs this repo checked out. The daemon is the half a
teammate can use without it.

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

- **Pinned versions.** Kotlin 2.3.20, Ktor 3.5.0, Compose MP 1.11.1, AGP 9.2.1, Gradle 9.5.1,
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
| `docs/ACCESS.md` | Who can get Inspector and how, and the honest answer for someone who cannot. Update it if the distribution story changes — it is the only doc that answers "am I blocked". |
| `docs/DAEMON.md` | Running the daemon: start, stop, restart, kill, the CLI, archive layout, troubleshooting. Update it when a flag or command changes. |
| `docs/INTEGRATION.md` | Self-contained guide for integrating into a consuming CMP app. **Versioned** — it is handed to other teams as a file, so a reader cannot diff it against anything. Any change that affects a consumer bumps the version line at the top and adds a §13 changelog entry saying what they must *do*, not just what changed. |
| `docs/implementation-plan.md` | Full build order, phases, acceptance criteria. |
| `api/inspector-public-api.txt` | Golden public API surface, asserted by both modules. |
| `inspector-core/.../InspectorPlugin.kt` | Capture hooks, `CallState`, per-attempt logic. |
| `inspector-core/.../BodyCapture.kt` | The tee. The byte-identical guarantee lives here. |
| `inspector-model/.../Filter.kt` | Filter grammar, frozen for v1. |
| `scripts/check-release-clean.sh` | Production-safety enforcement. |
| `.github/workflows/release.yml` | Tag-triggered daemon release. Smoke-tests the zip before publishing, because packaging is what this job can break. |
| `scripts/render-web-ui.js` | The only check the web UI has; run it after touching `web/`. |
| `inspector-daemon/.../SessionRepository.kt` | Every archive read. The MCP tools wrap this. |
| `inspector-daemon/src/main/resources/web/` | The web UI. No build step, no dependencies. |
