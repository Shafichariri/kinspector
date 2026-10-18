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
| `inspector-daemon/.../Har.kt` | HAR 1.2 export. Read the class doc before changing a field: most of it is about what the format asks for and capture cannot answer. |
| `inspector-daemon/.../Export.kt` | `SessionExport`: the one document all three exports render from. `ExportMarkdown.kt` and `HtmlExport.kt` beside it are the other two forms. |
| `inspector-daemon/.../SessionRepository.kt` | Every archive read. The MCP tools wrap this. |
