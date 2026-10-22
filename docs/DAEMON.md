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
| `--usb-port N` | the `--port` | Port a USB-attached iPhone's app listens on — see below |
| `--no-usb` | bridge on | Do not bridge USB-attached iPhones |

Both retention ceilings apply together: pruning continues until the archive is under *both*.

To run it in the background instead of holding a terminal:

```bash
nohup inspector serve > /tmp/inspector-daemon.log 2>&1 &
```

### Connecting a physical Android device over USB

The daemon binds loopback and nothing else, so a phone on your Wi-Fi cannot reach it and is not
meant to. What a USB cable gives you instead is `adb reverse`, which forwards a port on the
*device's* loopback to the same port on the machine running adb:

```bash
adb reverse tcp:8099 tcp:8099
```

That is the whole setup. The app then connects to `127.0.0.1:8099` from inside the phone, the
traffic goes down the cable, and the daemon keeps listening on `127.0.0.1` on your Mac — the
security boundary is untouched, because nothing was opened to the network.

`defaultDaemonHost()` already picks the right address: `10.0.2.2` on an emulator, `127.0.0.1` on
hardware. You do not pass anything to `StreamSink`.

Two things about `adb reverse` that will cost you a debugging session each:

- **It does not survive a replug**, an `adb kill-server`, or the device going away and coming
  back. Run it again; it is idempotent.
- **It is per-device.** With more than one device or emulator attached, `adb reverse` fails with
  `more than one device/emulator` — name the one you mean with `adb -s <serial> reverse …`, from
  `adb devices`.

Check what is forwarded, and tear it down:

```bash
adb reverse --list              # host-15 tcp:8099 tcp:8099
adb reverse --remove tcp:8099
```

The app still needs the cleartext exemption for `127.0.0.1` — see `INTEGRATION.md` §6d. That is a
separate thing from the tunnel, and forgetting it fails the same silent way.

### A physical iPhone, over USB

Plug it in and run `serve`. Nothing else: no forward to set up, and no flag.

There is no `adb reverse` on iOS, and the connection runs the other way round instead. On a physical
iPhone the app **listens** on the phone's own `127.0.0.1:8099`, and `serve` starts a bridge that
finds the phone through usbmuxd — the service Xcode uses, present on every Mac — and dials that
port. The daemon still binds your Mac's loopback and nothing else. You will see:

```
inspector: usb bridge on — a USB-attached iPhone is dialled on port 8099
inspector: usb — iPhone 00000000-… attached; waiting for an app listening on port 8099
inspector: usb — connected to 00000000-… on port 8099
inspector: started session …
```

The bridge dials again every two seconds, so the order does not matter: start the app first or the
daemon first, unplug and replug, relaunch the app. A phone with no app running says `attached` once
and then nothing.

- **The port is one number on both ends.** The bridge dials the daemon's own `--port`. If your app
  passes a `port` to `StreamSink`, run the daemon on the same one.
- **`--usb-port N`** dials a different port than the daemon listens on — for a second daemon beside
  your usual one while the app keeps its default: `serve --port 8231 --usb-port 8099`.
- **`--no-usb`** turns the bridge off.
- **Cable only.** A phone paired over Wi-Fi also shows up in usbmuxd, and is skipped: the app's
  listener is bound to the phone's loopback, which the network route cannot reach. That is what
  keeps the listener off the phone's network.
- **Two Inspector apps on one phone** cannot both listen on 8099. The second prints why; give it its
  own port and run a second daemon on that port.

