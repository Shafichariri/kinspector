# inspector-ui — agent guide

The Compose overlay: the draggable pill, the list, the detail screens and the signal views, on
Android, iOS and desktop from one codebase. Its render and interaction tests are JVM-only.

Read before changing anything here: `docs/decisions/overlay.md`, `docs/decisions/signals.md`, `docs/decisions/shared-rules.md`.

Also: `docs/decisions/twins-and-api.md` — this module's twin, `:inspector-noop-ui`, has no golden
file yet (phase I).

@../docs/decisions/overlay.md
@../docs/decisions/signals.md
@../docs/decisions/shared-rules.md

## Commands

```bash
./gradlew :inspector-ui:jvmTest                   # overlay: prefix rules + a real render
open inspector-ui/build/screenshots/list-dark.png # ... what that render produced
```

## Key files

| Path | Why it matters |
|---|---|
| `inspector-ui/.../InspectorList.kt` | The overlay's list and row. Two lines per call, and the bar that lifts the shared path prefix out of them. |
| `inspector-ui/.../Formatting.kt` | Row presentation, including `pathScope` — the shared-prefix rules, and the thresholds that decide whether the bar appears at all. |
| `inspector-ui/.../InspectorSignalDetail.kt` | One observation: payload, provenance and the history of its key. Where stage 1's undrawn payloads went. |
| `inspector-ui/.../NowStrip.kt` | What the app holds now — latest per `(tag, name)`, with age and provenance. Sorted stably, unlike `signalGroups`; read its doc before changing either. |
| `inspector-ui/src/jvmTest/.../ListRenderTest.kt` | The mobile equivalent of `render-web-ui.js`: renders the real list **and the signal screen** at 360dp and writes PNGs to `inspector-ui/build/screenshots/`. Run it after touching either, then *look at the output* — it is there to be read, not just to pass. Two layout defects and a misleading fixture clock in stage 2 were found only by looking. |
