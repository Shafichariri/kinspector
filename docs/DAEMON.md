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

## 1. Get it

Two ways, depending on whether you work *on* Inspector or only *with* it.

### Download a release — if you just want to run it

No clone, no Gradle, nothing but a JDK 21. From any directory:

```bash
gh release download --repo Shafichariri/kinspector --pattern '*.zip'
unzip inspector-*.zip
```

That gives you `inspector-<version>/bin/inspector`. The repository is public, so this needs no
token and no account — the Releases page works in a browser too. Unlike the library, which GitHub
Packages will not serve without an authenticated token.

Releases are cut by tag — `git tag v0.2.0 && git push origin v0.2.0`. CI builds the zip, starts it
and checks both surfaces answer before publishing, so a release that exists is a release that ran
at least once. See `.github/workflows/release.yml`.

### Build from source — if you change Inspector

```bash
./gradlew :inspector-daemon:installDist
```

That produces a launcher at:

```
inspector-daemon/build/install/inspector/bin/inspector
```

Re-run `installDist` after any change to `:inspector-daemon`, `:inspector-model`, or the web UI
files under `inspector-daemon/src/main/resources/web/` — the launcher runs the *installed* copy,
not your source tree, so an un-reinstalled change simply will not appear.

### Either way

Every command below says `inspector`, meaning whichever launcher you ended up with. The path is
long, so put its directory on your `PATH` — from the repo root, for a source build:

```bash
export PATH="$PWD/inspector-daemon/build/install/inspector/bin:$PATH"
```

Add that to your shell profile with an absolute path to make it stick. Otherwise substitute the full
path into every command below.

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

### From the web UI — Settings

The **settings** button lists every inspector process on the machine and offers to kill the ones
that are not this daemon. It exists precisely because the `pkill` patterns above are easy to get
wrong.

The daemon identifies siblings by **exact argv token** — the launcher's main class,
`dev.inspector.daemon.MainKt`, followed by the subcommand — never by a substring of the command
line. That is why the list cannot repeat either trap in the table above: an `mcp` process is
reported as `mcp` even though its classpath contains `ktor-server-cio-*.jar`.

```bash
curl -s http://127.0.0.1:8099/api/peers
```

```json
[ { "pid": 42941, "role": "serve", "port": 8099, "startedEpochMs": 1787050404327, "self": true },
  { "pid": 41540, "role": "mcp", "startedEpochMs": 1787049838507, "self": false } ]
```

`port` is resolved the way that peer itself would resolve it — its own `--port` flag over its own
`config.json` over the default — so it is the port the process is really on, not a guess. An `mcp`
peer has no `port` at all, because it speaks over stdio; the UI shows `stdio`.

```bash
curl -s -X POST -H "X-Inspector-Control: 1" http://127.0.0.1:8099/api/peers/<PID>/kill
curl -s -X POST -H "X-Inspector-Control: 1" "http://127.0.0.1:8099/api/peers/<PID>/kill?force=true"
```

`force=true` is `destroyForcibly` (SIGKILL); the default is `destroy` (SIGTERM), which runs the
shutdown hook.

The endpoint refuses rather than guessing:

| Situation | Response |
|---|---|
| No `X-Inspector-Control` header | `403` |
| The pid is this daemon | `409` — use `/api/server/stop`, which replies before shutting down |
| The pid is not an inspector process | `409` |
| The pid belongs to another user | `409` |
| No live process with that pid | `404` |
| The pid is not a number | `400` |

The identity check is re-run **at kill time**, not taken from the listing. Between listing and
killing, a process can exit and the OS can reuse its pid for something unrelated, so a pid on its
own is not evidence of what it now identifies. This is also what stops the endpoint from being a
general-purpose "kill any pid" facility on an unauthenticated loopback port.

Processes launched some other way — `./gradlew run`, an IDE run configuration — do not carry that
argv shape and so are not listed. Kill those where you started them.

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
        ├── index.jsonl         # one transaction per line, append-only
        ├── markers.jsonl
        ├── signals.jsonl       # one signal per line, append-only
        ├── bodies/             # request and response bodies, by ref
        └── signals/            # signal payloads, by ref
