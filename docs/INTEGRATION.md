# Integrating Inspector into a Compose Multiplatform app

**Document version: v31 — 2026-09-17.**
Already integrated from an earlier copy? Go to **[§14 Changelog](#14-changelog)** first — it says
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

### The short version

Enough to see traffic, if you would rather skim first and read properly after. Every line has a
section behind it.

```properties
# ~/.gradle/gradle.properties — once per machine, never committed. §2
gpr.user=your-github-username
gpr.key=ghp_yourClassicToken          # classic token, read:packages. Fine-grained returns 401.
```

```kotlin
// settings.gradle.kts, in dependencyResolutionManagement { repositories { … } }. §2
maven {
    url = uri("https://maven.pkg.github.com/Shafichariri/kinspector")
    credentials {
        username = providers.gradleProperty("gpr.user").orNull
        password = providers.gradleProperty("gpr.key").orNull
    }
}
```

```kotlin
// your module's build file. §2 — and check §1 first, version alignment is the usual failure
implementation("dev.inspector:inspector-core:0.7.0")
implementation("dev.inspector:inspector-ui:0.7.0")
```

```kotlin
Inspector.init()                        // optional; defaults are fine. §4
val client = HttpClient(engine) {
    Inspector.install(this)             // add last, after your other plugins. §4
}
InspectorOverlay { App() }              // wrap your root once. §5
```

Then **do §3 before you write any more app code.** It is the debug-only swap that keeps this out of
release builds, and retrofitting it costs more than starting with it.

For the web UI and the on-disk archive, add `inspector-stream` and run the daemon — §6.

---

## What you get, and what you don't

**You get:** every request made through Ktor `HttpClient`s you install it on — URL, method,
headers, query, status, timing, request and response bodies. Redirects and retries appear as
separate rows so you can see the whole chain.

**You don't get, by default:** traffic from anything that isn't your Ktor client — Auth0,
Firebase, analytics, image loaders with their own clients, WebViews, native `NSURLSession`. No
WebSocket or SSE frames. **Anything built on OkHttp can be added with one line, and Auth0 on
Android with one small class** (section 11); iOS-native transports cannot yet.

**You can also get app state** — which screen was up, what a state holder held, what was cached —
recorded on the same timeline as the traffic, if you add the few lines in section 12. That part is
opt-in and additive; skip it and everything else works exactly as described.

**It must never ship to production.** Section 3 is not optional — it is how that is enforced.

---

## 0. Getting Inspector

The library is published to **GitHub Packages**, so you do not need a checkout of Inspector —
§2 wires your build to the published artifacts, and that is the whole of it.

The repository, `Shafichariri/kinspector`, is **public** and Apache-2.0 licensed, so there is
nobody to ask for access.

What you do need is a **classic** GitHub personal access token carrying `read:packages`. GitHub
Packages requires an authenticated download *even for public packages* — there is no anonymous
route, and no visibility setting changes it. §2 says where the two lines go.

Clone Inspector only if you are **changing** Inspector. §2 covers that case too, at the end.

The **daemon** — the web UI and session archive in §6 — travels separately, as a zip on the
repository's Releases page, and needs only a JDK 21.

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

Inspector's artifacts are compiled by **Kotlin 2.3.20**, and KMP metadata is not compatible
across Kotlin versions, so yours has to match. A mismatch surfaces as a klib or metadata error
naming neither Inspector nor your Kotlin version.

**If your app is on an older Kotlin, do not upgrade your app to match.** Inspector's versions
were pinned to what happened to be available, not to anything the code requires. Ask for
Inspector to be re-pinned downward instead — that is the cheaper change.

Also confirm: your app builds and runs *before* you start. Do not debug two things at once.

---

## 2. Wire the build

### `settings.gradle.kts`

Add the repository, inside `dependencyResolutionManagement { repositories { … } }`:

```kotlin
maven {
    url = uri("https://maven.pkg.github.com/Shafichariri/kinspector")
    credentials {
        username = providers.gradleProperty("gpr.user").orNull
        password = providers.gradleProperty("gpr.key").orNull
    }
}
```

**GitHub Packages requires a token to download**, not only to publish, and that is true even of
public packages — there is no configuration that avoids it. So each developer adds this once, to
`~/.gradle/gradle.properties`, which is outside the repository:

```properties
gpr.user=their-github-username
gpr.key=ghp_theirClassicTokenWithReadPackages
```

The token needs the `read:packages` scope and nothing else. **It has to be a classic token** —
GitHub Packages does not accept fine-grained tokens, so one will return 401 no matter how you scope
it. **Do not commit it**, and do not put the token or any path in the file you check in: the point
of reading them from a Gradle property is that the committed build file is identical for everyone.

### Your shared module's `build.gradle.kts`

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("dev.inspector:inspector-core:0.7.0")
            implementation("dev.inspector:inspector-ui:0.7.0")
        }
    }
}
```

Build once now and confirm it resolves before writing any code:

```bash
./gradlew :shared:compileKotlinJvm     # or whichever target you build fastest
```

A 401 here means the token; a 404 usually means the repository, not the version — GitHub Packages
answers "not found" for a package you are not allowed to see.

### Only if you are changing Inspector: build against a checkout

A composite build compiles Inspector's source in place, so your edits show up in the consuming app
immediately, with no publish step. Consuming apps should not do this by default — it makes every
developer responsible for a second repository.

Clone it anywhere, then in `settings.gradle.kts`:

```kotlin
// The committed file is the same for everyone. The path comes from each developer's own
// ~/.gradle/gradle.properties (`inspectorPath=/where/they/cloned/it`) or INSPECTOR_PATH, so
// nobody's home directory ends up in version control, and anyone who has not set it simply
// keeps using the published artifacts.
val inspectorPath = providers.gradleProperty("inspectorPath")
    .orElse(providers.environmentVariable("INSPECTOR_PATH"))
    .orNull
