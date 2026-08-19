# Integrating Inspector into a Compose Multiplatform app

**Document version: v12 — 2026-08-19.**
Already integrated from an earlier copy? Go to **[§13 Changelog](#13-changelog)** first — it says
what changed and, for each version, what you actually have to do about it. Most upgrades are a
rebuild and nothing else.

Self-contained guide. You do not need to have read anything else about this project.

**Inspector** is a network debugger for CMP apps that use Ktor. It gives you two independent
surfaces, and you can take either or both:

| Surface | What you see | What it costs you |
|---|---|---|
| **In-app overlay** | A draggable pill over your app (`GET /users · 200 · 143ms`), tapping into a full inspector | one module, one wrapper composable |
| **Web UI** | Your app's traffic in a browser on your Mac, with every session archived to disk | one more module, plus running a daemon |

There is **no desktop viewer application**. The web UI is a page served by the daemon at
`http://127.0.0.1:8099`. (The `sample/desktop` project in the Inspector repo is a demo app, not
a tool — ignore it.)

Sections 1–5 give you the overlay. Section 6 adds the web UI and the archive. Do them in order;
the overlay working is how you know capture works before adding a second moving part.

---

## What you get, and what you don't

**You get:** every request made through Ktor `HttpClient`s you install it on — URL, method,
headers, query, status, timing, request and response bodies. Redirects and retries appear as
separate rows so you can see the whole chain.

**You don't get, by default:** traffic from anything that isn't your Ktor client — Auth0,
Firebase, analytics, image loaders with their own clients, WebViews, native `NSURLSession`. No
WebSocket or SSE frames. **Anything built on OkHttp can be added with one line, and Auth0 on
Android with one small class** (section 11); iOS-native transports cannot yet.

**It must never ship to production.** Section 3 is not optional — it is how that is enforced.

---

## 0. Getting Inspector

Inspector is consumed as a **composite build**: your Gradle build compiles its source, using your
Kotlin version. So you need the source tree on disk. It is not published to any artifact
repository, and there is no zip of the library that Gradle could resolve.

It lives in a private repository, `Shafichariri/kinspector`. Step zero is therefore access:

- **If you have access**, clone it anywhere and note the absolute path — §2 wires your build to it.

  ```bash
  git clone https://github.com/Shafichariri/kinspector.git
  ```

- **If you do not**, ask to be added to the repository. There is no workaround that gets you the
  library: no published artifact exists to fall back on, and the daemon zip on its own displays an
  empty archive forever, because every row comes from the library running inside your app.

The **daemon** — the web UI and session archive in §6 — travels separately, as a zip on the
repository's Releases page, and needs only a JDK 21. If you have cloned the repo you can build it
yourself instead; §6c covers both.

If you have the repository, `docs/ACCESS.md` is the longer version of this page, including what to
do about a teammate who cannot be given access.

---

## 1. Prerequisites — check these first

Version alignment is the single most common cause of integration failure, and the errors it
produces (klib/metadata mismatches) don't point at the real cause. Check before touching anything:

| Requirement | Value |
|---|---|
| Kotlin | **2.3.20** |
| Compose Multiplatform | **1.11.x** |
| Ktor | 3.x (3.5.0 is what Inspector is built against) |
| JDK toolchain | 21 |
| Android `minSdk` | ≥ 24 |
| Android `compileSdk` | ≥ 36 |
| Gradle | 9.x |

Inspector is consumed as a **composite build**, meaning it compiles inside your build using
*your* Kotlin version. KMP metadata is not compatible across Kotlin versions, so these must match.

**If your app is on an older Kotlin, do not upgrade your app to match.** Inspector's versions
were pinned to what happened to be available, not to anything the code requires. Ask for
Inspector to be re-pinned downward instead — that is the cheaper change.

Also confirm: your app builds and runs *before* you start. Do not debug two things at once.

---

## 2. Wire the build

No publishing or artifact repository is needed — but you do need the checkout from §0.

### `settings.gradle.kts`

```kotlin
includeBuild("/ABSOLUTE/PATH/TO/inspector")
```

Put this at the top level of the file, next to your other `include(...)` lines. Gradle
substitutes the `dev.inspector:*` coordinates below for the local projects automatically, because
the group and version already match.

### Your shared module's `build.gradle.kts`

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("dev.inspector:inspector-core:0.1.0-SNAPSHOT")
            implementation("dev.inspector:inspector-ui:0.1.0-SNAPSHOT")
        }
    }
}
```

Build once now and confirm it resolves before writing any code:

```bash
./gradlew :shared:compileKotlinJvm     # or whichever target you build fastest
```

---

## 3. Make it debug-only — do this before writing app code

Inspector ships a second set of modules with an **identical public API that does nothing**. A
Gradle property picks between them, so your app code compiles unchanged either way and release
builds contain no capture code at all.

Replace the dependency block from section 2 with:

```kotlin
val inspectorOff = providers.gradleProperty("inspector").orNull == "off"

