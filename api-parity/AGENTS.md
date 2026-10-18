# api-parity — agent guide

The reflection and golden-file machinery (`shared/`) and one parity test per twin pair (`core/`,
`stream/`). Each directory is compiled into the `jvmTest` of the two modules it guards, so both
halves of a pair assert the same golden file.

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
