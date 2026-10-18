# inspector-stream — agent guide

`StreamSink`: sends captured rows to the daemon, dialling `/ingest` or, on a physical iPhone,
listening on the phone's loopback for the USB bridge. It also answers signal and signing
requests from the host.

Read before changing anything here: `docs/decisions/devices-and-stream.md`, `docs/decisions/twins-and-api.md`.

Also: `docs/decisions/daemon-and-mcp.md` for why `ReplaySigner` asks for headers, not a signature.

@../docs/decisions/devices-and-stream.md
@../docs/decisions/twins-and-api.md

## Key files

| Path | Why it matters |
|---|---|
| `inspector-stream/.../Transport.kt` | How `StreamSink` reaches the host: dialling `/ingest`, or — on a physical iPhone — listening on the phone's loopback for the USB bridge. |