kotlin {
    sourceSets {
        commonMain.dependencies {
            if (inspectorOff) {
                implementation("dev.inspector:inspector-noop:0.1.0-SNAPSHOT")
                implementation("dev.inspector:inspector-noop-ui:0.1.0-SNAPSHOT")
            } else {
                implementation("dev.inspector:inspector-core:0.1.0-SNAPSHOT")
                implementation("dev.inspector:inspector-ui:0.1.0-SNAPSHOT")
            }
        }
    }
}
```

Then **pass `-Pinspector=off` in every release build command** — your CI release job, your
`assembleRelease`, your iOS archive step.

Verify the swap actually works:

```bash
./gradlew :shared:dependencies --configuration commonMainResolvableDependenciesMetadata -Pinspector=off | grep inspector
```

You should see only the `inspector-noop*` modules. If you see `inspector-core`, the property
isn't reaching this module and release builds would ship capture code.

> Inspector also has its own canary guard (`scripts/check-release-clean.sh`) that greps built
> binaries for a marker string. Wiring it into your release CI is worth it — the Gradle property
> is a convention, that script is the guarantee.

---

## 4. Code integration

Three touch points.

### 4a. Initialise once, at startup

```kotlin
import dev.inspector.Inspector

Inspector.init()
```

Put this wherever your app does one-time setup. It is safe to call more than once.

### 4b. Install on your Ktor client

```kotlin
import dev.inspector.Inspector
import io.ktor.client.HttpClient

val client = HttpClient(engine) {
    // ... your existing plugins: ContentNegotiation, Auth, Logging, HttpRequestRetry ...

    Inspector.install(this)   // add this line, last
}
```

Install it **last** in the block. Do this for every `HttpClient` you want visibility into — a
client without this line is invisible to the inspector.

### 4c. Wrap your root composable

```kotlin
import dev.inspector.ui.InspectorOverlay

@Composable
fun Root() {
    InspectorOverlay {
        App()          // your existing root
    }
}
```

Wrap the outermost composable you own, inside your theme or outside it — either works, the
overlay carries its own colours deliberately so it stays readable over any screen.

That's the entire integration for the overlay. No `Context` to thread through, no
platform-specific code, no manifest entries, no permissions.

(The web UI in section 6 *does* need one Android manifest change — see 6d.)

---

## 5. Verify

Run your app, trigger a network call, and look for a small pill near the left edge.

- **Tap** it → full inspector list
- **Drag** it → moves, snapping to the nearest edge
- **Long-press** it → collapses to a dot; tap the dot to restore
- In the list, tap a row → Overview / Request / Response tabs
- **Getting back to your app:** system back, or `✕ close`. Back unwinds one screen at a time and
  only while the inspector is open, so your app's own back behaviour is untouched.
- **Copying:** `cURL` in the detail toolbar, plus a `copy` next to the URL, the error, the header
  block and each body. Each one shows `copied` for a moment to confirm.

Try a filter in the list's filter bar:

```
status>=400
path:/v2/users
slower:500ms
has:error
method:POST host:api.example.com
```

If you have a desktop target, run that first — it is the configuration most thoroughly verified.

---

## 6. Adding the web UI and session archive

Optional, and independent of the overlay. This is what puts your app's traffic in a browser on
your Mac and keeps every session on disk.

### 6a. One more module

Add it to **both** branches of the if/else from section 3:

```kotlin
if (inspectorOff) {
    implementation("dev.inspector:inspector-noop:0.1.0-SNAPSHOT")
    implementation("dev.inspector:inspector-noop-ui:0.1.0-SNAPSHOT")
    implementation("dev.inspector:inspector-noop-stream:0.1.0-SNAPSHOT")
} else {
    implementation("dev.inspector:inspector-core:0.1.0-SNAPSHOT")
    implementation("dev.inspector:inspector-ui:0.1.0-SNAPSHOT")
    implementation("dev.inspector:inspector-stream:0.1.0-SNAPSHOT")
}
```

`inspector-noop-stream` has the identical API doing nothing, so the startup code below compiles
unchanged in release builds — no `if (BuildConfig.DEBUG)` guards needed around your own wiring.

### 6b. Two lines at startup

```kotlin
import dev.inspector.stream.StreamSink
import dev.inspector.stream.defaultClientInfo

