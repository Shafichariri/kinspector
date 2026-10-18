# Decisions — daemon and MCP
Owned by `:inspector-daemon`: the server, its security boundary, replay, peers and the MCP server. Imported by the `inspector-daemon` guide.

**Replay applies edits before asking the app to sign.** A signing scheme typically covers the
method and path, so signing first and editing after produces a signature for a request that was
never sent. Doing it in the wrong order works fine for unedited replays, so it would pass every
test that did not specifically edit a path — the worst possible failure distribution.
`ReplayTest.an edited path is what gets signed` pins it.

**The signing hook asks for headers, not for a signature over bytes.** `ReplaySigner` takes a
method and a URL and returns headers. Inspector therefore never learns the canonical-string format,
the algorithm, or which headers are involved, which means replay works for any scheme and no part
of this codebase becomes a signing oracle. `:inspector-noop-stream` declares the same type and
parameter so a consumer's call site compiles under `-Pinspector=off`; the sample passes one
specifically to keep that parity honest at compile time.

**MCP tools reject arguments they do not declare.** They used to ignore them. `list_sessions`
reports the field as `sessionId`, so calling another tool with `sessionId` left `session` absent,
defaulted to `latest`, and answered about a *different session* with no sign anything was dropped —
a confidently wrong answer to an agent that cannot tell. The allowlist is derived from
`descriptors()` so a new parameter cannot drift out of it, and the error names `session` explicitly
when it sees `sessionId`. Reported from a consuming app.

**There is no "start the MCP server" endpoint, and there should not be.** The MCP server speaks
stdio — `McpServer.run` blocks on `input.readLine()` and stops at EOF — so a process the daemon
spawned would have no client on its pipes and would exit at once or hang holding an empty one. The
editor owns that lifecycle. The two operations that mean something are `POST /api/mcp/probe`, which
spawns a throwaway server, handshakes, reports the tool count and stops it, and killing a wedged one
through the ordinary peer kill so the editor respawns it. `McpControlTest` (in
`PeerRegistryTest.kt`) asserts that probing requires the header and spawns nothing without it, and
that a probe reports a tool count or a reason. That a probe leaves no process behind rests on its
`finally { process.destroyForcibly() }` and is not pinned by a test; `ROADMAP.md` §3 → Guards
carries it.

**Peers are identified by argv token, never by command-line substring.** `PeerArgv.MAIN_CLASS`
is matched against `ProcessHandle` arguments by exact equality. Substring matching is what makes
the manual alternative dangerous: `pkill -f inspector` also kills the editor's MCP connection, and
`pkill -f "inspector.*serve"` kills it too, because the classpath contains `ktor-server-cio-*.jar`
and "server" contains "serve". Both traps are documented in `docs/DAEMON.md`; the token match is
what makes them unreachable from the UI. `PeerRegistryTest` asserts an `mcp` process whose
classpath carries that jar is still reported as `mcp`.

The kill endpoint **re-verifies process identity at kill time** rather than trusting the pid the
client sends back. A process can exit between listing and killing and have its pid reused, so a pid
alone is not evidence of what it identifies. Without that re-check the endpoint would be a
"kill any pid" facility on an unauthenticated loopback port. It also refuses the daemon's own pid —
`/api/server/stop` is the path that replies before shutting down.

**The daemon's control endpoints require `X-Inspector-Control: 1`.** Not ceremony. The daemon
listens on loopback with no authentication, so any page the developer has open can POST to
`127.0.0.1:8099`; a form post or a `no-cors` fetch would otherwise be an off switch for anyone's
web page. A custom header forces a CORS preflight, and this server answers none, so only its own
page gets through. Read-only endpoints are deliberately left open — `HttpMarkerPoster` and the
MCP tools post markers without it, and a marker is not a weapon.

`ControlHeaderCoverageTest` pins this from the server's own route tree rather than from a list in
the test. It walks every endpoint Ktor registered, sends every non-GET one a request with no header
and one with `X-Inspector-Control: 0`, and expects from both a 403 whose body names the header — so
a 403 from anything other than the guard does not count, and a future control gate must keep the
header's name in its refusal. A route with no method selector answers any method, so it is probed
too. GET is exempt wholesale, so the test also rests on no GET route ever changing anything. A new
control route is covered by being registered, which matters because `ROADMAP.md` §6 adds bulk kill
and bulk delete. `POST /api/sessions/{id}/markers` is the only
allowlist entry. Any new entry widens what a web page can do to the daemon, so it is a
`ROADMAP.md` decision, not a line added to make a test pass. Every side effect is an inert fake,
so a broken guard shows up as a wrong status rather than an exited test JVM. A scan that finds no
routes fails. **Revisit on a Ktor upgrade**, like `rawContent` in `capture.md`: the test depends on
`getAllRoutes`, on `lineage()`, and on `webSocket` putting its handler below the GET node under
header selectors, which is how the two handshakes are recognised as GET and printed as exempt.

**"Exempt" there is a fact about the header, not a claim that those routes are safe.** A browser
cannot put a custom header on a WebSocket handshake, so this header cannot guard `/api/live` or
`/ingest`. Two security-boundary gaps found while building phase C are tracked privately and are
open decisions for the maintainer; do not build a fix for either without that decision.

