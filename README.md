# Inspector

[![release](https://img.shields.io/github/v/release/Shafichariri/kinspector)](https://github.com/Shafichariri/kinspector/releases/latest)
[![maven-central](https://img.shields.io/maven-central/v/io.github.shafichariri/inspector-core)](https://central.sonatype.com/artifact/io.github.shafichariri/inspector-core)

A network debugger for **Compose Multiplatform** apps that use Ktor — the thing Wormholy is for
iOS, but cross-platform, archived to disk, and readable by an AI agent.

You get three surfaces, and you can take any subset:

| Surface | What it is | Costs you |
|---|---|---|
| **In-app overlay** | A draggable pill over your app (`GET /users · 200 · 143ms`) opening a full inspector | one module, one wrapper composable |
| **Web UI** | Your app's traffic in a browser on your Mac, every session archived to disk | one more module, plus a local daemon |
| **CLI + MCP** | Query sessions from a shell, or let Claude Code / Cursor / Codex read them directly | nothing in your app |

**It cannot reach production.** A Gradle property swaps every module for an API-identical no-op, and
a canary guard fails the build if capture code appears in a release artifact. Both are enforced in
CI — see [Production safety](#production-safety).

---

## Status

| Phase | Scope | |
|---|---|---|
| **0** | Schema, filter grammar, API contract, release guard | ✅ |
| **1** | Capture, ring buffer, redaction, overlay + inspector UI | ✅ |
| **2** | Host daemon, session archive, stream sink, web UI | ✅ |
| **3** | MCP server over the archive | ✅ |
| **4** | Signals — screens, view-model state and caches on the traffic timeline | ✅ |
| **4a** | OkHttp capture, for SDKs that own their transport (Auth0, Retrofit, Coil) | ✅ |
| **4b** | Report the traffic we know we cannot see | ⬜ not designed |
| **4c** | Proxy capture — iOS `URLSession`, WebViews, opaque SDKs | ⬜ not started |

**779 tests** across JVM, iOS simulator, Android host and the daemon. Used daily against a real
Compose Multiplatform app, on an Android emulator and the iOS simulator.

Honest gaps: the overlay has been run on one Android device, the iOS simulator and desktop, never on
iOS hardware — and everything added to it since 0.7.0 has only been exercised in an off-screen
render at 360dp and by driving its controls in tests, never on a phone. That is now the whole of
the signals work in the overlay: the payload viewer, the per-key history, the **now** panel and
pulling a provider on demand have between them never been touched by a thumb. The Auth0 adapter
compiles against the real SDK but has not been run against a live tenant, and nothing non-Ktor is
captured on iOS.

### One tag, two halves

The badges above are the current version of **everything**. A single tag publishes the daemon zip
to the Releases page *and* the seven library modules to GitHub Packages, at the same version —
there is no separate version per surface, and never has been.

**Maven Central takes one deliberate step.** A tag *stages* the artifacts there automatically, but
publishing is irreversible, so it stops short: the Portal validates the bundle and waits for a
human to press Publish. That means the Maven Central badge legitimately sits a version behind the
release badge until somebody does. If it stays behind, that button has not been pressed.

**1.0.1 signs the artifacts.** Every jar, klib, aar, POM and module file now ships with a
detached PGP signature and a javadoc jar beside it. No library source differs from 1.0.0 —
nothing under any `src/` directory — so there is nothing to do but bump the version if you want
the signatures.

**1.0.0 moved the group id.** The library is published as `io.github.shafichariri:…` and was
`dev.inspector:…` up to 0.9.1 — Maven Central verifies a `dev.*` namespace against the matching
domain, and `inspector.dev` belongs to somebody else, so that group was never going to be
claimable there. **Artifact ids and the Kotlin package did not change**: `inspector-core` is still
`inspector-core`, and `import dev.inspector.Inspector` still compiles. Three dependency lines,
no source edits.

What a release does **not** promise is that every surface changed in it. 0.9.1 was daemon-only —
not one line of Kotlin in it — while 0.9.0 was the unusual case of both halves at once, 0.8.0 was
library-only, and 0.6.0 was the reverse.
[`docs/INTEGRATION.md` §14](docs/INTEGRATION.md) says, per version, what changed and what you have
to do about it — that is the list to read before deciding whether to move.

| Half | Current version | How you get it |
|---|---|---|
| Daemon — web UI, archive, CLI, MCP | the badge above | a zip from [Releases](https://github.com/Shafichariri/kinspector/releases/latest), no account needed |
| Library — the seven modules | the same | GitHub Packages, which needs a token ([`docs/ACCESS.md`](docs/ACCESS.md)) |

---

## Requirements

| | | |
|---|---|---|
| JDK | **21** | toolchain for every module |
| Kotlin | **2.3.20** | must match your app's — see below |
| Compose Multiplatform | **1.11.x** | must match your app's |
| Ktor | 3.x (built against 3.5.0) | |
| Android SDK | `compileSdk` 36, `minSdk` 24 | for Android targets |
| Xcode | any recent | for iOS targets |
| Node | any recent | *optional*, only for the web UI smoke test |

Gradle comes from the wrapper — do not install it.

> **Version alignment is the single most common integration failure.** The published artifacts are
> compiled by Kotlin 2.3.20 and pinned to it, and KMP metadata is not compatible across versions.
> If your app is on an older Kotlin, ask for Inspector to be re-pinned downward rather than
> upgrading your app — these versions were pinned to what was available, not to anything the code
> needs.

There is no `iosX64` target: Compose Multiplatform 1.11+ dropped the Intel simulator, so no CMP app
can target it.

---

## Install

Three parts, and you can take any subset. Each is independent — the library works with no daemon,
the daemon runs with no app, the MCP server reads an archive with neither.

### Try it first, installing nothing

```bash
./gradlew :sample:desktop:run
```

Fire traffic with the buttons, then tap the pill. Drag to move it, long-press to collapse. This is
the whole overlay, running against a real Ktor client, with nothing else set up.

The same app builds for Android, which is where the overlay is actually meant to be looked at —
a phone screen is 360dp wide and a desktop window never is:

```bash
./gradlew :sample:android:installDebug
adb shell am start -n dev.inspector.sample/.MainActivity
```

It wires itself exactly as [`docs/INTEGRATION.md`](docs/INTEGRATION.md) tells a consuming app to,
debug-only cleartext config included, so it is the reference wiring as well as the demo.

And on iOS, through a checked-in Xcode project — open `sample/ios/iosApp/iosApp.xcodeproj` and run,
or from a terminal:

```bash
xcodebuild -project sample/ios/iosApp/iosApp.xcodeproj -scheme iosApp \
  -sdk iphonesimulator -destination 'platform=iOS Simulator,name=iPhone 17 Pro' build
```

The Swift is about twenty lines hosting one `UIViewController`. Everything you see — the app and
the inspector on top of it — is the same Kotlin the Android sample runs, which is the whole claim
a Compose Multiplatform overlay makes.

---

### 1. The library — capture and the in-app overlay

**You need:** nothing. From **1.0.1** the library is on Maven Central and resolves anonymously —
no account, no token, no repository block. Versions up to 1.0.0 are on GitHub Packages only, which
does require a classic token even for public packages; [`docs/ACCESS.md`](docs/ACCESS.md) has that
path.

```kotlin
// settings.gradle.kts — from 1.0.1, the whole of the setup
repositories { mavenCentral() }
```

Then depend on it, and add `inspector-stream` only if you want the web UI and the on-disk archive:

```kotlin
implementation("io.github.shafichariri:inspector-core:1.0.1")
implementation("io.github.shafichariri:inspector-ui:1.0.1")
implementation("io.github.shafichariri:inspector-stream:1.0.1")
```

Three lines of code:

```kotlin
Inspector.init()                        // optional; defaults are fine

val client = HttpClient(engine) {
    Inspector.install(this)             // add last, after your other plugins
}

InspectorOverlay { App() }              // wrap your root once
```

No `Context` to thread through, no platform code, no permissions.

**Do the debug-only swap before you write app code, not after** —
[`docs/INTEGRATION.md`](docs/INTEGRATION.md) §3. It is what keeps this out of your release builds,
and retrofitting it is harder than starting with it.

Redaction defaults to `Redaction.Off` — credentials are captured **verbatim**, because a debugger
that hides the auth header is useless when the bug *is* the auth header. Opt in with
`Inspector.init(InspectorConfig(redaction = Redaction.On()))`.

---

### 2. The daemon — web UI, session archive, CLI

**You need:** a JDK 21. No token, no GitHub account, no checkout.

```bash
gh release download --repo Shafichariri/kinspector --pattern '*.zip'
unzip inspector-*.zip
inspector-*/bin/inspector serve
```

The Releases page also works in a browser with no account at all. If you have this repository
checked out and are changing Inspector itself, build it instead:

```bash
./gradlew :inspector-daemon:installDist
inspector-daemon/build/install/inspector/bin/inspector serve
```

Open **http://127.0.0.1:8099**. It shows an empty archive until an app with `inspector-stream` in it
connects. Start, stop, restart, the CLI, the archive layout, deleting sessions and troubleshooting
are all in **[`docs/DAEMON.md`](docs/DAEMON.md)**.

---

### 3. The MCP server — let an AI agent read your sessions

**You need:** the daemon from step 2. Nothing changes in your app.

It speaks stdio, so your editor starts it — there is no server to run. Register it once with the
absolute path to the same launcher:

```bash
claude mcp add inspector -- /absolute/path/to/inspector/bin/inspector mcp
```

Cursor and Codex take the same command as JSON and TOML respectively —
[`docs/INTEGRATION.md`](docs/INTEGRATION.md) §10 has both, and the full tool list.

It reads the archive straight off disk, so it answers questions about old sessions with no daemon
running. Only `add_marker` and `request_signal` need a live one.

---

### Adding it to your app, properly

The full guide is **[`docs/INTEGRATION.md`](docs/INTEGRATION.md)** — versioned, with a changelog, so
an app that integrated from an older copy can see exactly what changed and what it must do about it.
Read §1 first: **version alignment is the single most common integration failure.**

---

## Documentation

| Doc | For |
|---|---|
| [`docs/ACCESS.md`](docs/ACCESS.md) | **Start here if you are new.** Getting Inspector: the token GitHub Packages requires, and what the daemon needs instead (nothing). |
| [`docs/INTEGRATION.md`](docs/INTEGRATION.md) | Adding Inspector to a consuming CMP app. Self-contained and versioned. |
| [`docs/DAEMON.md`](docs/DAEMON.md) | Running the daemon: start, stop, restart, kill, CLI, archive layout. |
| [`docs/SIGNALS.md`](docs/SIGNALS.md) | Why signals are shaped the way they are. Shipped in 0.3.0; kept as the design record. |
| [`docs/SIGNALS-CHECKLIST.md`](docs/SIGNALS-CHECKLIST.md) | What must be true of a build that records signals. Assertions only. |
| [`docs/REPLAY.md`](docs/REPLAY.md) | Design for request replay, re-signing, and daemon control. |
| [`docs/schema.md`](docs/schema.md) | The data contract. Read before touching `:inspector-model`. |
| [`docs/ROADMAP.md`](docs/ROADMAP.md) | What is next and what is deliberately not. The live list — start here. |
| [`docs/implementation-plan.md`](docs/implementation-plan.md) | Full build order, phases, acceptance criteria. A record of the original build, not a live plan. |
| [`AGENTS.md`](AGENTS.md) | Contributor and AI-agent brief: architecture, decisions that must not be "fixed", gotchas. |

`AGENTS.md` is canonical; `CLAUDE.md` and `.cursorrules` are committed symlinks to it, so every
assistant reads the same file.

---

## Modules

| Module | Targets | Role |
|---|---|---|
| `:inspector-model` | android, ios, jvm | Schema, wire protocol, filter grammar. Only depends on kotlinx-serialization. |
| `:inspector-core` | android, ios, jvm | Ktor plugin, OkHttp interceptor, ring buffer, redaction, sinks. |
| `:inspector-ui` | android, ios, jvm | Compose overlay pill and inspector screens. |
| `:inspector-stream` | android, ios, jvm | WebSocket sink to the daemon. |
| `:inspector-daemon` | jvm | Session archive, REST, web UI, CLI, MCP server. |
| `:inspector-noop`<br>`:inspector-noop-ui`<br>`:inspector-noop-stream` | android, ios, jvm | Identical public API, does nothing. Release builds. |

`:inspector-model` is shared between device and daemon on purpose: the schema types and the filter
parser are then literally the same code on device, in the web UI, the CLI and the MCP tools. A
filter you type in the app is a string you can hand an agent.

---

## Production safety

Two layers, because a convention is not a guarantee.

**1. Artifact swap.** `-Pinspector=off` substitutes the `:inspector-noop*` modules. Both sides are
verified against `api/inspector-public-api.txt` by `ApiParityTest`, which runs in *both* modules —
neither can drift without failing its own build.

```bash
./gradlew build -Pinspector=off
```

**2. Canary guard.** `:inspector-core` carries a canary string, and
`scripts/check-release-clean.sh` fails if it appears in a release artifact. The script reads the
canary from the Kotlin source instead of hardcoding it, so renaming the constant cannot quietly
turn the guard into a no-op.

```bash
./scripts/check-release-clean.sh --self-test   # asserts the canary IS detectable
./scripts/check-release-clean.sh               # asserts noop artifacts are clean
```

Run **both** in CI. The self-test proves the detector works; without it, a broken detector would
report every release build clean.

The daemon binds `127.0.0.1` only. There is no authentication and the archive holds unredacted
credentials by default, so that loopback bind is the security boundary — do not widen it.

---

## Building

```bash
./gradlew build                                   # every module, every target, all tests
```

Point Gradle at your Android SDK first if it cannot find it:

```bash
echo "sdk.dir=$ANDROID_HOME" > local.properties
```

Targeted tasks:

```bash
./gradlew :inspector-daemon:installDist           # build the daemon launcher
./gradlew :inspector-core:jvmTest                 # capture integration tests
./gradlew :inspector-model:iosSimulatorArm64Test  # iOS
./gradlew :inspector-model:testAndroidHostTest    # Android host
./gradlew :inspector-ui:jvmTest                   # overlay: prefix rules + a real render
./gradlew build -Pinspector=off                   # the release swap
```

The sample app doubles as the reference integration and as a traffic generator:

```bash
./gradlew :sample:desktop:run -Dinspector.sample.autofire=true   # scripted traffic, no clicking
```

The web UI has no Kotlin test behind it, so it has a jsdom harness that runs the *real* `app.js`
against a running daemon and reports what actually rendered:

```bash
npm install jsdom
node scripts/render-web-ui.js > /tmp/ui.html      # report on stderr, static snapshot on stdout
```

A healthy run reports non-zero rows and `errors : none`.

### Changing the public API

Deliberately high friction, because this surface is a contract:

```bash
# 1. change BOTH :inspector-core and :inspector-noop
# 2. regenerate the golden file:
./gradlew :inspector-core:jvmTest -Dinspector.api.regenerate=true
# 3. confirm both modules pass:
./gradlew :inspector-core:jvmTest :inspector-noop:jvmTest
```

Prefer adding parameters at the **end** of a data class. Inserting one in the middle changes
`componentN()` and breaks positional construction and destructuring in consuming apps.

---

## CI

[`.github/workflows/ci.yml`](.github/workflows/ci.yml) builds all modules and targets, runs the JVM,
iOS-simulator and Android-host test suites, runs both release-guard checks, and separately builds
the whole project with `-Pinspector=off`.

---

## Licence

[Apache License 2.0](LICENSE). Copyright 2026 Chafic El Hariri.

Apache rather than MIT for the explicit patent grant — this is a debugging tool that consuming
apps compile into their own builds, and the grant is the part that matters to anyone adopting it
inside a company.
