# Inspector

A network inspector for Compose Multiplatform apps using Ktor: an in-app overlay and inspector,
a host daemon that archives every session to disk, a web UI, and first-class access for AI
agents (files, CLI, MCP).

**Status: Phase 1 complete** — capture works end to end, with an in-app overlay and inspector.
Redirects and retries each produce their own row, bodies are teed without altering what the app
receives, redaction is a setting (off by default), and the release guard is enforced in CI.
Phase 2 adds the host daemon, session archive and web UI.

Integrating into your own app? See [`docs/INTEGRATION.md`](docs/INTEGRATION.md).

See [`docs/schema.md`](docs/schema.md) for the data contract and
[the implementation plan](docs/implementation-plan.md) for the full build order.

## Modules

| Module | Targets | Role |
|---|---|---|
| `:inspector-model` | android, ios, jvm | Schema, wire protocol, filter grammar. Depends only on kotlinx-serialization. |
| `:inspector-core` | android, ios, jvm | Ktor plugin, ring buffer, redaction, sinks. |
| `:inspector-noop` | android, ios, jvm | Identical API, does nothing. Release builds. |
| `:inspector-ui` | android, ios, jvm | Compose overlay pill + inspector screens. |
| `:inspector-noop-ui` | android, ios, jvm | Passthrough overlay. Release builds. |
| `:inspector-stream` | android, ios, jvm | WebSocket sink to the daemon. **Phase 2.** |
| `:inspector-daemon` | jvm | Session archive, REST, web UI, CLI, MCP. **Phase 2/3.** |

`:inspector-model` is shared between the device and the daemon on purpose: the schema types and
the filter parser are then literally the same code on device, in the web UI backend, the CLI and
the MCP tools. A filter you type in the app is the same string you hand an agent.

## Consuming app setup

Mode is a dependency choice, not a runtime flag:

```kotlin
// shared/build.gradle.kts
val inspectorOff = providers.gradleProperty("inspector").orNull == "off"

commonMain.dependencies {
    if (inspectorOff) {
        implementation("dev.inspector:inspector-noop:$version")
        implementation("dev.inspector:inspector-noop-ui:$version")
    } else {
        implementation("dev.inspector:inspector-core:$version")
        implementation("dev.inspector:inspector-ui:$version")      // internal mode
        implementation("dev.inspector:inspector-stream:$version")  // external mode
    }
}
```

| Mode | Depends on | Result |
|---|---|---|
| Internal only | core + ui | On-device inspector, no host, no history |
| External only | core + stream | Zero UI code in the app. Lightest. |
| Both | core + ui + stream | Overlay on device, archive on host |

Release CI passes `-Pinspector=off`. `sample/desktop` is wired exactly this way and doubles as
the reference integration.

Three lines is the whole integration:

```kotlin
Inspector.init()                                   // optional; defaults are fine

val client = HttpClient(engine) {
    Inspector.install(this)
}

InspectorOverlay { App() }                         // wrap your root once
```

Redaction defaults to `Redaction.Off` — full fidelity, credentials included, because a debugger
that hides the auth header is useless when the bug *is* the auth header. Opt in per run with
`Inspector.init(InspectorConfig(redaction = Redaction.On()))`.

## Try it

```bash
./gradlew :sample:desktop:run
```

Fire traffic with the buttons, then tap the pill. Drag to move it, long-press to collapse.

## Production safety

Two layers, because a convention is not a guarantee:

1. **Artifact swap** — `-Pinspector=off` substitutes `:inspector-noop`. Both modules expose an
   identical public surface, verified against `api/inspector-public-api.txt` by `ApiParityTest`
   running in *both* modules. Neither can drift without failing its own build.
2. **Canary guard** — `:inspector-core` carries a canary string;
   `scripts/check-release-clean.sh` fails if it appears in a release artifact. The script reads
   the canary from the Kotlin source rather than hardcoding it, so renaming the constant cannot
   turn the guard into a no-op.

```bash
./scripts/check-release-clean.sh --self-test
```

Run the self-test in CI alongside the real check. It asserts the canary **is** found in
`:inspector-core`, proving the detector works; without it a broken detector would report every
release build clean.

## Building

Requires JDK 21, Xcode (iOS targets), and an Android SDK with API 36.

```bash
./gradlew build
```

Tests run on all three platforms:

```bash
./gradlew build
./gradlew :inspector-model:iosSimulatorArm64Test :inspector-core:iosSimulatorArm64Test
```

Note there is no `iosX64` target: Compose Multiplatform 1.11+ dropped the Intel simulator, so no
CMP app can target it.

## Changing the public API

Deliberate friction, because this surface is a contract:

1. Change both `:inspector-core` and `:inspector-noop`.
2. Regenerate the golden file:
   ```bash
   ./gradlew :inspector-core:jvmTest -Dinspector.api.regenerate=true
   ```
3. Confirm both modules pass:
   ```bash
   ./gradlew :inspector-core:jvmTest :inspector-noop:jvmTest
   ```