If nothing connects, the app's log says what the listener saw — `inspector: cannot reach host over
USB …` names the port and the reason.

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

**Stop** on the **Processes** page, under *This daemon*. Click it twice: the first click changes it
to `stop daemon?` and the second stops the daemon (it disarms on its own after a few seconds). The
page then shows a `daemon stopped` banner with the time of the last capture, dims everything below
it, and keeps trying to reconnect — so running `inspector serve` again is enough, and the banner
clears itself when the daemon is back. There is no restart button on the banner, because there is no process
left to receive one. This is the easiest option when you have lost track of which terminal
launched it.

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

### From the web UI — Processes

The **Processes** page lists every inspector process on the machine and offers to kill the ones
that are not this daemon: one row's `kill`, or several at once with the checkboxes, the presets
(**all servers**, **all except this one**, and **stale**, those that started before this daemon)
and `kill selected`. Every kill arms on the first click and fires on the second, and the page then
says what happened to each process. No preset ever ticks an `mcp` process — it is your editor's
connection — and ticking one by hand shows a warning. The page exists precisely because the `pkill`
patterns above are easy to get wrong.

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

Several at once:

```bash
curl -s -X POST -H "X-Inspector-Control: 1" -H "Content-Type: application/json" \
  -d '{"pids":[41540,41622],"force":false}' http://127.0.0.1:8099/api/peers/kill
```

```json
{ "results": [ { "pid": 41540, "outcome": "signalled" },
               { "pid": 41622, "outcome": "refused", "error": "pid 41622 is not an inspector process — it may have exited and had its pid reused" } ] }
```

Each pid goes through the same check as the single route, at the moment it is signalled, and is
answered on its own line in the order you sent them: `signalled`, `noSuchProcess` or `refused`,
with the reason. The reply is `200` whenever the body is well formed, whatever each pid's outcome,
so read `results` rather than the status. 1 to 100 pids, repeats answered once; `force` is
optional. A body that is not that shape is `400`, and one not sent as `application/json` is `415`.

Processes launched some other way — `./gradlew run`, an IDE run configuration — do not carry that
argv shape and so are not listed. Kill those where you started them.

---

## 4. Restart

### From the web UI

**Restart** on the **Processes** page, under *This daemon*. The daemon relaunches itself and the
page reconnects on its own — no manual reload. If it has not reconnected 20 s later, the page says
the daemon is lost rather than reading `Restarting…` for ever. The archive is untouched, so every
session is still there afterwards.

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

The daemon can also write the file, and the web UI's **Settings** page does, under *The daemon's
config.json*: it shows what this daemon runs on beside each value, disables a key a flag sets, and
does not offer `port` (a page that saved a new port and restarted would be left dialling one nobody
listens on). `GET /api/config` shows the file, the values this daemon is running on, and which keys
a flag is overriding; `PUT /api/config` saves:

```bash
curl -s http://127.0.0.1:8099/api/config
curl -s -X PUT -H "X-Inspector-Control: 1" -H "Content-Type: application/json" \
  -d '{"maxSessions":50,"signalCaps":{"cache":2000}}' http://127.0.0.1:8099/api/config
```

```json
{ "path": "/Users/you/.inspector/config.json",
  "file": { "maxSessions": 50, "signalCaps": { "cache": 2000 } },
  "effective": { "port": 8099, "maxSessions": 100, "maxTotalMb": 300, "signalCaps": { "cache": 500, "state": 500 },
                 "routeTemplates": [], "findings": { "rules": [ … ], "payment": { … } } },
  "flags": [] }
