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
through the ordinary peer kill so the editor respawns it. `McpControlTest` asserts the probe leaves
nothing behind.

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
routes fails. **Revisit on a Ktor upgrade**, like `rawContent` above: the test depends on
`getAllRoutes`, on `lineage()`, and on `webSocket` putting its handler below the GET node under
header selectors, which is how the two handshakes are recognised as GET and printed as exempt.

**"Exempt" there is a fact about the header, not a claim that those routes are safe.** A browser
cannot put a custom header on a WebSocket handshake, so this header cannot guard `/api/live` or
`/ingest`. Two security-boundary gaps found while building phase C are tracked privately and are
open decisions for the maintainer; do not build a fix for either without that decision.

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
