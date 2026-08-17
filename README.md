# Inspector

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
| **4a** | OkHttp capture, for SDKs that own their transport (Auth0, Retrofit, Coil) | ✅ |
| **4c** | Proxy capture — iOS `URLSession`, WebViews, opaque SDKs | ⬜ not started |

**167 tests** across JVM, iOS simulator, Android host and the daemon. Used daily against a real
Compose Multiplatform app on Android.

Honest gaps: the overlay has been run on one Android device and desktop, never on iOS hardware; the
Auth0 adapter compiles against the real SDK but has not been run against a live tenant; and nothing
non-Ktor is captured on iOS.

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

> **Version alignment is the single most common integration failure.** Inspector is consumed as a
> composite build, so it compiles inside *your* build with *your* Kotlin version, and KMP metadata
> is not compatible across versions. If your app is on an older Kotlin, ask for Inspector to be
> re-pinned downward rather than upgrading your app — these versions were pinned to what was
> available, not to anything the code needs.

There is no `iosX64` target: Compose Multiplatform 1.11+ dropped the Intel simulator, so no CMP app
can target it.

---

## Quick start

### See it working, without touching your app

```bash
./gradlew :sample:desktop:run
```

Fire traffic with the buttons, then tap the pill. Drag to move it, long-press to collapse.

### Add it to your app

Full guide: **[`docs/INTEGRATION.md`](docs/INTEGRATION.md)** — versioned, with a changelog so an app
that integrated from an older copy can see exactly what changed.

The short version. In `settings.gradle.kts`:

```kotlin
includeBuild("/absolute/path/to/inspector")
```

Then three lines of code:

```kotlin
Inspector.init()                        // optional; defaults are fine

val client = HttpClient(engine) {
    Inspector.install(this)             // add last, after your other plugins
}

InspectorOverlay { App() }              // wrap your root once
```

No `Context` to thread through, no platform code, no permissions.

Redaction defaults to `Redaction.Off` — credentials are captured **verbatim**, because a debugger
that hides the auth header is useless when the bug *is* the auth header. Opt in with
`Inspector.init(InspectorConfig(redaction = Redaction.On()))`.

### Run the web UI

```bash
./gradlew :inspector-daemon:installDist
inspector-daemon/build/install/inspector/bin/inspector serve
```

Open **http://127.0.0.1:8099**. Starting, stopping, restarting, killing, the CLI, the archive layout
and troubleshooting are all in **[`docs/DAEMON.md`](docs/DAEMON.md)**.

---

## Documentation

| Doc | For |
|---|---|
| [`docs/INTEGRATION.md`](docs/INTEGRATION.md) | Adding Inspector to a consuming CMP app. Self-contained and versioned. |
| [`docs/DAEMON.md`](docs/DAEMON.md) | Running the daemon: start, stop, restart, kill, CLI, archive layout. |
| [`docs/schema.md`](docs/schema.md) | The data contract. Read before touching `:inspector-model`. |
| [`docs/implementation-plan.md`](docs/implementation-plan.md) | Full build order, phases, acceptance criteria. |
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

Not yet chosen.