if (inspectorPath != null) {
    require(file(inspectorPath).resolve("settings.gradle.kts").isFile) {
        "inspectorPath '$inspectorPath' is not an Inspector checkout."
    }
    includeBuild(inspectorPath)
}
```

The dependency lines above do not change. Gradle substitutes the `dev.inspector:*` coordinates for
the local projects automatically, matching on group and module name, and ignores the version you
asked for.

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
                implementation("dev.inspector:inspector-noop:0.7.0")
                implementation("dev.inspector:inspector-noop-ui:0.7.0")
            } else {
                implementation("dev.inspector:inspector-core:0.7.0")
                implementation("dev.inspector:inspector-ui:0.7.0")
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

> Inspector also has a canary guard, `scripts/check-release-clean.sh`, which greps built artifacts
> for a marker string. Wiring a check like it into your release CI is worth it — the Gradle
> property is a convention, a grep of the artifact is the guarantee.
>
> **That script needs an Inspector checkout**, because it reads the canary out of Inspector's own
> source rather than hardcoding it. If you consume the published artifacts, you do not have it.
>
> The same guarantee is a few lines of your own: grep your release artifacts for `dev/inspector/`
> and `dev.inspector.`, and fail the build if either appears. Those needles work on every target.
>
> **Do not grep for the canary string itself on iOS.** Kotlin/Native stores string literals as
> UTF-16, so an ASCII search for the canary finds nothing in an iOS binary *whether or not capture
> code is present* — a guard written that way passes forever and tells you nothing. The package
> path above is what actually catches it.

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

Each row is two lines. The first carries the method, anything unusual about the call, the size and
the status; the second carries the duration and then the path. A few things you may wonder about:

- **A bar above the list reading something like `api.example.com/v3/some-service/` with `8 of 9`.**
  Most apps talk to one host under one API version, so that front is the same on every row and the
  rows below show only the part that differs. It appears only when a session has such a prefix, and
  rows outside it keep their full path and name their own host.
- **A coloured stripe down the left edge** of any call that failed.
- **An amber duration** on anything that took longer than a second.
- **Words like `repeated` or `attempt 2`** on the first line — a request sent twice by separate
  calls, or a retry.

If a path is still too long to fit, it is truncated from the **front**: the tail is what identifies
an endpoint, so that is the part kept.
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
    implementation("dev.inspector:inspector-noop:0.7.0")
    implementation("dev.inspector:inspector-noop-ui:0.7.0")
    implementation("dev.inspector:inspector-noop-stream:0.7.0")
} else {
    implementation("dev.inspector:inspector-core:0.7.0")
    implementation("dev.inspector:inspector-ui:0.7.0")
    implementation("dev.inspector:inspector-stream:0.7.0")
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
your Mac, and `127.0.0.1` on a physical Android device, which reaches your Mac over `adb reverse`
(§6f). You do not pass a host.

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
`ws://…:8099`.** Without this the socket fails and nothing reaches the web UI. This is the
single most common reason an Android app records nothing.

Add a debug-only network security config. Create
`src/debug/res/xml/network_security_config.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <!-- The Android emulator's alias for your Mac's loopback. -->
        <domain includeSubdomains="false">10.0.2.2</domain>
        <!-- A USB device's own loopback, which `adb reverse` points at your Mac. See 6f. -->
        <domain includeSubdomains="false">127.0.0.1</domain>
    </domain-config>
</network-security-config>
```

Include both even if you only use one today. They cost nothing, and the failure when the right one
is missing looks exactly like the daemon not running.

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

### 6e. What the web UI shows

One tab per kind of thing the session recorded, because each kind reads differently.

| Tab | What it is |
|---|---|
| `network` | Traffic, with marker dividers, filters, endpoint shortcuts, and a detail pane carrying headers, bodies and copy-as-cURL |
| `all` | Traffic, signals and markers merged on the device clock — appears once the session has signals (§12), and the session opens on it |
| one per tag | A key list beside one key's detail, for `cache`, `screen`, `state`, and any tag Inspector has never heard of |

On `all`, `screen` and `state` are drawn as points, `cache` as spans showing how long a value was
held, and an unrecognised tag gets a generic lane. A **Now** panel in the rail answers what screen
was up and what was cached, whatever tab you are on, with an age counted against your device's own
timestamp.

A tag tab lists one entry per key — the cache key, or the signal name — with its freshness
and how long ago it was last seen. Selecting one shows its current value pretty-printed, with a copy
button, and every observation of that key underneath, so you can step back through what it held.
Filters are a key search plus a chip per facet the payloads actually vary on: a facet with one
value is not drawn, because a filter you can only leave on is not a filter. Where a provider has
been seen to answer, a **pull latest** button asks the app for a fresh snapshot.

Reading order — oldest first or newest first — is a rail control, and it applies to the key list
and to each key's history as well as to the traffic list.

The columns are filled from your payload — see §12d for the field names the tag browser reads.

**Sessions** can be deleted from the UI: the `✕` beside the session picker removes the one on
screen, and Settings → Sessions lists every session with a per-row delete and a **clear all**.
Both arm on the first click and fire on the second. A session the app is still writing to is never
deleted — pulling the folder out from under an open writer would lose the traffic on screen.

### 6f. A physical Android device, over USB

Works, with one command and no code change. The daemon still binds your Mac's loopback and
nothing else; what the cable gives you is `adb reverse`, which forwards a port on the *phone's*
loopback to the same port on the machine running adb:

```bash
adb reverse tcp:8099 tcp:8099
```

