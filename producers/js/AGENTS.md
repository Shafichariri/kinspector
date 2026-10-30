# producers/js — agent guide

The JavaScript producer: `inspector-producer.js`, one CommonJS file a React Native app's harness
vendors, which sends the app's traffic and signals to the daemon's `WS /ingest` as the same
`WireMsg` JSON the Kotlin `StreamSink` sends. A plain folder, not a Gradle module, so nothing in
`structure/` names it. No packages: Node built-ins only, and the file never requires `react-native`.

Read before changing anything here: `docs/decisions/capture.md` (its "JavaScript producers" lead),
`docs/decisions/devices-and-stream.md`.

Also: `docs/schema.md` before changing any frame, and its "Second implementations" rules; the
daemon decodes every frame with the Kotlin serializer, so a fractional Long or an array frame is
dropped there without a word to the app.

@../../docs/decisions/capture.md
@../../docs/decisions/devices-and-stream.md

## Commands

```bash
node --test producers/js/test/*.test.js           # the producer against a scripted socket; CI's web job runs it
```

## Key files

| Path | Why it matters |
|---|---|
| `producers/js/inspector-producer.js` | The vendorable file. Under 600 lines (`FileBudgetTest`). Its canary is a used top-level reference on purpose. |
| `producers/js/test/fake-ws.js` | The scripted WebSocket, timers and clocks every test drives the producer with. |