```

- **Nothing but `findings` applies until the daemon restarts.** `effective` is what this daemon
  runs on and stays so; `file` is what the next start reads. A port cannot move under a listening
  socket. The one exception is `findings` (below): it is read again on the next findings read, so
  `effective.findings` is the file as last read, and `findingsConfigError` appears when the key on
  disk could not be read.
- **Flags still win.** A key in `flags` was set on the command line and overrides the file, so
  saving it changes nothing until the daemon starts without that flag.
- **A save is a merge.** The keys you send replace those keys; every other key in the file is kept
  as it was, including ones this version does not know. `null` removes a key, so its default applies
  again (inside `signalCaps`, `null` still means uncapped).
- **An unknown key is refused by name** with `400`, as is a value out of range (`port` 1–65535,
  `maxSessions` and `maxTotalMb` at least 1, a `signalCaps` cap `null` or at least 0, a
  `routeTemplates` entry that is not a valid template, anything in `findings` its section below
  does not allow).
- **A malformed file is refused with `409` and left untouched**: a save will not overwrite settings
  it could not read. Fix or remove it by hand; `GET` reports it as `fileError`.
- **The write is atomic.** The new file is written beside the old one and renamed over it, so a
  crash leaves one whole file or the other. A `config.json` that is a symlink stays one.

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

#### `routeTemplates` — how paths group into routes

The Stats page, `GET …/stats`, `inspector stats` and the MCP tool `route_stats` group calls by
**route**: the method, the host and the path with its id-like segments read as `{id}` — numeric
(`123`), UUID, hex of 8 or more characters with a digit, and base64-like runs of 20 or more with a
digit and both cases. That is a heuristic. Where it groups wrongly, list templates; the first
that matches a path wins, and anything no template matches falls back to the heuristic:

```json
{ "routeTemplates": ["/reports/2024", "/articles/{slug}", "/v2/users/{userId}/avatar"] }
```

A template starts with `/` and matches a path with the same number of segments: a literal segment
must be equal, case included, and `{name}` takes any one non-empty segment. `/reports/2024` keeps
a year from reading as `{id}`; `/articles/{slug}` groups slugs the heuristic would keep apart.
Trailing slashes count, so `/users/{id}` does not match `/users/1/`. No regular expressions. At
most 200 entries.

Like every key here but `findings`, `routeTemplates` applies when the daemon next starts, and then to every
recorded session, because routes are computed when they are read. `PUT /api/config` accepts it
and refuses an invalid entry with a 400 that names it. An invalid entry written by hand is
reported on stderr at start and skipped, and the rest apply. `inspector mcp` reads the file when
your editor starts it, so after an edit, kill it under Processes and the editor starts a fresh
one. Until then an agent's `route_stats` groups by the old templates.

#### `findings` — what the findings rules look for, and which are on

Findings (§7) come from rules the daemon ships. This key turns rules off, changes their severity,
and tells the payment rules what an idempotency key and a payment path look like here:

```json
{ "findings": {
    "rules": {
      "payment.retry-with-key": { "enabled": false },
      "payment.duplicate-mutation": { "severity": "medium" } },
    "payment": {
      "idempotencyHeaders": ["Idempotency-Key", "X-Idempotency-Key"],
      "paymentPaths": ["payment", "checkout", "charge", "transfer", "refund", "payout"],
      "ignorePaths": [],
      "duplicateWindowMs": 3000,
      "resubmitWindowMs": 60000 } } }
```

- **`rules`** is keyed by rule id (the table in §7, "Findings"). Each entry takes `enabled`
  (`true` or `false`) and `severity` (`high`, `medium` or `info`), and nothing else; a rule with no
  entry runs at its default.
- **`payment`**: a field you set replaces its default; the values above are the defaults.
  - `idempotencyHeaders`: 1 to 20 header names, matched in any case. The first one with a
    non-blank value is the call's key.
  - `paymentPaths` and `ignorePaths`: up to 100 patterns each, written as `path:` takes them but
    without `path:`, at most 256 characters. A pattern with a `*` is a glob (`*/charges` matches
    `/v1/charges` and not `/v1/charges/1`); one without is a substring (`payment` matches
    `/v1/payments/7`), in any case. `path:<pattern>` in the traffic filter finds the same calls.
    `charge` also matches `/recharge`; list such paths in `ignorePaths`, whose calls no payment
    rule looks at.
  - `duplicateWindowMs` (Duplicate mutation) and `resubmitWindowMs` (Ambiguous resubmit): whole
    milliseconds, 1 to 600000.
- **It applies on the next findings read, unlike every other key.** The daemon and `inspector mcp`
  check the file's size and modified time on each read of findings and parse the key again when
  either changed; no restart, for either.
- `PUT /api/config` refuses a bad value with a 400 naming it. A hand edit with a bad entry has that
  entry named on stderr and skipped, and the rest applies; a hand edit that is not JSON, or a
  `findings` that is not an object, keeps the settings last read and says so in the findings
  reply's `configError`. A `findings` that is not an object also makes the file malformed for every
  other key, as any wrong-typed key does.

Nothing here is encrypted and, with redaction off (the default), bodies and headers contain real
credentials. Treat `~/.inspector` as you would a log directory full of tokens.

### Deleting sessions

A pile of one-run simulator sessions is the normal state of this directory. Retention prunes the
oldest once the archive passes `maxSessions` or `maxTotalMb`, but you can remove them yourself.

From the web UI: the **Sessions** page lists every session with a checkbox, presets (**keep last N**
and **older than D days**, which never select a session still recording), `delete selected`, a
per-row `✕` and **clear all**. Each arms on the first click and fires on the second, and a bulk
delete says what happened to each session, the daemon's reason included when it refused one.

From a terminal:

```bash
curl -X DELETE -H 'X-Inspector-Control: 1' \
  http://127.0.0.1:8099/api/sessions/2026-08-17T05-57-58_myapp_Pixel-8_debug
