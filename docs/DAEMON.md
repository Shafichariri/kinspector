# Running the daemon

Everything about starting, stopping, restarting and inspecting the host daemon — the process that
serves the web UI, archives sessions to disk, and answers the CLI and MCP tools.

Integrating the library into an app instead? That is [`INTEGRATION.md`](INTEGRATION.md). You do not
need the daemon at all for the in-app overlay; it is only for the web UI and the on-disk archive.

---

## Requirements

| | |
|---|---|
| JDK | **21** |
| OS | macOS or Linux (developed on macOS; nothing is macOS-specific) |
| Network | none — it binds `127.0.0.1` and never calls out |

Gradle comes from the wrapper (`./gradlew`); you do not install it.

---

## 1. Build it once

```bash
./gradlew :inspector-daemon:installDist
```

That produces a launcher at:

```
inspector-daemon/build/install/inspector/bin/inspector
```

Every command below says `inspector`, meaning that launcher. The path is long, so put its directory
on your `PATH` — from the repo root:

```bash
export PATH="$PWD/inspector-daemon/build/install/inspector/bin:$PATH"
```

Add that to your shell profile with an absolute path to make it stick. Otherwise substitute the full
path into every command below.

Re-run `installDist` after any change to `:inspector-daemon`, `:inspector-model`, or the web UI
files under `inspector-daemon/src/main/resources/web/` — the launcher runs the *installed* copy,
not your source tree, so an un-reinstalled change simply will not appear.

---

## 2. Start

```bash
inspector serve
```

It prints three lines and then blocks:

```
inspector: archive at /Users/you/.inspector
inspector: listening on http://127.0.0.1:8099
inspector: retention 100 sessions / 300 MB
```

Open **http://127.0.0.1:8099**.

Options:

| Flag | Default | Meaning |
|---|---|---|
| `--port N` | `8099` | HTTP and WebSocket port |
| `--data DIR` | `~/.inspector` | Archive root |
| `--max-sessions N` | `100` | Prune oldest beyond this |
| `--max-mb N` | `300` | Prune oldest beyond this total size |

Both retention ceilings apply together: pruning continues until the archive is under *both*.

To run it in the background instead of holding a terminal:

```bash
nohup inspector serve > /tmp/inspector-daemon.log 2>&1 &
```

### It refuses to start: "port 8099 is already in use"

That is deliberate and it means another daemon is already running. It used to start anyway and
serve nothing, which is how three `inspector serve` processes ended up on one machine with only one
of them working. Either use the one that is already up, or:

```bash
lsof -nP -iTCP:8099 -sTCP:LISTEN     # find the culprit's PID
inspector serve --port 8100          # or just use another port
```

If you change the port, the app's `StreamSink` must be told too — see `INTEGRATION.md` §6b.

---

## 3. Stop

Three ways, in order of preference.

### From the web UI

The **stop** button in the top bar. It confirms first, then the page tells you the daemon is gone
rather than silently looking idle. This is the easiest option when you have lost track of which
terminal launched it.

### From the terminal that started it

`Ctrl-C`. Sessions are closed cleanly by a shutdown hook, so nothing in the archive is left
half-written.

### By PID, when it is detached or wedged

```bash
lsof -nP -iTCP:8099 -sTCP:LISTEN                    # get the PID
kill <PID>                                          # graceful: runs the shutdown hook
kill -9 <PID>                                       # last resort, skips clean session close
```

Or in one step:

```bash
pkill -f "MainKt serve"
```

**Use exactly that pattern.** The obvious-looking alternatives both kill more than you meant:

| Pattern | Also matches | Why |
|---|---|---|
| `inspector` | `inspector mcp` | Your editor's MCP connection is a separate long-lived process. |
| `inspector.*serve` | `inspector mcp` | The classpath contains `ktor-server-cio-*.jar`, and "server" contains "serve". |

`MainKt serve` matches the launched command rather than the classpath, so it hits the daemon and
nothing else. Confirm before you kill:

```bash
pgrep -fl "MainKt serve"
```

`kill -9` is safe for the archive in the sense that finished sessions are already on disk; the only
loss is the metadata flush for a session still recording, which shows up as a session with a stale
transaction count.

---

## 4. Restart

### From the web UI

The **restart** button. The daemon relaunches itself and the page reconnects on its own — no manual
reload. The archive is untouched, so every session is still there afterwards.

This also picks up a rebuilt daemon, which makes it a genuinely fast development loop:

```bash
./gradlew :inspector-daemon:installDist
curl -s -X POST -H "X-Inspector-Control: 1" http://127.0.0.1:8099/api/server/restart
```