That is the setup. `defaultDaemonHost()` already returns `127.0.0.1` on hardware and `10.0.2.2` on
an emulator, so `StreamSink(defaultClientInfo(…))` is unchanged — you pass no host. You do need
`127.0.0.1` in the cleartext config from §6d.

Two things will each cost you a debugging session:

- **The forward does not survive a replug**, an `adb kill-server`, or the device dropping off and
  coming back. Run it again; it is idempotent, and `adb reverse --list` tells you whether it is
  there.
- **It is per-device.** With a phone and an emulator both attached, plain `adb reverse` fails with
  `more than one device/emulator`; use `adb -s <serial> reverse …`, from `adb devices`.

If nothing arrives, the app says why — on a device the message names `adb reverse` rather than
leaving you with a disconnected sink and no reason.

**A physical iPhone is still not supported.** There is no `adb reverse` for iOS, so the routes are
widening the daemon's bind to your network — which needs authentication the daemon does not have,
and the archive holds unredacted credentials by default — or a `usbmuxd` tunnel, which nobody has
built. The overlay works on an iPhone; the web UI and the archive do not.

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
        signals = SignalPolicy(),                // app-state capture; see §12h
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

A marker is visible on **both** surfaces. In the overlay it draws as a labelled rule across the
list, between the calls before it and the calls after, and every distinct label also appears as a
chip under the filter field — tapping one applies `since:marker("…")`, tapping it again clears.
The `mark` button in the overlay toolbar drops one too, so a marker made on a phone is visible on
that phone without a daemon.

Labels are yours to choose and nothing parses them, with one caveat worth knowing: the filter
grammar quotes labels and has no escape inside the quotes, so a label containing an **odd** number
of `"` characters cannot be written as a filter term. Such a marker still draws its rule; it just
gets no chip. Any label without quotes — which is all of them in practice — is fine.

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
The dependency is not on the compile classpath. On the published artifacts, check the coordinates
are exactly `dev.inspector:inspector-core` and that the `maven { … }` block from §2 is in
`dependencyResolutionManagement`, not in a `buildscript` block. If you are on a checkout instead,
the composite build is not substituting — check the `includeBuild` path is correct and points at
the repository root.

**`401 Unauthorized` or `404 Not Found` from `maven.pkg.github.com`**
401 is the token. Check it is a **classic** token — fine-grained ones are rejected by GitHub
Packages — that it carries `read:packages`, and that `gpr.user` is your GitHub username.
404, now that the repository is public, really does mean not found: check the coordinates and that
the version you asked for has been released. It no longer means "you cannot see this" — GitHub
Packages answers that way for packages you lack access to, which was the usual cause while the
repository was private.

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
6. **Android over USB: is the forward still there?** `adb reverse --list`. It does not survive a
   replug or an adb restart, so an empty list is the usual answer. See §6f.
7. **A physical iPhone.** Not supported — the overlay works, the web UI cannot. See §6f.

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

Tools for traffic: `list_sessions`, `session_summary`, `list_transactions(filter, limit, offset)`,
`get_transaction`, `get_body(id, side, maxBytes)`, `add_marker`.

`explain(id)` is the one to reach for when the question is **why** rather than **what**. It joins,
in one call, what the others make you assemble by hand: the screen the app was on when the call
started and how long it had been there, every attempt of a retried call, how often the same
endpoint was hit, the signals either side of it — and the calls that were *in flight at the same
moment*, which no other tool can tell you, because a call is an interval and everything else
treats it as the instant it began. Headers are not inlined; `get_transaction` still has those.

Tools for app state, if you record signals (§12): `timeline(filter, since, until, limit)` merges
traffic, signals and markers on the device clock; `current(tag)` gives the latest observation per
`(tag, name)` with its age; `list_signals(filter, limit, offset)` and `get_signal(id, maxBytes)`
mirror the transaction pair; `request_signal(tag, name)` pulls a fresh value from a live app.

`add_marker` and `request_signal` need a running daemon — one has to land in a session that is
currently recording, the other has to reach an app that is currently attached. The rest read the
archive off disk.

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

With signals recorded, *"why did the KYC submit fail?"* is answerable the same way:
`timeline(since: the marker)` to see the screen, the state and the failing call in order, then
`get_signal` for the state payload and `get_body` for the response. Also three calls.

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
→ "Capturing what Ktor cannot see".

---

## 12. Recording app state alongside traffic (signals)

**Optional, and additive.** If you skip this section entirely, everything you already have keeps
working exactly as before. Nothing below is required to upgrade.

Inspector answers *what went over the wire*. On its own it cannot answer *what your app was doing
at the time* — which screen was up, what your state holder held, what was in a cache. A **signal**
is an app-defined observation recorded on the same clock as a transaction, so both appear on one
timeline.

The payoff is a question that used to take inference: *"the KYC submit returned 502 — what was the
app doing?"* becomes one merged view showing the screen, the state, the cache age, and the failing
call in order.

### 12a. Emitting signals

Two calls, and Inspector never learns what your categories mean — `tag` and `name` are your words:

```kotlin
// Structured payload.
Inspector.signal(
    tag = "screen",
    name = "Checkout",
    data = Json.encodeToJsonElement(destination),
)

// Text payload — the common case, because Kotlin/Native has no runtime reflection and
// serialising arbitrary app state is not free.
Inspector.signal("state", viewModelName, text = state.toString())
```

Conventional tags are `screen`, `state`, `cache` and `session`, and the web UI gives each its own
lane. **Any other tag works identically** — `featureflags`, `bluetooth`, whatever your app cares
about. It archives, filters, merges into the timeline and reads over MCP the same way; it simply
renders in a generic lane.