val stream = StreamSink(defaultClientInfo(appId = "com.example.app", appVersion = "1.4.2"))
stream.start()
Inspector.addSink(stream)
```

`defaultClientInfo` fills in platform, device name and OS version, and detects whether you are on
a simulator or emulator. The daemon host defaults correctly per platform too — `127.0.0.1` on the
iOS simulator and desktop, `10.0.2.2` on the Android emulator, which is how the emulator reaches
your Mac.

If no daemon is running, the sink stays disconnected and drops what it cannot send. Your app is
unaffected either way — this is never on your app's critical path.

### 6c. Run the daemon on your Mac

Either download it — no checkout, no Gradle, just a JDK 21:

```bash
gh release download --repo Shafichariri/kinspector --pattern '*.zip'
unzip inspector-*.zip
```

which gives you `inspector-<version>/bin/inspector`. Or build it, once, from the Inspector repo:

```bash
./gradlew :inspector-daemon:installDist
```

which gives you `inspector-daemon/build/install/inspector/bin/inspector`. Build from source if you
are changing Inspector itself — the launcher runs the *installed* copy, so a source change you have
not reinstalled will not appear. Otherwise the download is less to maintain.

Then, whichever launcher you have, whenever you want to watch traffic:

```bash
<launcher> serve
```

Open **http://127.0.0.1:8099**. Sessions are archived to `~/.inspector/sessions/`, oldest pruned
past 100 sessions or 300 MB.

The top bar has **restart** and **stop**. `restart` relaunches the daemon in place — the page
reconnects by itself and the archive is untouched. `stop` ends it, and the page says so instead of
looking idle; start it again with the same command above. Both refuse any request that does not
come from this page, so another browser tab cannot reach in and kill your daemon.

### 6d. Android: permit cleartext to the daemon

**Android 9+ blocks cleartext traffic by default, and the daemon connection is cleartext
`ws://10.0.2.2:8099`.** Without this the socket fails and nothing reaches the web UI. This is the
single most common reason an Android app records nothing.

Add a debug-only network security config. Create
`src/debug/res/xml/network_security_config.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <!-- The Android emulator's alias for your Mac's loopback. -->
        <domain includeSubdomains="false">10.0.2.2</domain>
    </domain-config>
</network-security-config>
```

