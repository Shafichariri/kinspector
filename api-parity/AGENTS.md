# api-parity — agent guide

The reflection and golden-file machinery (`shared/`) and one parity test per twin pair (`core/`,
`stream/`, `ui/`). Each directory is compiled into the `jvmTest` of the two modules it guards, so both
halves of a pair assert the same golden file. The `*IosApiParityTest` classes compiled there run only in
the root build's `iosApiTest` task, against the pair's iOS golden file.

Every pair runs two tests in both of its modules: its golden file, and a completeness test that fails
when a class outside the pair's contract list holds public API. Both also run in each module's
`testAndroidHostTest`, against the same golden file, which only `jvmTest` regenerates. Each module declares
the golden file as an input of both tasks, and `structure/twins.txt` names it on the pair's line.

Read before changing anything here: `docs/decisions/twins-and-api.md`.

@../docs/decisions/twins-and-api.md

Changing the public API is deliberately high-friction, because it is a contract:

```bash
# 1. change BOTH :inspector-core and :inspector-noop
# 2. regenerate:
./gradlew :inspector-core:jvmTest -Dinspector.api.regenerate=true
# 3. confirm both modules pass:
./gradlew :inspector-core:jvmTest :inspector-noop:jvmTest
```

The stream pair has its own golden file, and the same friction:

```bash
# 1. change BOTH :inspector-stream and :inspector-noop-stream
# 2. regenerate (from the REAL module — it is the one that knows what is Ktor-typed):
./gradlew :inspector-stream:jvmTest -Dinspector.api.regenerate=true
# 3. confirm both modules pass:
./gradlew :inspector-stream:jvmTest :inspector-noop-stream:jvmTest
```

The ui pair has its own golden file, and the same friction:

```bash
# 1. change BOTH :inspector-ui and :inspector-noop-ui
# 2. regenerate:
./gradlew :inspector-ui:jvmTest -Dinspector.api.regenerate=true
# 3. confirm both modules pass:
./gradlew :inspector-ui:jvmTest :inspector-noop-ui:jvmTest
```

Each pair also has an iOS golden file, read from both iOS targets' klibs by `klib dump-abi` in each
module's `iosApiTest` (macOS only; `check` runs it). Regenerate it from the real module after the same
change to both twins:

```bash
./gradlew :inspector-core:iosApiTest -Dinspector.api.regenerate=true
./gradlew :inspector-stream:iosApiTest -Dinspector.api.regenerate=true
./gradlew :inspector-ui:iosApiTest -Dinspector.api.regenerate=true
# confirm the noop twins pass:
./gradlew :inspector-noop:iosApiTest :inspector-noop-stream:iosApiTest :inspector-noop-ui:iosApiTest
```

## Key files

| Path | Why it matters |
|---|---|
| `api/inspector-public-api.txt` | Golden public API surface for the `core` pair, asserted by both modules. |
| `api/inspector-stream-public-api.txt` | The same for the `stream` pair, minus the Ktor-typed members a release build cannot carry. |
| `api/inspector-ui-public-api.txt` | The same for the `ui` pair: `InspectorOverlay`'s declaration and its JVM descriptor, Compose's parameters included. |
| `api/inspector-public-api.ios.txt` | The `core` pair's public iOS surface, read from its klibs and asserted by both modules' `iosApiTest`. |
| `api/inspector-stream-public-api.ios.txt` | The same for the `stream` pair, with the real module's Ktor rule. |
| `api/inspector-ui-public-api.ios.txt` | The same for the `ui` pair: `InspectorOverlay` alone, the Compose compiler's `$stableprop` markers dropped. |
| `api-parity/` | `shared/` is the reflection and golden-file machinery; `core/`, `stream/` and `ui/` hold their pair's JVM and iOS test classes. Every directory here is compiled into the `jvmTest` of the two modules it guards. |