```

```bash
curl -X POST -H 'X-Inspector-Control: 1' http://127.0.0.1:8099/api/sessions/clear
```

Several named sessions in one request:

```bash
curl -X POST -H 'X-Inspector-Control: 1' -H 'Content-Type: application/json' \
  -d '{"ids":["2026-08-16T21-03-11_myapp_Pixel-8_debug","2026-08-14T08-22-40_myapp_Pixel-8_debug"]}' \
  http://127.0.0.1:8099/api/sessions/delete
```

That one answers per session, in the order you sent them, with `deleted`, `refused`, `notFound` or
`failed` and the reason, plus the ids that went, how many remain and the bytes freed. It is `200`
whenever the body is well formed, so read `results`; 1 to 1000 ids, repeats answered once. A body
that is not that shape is `400`, and one not sent as `application/json` is `415`.

All three report what went, what stayed and how many bytes came back. Two rules the daemon enforces
rather than trusting the caller with, on all three:

- **A session still being written is never deleted.** Removing the folder underneath an open
  writer corrupts the append stream and throws away the traffic you are looking at. `clear` skips
  it and names it in `kept`; a single delete returns `409`; a bulk delete answers it `refused`.
- **`latest` is refused as a delete target, not resolved.** It names a different folder depending
  on when you call it, which is fine for a read and is not fine for a delete — the session it
  points at is the one you are most likely to still want. Name the session explicitly.

Deleting the session `latest` points at repoints the link at the newest survivor, so `latest` keeps
working; deleting the last session removes the link rather than leaving it dangling.

Nothing is recoverable. There is no trash.

### What removed a session

Because nothing is recoverable, every removal writes a line naming the session, its size and
which of the three paths took it — a single request, the clear-all sweep, or retention hitting a
ceiling:

```
inspector: deleted session 2026-08-17T05-57-58_myapp_Pixel-8_debug (412 KB, requested)
inspector: deleted session 2026-08-16T21-03-11_myapp_Pixel-8_debug (1180 KB, clear all)
inspector: deleted session 2026-08-14T08-22-40_myapp_Pixel-8_debug (904 KB, retention)
inspector: cleared 2 session(s), kept 1, freed 1592 KB
```

`clear` adds that last total beside the per-session lines, since it is the call that removes many
at once. A session that is skipped is named in the response's `kept`, not logged.

This matters more than it looks. Sessions vanishing with nothing in the output to say what took
them is indistinguishable from a bug in the daemon, and the archive is the only copy — so if you
are ever short a session, the daemon's terminal is the first place to look, and the cause in that
line is the half of the question that is hard to answer afterwards.

If you run the daemon detached, keep its output: `inspector serve > ~/.inspector/daemon.log 2>&1 &`.
Nothing rotates it, and nothing else writes these lines.

---

## 7. Reading sessions without the web UI

Every command defaults to the `latest` session; pass `--session ID` for another. `--data DIR` works
on all of them, so you can read an archive a different daemon wrote.

```bash
inspector sessions                                  # list them
inspector summary                                   # counts, hosts, error breakdown
inspector query 'status>=400'                       # filter, same grammar as the UI
inspector query 'has:error' --limit 10 --offset 0
inspector stats 'status>=400' --sort p90   # per-route latency and failure rates
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