And reference it from `src/debug/AndroidManifest.xml`:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <application android:networkSecurityConfig="@xml/network_security_config" />
</manifest>
```

Putting both under `src/debug/` means the release manifest is untouched — the exemption cannot
reach production, matching how the rest of the tool is gated.

If you prefer the blunt instrument, `android:usesCleartextTraffic="true"` in the debug manifest
works too, but it permits cleartext to *everywhere* rather than just your Mac.

### 6e. Note for physical devices

`10.0.2.2` and `127.0.0.1` only work on emulators and simulators. On a real phone the daemon is
not reachable at those addresses, so the overlay works but the web UI gets nothing. Physical
device support is deliberately out of scope for v1.

---

## 7. Configuration

Defaults are sensible; you likely need none of this.

```kotlin
Inspector.init(
    InspectorConfig(
        ringBufferMaxBytes = 8L * 1024 * 1024,   // in-memory budget, evicts oldest
        bodyCaptureMaxBytes = 256 * 1024,        // per-body cap; larger bodies are counted only
        captureContentTypes = listOf(            // everything else gets a byte count, no buffer
            "application/json", "text/", "application/xml",
            "application/x-www-form-urlencoded", "application/problem+json",
            "application/graphql", "application/x-ndjson",
            "application/javascript", "application/jwt",
        ),
        captureAllBodies = false,                // see below
        redaction = Redaction.Off,
    )
)
```

`+json` and `+xml` suffix types are captured without being listed, so `application/vnd.api+json`
and `application/hal+json` work out of the box.

### If a body says it wasn't captured

The detail pane names the actual reason, and the reason decides the fix:

| What it says | What happened | Fix |
|---|---|---|
| `content type X is not on the capture allowlist` | Working as configured | Add `X` to `captureContentTypes`, or set `captureAllBodies = true` |
| `no content type was declared` | The server sent no `Content-Type` | `captureAllBodies = true` |
| `streamed body, never held in memory` | A streaming upload | Nothing — buffering it is the one cost this tool refuses to impose |
| `empty` | There genuinely was no body | Nothing |

```kotlin
InspectorConfig(captureAllBodies = true)   // capture every content type, up to the byte cap
```

This is the same "show me everything" stance as redaction being off. Binary bodies survive intact
(they travel base64-encoded) and `bodyCaptureMaxBytes` still applies, so an image-heavy screen
costs at most that per response.

### Redaction is off by default

Credentials, tokens and passwords are captured and displayed **verbatim**. That is deliberate: a
debugger that hides the auth header is useless when the bug *is* the auth header, and capture
code cannot reach production.

Turn it on when you want it:

```kotlin
Inspector.init(InspectorConfig(redaction = Redaction.On()))                  // default denylists
Inspector.init(InspectorConfig(redaction = Redaction.On(
    headers = listOf("authorization", "x-customer-ssn"),                     // narrow it
)))
```

When on, redaction happens at capture time and never touches what your app receives — only what
the inspector stores.

### Markers

Drop a labelled point into the timeline to correlate UI actions with traffic:

```kotlin
Inspector.mark("tapped checkout")
```

---

## 8. Performance

The design contract is that your app never blocks on, waits for, or meaningfully allocates
because of the inspector:

- The capture path on your coroutine does a metadata snapshot and a non-blocking queue offer.
- Everything else runs on one dedicated background worker.
- The queue drops its oldest entry when full — the inspector loses rows before your app loses
  throughput.
- Bodies are capped at 256 KB and gated by content type; binary and large downloads are counted,
  never buffered.
- Release builds use the no-op modules, so the cost is zero rather than small.

Response bodies are teed, not consumed — your app receives byte-identical bytes. This is covered
by tests for 10 MB, gzipped, chunked, empty and binary responses.

---

## 9. Troubleshooting

**Klib / metadata / "was compiled with an incompatible version of Kotlin"**
Version mismatch. Check section 1. This is the most likely failure by a wide margin.

**`Unresolved reference: Inspector`**
The composite build isn't substituting. Check the `includeBuild` path is absolute and correct,
and that the dependency coordinates are exactly `dev.inspector:inspector-core:0.1.0-SNAPSHOT`.

**Compose compiler plugin version conflicts**
Your app must be on Compose Multiplatform 1.11.x with the Kotlin Compose compiler plugin at
Kotlin 2.3.20.

**Pill doesn't appear**
`InspectorOverlay` must wrap something that fills the screen. If your root is inside a
`Box`/`Surface` with constrained size, move the wrap outward.

**Pill appears but stays empty**
`Inspector.install(this)` is missing from the client actually making requests, or that call path
uses a different `HttpClient`.

**Pill is hidden behind a dialog or a native screen**
Expected. The overlay lives inside your Compose hierarchy, so it cannot draw above Android
`Dialog`s or native iOS view controllers presented over Compose. A real floating window is a
possible later addition — say if you need it.

**Web UI shows no sessions**
Your app never connected. Work through these in order:

1. **Look at your app's log.** The sink prints `inspector: connected to daemon at …` on success,
   or `inspector: cannot reach daemon at … — <reason>` with the actual exception. That line
   usually names the problem outright.
2. **Android: cleartext.** See 6d. This is the most common cause by a wide margin, and it fails
   silently without the log line above.
3. **Is the port free?** `lsof -nP -iTCP:8099 -sTCP:LISTEN`. If something else holds 8099,
   `inspector serve` fails to bind — check its output rather than assuming it started.
4. **Check the daemon's console.** It prints `inspector: started session …` on every connect.
   Nothing there means nothing reached it.
5. `stream.start()` was never called, or `Inspector.addSink(stream)` was missed.
6. You are on a physical device (see 6e).

**Web UI shows an old session that is not yours**
You are looking at an archive from a different daemon run. Check which directory it is using —
the daemon prints `inspector: archive at …` on startup, and `--data DIR` overrides it.

**Web UI shows a session but no new traffic**
Traffic captured while the daemon was down is not backfilled — it stays in the device ring buffer
only. Fire a fresh request.

**Redirects show as one row instead of a chain**
Some Ktor engines follow redirects internally, below the level the inspector observes. Report
which engine you use (`ktor-client-okhttp`, `ktor-client-darwin`, `ktor-client-cio`) — this is a
known open question.

---

## 10. Letting an AI agent read your sessions (MCP)

`inspector mcp` exposes the archive over the Model Context Protocol, so an agent answers
questions about a recorded session directly instead of you pasting logs. It reads the archive
straight off disk, so it works on old sessions with no daemon running. It needs **no change to
the app integration above**.

Tools: `list_sessions`, `session_summary`, `list_transactions(filter, limit, offset)`,
`get_transaction`, `get_body(id, side, maxBytes)`, `add_marker`. The last one needs a running
daemon, since a marker has to land in a session that is currently recording.

Register it once, using the absolute path to your launcher — whichever of the two from §6c you
ended up with. The examples below show the source-build path; if you downloaded the release,
substitute `/absolute/path/to/inspector-<version>/bin/inspector`.

```bash
claude mcp add inspector -- /Users/you/development/tools/inspector/inspector-daemon/build/install/inspector/bin/inspector mcp
```

**Cursor** — `.cursor/mcp.json`, or the global `~/.cursor/mcp.json`:

```json
{
  "mcpServers": {
    "inspector": {
      "command": "/absolute/path/to/inspector-daemon/build/install/inspector/bin/inspector",
      "args": ["mcp"]
    }
  }
}
```

**Codex** — `~/.codex/config.toml`:

```toml
[mcp_servers.inspector]
command = "/absolute/path/to/inspector-daemon/build/install/inspector/bin/inspector"
args = ["mcp"]
```

Then ask, in plain language: *"What failed after the 'tapped checkout' marker, and what did the
server return?"* The agent calls `session_summary`, filters with
`since:marker("tapped checkout") has:error`, and reads the one body that matters — three calls,
about two kilobytes of context.

Point it at a different archive with `--data /path/to/dir`, or a non-default daemon port for
`add_marker` with `--port N`.

---

## 11. Capturing traffic that never touches your Ktor client

`Inspector.install(...)` is a Ktor plugin, so it sees Ktor and nothing else. SDKs that own their
transport — Auth0, Retrofit, Coil — are invisible to it, and their absence looks exactly like
"no traffic happened".

For anything built on **OkHttp**, add one interceptor and its calls land in the same list, the
same archive and the same web UI, indistinguishable from Ktor's once recorded:

```kotlin
import dev.inspector.Inspector
import dev.inspector.okHttpInterceptor