**Control routes are gated by one route-scoped plugin, and the coverage test, not the gate, catches
a route left outside it.** `controlGated { }` in `http/ControlGate.kt` installs the `ControlGate`
plugin on each route its block registers, and the plugin refuses a call without
`X-Inspector-Control: 1` with the same 403 and the same body the eight per-route checks gave. It is
installed on the routes themselves and adds no node of its own, and that is load-bearing. The first
version hung the gated routes under a shared transparent child node, which left paths and templates
unchanged and still changed behaviour: Ktor reports the deepest failure it met while resolving, the
transparent node counts towards that depth, so `PUT /api/sessions` failed on the path inside the
gated subtree rather than on the method of the open `GET /api/sessions`, and four paths answered 404
where they had always answered 405. Every guard stayed green; the base-against-head route matrix
found it. `ControlGateTest` case 9 and `WrongMethodStatusTest` pin it. A nested `controlGated`
reaches a route twice, and the plugin is installed only when absent, because a second install
throws. A response from the plugin's `onCall` stops the handler only because Ktor 3.5.0's routing
wrapper checks `isHandled` before invoking each handler. That is a dependency on Ktor internals, so
revisit it on a Ktor upgrade; `ControlGateTest` counts handler runs and fails if it stops holding.
What the gate cannot do is notice a control route registered outside it, or added to a node that
already had a handler. `ControlHeaderCoverageTest` walks every registered route, and it is what
catches that.

**A posted marker says who dropped it, and `source` is not decoration.** `MarkerSource.USER`
existed with no caller until the web UI grew the button: every marker in every archive said `app`
or `agent`. A marker left by an agent working through a session is a different claim from one a
person dropped while watching it, and whoever reads the archive later is entitled to tell them
apart. The field defaults to `agent`, because that is what every caller was before, and an
unrecognised value is refused rather than stored — the archive outlives the binary, and a fourth
value would render as nothing in both UIs and in the MCP tools.

**`ServerControl` is an interface because the real one ends the JVM.** The daemon tests run a
daemon inside the test JVM; a hardcoded `exitProcess` in the stop handler would kill the test
runner rather than fail a test. On restart the port is released *before* the replacement spawns —
the other order has the new process fail its own pre-flight bind and exit, leaving no daemon at
all.

**Session resume is decided from `endedAt` on disk, not memory.** An in-memory "recently closed"
map is empty after a daemon restart — which is one of the very cases the grace period exists to
cover — so it silently refused every resume. The round-trip test caught it.

**The daemon binds 127.0.0.1 only.** There is no auth and the archive holds unredacted
credentials by default. The loopback bind *is* the security boundary; do not widen it without
adding authentication first.

**MCP tool failures are results, not JSON-RPC errors.** An agent can read `isError: true` with a
message and correct itself; a transport-level error just ends its turn. Reserve JSON-RPC errors
for protocol problems like an unknown method.

**`serverInfo.version` is the jar's version, and the release job checks it against the tag.** It
was the literal `1.0.0` from 1.0.0 through 1.1.0 — the number an agent reads to tell which server
it is talking to, and it never moved. `McpServer` now takes `daemonVersion()`, the manifest value a
HAR export's `creator` already uses. Watch the name: `initialize()` had a local `version` holding
the *protocol* date, and the first attempt at this fix shadowed the property with it and reported
`2025-06-18` — `McpTest.initialize_reports_the_version_it_was_built_as` caught it.

**`inspector mcp` owns stdout.** Anything printed there that is not a JSON-RPC frame
desynchronises the client. Diagnostics go to stderr.

**The daemon's packages are layers, and an import scan holds them because `internal` cannot.**
Kotlin's `internal` is module-wide, so to the compiler `archive/`, `service/`, `http/`, `mcp/` and
`usb/` are one visibility domain, and a package boundary enforces nothing by itself.
`DaemonLayersTest` in `:repo-checks` reads the imports and the inline qualified names instead.
`archive/` imports no other layer and `service/` imports `archive/`. `http/` may also import
`io.ktor`. `mcp/` reads `archive/` and `service/` and never Ktor. `usb/` imports no daemon package,
because the bridge goes through the daemon's own `WS /ingest`. The root package holds `Main.kt`
alone. The adapters read `archive/` directly rather than only through `service/`, because the strict
version needs a pass-through wrapper for every repository method, which is a second copy of each. "A
rule both adapters serve lives in `service/` or `archive/`, never in a route or tool body." Two
places break that today, recorded here and not fixed. `GET …/signals` sorts by `mono` descending and
clamps its limit to 1..1000 in the route, while the MCP `list_signals` goes through
`SessionRepository.querySignals` and filters differently: the route matches `tag` with
`equalsIgnoreCase`, the tool uses the filter grammar. And the session delete and clear loops live in
`http/SessionRoutes.kt`. Every mechanism in the scan must find something to check, so an import
reader or a name matcher that stops matching fails instead of reporting a clean tree.

**A daemon class decoded from outside input keeps its pre-move serial name.** kotlinx-serialization
quotes a class's serial name in `MissingFieldException`, and a serial name defaults to the fully
qualified class name. The replay and signal-pull routes echo that message into their 400 body, and
the `config.json` reader prints it to stderr, so moving `ReplayRequest`, `SignalPullRequest` or
`DaemonConfig.FileConfig` to another package would change what a caller reads, with no other
symptom. Each carries `@SerialName` with the name it had before phase D, and a comment saying not to
"correct" it to the current package. They are the only three daemon-owned types ever decoded;
encode-only types never write their serial name. `SerialNameTest` pins all three.