### Marking a moment

The web UI has a field under the **markers** button. Markers are what make "what happened when I
tapped checkout" a lookup instead of timestamp arithmetic, and they back the
`since:marker("…")` filter term.

A marker needs a session your app is **still connected to** — there is no clock to place it on
once the app has gone, and the daemon refuses with a 409. That is not the same as the session
having an `endedAt`: one whose daemon was killed, or whose app vanished without a clean close, has
neither. `GET /api/recording` lists the sessions something is actually writing to, which is the
question the button asks before enabling itself.

```bash
curl -s http://127.0.0.1:8099/api/recording
curl -s -X POST http://127.0.0.1:8099/api/sessions/latest/markers \
     -d '{"label":"tapped checkout","source":"user"}'
```

`source` is one of `app`, `agent` or `user`, and defaults to `agent` — which is what every caller
was before the web UI grew the button. An unrecognised value is refused rather than stored: the
archive outlives the binary that wrote it, and a fourth value would render as nothing everywhere
that reads one.

Posting a marker deliberately needs **no** `X-Inspector-Control` header. That header exists to stop
any page you have open from POSTing to the daemon's control endpoints, and a marker is not a
weapon.

### Exporting a whole session

One file holding everything a session recorded — every call with both bodies, every signal and
payload, every marker — in the form that suits whoever receives it:

```bash
curl -O -J 'http://127.0.0.1:8099/api/sessions/latest/export.html'   # the web UI, offline
curl -O -J 'http://127.0.0.1:8099/api/sessions/latest/export.md'     # a document to read
curl -O -J 'http://127.0.0.1:8099/api/sessions/latest/export.json'   # everything, for tools
```

The web UI's **export** menu, beside the session picker, offers the same three plus HAR. An agent
can call the MCP tool `export_session`, which writes the files to `~/.inspector/exports/` (or a
`dir` it names) and returns the paths.

| File | What it is |
|---|---|
| `.html` | **This web UI, working from disk.** Double-click it on a machine with no Inspector: the list, timeline, time axis, waterfall, signal browsers, detail pane, JSON tree and the Stats page's route table all work, because they are the same code with the session embedded. What needs a daemon is hidden, and a banner says so: the filter box (the grammar is evaluated by the daemon), sorting the route table or opening a route's calls from it, replay, adding markers, pulls, and process control. The time axis still narrows the view. |
| `.md` | **A readable record.** The session's details, then a Routes table (calls, attempts and retries, failures and latency per route), then a Findings section (each rule that found something, with its findings, the rows they name and their evidence; then the rules that found nothing or are off, and what could not be judged), then the merged timeline as a table, then one section per call in time order: URL, start time and duration, attempts, request and response headers and bodies. JSON bodies are re-indented, never re-serialised, so ids keep every digit. A body that is absent says why, from what capture recorded, and a binary one points at the JSON. |
| `.json` | **The complete document**: session metadata and summary, the route table as `routeStats`, the findings report as `findings` (§7, "Findings"; the HTML page reads it from the same place), the merged timeline, every transaction row exactly as archived with its bodies beside it, every signal with its payload, and every marker. A body is `{"encoding": "utf8" \| "base64", "text": …}`. |

All three are the **whole session** — no filter, no page limit. That differs from the live web
UI, which lists at most 1000 calls, so the export of a long session holds more than the page shows.

Two things the exports say out loud rather than leave to be inferred: what **redaction** removed,
in the Markdown header and on each call, and that a row is one **attempt** of a call that
redirected or retried.

The files carry whatever capture recorded, credentials included unless the app enabled redaction.
Read one before you send it anywhere.

### Exporting a session as HAR

For handing a session to somebody who does not have Inspector. HAR 1.2 is what Chrome and Firefox
DevTools, Charles, Proxyman and Postman all import.

