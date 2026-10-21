# api-parity — agent guide

The reflection and golden-file machinery (`shared/`) and one parity test per twin pair (`core/`,
`stream/`, `ui/`). Each directory is compiled into the `jvmTest` of the two modules it guards, so both
halves of a pair assert the same golden file.

Every pair runs two tests in both of its modules: its golden file, and a completeness test that fails
when a class outside the pair's contract list holds public API. Each module declares the golden file
as a `jvmTest` input, and `structure/twins.txt` names it on the pair's line.

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

## Key files

| Path | Why it matters |
|---|---|
| `api/inspector-public-api.txt` | Golden public API surface for the `core` pair, asserted by both modules. |
| `api/inspector-stream-public-api.txt` | The same for the `stream` pair, minus the Ktor-typed members a release build cannot carry. |
| `api/inspector-ui-public-api.txt` | The same for the `ui` pair: `InspectorOverlay`'s declaration and its JVM descriptor, Compose's parameters included. |
| `api-parity/` | `shared/` is the reflection and golden-file machinery; `core/`, `stream/` and `ui/` hold one test class each. Every directory here is compiled into the `jvmTest` of the two modules it guards. |