val client = OkHttpClient.Builder()
    .addInterceptor(Inspector.okHttpInterceptor())   // add it, don't addNetworkInterceptor
    .build()
```

Available on Android and JVM only. No extra module and no new dependency: OkHttp is `compileOnly`,
so you keep whatever version you already have. Under `-Pinspector=off` the same call returns a
pass-through interceptor, so this line is safe to leave in shared code.

Three things behave differently from the Ktor path, all by design:

| | Ktor | OkHttp |
|---|---|---|
| Redirects and retries | one row per hop, shared `callId` | one row per call, `attempt = 1` — OkHttp retries *below* an application interceptor |
| Headers | as sent | as sent, **minus** the ones OkHttp adds later (`User-Agent`, `Accept-Encoding`, `Host`, `Connection`) — those are attached below this interceptor |
| Response body | decompressed | decompressed |

If you want a row per hop, register it with `addNetworkInterceptor` instead — you will then see
OkHttp's own headers, but bodies arrive gzipped.

### Auth0 on Android

`com.auth0.android` builds its OkHttp client internally and will not take one from you. Its
`DefaultClient` keeps that `OkHttpClient` in an `internal` field, so there is nothing to add an
interceptor to. What Auth0 *does* expose is the seam one level up: `Auth0.networkingClient` is a
public `var` of type `NetworkingClient`, a one-method interface.

So you supply your own `NetworkingClient` whose OkHttp client carries the interceptor. The class
below mirrors `DefaultClient` from **auth0-android 3.12.0** — same URL/body/header construction,
same stream ownership — with the interceptor added.

> Verified: this compiles against `com.auth0.android:auth0:3.12.0`, OkHttp 4.12.0 and Gson, with
> no dependency you do not already have (Auth0 brings OkHttp and Gson at compile scope). It has
> not been run against a live Auth0 tenant — read the two caveats below before adopting it.

Put it in `src/debug/`, or guard its use with `BuildConfig.DEBUG`:

```kotlin
package com.example.debug

import com.auth0.android.request.HttpMethod
import com.auth0.android.request.NetworkingClient
import com.auth0.android.request.RequestOptions
import com.auth0.android.request.ServerResponse
import com.google.gson.Gson
import dev.inspector.Inspector
import dev.inspector.okHttpInterceptor
import okhttp3.Headers.Companion.toHeaders
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * An Auth0 [NetworkingClient] whose OkHttp client carries the Inspector interceptor, so Auth0's
 * traffic lands in the same list, archive and web UI as the rest of the app.
 *
 * Mirrors `DefaultClient` from auth0-android 3.12.0. Debug builds only.
 */