The header is required — see [Why control needs a header](#why-control-needs-a-header).

If the button is disabled, this process cannot read its own command line and so cannot honour a
restart. Stop it and start it again by hand; it refuses rather than exiting and leaving you with no
daemon at all.

### From the terminal

Stop it, then start it. Restarting immediately after `Ctrl-C` works: the pre-flight bind check
permits a port left in `TIME_WAIT` by a daemon that just exited, while still refusing one with a
live listener.

---

## 5. Is it running, and which one?

```bash
curl -s http://127.0.0.1:8099/api/server
```

```json
{"pid":30608,"port":8099,"canRestart":true}
```

That is the quickest way to confirm you are talking to the daemon you think you are. The startup
line `inspector: archive at …` tells you which archive it opened, which matters when a session in
the web UI is not the one you expect.

---

## 6. Where things live

```
~/.inspector/
├── config.json                 # optional, see below
├── latest -> sessions/…        # symlink to the most recent session
└── sessions/
    └── 2026-08-17T05-57-58_myapp_Pixel-8_debug/
        ├── meta.json           # session metadata
        ├── index.jsonl         # one transaction per line
        ├── markers.jsonl
        └── bodies/             # request and response bodies, by ref
```

`latest` is what the CLI and MCP tools mean by the session id `latest`.

`config.json` sets the same things as the flags, so you do not have to repeat them. Flags win over
the file, and the file wins over defaults:

```json
{ "port": 8099, "maxSessions": 100, "maxTotalMb": 300 }
```

A malformed `config.json` is reported on stderr and ignored rather than blocking startup — a
debugging tool that will not start because of its own settings file is worse than one on defaults.

Nothing here is encrypted and, with redaction off (the default), bodies and headers contain real
credentials. Treat `~/.inspector` as you would a log directory full of tokens.

---

## 7. Reading sessions without the web UI

Every command defaults to the `latest` session; pass `--session ID` for another. `--data DIR` works
on all of them, so you can read an archive a different daemon wrote.

```bash
inspector sessions                                  # list them
inspector summary                                   # counts, hosts, error breakdown
inspector query 'status>=400'                       # filter, same grammar as the UI
inspector query 'has:error' --limit 10 --offset 0
inspector body <txnId> --side res                   # raw bytes to stdout
inspector prune                                     # apply retention now
inspector --help                                    # usage and the full filter grammar
```

These read the archive straight off disk and need **no running daemon**.

Filter grammar, in brief:

```
status:404   status>=400        method:POST      host:api.example.com
path:/v2     slower:500ms       larger:10kb      attempt>1
has:error    text:refund        since:marker("tapped checkout")
```

Space-separated terms are ANDed; `|` ORs and binds more loosely.

---

## 8. The MCP server

A separate long-lived process, started by your editor rather than by you:

```bash
inspector mcp
```

It speaks JSON-RPC on stdout, so never expect log output there — diagnostics go to stderr. Like the
CLI it reads the archive off disk and works with no daemon running, except `add_marker`, which needs
a session that is currently recording.

Registration for Claude Code, Cursor and Codex is in [`INTEGRATION.md`](INTEGRATION.md) §10.

To drive it by hand, which is the fastest way to check a tool change:

```bash
printf '%s\n' \
  '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{}}}' \
  '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"session_summary","arguments":{}}}' \
  | inspector mcp
```

---

## Why control needs a header

`POST /api/server/stop` and `/api/server/restart` require `X-Inspector-Control: 1`.

The daemon listens on loopback with no authentication, which is fine for reading your own traffic
but means *any* page open in your browser can POST to `127.0.0.1:8099`. Without a guard, a plain
form post from an unrelated site would be an off switch for your daemon. A custom header forces the
browser to send a CORS preflight first, and this server answers no preflight, so only its own page
gets through.

Read-only endpoints and the marker POST are deliberately left open — the MCP tools post markers, and
a marker is not a weapon.

This is a nuisance guard, not real security. **The loopback bind is the actual boundary.** Do not
expose the daemon on `0.0.0.0` or through a tunnel: there is no authentication and the archive holds
unredacted credentials by default.

---

## Troubleshooting

**The web UI shows no sessions.** The app never connected. Work through `INTEGRATION.md` §9 —
on Android the cause is almost always the cleartext config in §6d.

**The web UI shows an old session that is not mine.** You are looking at a different archive. Check
the `inspector: archive at …` line, and whether something passed `--data`.

**A session exists but no new rows appear.** Traffic captured while the daemon was down is not
backfilled — it stays in the device ring buffer. Fire a fresh request.

**Changes to the web UI do not show up.** Re-run `installDist`; the launcher serves the installed
resources, not your working tree. Then hard-reload the page.

**`inspector serve` exits immediately with no message.** Check the log if you backgrounded it with
`nohup`. A `PortUnavailableException` prints the `lsof` command that finds the other process.

**Two daemons, and traffic goes to the wrong one.** Only one can hold the port, and the other is not
running. Confirm with `/api/server` which PID you are talking to.