```

Both `.jsonl` files are append-only and both keep their payloads beside them rather than inline, so
a row stays one greppable line however large the body or the signal behind it.

`latest` is what the CLI and MCP tools mean by the session id `latest`.

`config.json` sets the same things as the flags, so you do not have to repeat them. Flags win over
the file, and the file wins over defaults:

```json
{ "port": 8099, "maxSessions": 100, "maxTotalMb": 300 }
```

A malformed `config.json` is reported on stderr and ignored rather than blocking startup — a
debugging tool that will not start because of its own settings file is worse than one on defaults.

#### `signalCaps` — how many signal rows a session keeps, per tag

The archive ceilings above count sessions and bytes. Inside one session, signals are trimmed per
tag on close, because what a row costs varies by orders of magnitude: a `screen` row is a route
name and a few arguments, while `cache` and `state` rows carry the value they observed.

| Tag | Default | |
|---|---|---|
| `cache` | 500 | |
| `state` | 500 | |
| everything else | uncapped | including a tag this build has never heard of |

Treat the defaults as a runaway guard for one unusually chatty session rather than a size budget —
`maxSessions` and `maxTotalMb` are what actually bound the disk, and they apply across sessions
where a per-tag cap cannot. There is no flag for this; it is `config.json` only.

```json
{ "signalCaps": { "cache": 2000, "screen": 5000, "state": null } }
```

Overrides **merge** over the defaults rather than replacing them, so naming one tag does not
silently uncap the rest. That means:

- **a number** caps that tag. `0` is honoured — "do not archive this tag" is a real thing to want.
- **`null`** uncaps it. Since merging is the rule, this is the only way to say "keep every row".
- **a negative number** is reported on stderr and ignored, like a malformed file.

Tags match case-insensitively. Trimming happens when the session closes, keeps the newest rows by
`mono`, writes the survivors back in their original append order, and deletes the payload files of
the rows it drops.

Uncapped is the default for a reason worth knowing before you change it: `screen` rows are the
backbone of the merged timeline, so losing old ones leaves gaps in the very thing signals exist to
build.

Nothing here is encrypted and, with redaction off (the default), bodies and headers contain real
credentials. Treat `~/.inspector` as you would a log directory full of tokens.

### Deleting sessions

A pile of one-run simulator sessions is the normal state of this directory. Retention prunes the
oldest once the archive passes `maxSessions` or `maxTotalMb`, but you can remove them yourself.

From the web UI: the `✕` beside the session picker deletes the session on screen, and
**Settings → Sessions** lists every session with a per-row delete and a **clear all**. Both arm on
the first click and fire on the second.

From a terminal:

```bash
curl -X DELETE -H 'X-Inspector-Control: 1' \
  http://127.0.0.1:8099/api/sessions/2026-08-17T05-57-58_myapp_Pixel-8_debug
```

```bash
curl -X POST -H 'X-Inspector-Control: 1' http://127.0.0.1:8099/api/sessions/clear
```

Both report what went, what stayed and how many bytes came back. Two rules the daemon enforces
rather than trusting the caller with:

- **A session still being written is never deleted.** Removing the folder underneath an open
  writer corrupts the append stream and throws away the traffic you are looking at. `clear` skips
  it and names it in `kept`; a single delete returns `409`.
- **`latest` is refused as a delete target, not resolved.** It names a different folder depending
  on when you call it, which is fine for a read and is not fine for a delete — the session it
  points at is the one you are most likely to still want. Name the session explicitly.

Deleting the session `latest` points at repoints the link at the newest survivor, so `latest` keeps
working; deleting the last session removes the link rather than leaving it dangling.

Nothing is recoverable. There is no trash.

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

### From the web UI — Settings

The settings panel has an MCP section with a **test** button and a copyable registration command.

There is **no start button**, and that is deliberate rather than missing. The MCP server speaks
stdio: `McpServer.run` blocks on `input.readLine()` and stops at EOF, so a server the daemon
spawned would have nobody on the other end of its pipes and would exit immediately. Your editor
spawns it when it connects. To recover a wedged one, kill it in the process list above — the editor
spawns a fresh one on reconnect.

**Test** answers the question that button would have been for: does the binary actually work? It
spawns a throwaway server, completes an `initialize` and `tools/list` handshake, reports the tool
count, and stops it. It leaves nothing behind.

```bash
curl -s http://127.0.0.1:8099/api/mcp                                        # how to register it
curl -s -X POST -H "X-Inspector-Control: 1" http://127.0.0.1:8099/api/mcp/probe
```

```json
{ "ok": true, "serverName": "inspector", "toolCount": 6 }
```

Probing spawns a process, so it needs the control header; reading the registration details does
not. `/api/mcp` reports the `bin/inspector` launcher path when it can find one on disk, and the raw
`java -classpath …` line otherwise — the launcher is checked for existence before being offered,
because a path that only looks right gets pasted into an editor config and fails there instead of
here.



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

`POST /api/server/stop`, `/api/server/restart` and `/api/peers/{pid}/kill` require
`X-Inspector-Control: 1`.

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