class InspectedAuth0Client(
    connectTimeoutSeconds: Long = 10,
    readTimeoutSeconds: Long = 10,
    private val defaultHeaders: Map<String, String> = emptyMap(),
) : NetworkingClient {

    private val gson = Gson()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
        .addInterceptor(Inspector.okHttpInterceptor())
        .build()

    @Throws(IOException::class)
    override fun load(url: String, options: RequestOptions): ServerResponse {
        val urlBuilder = url.toHttpUrl().newBuilder()
        val requestBuilder = Request.Builder()

        // Auth0 puts a GET's parameters in the query string and everything else in a JSON body.
        if (options.method is HttpMethod.GET) {
            options.parameters
                .filterValues { it is String }
                .forEach { (key, value) -> urlBuilder.addQueryParameter(key, value as String) }
            requestBuilder.method("GET", null)
        } else {
            val body = gson.toJson(options.parameters).toRequestBody(APPLICATION_JSON_UTF8)
            requestBuilder.method(options.method.toString(), body)
        }

        val response = client.newCall(
            requestBuilder
                .url(urlBuilder.build())
                // Per-request headers win over defaults, matching DefaultClient.
                .headers(defaultHeaders.plus(options.headers).toHeaders())
                .build(),
        ).execute()

        // ServerResponse takes ownership of the stream and Auth0 closes it. Do not wrap this call
        // in `use {}` — closing here would hand Auth0 an already-consumed body.
        return ServerResponse(
            response.code,
            response.body!!.byteStream(),
            response.headers.toMultimap(),
        )
    }

    private companion object {
        private val APPLICATION_JSON_UTF8 = "application/json; charset=utf-8".toMediaType()
    }
}
```

Then install it where you build your `Auth0` instance:

```kotlin
val auth0 = Auth0.getInstance(clientId, domain).apply {
    if (BuildConfig.DEBUG) {
        networkingClient = InspectedAuth0Client()
    }
}
```

`AuthenticationAPIClient`, `UsersAPIClient` and `MyAccountAPIClient` all read
`auth0.networkingClient` and route every call through it, so all three become visible — and with
them `SecureCredentialsManager`, which renews tokens through an `AuthenticationAPIClient`. With
redaction off (the default) you see the real `client_id`, `code_verifier`, `refresh_token` and
returned JWTs, which is normally the point of looking.

**Order matters.** Those clients capture `networkingClient` once, when they are constructed. Set
it on the `Auth0` instance *before* you build any API client from it, or the ones built earlier
keep the default and stay invisible.

**Two caveats, both real:**

1. **You lose Auth0's DPoP nonce retry.** `DefaultClient` installs an internal `RetryInterceptor`
   that stores the DPoP nonce from each response and re-signs a request once when the server
   demands a fresh one. That class and `DPoP.storeNonce` are both `internal`, so no external
   `NetworkingClient` can reproduce them. If your tenant uses DPoP (sender-constrained tokens),
   this adapter will fail the calls that need a nonce retry. If you use ordinary bearer tokens —
   most integrations — this costs you nothing.
2. **You lose the browser leg.** `WebAuthProvider` hands off to a Custom Tab, and that traffic
   belongs to Chrome, not your process. You see the `/oauth/token` exchange that follows, not the
   `/authorize` page. Nothing can change that short of a proxy.

Both are reasons to keep this to debug builds, which the `BuildConfig.DEBUG` guard already does.

If you are on an Auth0 version other than 3.12.0, check `DefaultClient.kt` in that version's
sources jar before trusting the body-construction logic above; the `NetworkingClient` interface
itself has been stable, but the parameter-to-body mapping is the part worth re-reading.

### Still not covered

iOS `URLSession`, WebViews, and any SDK that hides its transport entirely. The universal answer
is proxy-based capture — the daemon running a local proxy with a generated CA that the simulator
trusts. That is a substantially larger piece of work and has not been started; see `AGENTS.md`
→ "What is next".

---

## 12. What to report back

1. **Your Ktor engine per target** — settles the redirect-chain question.
2. **Whether the overlay looks right on a real phone.** Verified on desktop only.
3. Anything that felt slow, any body that came back wrong, any call that didn't appear.
4. **If you wire up the Auth0 adapter** — whether it worked, and whether your tenant uses DPoP.

---

## 13. Changelog

Find the version you integrated from, then read downward. Everything below your row applies to you.

If your copy has no version line at the top, identify it by what it contains:

| Your copy | You have |
|---|---|
| Overlay only, no daemon or web UI | **v1** |
| Has a "web UI and session archive" section | **v2** |
| That section mentions Android **cleartext** / `network_security_config.xml` | **v3** |
| Has an **MCP** section and a `captureAllBodies` option | **v4** |
| Has an **OkHttp interceptor** section | **v5** |
| Has a real **Auth0 adapter** class in §11 | **v6** |
| Overlay insets itself, system back works, bodies are copyable | **v7** |
| Web UI has stop/restart buttons and coloured methods | **v8** |
| Methods are badges; web UI has a sort toggle | **v9** |
| §1 says Kotlin 2.3.20 | **v10** |

### v12 — 2026-08-19 (this document)

**Nothing to do.** No API, build or behaviour change. This version adds §0, which says where
Inspector comes from and what your options are if you have no access to the repository — if you are
reading this because you already integrated, you are past the problem §0 describes.

One thing worth knowing anyway: the daemon now ships as a downloadable zip on the repository's
Releases page, so a teammate who wants only the web UI no longer needs a checkout or Gradle. §6c
covers both ways. Building from source is unchanged and still correct.

### v11 — 2026-08-18

- **Request replay, with re-signing on the device.** The web UI can re-send a captured request.
  Requests whose headers are single-use — a timestamp, a nonce, a signature over them — cannot be
  replayed verbatim, and a host cannot regenerate them because the device key is non-exportable.
  So the host asks the app, over the connection it already has.

  Opt in by passing a `signer` to `StreamSink`. It is the **last** constructor parameter and
  defaults to null, so an existing `StreamSink(clientInfo)` call is unaffected and needs no change:

  ```kotlin
  StreamSink(
      client = defaultClientInfo(appId, appVersion, buildType),
      signer = { method, url ->
          // Your existing per-request header code. Return only what must be fresh;
          // the host merges it over the captured headers.
          deviceProof.headersFor(method, url)
      },
  ).start()
  ```

  `:inspector-noop-stream` declares the same `ReplaySigner` type and the same parameter, so this
  call site compiles in release configurations too — it is simply never invoked there. A release
  build has no daemon to ask, which is the point: a shipped binary must not carry a signing oracle.

  The contract is deliberately "headers for this request", not "sign these bytes". Inspector never
  learns your signing scheme, so this works whatever it is, and no part of Inspector becomes a
  signer. Edits are applied **before** your signer is called, so a signature covers what is
  actually sent.

- **Fixed: the port was never captured.** A request to `127.0.0.1:8080` was recorded with host
  `127.0.0.1` and no port, so `NetworkTransaction.url` — and therefore the **copied cURL** — pointed
  at the default port for the scheme. Anyone who copied a cURL for a service on a non-default port
  got a command that addressed the wrong one, silently. `NetworkTransaction` now carries
  `port: Int?`; it is null for default ports and for rows captured before this change, so older
  archives still read.

**Action:** rebuild. Nothing you already wrote changes. Add a `signer` only if you want replay of
signed requests; without one, replay still works for requests that need no fresh headers, and says
so explicitly when it cannot. **If you copy cURL commands for anything on a non-default port, the
ones you copied before this version were wrong** — recopy them.

### v10 — 2026-08-17

- **Corrected: §1 said Kotlin 2.3.21. The real pin is 2.3.20.** The version catalog pins 2.3.20 and
  the build resolves 2.3.20; the table had been wrong since v1. This is the one number in this
  document that most needs to be right, because §1 tells you it must match your app's and a
  mismatch surfaces as a klib/metadata error that never names the real cause.
- Also corrected in the repo's other docs: AGP is 9.2.1, not 9.3.1.

**Action:** almost certainly none. **If your app already builds against Inspector, your Kotlin
version is already compatible** — this was a documentation error, not a change to what the code
requires. It only matters when someone reads §1 to decide what to align to. If you pinned your app
to 2.3.21 *because this document said so*, and it builds, leave it; if it does not build, 2.3.20 is
the number to match.

### v9 — Method badges and sort order

- **Methods are badges, not coloured text**, in both the web UI and the overlay — a tinted chip in
  the method's colour. A hue change alone was too easy to miss at a glance, which is the only job
  this label has.
- **Sort order toggle in the web UI.** The button next to the counts flips newest-first and
  oldest-first; <kbd>o</kbd> does the same. The choice is remembered across reloads, live tail
  follows the newest row to whichever end it now lives at, and <kbd>j</kbd>/<kbd>k</kbd> follow
  what you see rather than a fixed direction.

**Action:** rebuild the daemon for the sort toggle, rebuild the app for the overlay badges.

### v8 — Daemon control from the web UI

- **Stop and restart the daemon from the web UI.** Two buttons in the top bar. `restart` relaunches
  the daemon and the page reconnects on its own; `stop` ends it and says so, rather than leaving a
  page that looks merely idle. This is the cure for the "three daemons, two of them serving
  nothing" problem — you no longer have to find the terminal that launched it.
- **HTTP methods are colour coded** in the web UI, the overlay list, the detail header and the
  pill — GET blue, POST green, PUT purple, PATCH amber, DELETE red, anything else muted. The
  palette deliberately avoids the status colours so a PUT never reads as a 4xx.

**Action:** rebuild the daemon to get the buttons — `./gradlew :inspector-daemon:installDist`.
Rebuild the app for the method colours in the overlay. Neither is required for the other.

### v7 — Overlay fixes from a real phone

First round of fixes from running the overlay on a real Android phone. All four were reported
from one screenshot, and all four are overlay-only — capture, the daemon and the archive are
untouched.

- **Fixed: the inspector drew under the status bar and the camera cutout.** The list and detail
  screens now inset themselves against system bars, cutout and keyboard. The background still
  bleeds edge to edge, so it looks intentional rather than clipped. Apps that are not edge-to-edge
  report zero insets and are unaffected.
- **Fixed: no way back to the app.** System back now unwinds the inspector one screen at a time —
  detail → list → closed — and only while it is open, so it never steals back from your app. On
  Android this uses `androidx.activity`'s back handler.
- **Fixed: nothing could be copied except cURL.** There are now copy buttons on the URL, the error,
  the header block, and the request and response bodies. Each confirms with `copied` for a moment,
  because on iOS and desktop nothing else tells you the tap registered.
- **Also:** the close button is now a filled `✕ close`, and detail has its own `✕` so leaving takes
  one tap from anywhere instead of two.

**Action:** rebuild. Android picks up `androidx.activity:activity-compose` transitively, which
your app already has — nothing to add.

### v6 — Auth0

- **Real Auth0 adapter** (§11). Replaced the "here is roughly the approach" placeholder with a
  complete `NetworkingClient` implementation, verified to compile against
  `com.auth0.android:auth0:3.12.0`. Documents the two things it costs you (DPoP nonce retry, the
  Custom Tab leg) and the construction-order trap.
- This changelog, and a version line at the top.

**Action:** none required. Adopt §11 only if you want Auth0 traffic captured.

### v5 — Capturing non-Ktor traffic

- `Inspector.okHttpInterceptor()` (§11): one line puts any OkHttp-based SDK's calls into the same
  list, archive and web UI. Android and JVM only. No new dependency — OkHttp is `compileOnly`, so
  you keep whatever version you already have, and under `-Pinspector=off` it returns a
  pass-through interceptor.

**Action:** rebuild. Optionally add `.addInterceptor(Inspector.okHttpInterceptor())` to your own
OkHttp clients.

### v4 — Body capture fixes, and MCP

This is the version that fixes bodies reporting themselves as absent when they had in fact been
captured. If your web UI is showing *"not captured — content type outside the capture allowlist"*
on bodies you know exist, this is your row.

- **Fixed:** the live stream to the web UI dropped body references, so a body already written to
  disk showed as missing in a live session. The archive was always correct; the live view was not.
- **Fixed:** the UI printed a guessed reason for an absent body. It now reports the reason the
  capture side actually recorded — see the table in §7, which is new.
- **Fixed:** request `Content-Type` was never read, because Ktor keeps it on the outgoing body
  rather than in the header map. This also silently disabled request-body redaction.
- **Widened** the default content-type allowlist, and `+json` / `+xml` suffix types
  (`application/vnd.api+json`, `application/hal+json`) are now captured without being listed.
- **New** `captureAllBodies = true` — capture every content type up to the byte cap (§7).
- **New** `inspector mcp` (§10): read your sessions from Claude Code, Cursor or Codex. Needs no
  change to the app integration.
- The daemon now fails loudly when port 8099 is already held, instead of starting a process that
  quietly serves nothing.

**Action:** rebuild the app, **and rebuild the daemon** (`./gradlew :inspector-daemon:installDist`)
— the live-view fix is daemon-side, so an old daemon keeps showing bodies as missing.

> One source-compatibility note: `captureAllBodies` was added to `InspectorConfig` *before*
> `redaction`, which shifts the positional parameters. If you construct the config with named
> arguments — as every example in this document does — nothing changes. If you construct it
> positionally, or destructure it, you get a compile error naming the type mismatch. Switch to
> named arguments.

### v3 — Android cleartext

- **Android 9+ blocks the daemon connection by default** (§6d). This was the single most common
  reason an Android app recorded nothing, and it failed silently.
- The stream sink now logs `inspector: connected to daemon at …` or
  `inspector: cannot reach daemon at … — <reason>`, so the failure names itself.

**Action:** add the debug-only `network_security_config.xml` from §6d if you have not already.
Without it, Android records nothing to the web UI.

### v2 — Web UI and session archive

- `inspector-stream` module, the daemon, the browser UI at `http://127.0.0.1:8099`, and the
  on-disk archive under `~/.inspector/sessions/`.
- `inspector-noop-stream`, so the startup wiring compiles unchanged in release builds.

**Action:** §6, all of it.

### v1 — Overlay

- In-app overlay, Ktor plugin, the `-Pinspector=off` release swap.