```bash
curl -O -J 'http://127.0.0.1:8099/api/sessions/latest/har'       # whole session
curl -O -J 'http://127.0.0.1:8099/api/sessions/latest/har?filter=has:error'
```

The `filter` parameter is the same grammar as everywhere else, because "export the four calls that
failed" is usually the reason to export at all. `-O -J` makes curl honour the filename the daemon
sends; without `-J` you get a file named `har`.

**Read the entry comments before drawing conclusions from one.** Inspector is not a wire-level
capture, and HAR was designed by tools that are, so several fields it asks for describe things
capture never saw. Every one of them is `-1`, which is what the spec reserves for "no information
available":

| Field | Why it is unknown |
|---|---|
| `timings.send` / `wait` / `receive` | There is one duration, measured around the call. `time` carries it. A plausible-looking breakdown would draw a waterfall in DevTools out of numbers nobody measured |
| `headersSize` | Capture holds parsed headers, never the raw block |
| `httpVersion` | Ktor's client does not surface the negotiated version at this layer |

`bodySize` is **not** unknown — `reqBytes`/`resBytes` are true sizes, counted even when the body
itself was not captured. Cookies are empty arrays; the `Cookie` and `Set-Cookie` headers are
present and complete, so nothing is lost.

Two things the export discloses per entry, in `comment`, that a HAR has no field for: that a row is
one **attempt** of a call that redirected or retried, and **what redaction removed**. Handing
somebody a redacted capture as though it were complete is the kind of omission that gets read as
"no credential was sent". A third joins them when there is one: each finding that names the entry,
as `finding <severity> <ruleId>: <message>`, from the whole session's findings whatever `filter`
kept. An entry no finding names is exactly what it was before findings existed.

A response body that is not valid UTF-8 travels base64-encoded, which the format supports. A
**request** body that is not valid UTF-8 does not: `postData.text` is a string and HAR offers no
encoding escape for it, so it is reported as absent with the reason rather than decoded into
replacement characters that would read as a captured body.

### Route stats

```bash
curl -s 'http://127.0.0.1:8099/api/sessions/latest/stats'
curl -s 'http://127.0.0.1:8099/api/sessions/latest/stats?filter=method:POST&sort=p95&limit=20'
inspector stats 'host:api' --sort errors
```

One row per method, host and route, over the whole session or what `filter` keeps. Calls to
one IPv6 server and port are one row whichever client stored the host (`::1` from OkHttp, `[::1]`
from Ktor), and the row's `host` is the bare literal. Each row holds `calls`
(distinct calls) and `attempts` (each redirect hop or retry is one), `retries` and `retried`,
counts per status class, `transportFailures`, rates over attempts that had an outcome, and
`p50Ms`/`p90Ms`/`p95Ms`/`p99Ms`/`maxMs` over attempts that got a response. A transport failure is
counted and never enters latency. Percentiles are nearest-rank; **below 20 samples `p95Ms` and
`p99Ms` are absent and `tailWithheld` reads `n<20`**. A field that is null is absent from the
reply. `sort` is `attempts` (default), `p50`, `p90`, `p95`, `p99`, `max` or `errors` (by
`errorRate`), nulls last; `limit` defaults to 1000, at most 5000, and `routeCount` says how many
there were.

Each row's `filterTerm` opens the matching calls in the traffic list, or in `list_transactions`;
a row on the web UI's Stats page opens Traffic on it, and says so above the list. It is a
**superset**: the filter grammar's `*` crosses `/`, so
`path:/users/*/profile` also matches `/users/1/x/profile`, which the route keeps apart. When
`filter` has no `|`, the term includes it; with one, it cannot, and the reply says
`"filterCarried": false`.

The JSON and HTML exports carry the whole session's table as `routeStats`, and the Markdown
export as a Routes section. A HAR does not.

### Findings

```bash
curl -s 'http://127.0.0.1:8099/api/sessions/latest/findings'
curl -s 'http://127.0.0.1:8099/api/sessions/latest/findings?category=payment&limit=20'
inspector findings --session latest --category payment
```

