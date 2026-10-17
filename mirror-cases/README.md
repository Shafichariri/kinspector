# Mirror cases

`app.js` carries copies of rules whose authority is Kotlin, because the web UI has no build step and
cannot call `:inspector-model`. Each file here is one rule's cases, and **both sides run every
case**:

| File | Kotlin authority | Kotlin runner | `app.js` block |
|---|---|---|---|
| `timeline.json` | `timeline()` (oldest first) | `MirrorCasesTest` | `visits` → `timelineEntries` |
| `visits.json` | `visits()`, `visitIndexAt()` | `MirrorCasesTest` | `visits` (+ `tagOf`) |
| `pathScope.json` | `pathScope()` | `MirrorCasesTest` | `pathScope` |
| `endpointShortcuts.json` | `endpointShortcuts()` | `MirrorCasesTest` | `endpointShortcuts` |
| `timelineRuns.json` | `timelineRuns()`, `TimelineRun.id` | `MirrorCasesTest` | `timelineRuns` |
| `duplicates.json` | `duplicateGroups()` | `MirrorCasesTest` | `duplicates` (+ `urlOf`) |
| `hopByHop.json` | `HOP_BY_HOP_HEADERS`, `replayableHeaders()` in `Replay.kt` | `HopByHopMirrorTest` (daemon) | `hopByHop` |

The JavaScript runner is `scripts/check-web-static.js`, which lifts each block out of the real
`app.js` between `// @mirror-begin <name>` and `// @mirror-end <name>`.

```bash
node scripts/check-web-static.js
./gradlew :inspector-model:jvmTest :inspector-daemon:test
```

## Format

```json
{ "rule": "pathScope",
  "cases": [
    { "name": "never takes the last segment of a path",
      "from": "PathScopeTest.never_takes_the_last_segment_of_a_path",
      "input":  { "transactions": [ { "id": "a", "path": "/v3/orders/submit", "mono": 1 } ] },
      "expect": { "host": "api.example.invalid", "prefix": "/v3/orders/", "covered": 4, "label": "…" } } ] }
```

- A file is named after its `rule`, holds `rule` and `cases` and nothing else, and has at least one
  case. An unknown rule, an empty file, or an unknown key on a case or its `input` fails both sides.
- `from` names the Kotlin test or source the case was taken from, so a reader can find the reasoning.
- Rows are wire-shaped and partial. Both runners merge each one over its entry in `_defaults.json`
  (`txn`, `signal`, `marker`); Kotlin then decodes it with the real serializer and **unknown keys
  refused**, so a misspelt field fails instead of quietly falling back to the default. The default
  `callId` is one shared value, so a duplicates case always sets `callId` on every row.
- Integers stay below 2^53: JavaScript reads these files with `JSON.parse`.

## Projections

What each rule's answer is compared as. Each side keeps its own small adapter, and adapters hold no
rules — anything that decides something belongs in the code under test.

- **Entry tokens:** `T:<id>` for a call, `M:<label>` for a marker, `S:<tag>/<name>` for a signal.
- **`timeline`:** the oldest-first list of tokens. Newest-first is a reverse of the same list, and
  `render-web-ui.js` checks that on the rendered page.
- **`visits`:** `{ "visits": [ { "screen": name or null, "entries": [tokens] } ] }`, plus
  `"indexAt": [visitIndexAt(mono)…]` when the input lists `indexAt`.
- **`pathScope`:** `{ host, prefix, covered, label }`, or `null`.
- **`endpointShortcuts`:** `[[segment, count]…]` for the given `limit`.
- **`timelineRuns`:** input `entries` are in **draw order**, each `{ "T" | "M" | "S": row }`. Output
  `[{ key, entries: [tokens], id }]`, where `key` is `"<tag>\u0000<name>"` or `null` and `id` is
  present only for signal runs.
- **`duplicates`:** `[{ ids, spanMs, callCount }]`, ordered by each group's first member. `windowMs`
  defaults to 3000.
- **`hopByHop`:** with no `headers`, `{ "names": [the set, sorted] }`; with `headers`, the editor's
  seeded lines as `{ "lines": ["Name: v1, v2"…] }`.

## Adding a case

Add it here, not to one side. Run both commands above; a new case that only one side passes is a
drift, and the Kotlin answer is the right one.
