# Integrating Inspector into a Compose Multiplatform app

Self-contained guide. You do not need to have read anything else about this project.

**Inspector** is a network debugger for CMP apps that use Ktor. Once integrated you get a small
draggable pill floating over your app showing the most recent call (`GET /users · 200 · 143ms`),
which taps open into a full request/response inspector with filtering and copy-as-cURL.

---

## What you get, and what you don't

**You get:** every request made through Ktor `HttpClient`s you install it on — URL, method,
headers, query, status, timing, request and response bodies. Redirects and retries appear as
separate rows so you can see the whole chain.

**You don't get:** traffic from anything that isn't your Ktor client. No third-party SDKs
(Firebase, analytics, image loaders with their own clients), no WebView traffic, no native iOS
`NSURLSession` calls. No WebSocket or SSE frames.

**It must never ship to production.** Section 3 is not optional — it is how that is enforced.

---

## 1. Prerequisites — check these first

Version alignment is the single most common cause of integration failure, and the errors it
produces (klib/metadata mismatches) don't point at the real cause. Check before touching anything:

| Requirement | Value |
|---|---|
| Kotlin | **2.3.21** |
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

No publishing or artifact repository is needed.

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

You should see only `inspector-noop` and `inspector-noop-ui`. If you see `inspector-core`, the
property isn't reaching this module and release builds would ship capture code.

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

That's the entire integration. No `Context` to thread through, no platform-specific code, no
manifest entries, no permissions.

---

## 5. Verify

Run your app, trigger a network call, and look for a small pill near the left edge.

- **Tap** it → full inspector list
- **Drag** it → moves, snapping to the nearest edge
- **Long-press** it → collapses to a dot; tap the dot to restore
- In the list, tap a row → Overview / Request / Response tabs, plus **copy cURL**

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

## 6. Configuration

Defaults are sensible; you likely need none of this.

```kotlin
Inspector.init(
    InspectorConfig(
        ringBufferMaxBytes = 8L * 1024 * 1024,   // in-memory budget, evicts oldest
        bodyCaptureMaxBytes = 256 * 1024,        // per-body cap; larger bodies are counted only
        captureContentTypes = listOf(            // everything else gets a byte count, no buffer
            "application/json", "text/", "application/xml",
            "application/x-www-form-urlencoded", "application/problem+json",
        ),
        redaction = Redaction.Off,
    )
)
```

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

## 7. Performance

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

## 8. Troubleshooting

**Klib / metadata / "was compiled with an incompatible version of Kotlin"**
Version mismatch. Check section 1. This is the most likely failure by a wide margin.

**`Unresolved reference: Inspector`**
The composite build isn't substituting. Check the `includeBuild` path is absolute and correct,
and that the dependency coordinates are exactly `dev.inspector:inspector-core:0.1.0-SNAPSHOT`.

**Compose compiler plugin version conflicts**
Your app must be on Compose Multiplatform 1.11.x with the Kotlin Compose compiler plugin at
Kotlin 2.3.21.

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

**Redirects show as one row instead of a chain**
Some Ktor engines follow redirects internally, below the level the inspector observes. Report
which engine you use (`ktor-client-okhttp`, `ktor-client-darwin`, `ktor-client-cio`) — this is a
known open question.

---

## 9. What to report back

Useful feedback for the next phase:

1. **Your Ktor engine per target** — settles the redirect-chain question.
2. **Whether the overlay looks right on a real phone.** It has been verified on desktop only; it
   has never been seen on an Android or iOS device.
3. Anything that felt slow, any body that came back wrong, any call that didn't appear.

---

## 10. What's coming

Phase 2 adds a host-side daemon: your app streams transactions over a WebSocket to your Mac,
which archives every session to `~/.inspector/sessions/<timestamp>_<app>_<device>/` as readable
JSONL and serves a web UI. That's what makes "see my app's traffic in a window on my laptop"
work, plus lets an AI agent read a whole session. It needs no change to the integration above —
one extra module and one line.