What the daemon's rules flag in a session, worked out from every call when you ask and never
stored. `category` narrows to one family (`payment` for now); an unknown one is a 400 naming those
there are. `limit` defaults to 1000, at most 5000. There is no `filter`: a finding is about calls
next to each other, and hiding one of them would make the rest look safe.

The reply holds `ruleVersion` (a hash of the rules and their settings), `total` rows read,
`findingCount` and the `high`/`medium`/`info` counts before the limit, `rules` (every rule checked,
with `enabled`, its `severity` and `count`, so a 0 is "checked, none found"), `findings`, and
`coverage`, which says per category what could not be judged (a body capture truncated cannot be
compared). Each finding has `ruleId`, `severity`, `route`, `message`, `txnIds` and `callIds` (at
most 200 each; `txnCount` is all of them) and `evidence`, one line per row. **Evidence never
carries a captured value**: bodies are compared by hash in memory and never shown, an
idempotency key shows only as `•••• 9f3a`, and a transport failure only by its exception class.

The payment rules look at POST, PUT, PATCH and DELETE calls:

| Rule id | Fires when | Severity |
|---|---|---|
| `payment.duplicate-mutation` | Two or more calls send the same request body to one route, each within `duplicateWindowMs` of the last, with no idempotency key | high |
| `payment.key-reused-body-changed` | One idempotency key goes out with two different request bodies | high |
| `payment.retry-without-key` | A call is retried automatically and some attempt has no key, or the key changed | high |
| `payment.ambiguous-resubmit` | A call ends in a timeout or transport failure that may have reached the server, and the same route is called again within `resubmitWindowMs` without the same key | medium |
| `payment.key-missing-payment-path` | A call to a path matching `paymentPaths` carries no key | medium |
| `payment.retry-with-key` | A call is retried automatically with the same key on every attempt: the safe case | info |

A duplicate is never claimed from a body capture did not keep whole, or with a redacted path. A
redirect hop is not a retry, and a failure that never reached a server (`UnknownHostException`,
`ConnectException` and the like) is not an ambiguous resubmit. Rules and their settings are the
`findings` key in §6.

Every export carries them: the JSON and HTML as `findings`, the whole session's report with no
category or limit; the Markdown as a Findings section before the timeline; the HAR as a note on
each entry a finding names.

---

## 8. The MCP server

### From the web UI — Processes

The **Processes** page has an MCP section with a **test** button and a copyable registration command.

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

`list_findings` reads `config.json`'s `findings` key on every call, so it needs no restart after a
findings edit, unlike `routeTemplates`.

Registration for Claude Code, Cursor and Codex is in [`INTEGRATION.md`](INTEGRATION.md) §10.

**Ordering.** `timeline` and `explain` order entries through the same `timeline()` rule as both
UIs. In 1.3.0 and earlier, a marker at the same millisecond as a call was listed after it; it now
comes before. `explain` splits its `before` and `after` context by position in that order, so a
marker dropped at the call's own millisecond is reported as leading to it, not as caused by it.
The same positional rule moves another *call* at the same `mono`, recorded before it, into
`before`, where a split by time put it in `after`. A signal at that millisecond stays in `after`:
the shared rule puts calls ahead of signals in a tie, whatever order they were recorded in.

To drive it by hand, which is the fastest way to check a tool change:

```bash
printf '%s\n' \
  '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{}}}' \
  '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"session_summary","arguments":{}}}' \
  | inspector mcp
```

---

## Why control needs a header

`POST /api/server/stop`, `/api/server/restart`, `/api/peers/{pid}/kill`, `/api/peers/kill` and
`/api/sessions/delete`, and `PUT /api/config`, require `X-Inspector-Control: 1`.

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

**A USB Android device records nothing.** Check `adb reverse --list` first: an empty list is the
usual answer, because the forward does not survive a replug or an adb restart. The app prints the
reason it could not connect, and on a device it names `adb reverse` explicitly. If the forward is
listed and it still fails, it is the cleartext exemption for `127.0.0.1` (`INTEGRATION.md` §6d).

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