### 12b. Emit freely — throttling is the library's job

**Do not throttle in your own code.** Values for one `(tag, name)` are conflated on Inspector's
capture worker, keeping the **last** value of a burst rather than the first, because in a rapid
sequence of state changes the one worth having is where the state settled.

That matters most for the state hook, which is a firehose by nature:

```kotlin
scope.launch {
    viewModel.state.collect { Inspector.signal("state", viewModelName, text = it.toString()) }
}
```

No throttling in that code, deliberately. If conflation lived in app code, every consuming team
would reinvent it and most would get it wrong the same way — dropping everything after the first
in a burst, which looks correct in a test and is useless in practice.

`signal()` itself only offers the observation to a bounded queue on your coroutine. A slow or
absent daemon costs dropped signals, never backpressure into your app.

### 12c. Navigation

If your destinations are `@Serializable`, their arguments come free:

```kotlin
LaunchedEffect(backStack) {
    snapshotFlow { backStack.lastOrNull() }
        .filterNotNull()
        .collect { destination ->
            Inspector.signal(
                tag = "screen",
                name = destination::class.simpleName.orEmpty(),
                data = Json.encodeToJsonElement(destination),
            )
        }
}
```

### 12d. Caches, and answering "what is in there *now*"

A push records what was true at a moment. A **provider** lets the host ask what is true now:

```kotlin
Inspector.signal("cache", "response", data = cache.debugDump())      // at ready
Inspector.registerProvider("cache", "response") { cache.debugDump() } // on demand
```

Your app owns `debugDump()`; Inspector never learns what a cache is. The provider is called off the
main thread and may suspend. If it throws, the host is told why rather than being left to time out.

#### Field names the tag browser reads

Inspector does not define what a payload contains — `tag` is yours and so is the payload. The web
UI's tag browser therefore reads a small set of **conventional field names** and omits what it does
not find, so any payload still gets a key and a value, and one that follows the convention gets the
facets and the full detail panel:

| Field | Type | Where it shows |
|---|---|---|
| `key` | string | the key in the list (falls back to the signal's `name`) |
| `storage` | string | under the key, and a filter chip — your word, e.g. `Memory`, `Disk` |
| `scopes` (or `scope`) | list of strings, or one string | under the key, and a filter chip |
| `expired` | bool | the freshness dot, and a filter chip — **tri-state**: absent means "not stated", and is drawn as unknown rather than live |
| `kind` | string | a badge, and a filter chip — e.g. `Written`, `Removed`, `Cleared` |
| `value` | any | the detail panel, pretty-printed and copyable |
| `payloadBytes` | number | size, beside the value and in the history |

A facet chip only appears when the payloads actually vary on that field: a filter with one
value is not a filter. Nothing here is required — a payload using none of these still gets a
row with its time and raw value.

Two payload shapes both feed that view, and emitting both is worth it:

- **A whole-cache snapshot,** `{ "items": [ … ] }`, each item using the fields above. This is what a
  provider answers with, and it expands to one key per entry.
- **A single entry,** the fields above at the top level, pushed as that entry changes.

Emit the second one. A cache that is only ever described at startup and on demand leaves the
timeline asserting an empty cache for the whole session — a cache row is an interval claim, true
from its `mono` until the next observation of the same key, so two observations hours apart is not
merely sparse, it is wrong. Name each entry's signal by a key that survives your own invalidation
bookkeeping, so one entry keeps one identity across a session.

One row per entry adds up, and the archive trims `cache` to its newest 500 rows per session when
the session closes. That is generous for most caches and is not for all of them; §12h says how to
raise it.

The two are told apart in the archive by `trigger`: `app` for a push, `request` for a pull. **This
distinction is load-bearing.** An agent handed a cache snapshot with no provenance will report it
as the current state of the cache — and if that snapshot was pushed at app start twenty minutes
ago, it has just given you a confidently wrong answer. The web UI badges every row, and the MCP
tool descriptions say so.

### 12e. Where this code lives

All of it belongs in your app's composition root, behind whatever gate you already use for
internal builds, and it compiles unchanged against `:inspector-noop` — see §3. No production module
should depend on Inspector; declare your own no-op-default port and install an Inspector-backed
implementation only where capture is wanted.

`registerProvider` in the no-op **discards** the lambda rather than storing it, so a release build
retains no reference to whatever your provider closes over.

### 12f. Reading them back

- **Web UI.** A session with signals opens on the merged timeline; sessions without them are
  unchanged. The "Now" panel answers "what screen, what's cached" at a glance.
- **Filter.** `tag:screen`, `name:Checkout`. One rule is worth knowing: *a term whose field does
  not exist on a row type excludes that row type*. So `status:500 tag:screen` matches **nothing**
  — `status` excludes signals and `tag` excludes transactions. Use `|` to span both:
  `status:500 | tag:screen`.
- **MCP.** `timeline` merges traffic, signals and markers by the device clock; `current` gives the
  latest observation per key with its age; `get_signal` fetches one payload; `request_signal` pulls
  a fresh value from a live app.

### 12g. Signal payloads are not redacted

`Redaction.On` covers headers, query parameters and JSON body keys. It does **not** touch signal
payloads. A session recorded with redaction on still archives your state dumps and cache snapshots
verbatim, including anything your app was holding — customer records, form contents, tokens in
state.

This is the same verbatim-capture stance as the rest of Inspector, and the same mitigation applies:
debug builds only, §3. The difference worth stating plainly is that signals carry *your domain*
data rather than wire data, so the blast radius is larger. Do not emit a payload you would not want
sitting in `~/.inspector` on your own machine.

### 12h. Tuning

```kotlin
Inspector.init(
    InspectorConfig(
        signals = SignalPolicy(
            minIntervalMs = 150,                 // conflation window per (tag, name)
            dropUnchanged = true,                // skip a byte-identical repeat
            maxPayloadBytes = 64 * 1024,         // per-payload cap; `bytes` still reports the truth
            ringBufferMaxBytes = 2L * 1024 * 1024,  // separate from the transaction budget
        ),
    )
)
```

The signal ring is budgeted **separately** on purpose: sharing one budget would let a single large
cache snapshot evict your whole network history, at exactly the moment you needed both side by
side.

Both `minIntervalMs` and `ringBufferMaxBytes` are starting guesses rather than measurements. If you
tune them against a real session, that is worth reporting back (§13).

**The archive has its own, separate cap.** Everything above bounds what the *library* holds in
memory and sends. What the daemon *keeps on disk* is trimmed per tag when a session closes:
`cache` and `state` keep their newest 500 rows, and every other tag is kept in full. So a signal
can be delivered, shown live, and still not be in the session you open tomorrow.

Raise it in `~/.inspector/config.json` on your Mac — it is daemon-side, so it is not in
`InspectorConfig` and does not need a rebuild of your app:

```json
{ "signalCaps": { "cache": 2000 } }
```

Overrides merge over the defaults, `null` uncaps a tag, and `DAEMON.md` §6 has the full rules.

---

## 13. What to report back

1. **Your Ktor engine per target** — settles the redirect-chain question.
2. **Whether the overlay looks right on real hardware.** It has been run on one Android phone,
   plus desktop, an Android emulator and an iOS simulator — never on an iPhone, which is where
   insets and the system back gesture are most likely to differ from what was tested.
3. Anything that felt slow, any body that came back wrong, any call that didn't appear.
4. **If you wire up the Auth0 adapter** — whether it worked, and whether your tenant uses DPoP.

---

## 14. Changelog

Find the version you integrated from, then read downward. Everything below your row applies to you.

Where an entry below says to rebuild the daemon with `./gradlew :inspector-daemon:installDist`,
the equivalent today is downloading the current release (§6c). The Gradle command still works if
you have a checkout; the download works either way.

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

### v31 — 2026-09-17 (this document)

**Nothing to do. The overlay row says more.**

- **A wall-clock time per row**, `HH:MM:SS` in the device's own zone, so a call can be lined up
  against logcat or a backend log without exporting anything. Seconds and no milliseconds — the
  web has the width for `.mmm` and a phone does not.
- **A repeated call now says how many and over how long** — `2× / 1.9s` instead of `repeated`,
  which is the part that separates one code path fetching twice from a poll or a retry storm. The
  count is calls, not rows: a repeat that swept up a retry still asked twice.

Arrives with the library, like v30.

### v30 — 2026-09-17

**Nothing to do. Markers you already create now show up in the overlay.**

`Inspector.mark("…")` has always reached the in-app inspector — it is what `since:marker(…)`
filters on there — but nothing drew it, so a marker was invisible on the surface that made it and
there was no way to discover which labels existed to type. Now: a labelled rule across the list
where the marker falls, and a chip per distinct label under the filter field that applies and
clears `since:marker("…")` on tap. §7 → Markers describes it.

Arrives with the library, not the daemon — the list lives in `:inspector-ui`, so a consumer who
rebuilds the daemon and leaves the coordinates alone gets none of it. Same release as v29's
`adb reverse` support.

### v29 — 2026-09-17

**If you test on a physical Android phone, you now can. Two things to do, both in §6f.**

Run `adb reverse tcp:8099 tcp:8099` with the phone on USB, and add `127.0.0.1` alongside
`10.0.2.2` in the cleartext config in §6d. No code change: `defaultDaemonHost()` returns
`10.0.2.2` on an emulator and `127.0.0.1` on hardware, so the `StreamSink(defaultClientInfo(…))`
line you already have is correct on both.

This needs the library release that carries it, not just a rebuilt daemon — the host choice is in
`:inspector-stream`. Until then §6f describes what is coming; the emulator path is unchanged and
keeps working.

Two related corrections while here:

- **Emulator sessions were being archived as `android-device`.** The detection matched strings a
  current AVD stopped reporting years ago, so every emulator session claimed to be a phone. Fixed.
  Sessions already in your archive keep the wrong label — nothing rewrites them. Nothing to do.
- **§6f used to say physical devices were out of scope.** For Android that is no longer true. For
  iOS it still is, and §6f now says why rather than only that.

### v28 — 2026-09-17

**Nothing to do. One dead cross-reference, fixed.**

§10 "Still not covered" pointed at `AGENTS.md` → "What is next", a heading that was renamed to
"Capturing what Ktor cannot see" without this pointer following it. No code, and no other change.

### v27 — 2026-09-16

**Released as 0.7.0. Bump your coordinates — a rebuild alone will not get you this one.**

**The first release since 0.3.0 where the library itself changed.** 0.4.0, 0.5.0, 0.5.1 and 0.6.0
were all daemon-only, and each said so: the coordinates were worth updating for tidiness and
nothing more. That is no longer true. The list lives in `:inspector-ui`, so this arrives through
the dependency — a consumer who rebuilds the daemon and leaves the coordinates at `0.6.0` gets
none of it.

Every snippet in this document now reads `0.7.0`. What is in it:

- **The overlay's list row, rebuilt** — the path has a line of its own, the shared prefix moves
  into a bar above the list, and truncation happens at the front. **v25 below** has the detail
  and the reasoning.
- **Corrections to this document** — see **v26 below**. No code.

The daemon and the web UI are unchanged since 0.6.0. Two fixes landed in `scripts/render-web-ui.js`,
which is repo tooling and ships to nobody.

### v26 — 2026-09-16

**Nothing to do. Two corrections to this document; no code changed.**

**§5 Verify now describes the list you are looking at.** It walked through the pill, the tabs and
the filter bar, and said nothing about the row itself — so the bar above the list, the partial
paths beneath it, the edge stripe and the flag words were explained only in v25 below, which is
addressed to people upgrading. Someone integrating for the first time had no way to find out what
they were seeing.

**§13 no longer says the overlay has never run on a physical device.** It has, on one Android
phone — which `README.md` and the v7 entry below have both said since. v14 claimed this sentence
was corrected at the time and it was not. What is still true, and now what §13 says, is that it
has never run on an iPhone.

### v25 — 2026-09-15

**Released as 0.7.0. Rebuild, and bump your coordinates to `0.7.0`. The in-app overlay's list
looks different.**

This is the **first library change since 0.3.0** — every release in between was daemon-only, so a
consumer pinned to older coordinates has been getting nothing new. That ends here: the list lives
in `:inspector-ui`, so this one arrives through the dependency, not through the daemon.

Nothing in your code or your build changes beyond the version.

**The path had about 22 characters and used to lose the rest.** On a 360dp phone the row spent
roughly half its width on the status dot, the method badge and the timings before the path got
any, then clipped what was left from the end — so `/v3/some-service/client-dashboard` and
`/v3/some-service/client-settings` both rendered as `/v3/some-service/client-` and the two rows
were indistinguishable.

Three changes, together:

- **The path has a line of its own.** The method, flags, size and status sit on the first line;
  the second carries the duration and then the path, with about 37 characters instead of 22. The
  row is no taller than before — it was already two lines, and the second one held the host.
- **The shared prefix is lifted into a bar above the list.** One app's traffic is mostly one host
  under one API version, and every row was restating it. The bar says
  `api.example.com/v3/some-service/` once, rows show what differs, and it names how many of the
  session it covers. It engages only when a session actually has one — four rows or more, on the
  busiest host, and never taking the last segment of any path. Rows outside it keep their full
  path and say which host they came from.
- **Truncation is from the front.** When a path still will not fit, the tail survives, because
  the tail is what identifies the endpoint.

Three smaller ones that came with it: a **3dp stripe** down the edge of any row that failed, so a
bad row is findable without reading; **durations past a second** take the warning colour; and the
space that opens up on the first line carries **`repeated`, `attempt 2`, an unexpected host or a
transport error**, which were previously crowded onto a line with the host.

The web UI is unchanged — this is the in-app overlay only.

### v24 — 2026-09-12

**Released as 0.6.0. Rebuild the daemon — the web UI is substantially different.**

The library modules are still **byte-for-byte unchanged from 0.3.0**; `git diff v0.3.0..v0.6.0`
over the seven of them and `api/` is empty. The coordinates for that release were `0.6.0`, worth
updating only if you liked them to match — the whole of it was on the daemon side.

Two releases' worth of web UI landed here, and none of it had a changelog entry of its own. What
you will notice, roughly in the order you will notice it:

- **Traffic is the tab a session opens on.** The merged view is still there, renamed **timeline**.
  It held 126 rows to 13 calls on a real session, which is the wrong place to start a network
  debugger.
- **A time axis above the list.** Activity across the session, a band showing which screen was on,
  marker flags. Drag across it to narrow every view below to that stretch; click to clear.
- **A waterfall tab** — calls grouped by the screen that was showing when each one started, with
  what that screen cost in calls, wall time and bytes. This is the only place traffic and screen
  signals are put together.
- **Runs of identical observations collapse.** One state holder emitted 48 adjacent rows differing
  only in a payload you cannot see from the row.
- **Long header values wrap.** A bearer token used to lay out as one unbroken 7000px line, push
  itself outside the detail pane, and give the page a horizontal scrollbar — so reading a header
  slid the whole layout sideways. It hit every authenticated request.
- **Controls belong to the view.** The filter box and endpoint chips are a toolbar above the list
  now, and absent on the tag browsers, where they never applied. `Now` is a one-line strip.
- **The detail pane has tabs, response body first.** It used to be the last of five sections,
  behind every header on both sides of the call.
- **Below 1200px the detail slides over the list** instead of squeezing it.
- **Empty panes summarise** the session or the tag, and every line in them is a way in.
- **Two "for AI" buttons** copy a paste for an agent: one call with its context, or the session —
  or the stretch, if you have narrowed it on the axis. Both carry the MCP calls that fetch more.

And `explain`, from v23 below.

Nothing in your app or its build changes.

### v23 — 2026-09-11

**Nothing to do. One new MCP tool, if you point an agent at your sessions.**

`explain(session, id)` answers "why did this call go wrong" in one call. Everything in it was
derivable before, and that was the problem: it took `get_transaction`, two windowed `timeline`
calls, a scan for the shared `callId`, and interval arithmetic nothing exposed at all.

```
explain(session: "latest", id: "56f60e95")
```

```jsonc
{
  "call":   { "id": "56f60e95", "method": "GET", "url": "…/variables", "status": 200, "ms": 359 },
  "screen": { "name": "KycReview", "arrivedMsBefore": 38 },   // the call fired 38ms after arriving
  "concurrent": [ … ],                                        // what was in flight alongside it
  "repeats": { "total": 2, "monos": [18, 73433] },
  "attempts": [ … ],                                          // every try, when it was retried
  "before": [ … ], "after": [ … ],                            // the signals either side
  "notes": [ "1 other call(s) were in flight while this one ran" ]
}
```

Three things worth knowing about the shape:

- **`concurrent` is the part nothing else could give you.** A call is an interval; every other
  tool treats it as the instant it started. This is what separates "slow" from "queued behind
  four others".
- **Headers are not inlined.** They were 3 KB of an 11 KB answer on a real call, and
  `get_transaction` already returns them. A note in the reply says so.
- **Empty fields are still present.** An empty `concurrent` means "nothing ran alongside this",
  which is a finding — an agent that saw no key at all could not tell that from a daemon that
  does not report overlap.

This is daemon-side. Rebuild the daemon; nothing in your app or its build changes, and the
library is untouched.

### v22 — 2026-09-11

**Nothing to do. Worth knowing the day an archived session is missing.**

Deleting a session used to be silent. The archive is the only copy and there is no trash, so a
session that vanished was indistinguishable from a bug in the daemon — and the hard question
afterwards was never *that* something went, it was **which path took it**.

Every removal now writes a line naming the session, its size and its cause:

```
inspector: deleted session 2026-08-17T05-57-58_app_dev_debug (412 KB, requested)
inspector: deleted session 2026-08-16T21-03-11_app_dev_debug (1180 KB, clear all)
inspector: deleted session 2026-08-14T08-22-40_app_dev_debug (904 KB, retention)
inspector: cleared 2 session(s), kept 1, freed 1592 KB
```

`requested` is `DELETE /api/sessions/{id}` and the `✕` in the UI, `clear all` is the sweep, and
`retention` is the daemon dropping the oldest to stay under `maxSessions` / `maxTotalMb`. The line
comes from the one place a session folder is removed, so no deletion path can be silent.

**If you run the daemon detached, keep its output** — `inspector serve > ~/.inspector/daemon.log
2>&1 &`. Nothing rotates it, and nothing else writes these lines. `DAEMON.md` §6 has the detail.

Released as 0.5.1, daemon-only again: the library modules are still byte-for-byte unchanged from
0.3.0.

### v21 — 2026-09-11

**Released as 0.5.0. Rebuild the daemon — and if you record per-entry `cache` signals, you
were losing them.**

The archive trims signal rows per tag when a session closes. `cache` was capped at 20, set when a
cache signal meant one whole-cache snapshot and 20 rows bought 20 points in time. Since v18 this
document tells you to emit a row per entry write, removal and scope invalidation instead — against
which 20 rows does not reliably span 20 distinct *keys*, so past the cap the cache tab stops losing
history and starts losing whole keys. It bound routinely rather than at an extreme.

**The default is now 500**, matching `state`. Nothing you emit changes and no call site moves.
Sessions already trimmed on disk cannot be recovered; re-record one if you were relying on it.

As in v20, **the library modules are byte-for-byte unchanged from 0.3.0** — this release is
entirely daemon-side, so a new daemon is the whole upgrade. The coordinates below now read
`0.5.0` if you prefer your versions to match; staying where you are gets you exactly the same
library.

The caps are also **configurable now**, which they were advertised as being and silently were not:
a `signalCaps` block in `~/.inspector/config.json` parsed, was discarded, and said nothing. It is
honoured, and `DAEMON.md` §6 documents it:

```json
{ "signalCaps": { "cache": 2000, "screen": 5000, "state": null } }
```

Overrides merge over the defaults, so naming one tag does not uncap the others; `null` uncaps a
tag, `0` means "do not archive this tag at all", and a negative number is reported and ignored.
Per-tag caps are a runaway guard for one chatty session — `maxSessions` and `maxTotalMb` are what
bound the disk.

### v20 — 2026-09-10

**Released as 0.4.0. Rebuild the daemon; the library needs nothing.**

Everything v18 and v19 describe is in this release. The coordinates in this document now read
`0.4.0`, but **the library modules are byte-for-byte unchanged from 0.3.0** — nothing in
`:inspector-core`, `:inspector-ui`, `:inspector-stream` or any no-op twin was touched, and
`api/inspector-public-api.txt` did not move. Bump the coordinate if you prefer your versions to
match; staying on `0.3.0` gets you exactly the same library.

What you do want is the **new daemon**, because everything in this release is on that side:

- The tab bar, and a view per tag — `network` and `all` are what `traffic` and `timeline` were.
- The cache view as a key list beside one key's detail, with the value pretty-printed rather than
  clipped, and every observation of that key underneath it.
- Ages that tick. They were measured against the newest observation in the session, which has no
  clock in it — a refresh never moved them and a pull moved every row at once.
- Session deletion, from the UI or from `DELETE /api/sessions/{id}` and
  `POST /api/sessions/clear`.

```bash
gh release download v0.4.0 --repo Shafichariri/kinspector --pattern '*.zip'
unzip inspector-0.4.0.zip
```

If you run the daemon from a checkout instead, `./gradlew :inspector-daemon:installDist` — and
note that the launcher runs the *installed* copy, so a web-UI change that is not reinstalled simply
will not appear.

### v19 — 2026-08-26

**Nothing to do.** There is now a **short version** at the top — the whole install as four code
blocks with section numbers beside them — for skimming before you read properly. The web UI's view
switch became a tab bar, and each tag got a view built for its own data.

`traffic` and `timeline` are now `network` and `all`, and every tag in the session gets a tab of
its own beside them — including one this build has never heard of, which is what the schema
already promised and the old two-button switch could not express.

The cache tab is no longer a flat table of every observation. It is a key list beside one key's
detail: the list answers *what is cached and is it fresh*, the panel answers *what is in this one
and what happened to it*, with the value pretty-printed instead of clipped to 160 characters.
"Latest per key" stopped being a checkbox and became the structure. The same view serves every
other tag, so `state` and `screen` are browsable the same way.

Two fixes worth knowing about if you read the UI closely:

- **Ages are wall-clock now.** The "Now" panel measured each row against the newest observation in
  the session, which has no clock in it — it could not tick, a refresh never moved it, and a pull
  moved every row at once because it shifted the reference point. Ages come from the signal's own
  `ts` and update every second. It is still your device's clock, so a simulator whose clock has
  drifted reports the drift.
- **Sessions can be deleted.** `DELETE /api/sessions/{id}` and `POST /api/sessions/clear`, both
  behind `X-Inspector-Control: 1`, with buttons for each in the UI. A session still being written
  is never deleted, and the `latest` alias is refused as a delete target rather than resolved.

### v18 — 2026-08-26

**Nothing to do, but §12d is worth two minutes if you record cache signals.**

The web UI has a **cache** tab on any session that recorded one: a table of what was cached and
when — storage, key, scope, expired, value — with filters, a "latest per key" collapse, and a
button that pulls a fresh snapshot from every cache provider the session has seen answer.

Nothing about the schema changed and no call site moves. What is new is written down rather than
required: §12d now lists the **field names** the table reads out of your payload (`key`,
`storage`, `scopes`, `expired`, `value`, `payloadBytes`). A payload using none of them still
renders; one that follows the convention gets every column.

The other half of §12d is a correction worth acting on. Pushing a cache snapshot only at startup
and on demand is not merely sparse — a cache row is an interval claim, true from its `mono` until
the next observation of the same key, so a session with two observations hours apart *asserts* the
cache was empty throughout. If your cache can tell you when it changes, emit a signal per entry as
it changes. §12d says how, and what to name them so an entry keeps one identity across a scope
invalidation.

### v17 — 2026-08-26

**Nothing to do.** v16 introduced signals in §12 but left the rest of the document describing
traffic only, which made the feature easy to miss if you were not reading §12 in particular. Four
places now mention it: the "what you get" summary, the §7 configuration reference, the §10 MCP
tool list, and a new §6e on what the web UI shows. No behaviour changed and no coordinates moved —
`0.3.0` is still current.

If you are on v16 and have already read §12, there is nothing new here for you.

### v16 — 2026-08-25

**Nothing to do.** Inspector can now record app state — which screen was up, what a state holder
held, what was in a cache — on the same timeline as your traffic. It is entirely opt-in: every
call site you already have compiles and behaves identically, and a session with no signals looks
and works exactly as it did before, including in the web UI.

If you want it, §12 is the whole of it: two calls at your composition root, and the library does
the throttling. Move to `dev.inspector:*:0.3.0` when you do — the version bump is because the
public API grew, not because anything changed under you.

Three things are worth knowing even if you skip the feature:

- **`InspectorSink` gained `onSignal`,** with a default no-op body. A sink you wrote compiles
  untouched. If you want your own sink to receive signals, override it.
- **Signal payloads are never redacted,** including in a session recorded with `Redaction.On`.
  They carry your domain data rather than wire data, so read §12g before instrumenting anything
  that holds customer information.
- **The `dropped` counter now works.** `Inspector.recorder.dropped` and `StreamSink.dropped` have
  read zero since they were written — a `DROP_OLDEST` channel returns success when it discards, so
  the check that guarded them was unreachable. If you have ever looked at that number and read it
  as "nothing was dropped", it was not telling you that. It is now.

### v15 — 2026-08-21

**Nothing to do.** Inspector is now a public, Apache-2.0 repository, so §0 no longer talks about
being granted access — there is nobody to ask. Everything you already configured keeps working
unchanged.

The token does **not** go away, and that is the part worth knowing before you tell a teammate "it's
public now, just add the dependency". GitHub Packages requires an authenticated download even for
public packages. The daemon zip is a genuine anonymous download; the library is not.

Also: **0.2.1** is the first release whose POM carries an Apache-2.0 `<licenses>` block, which
matters if your company runs a dependency scanner. `0.2.0` and earlier declare no licence at all,
and package metadata cannot be edited after publication — so if a scan flagged Inspector, moving to
0.2.1 is the fix, not an exemption.

### v14 — 2026-08-21

**If you took v13, re-read §3.** It pointed at `scripts/check-release-clean.sh` without saying the
script needs an Inspector checkout to run — which, now that the library is published, most
consumers no longer have. §3 now says so and gives the grep that replaces it.

Worth reading even if your release guard already passes, because it also says which needle *not*
to use: searching an iOS artifact for the canary string finds nothing whether or not capture code
is present, since Kotlin/Native stores string literals as UTF-16. A guard written that way passes
forever. Grep for `dev/inspector/` and `dev.inspector.` instead.

Also corrected: "What to report back" no longer says the overlay has only been seen on
desktop.

### v13 — 2026-08-19

**The library is published now. You can delete your `includeBuild` line.**

Inspector no longer has to be a checkout on every developer's machine. It is published to GitHub
Packages, so it is an ordinary dependency, and the file you commit no longer contains anybody's
home directory.

To switch, do §2: add the `maven { … }` block with credentials, put a `read:packages` token in
your own `~/.gradle/gradle.properties`, change the version on the two `implementation` lines from
`0.1.0-SNAPSHOT` to the release you want, and delete the `includeBuild`. Nothing in §3 onwards
changes — the debug-only swap, the code, the daemon and the MCP setup are all as they were.

**You do not have to switch.** A composite build still works and is still the right thing while
you are changing Inspector itself; §2's last subsection shows how to do it without committing a
path. What you should not do is keep a hardcoded absolute path in a shared repository, which is
what the old §2 told you to write.

One thing to know before you move: the published artifacts are compiled by Kotlin 2.3.20 and are
pinned to it, whereas a composite build compiled against whatever Kotlin *you* were using. If your
app is not on 2.3.20, see §1 — ask for a re-pin rather than upgrading your app.

### v12 — 2026-08-19

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
