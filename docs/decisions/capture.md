# Decisions — capture
Owned by `:inspector-core`: the Ktor plugin, the OkHttp interceptor, the tee and redaction. Imported by the `inspector-core` guide.

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

**`DROP_OLDEST` channels never fail a send.** `Recorder` and `StreamSink` both counted dropped
rows with `if (!queue.trySend(x).isSuccess) dropped++`. That branch is unreachable: a
`DROP_OLDEST` channel discards the oldest entry and returns success, so the counter read zero no
matter how far behind the consumer fell. Measured directly — 100 sends into a 4-slot buffer gave 0
`trySend` failures and 96 `onUndeliveredElement` callbacks. Both now report through
`onUndeliveredElement`, which is the only thing the channel tells about a discarded element. Any
new bounded queue here must do the same.

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

**Capture records the port, and `url` omits it only when it is the default.** It used to be dropped
entirely: `RequestSnapshot` took `url.host`, which excludes the port, so a capture of
`127.0.0.1:8080` rendered as `http://127.0.0.1`. Every copied cURL for a non-default port was
silently wrong, and it went unnoticed for the whole life of the feature because the host was right
and only the port was missing. Found when replay could not connect to the sample's own demo server.
`port` is nullable so archives written before it existed still read.
The labels the daemon builds for a call (the MCP `timeline` entry, the Markdown and HTML exports)
had the same defect after `url` was fixed, because they were assembled from `host` by hand; a call to
`127.0.0.1:9090` read `GET 127.0.0.1/json`. They now use `NetworkTransaction.hostPort`, which `url`
is built on too, so the default-port decision exists once. A label still leaves the query out.
`hostPort` also brackets an IPv6 literal, decided on read rather than at capture so archives
recorded before it read the same: OkHttp's `HttpUrl.host` gives `::1` and the interceptor stores it
as it comes, which made `url` read `http://::1:8080/v6`, while Ktor's `Url.host` keeps `[::1]`, so a
host already starting with `[` is not bracketed twice. `Ipv6CaptureTest` holds both paths to one
`url` against a real server on `[::1]`, and skips with the reason where there is no IPv6 loopback.
The session summary's `byHost` keys on `hostPort` too. The `host:` filter term does not: it still
matches the stored `host`, which differs by capture path for an IPv6 literal.

**`rawContent` is `@InternalAPI`.** There is no public accessor for the undecoded body channel;
Ktor's own Logging plugin reads it the same way. Opted in explicitly, pinned to Ktor 3.5.0.
Revisit on upgrade. The same upgrade must recheck `ControlHeaderCoverageTest`'s route walk, which
is public API but leans on the shape of Ktor's route tree (see the control-header paragraph in `daemon-and-mcp.md`).

**Redaction is OFF by default.** This is a debugging tool for debuggable builds whose capture
code cannot reach production. A debugger that hides the auth header is useless when the bug *is*
the auth header. Do not flip the default. `redacted[]` records what was stripped so consumers can
say "redacted" rather than "absent".

**Streaming request bodies are not buffered.** `ByteArrayContent`/`TextContent` are captured;
channel-backed uploads report `contentLength` only. Buffering a file upload to inspect it is the
exact cost the efficiency contract forbids.

**No eager `Redaction.Default`.** `On`'s default args read `DEFAULT_HEADERS` off the companion,
so constructing one during the companion's own init deadlocks `<clinit>`. Use `Redaction.On()`.

**A request's content type lives on the body, not the headers.** `HttpRequestBuilder.headers`
does not carry `Content-Type`; Ktor puts it on the rendered `OutgoingContent`. Read both, always.
Getting this wrong reports `reqContentType = null` *and* silently turns off request-body
redaction, because `Redactor.body` only touches bodies declared JSON.

**Never guess why a body is absent — read `reqBodyOmitted`/`resBodyOmitted`.** Those exist
precisely because a UI that guessed sent someone chasing a content-type bug that did not exist.
If a new code path can drop a body, give it a `BodyOmission` constant.

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
