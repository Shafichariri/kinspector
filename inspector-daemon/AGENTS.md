# inspector-daemon — agent guide

The host daemon: the session archive, REST, the live socket, the web UI it serves, the CLI, the
MCP server and the USB bridge. JVM only, binds `127.0.0.1`, tests on JUnit 5.

Read before changing anything here: `docs/decisions/daemon-and-mcp.md`, `docs/decisions/exports.md`.

Also: the web guide in `src/main/resources/web/AGENTS.md` before touching the web UI,
`docs/decisions/release.md` for the release zip, and `docs/decisions/devices-and-stream.md` for
the USB bridge.

@../docs/decisions/daemon-and-mcp.md
@../docs/decisions/exports.md

## Commands

```bash
./gradlew :inspector-daemon:installDist           # then: inspector-daemon/build/install/inspector/bin/inspector serve
inspector serve --port 8231 --usb-port 8099       # a second daemon beside your usual one; the app still listens on 8099
./gradlew :inspector-daemon:distZip -Pinspector.version=0.2.0   # the release zip, as CI builds it
```

Driving the MCP server by hand, which is the fastest way to check a tool change:

```bash
printf '%s\n' \
  '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{}}}' \
  '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"session_summary","arguments":{}}}' \
  | inspector-daemon/build/install/inspector/bin/inspector mcp
```

## Reproducing the full stack locally

```bash
./gradlew :inspector-daemon:installDist
inspector-daemon/build/install/inspector/bin/inspector serve --data /tmp/demo &
./gradlew :sample:desktop:run -Dinspector.sample.autofire=true
open http://127.0.0.1:8099
```

Sessions land in `/tmp/demo/sessions/`; `/tmp/demo/latest` symlinks the newest.

## Key files

| Path | Why it matters |
|---|---|
| `inspector-daemon/.../usb/UsbBridge.kt` | The iOS counterpart of `adb reverse`: polls usbmuxd, dials the app's port, relays into the daemon's own `/ingest`. `Usbmux.kt` and `Plist.kt` beside it are the protocol. |
| `inspector-daemon/.../http/InspectorDaemon.kt` | The daemon: the `127.0.0.1` bind that is the security boundary, the port pre-flight, stop and restart, and `module()`, which registers the route groups that sit beside it in `http/`. A route that changes something goes inside `controlGated { }` from `ControlGate.kt`. |
| `inspector-daemon/.../service/Har.kt` | HAR 1.2 export. Read the class doc before changing a field: most of it is about what the format asks for and capture cannot answer. |
| `inspector-daemon/.../service/Export.kt` | `SessionExport`: the one document all three exports render from. `ExportMarkdown.kt` and `HtmlExport.kt` beside it are the other two forms. |
| `inspector-daemon/.../archive/SessionRepository.kt` | Every archive read. The MCP tools wrap this. |
| `inspector-daemon/.../service/RouteStats.kt` | Route stats: the accumulator `GET …/stats`, `route_stats`, `inspector stats` and the exports share, and its order. `RouteStatsService.kt` beside it is the cache that folds `index.jsonl` from where the last read stopped; the route rule is `archive/RouteTemplates.kt`. |
| `inspector-daemon/.../service/FindingsEngine.kt` | Findings: the engine every family plugs into (`FindingSources.all` is the one registry; the extension point is in `Findings.kt` beside it), its order, caps and masking check. The payment rules are `PaymentRules.kt` and `PaymentLedger.kt`; the sensitive-data rules are `SensitiveSource.kt` with `SensitiveValues.kt` and `SensitiveMask.kt` (their config is `archive/SensitiveConfig.kt`); dismissals are `FindingDismissals.kt`; `FindingsService.kt` is the cache; the `findings` key of `config.json`, read live, is `archive/FindingSettings.kt` and `FindingSettingsSource.kt`. |
| `repo-checks/.../DaemonLayersTest.kt` | Holds the layer table below, from the imports and inline qualified names in the daemon's main sources. |

## Layers

Each package under `dev.inspector.daemon` is a layer. Kotlin's `internal` does not separate them,
so `DaemonLayersTest` does; the reasoning is in `docs/decisions/daemon-and-mcp.md`.

| Package | Holds | May import, beyond `kotlin.`, `kotlinx.serialization.`, `kotlinx.coroutines.`, `java.` and `dev.inspector.model.` |
|---|---|---|
| `archive/` | the on-disk archive: config, layout, reader, writer, retention, route templates (the config's `routeTemplates` and the heuristic they override), findings settings (the `findings` key, read live) and the rule catalogue | nothing else in the daemon |
| `service/` | live sessions, live apps, replay, peers, MCP control, exports, route stats, findings, `daemonVersion()` | `archive/` |
| `http/` | the Ktor server, its route groups and the control gate | `archive/`, `service/`, `io.ktor.` |
| `mcp/` | the MCP server and its tools | `archive/`, `service/` |
| `usb/` | the USB bridge and the usbmuxd protocol | `javax.xml.`, `org.w3c.dom.`, `org.xml.sax.`; no daemon package, because the bridge goes through `WS /ingest` |
| root | `Main.kt` alone, the composition root | anything |
