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
`finally { process.destroyForcibly() }`, and `McpProbeCleanupTest` holds it on the success, timeout,
failed-write and silent-child paths. The probe reads the child's stdout on a thread of its own and polls it
against its deadline, because a `readLine` on its own thread waits for good on a child that holds stdout
open and never writes. The test drives a stub child that **ignores EOF** through `ProcessMcpControl`'s
internal launch seam: the real server exits when the probe closes its stdin, so a test against it
passes with the kill deleted. The seam stays off `InspectorDaemon`'s constructor.

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

**A bulk control route answers per item, through the same rule as its single route.**
`POST /api/peers/kill {pids}` calls `PeerRegistry.kill` once per pid, so each pid's identity is
re-verified at kill time and the daemon's own is refused exactly as on the single route; `POST
/api/sessions/delete {ids}` calls `SessionDeleter`, the one place that refuses the active session and
the `latest` alias, which the single delete and `clear` call too. Both answer 200 with one result per
item in request order whenever the body is well formed, because a batch that reports one status for
five kills hides the one that failed. Both read nothing from a request but its JSON body and are
registered only inside `controlGated { }`, so whatever the control boundary later requires is a
change to the gate, never to them. `SessionBulkDeleteTest` and `PeerBulkKillTest` pin it, the latter
with a live process that is not an inspector refused and left running; `SessionDeletionTest` passed
unchanged across the extraction.

**`config.json` is written by merging into what is there and renaming over it, and flags still
win.** `PUT /api/config` merges the keys it knows into the file's JSON object and keeps any other
key as it was, because the file is also edited by hand and will carry the findings rules. It writes a
temporary file in the same directory and moves it over the old one atomically, so a crash leaves the
old file or the new one, never half of each. A malformed file is refused with 409 and left untouched:
a save must never overwrite settings it could not read. An unknown key in the request is a 400 that
names it, since a misspelt key silently dropped is a setting the user believes they made. Nothing
but the `findings` key applies until the next start (see "The findings settings are read on the
next findings read" below) — a port cannot move live and `Retention` reads the config it was built
with — and `GET` reports the flags that override the file so the page can say why a value will not
change. `ConfigFileTest` pins each rule.

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
unchanged and still changed behaviour. When nothing matches, Ktor 3.5.0 keeps a later failure over
the one it holds if the later one is of higher quality or deeper, but only while every selector on
its trail is constant or transparent. The transparent node counted towards that depth, so the gated
subtree's path failure sat one level deeper than the open `GET /api/sessions` failing on its method;
wherever that subtree was resolved second, which is a matter of registration order,
`PUT /api/sessions` answered 404 instead of 405, and so did three other paths. Every guard stayed
green; the base-against-head route matrix found it. `ControlGateTest` case 9 and
`WrongMethodStatusTest` pin it. A nested `controlGated` reaches a route twice, and the plugin is
installed only when absent, because a second install throws. A response from the plugin's `onCall`
stops the handler only because Ktor 3.5.0's routing wrapper checks `isHandled` before invoking each
handler. That is a dependency on Ktor internals, so revisit it on a Ktor upgrade; `ControlGateTest`
counts handler runs and fails if it stops holding. What the gate cannot do is notice a control route
registered outside it, or added to a node that already had a handler. `ControlHeaderCoverageTest`
walks every registered route, and it is what catches that. The reverse is as quiet: a gated route
registered first, then an open route landing on the same node, gates the open one too.
`ControlHeaderCoverageTest`'s allowlist check catches that for the marker route, and
`ReadRoutesOpenTest` catches it for every GET.

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
`2025-06-18` — `McpProtocolTest.initialize_reports_the_version_it_was_built_as` caught it.

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
rule both adapters serve lives in `service/` or `archive/`, never in a route or tool body." The
session delete and clear loops were the last such copy in a route body. They moved into
`service/SessionDeleter` with the bulk delete, its second caller, and not before: with one caller
the extraction had nothing to prove it right. The signal listing was the other until
`item:signals-query-one-rule`.
`GET …/signals` sorted by `mono`, clamped its limit and matched `tag` with `equalsIgnoreCase` in the
route body and ignored `filter`, while `list_signals` used the grammar through
`SessionRepository.querySignals`. Both now read `querySignals`, the one place signals are filtered,
sorted and clamped. The route keeps only its parameters, its default limit of 100, the 400 carrying
the parser's message and the bare-array reply. Its `tag=` is ANDed in as a constructed `TagTerm`,
never pasted into the filter text, because a tag value holding `|` or whitespace would otherwise
rewrite the query. `SignalQueryTest` pins it: the route and the tool return the same ids in the same
order across eight filters, on a session whose `mono` ties are written out of archive order and whose
`ts` runs against `mono`. Every mechanism in the scan must find something to check, so an import
reader or a name matcher that stops matching fails instead of reporting a clean tree.

**A route is the daemon's template of a path, and the page never derives one.** The traffic
page's endpoint chips key on the last path segment (`shared-rules.md`) and answer "take me there
in one tap"; a route keys on method, scheme, host, port and the whole path, with numeric, UUID,
long hex and long base64-like segments read as `{id}`, and answers "how does this route behave".
They are kept apart and named apart, "endpoint" for the chips and "route" everywhere else,
because one name for both made two different numbers read as a bug (ROADMAP §6, decided
2026-10-17). The rule lives in `archive/RouteTemplates.kt`, not `:inspector-model`, so it needs
no web mirror: the daemon returns each row's route and filter term and the page draws them. It is
in `archive/` so `ConfigFile`, the config's validator, can reach its override syntax. The host is part of the key
because one template on two servers is two routes, and the diff and the findings reuse the key.
One server is one host in the key, though: an IPv6 literal is keyed bare, because OkHttp stores
`::1` and Ktor `[::1]` and capture keeps both (`capture.md`), which made one server two rows with
half the calls each. Bare and not bracketed, because the row's `host:` term is a substring match
and only `::1` finds both stored forms; the page and the Markdown export bracket it again on read.
A host that is not a bracketed literal holding a `:` is keyed as stored, lowercased. `keyOf` is the
one place a key is built, so the stats, exports and MCP agree, and the diff and findings inherit it.
Trailing slashes and path case are kept, and the query never takes part. The segment rule is a
heuristic; among what it misses: a long camelCase name with a digit reads as `{id}`, and slugs,
short tokens and ids with an extension stay literal. `routeTemplates` in `config.json` overrides
it, first match wins, matched segment by segment with no regular expressions, so no entry can
make a stats read slow. Like every key in `config.json` but `findings` it is read at start (see
"`config.json` is written by merging …" above). The daemon and `inspector mcp` are two processes, so after an
edit each groups by the new templates once it has restarted. `RouteTemplatesTest` pins each
class and each miss, and `ConfigFileTest` pins the key's validation.

**A route's filter term is a superset of the route, and nothing tighter can be written.** The
grammar is frozen and its `*` crosses `/`, so `GET /users/{id}/profile` links with
`method:GET host:… path:/users/*/profile`, which also matches `/users/1/x/profile`. The term is
the path with each `{id}` segment, and each segment the tokenizer cannot carry, replaced by `*`;
a route with no `*` gets one in front, the chips' shape; the root route has no path term, because
no glob matches both an empty path and `/` without matching every path. Replacing whole segments
is what makes the superset provable: `globMatches` matches any value its pattern was cut from,
and lowercasing cannot cross a `/`. Normalising trailing slashes would break it, which is the
second reason they are kept. The stats filter is carried into the term only when the parser says
it has no top-level `|`, as marker chips ask the parser rather than look for a quote; with one,
the term is the route's alone and the reply says `filterCarried: false`. `RouteFilterTermTest`
parses every term and matches it against every path of its corpus; it was shown red with trailing
slashes normalised and with `{id}` emitted verbatim. It also asserts the known superset cases
match, so the Traffic caveat stays true if somebody tightens the term.

**Route stats count attempts, and a percentile below 20 samples is withheld, not printed.**
Every row is one attempt, so `attempts` counts rows and `calls` counts distinct `callId`s. A
retry is an attempt after a non-3xx attempt of the same call, and a redirect hop is one after a
3xx; the schema records neither, so this is inferred, from every row of the session and not the
filtered ones, or `status:200` would turn redirects into retries. Rates divide by attempts with
an outcome. Latency is over attempts that got a response: a transport failure is counted and
never averaged in, because a timeout is not a response time. Percentiles are nearest-rank, so
each is a latency somebody waited and two implementations cannot round differently. Below 20
samples p95 and p99 are absent and the row says `tailWithheld: "n<20"`, and the page prints that
string rather than compare against 20 itself. `RouteStatsTest` pins each rule; it was shown red
with interpolated percentiles, with the threshold at 19, with transport failures in the samples
and with the 3xx set built from filtered rows.

**Route stats are computed on read and folded from where the last read stopped.** Never on
ingest, so a slow stats read cannot slow archiving, and on `Dispatchers.IO`, so a cold read of a
large session does not hold a worker the live socket and `/ingest` share. They read every row
through `IndexTail`, never `queryTransactions`, for the reason `HarTest` exists. A cache entry
per session and filter keeps the byte offset after the last complete line; the
next read folds only what was appended, because `index.jsonl` is append-only. A final line
without its newline waits, because the writer flushes a long row in pieces; a session file that
shrank or was replaced, or a markers file that grew under a filter, rebuilds from the start.
Memory only, four entries. `RouteStatsServiceTest` holds a fold equal to a cold compute, and was
shown red with the offset advanced past an incomplete line. `StatsRouteTest` and
`RouteStatsToolTest` pin 1203 rows on both adapters.

**Findings are computed on read from every call, cached in memory, and never written to the
archive.** The rules run in `service/`, not `:inspector-model` (ROADMAP §5, decided 2026-10-18), so
the web UI mirrors nothing and the overlay has no findings. Each source folds a row once, through
the `IndexCursor` route stats also use, so a live refresh reads only what was appended, and the list
is collected again only when the row count or the rule version changed. Memory only, four entries:
the disk cache the roadmap allows under three conditions is not built, because nothing has measured
recomputing as slow. Rows are never read through `queryTransactions`, for the reason `HarTest`
exists. `FindingsServiceTest` holds a fold equal to a cold compute and a finding on the oldest of
1203 rows; it was shown red reading through `queryTransactions`.

**The findings settings are read on the next findings read, not at start; every other key still
waits for a restart.** The `findings` key is re-parsed whenever `config.json`'s size, modified time
or file key changed, and `ruleVersion`, a hash of the sources' versions and those settings, is part
of the cache key, so a changed rule rebuilds and an unchanged file costs one `stat`. The daemon and
`inspector mcp` are two processes, and the roadmap's reason for keeping rules in the config — an
agent and the page disagreeing about what counts as a finding is a worse bug than either alone — is
exactly what a read at start produced between a restarted daemon and an MCP server started before
the edit. A hand edit that does not parse keeps the last good settings and says so in `configError`.
`routeTemplates` stays read at start, so after a template edit a finding's route still changes only
on restart. Decided 2026-10-22 by the maintainer's proxy, for `findings` and, when the
sensitive-data item adds it, `findingDismissals`, and for no other key. `FindingSettingsSourceTest`
and `FindingsServiceTest` pin it, the latter with a second service instance standing for the MCP
process; it was shown red with the settings parsed once.

**A finding family plugs in as a `FindingSource`; the engine owns everything around it.** A source
folds rows into an accumulator and returns findings under the rule ids it registered in
`FindingRuleCatalog`, with its own coverage. The engine drops a disabled rule's findings and any
finding under an unregistered id, checks hints, orders by severity then time, applies caps, the
category and the limit, and serves the one route, the one MCP tool, the CLI and the exports. A
second family adds a source and one line in `FindingSources.all`, never a second endpoint, cache or
page, because a second copy of the order or the masking check is the one that would drift.
`FindingsEngineTest` pins the engine's half.

**A finding carries no captured value: evidence names where, and masks what.** Location and path use
the Redactor's syntax, so whether a location was redacted is answered by the same `redacted[]`
reader the diff uses. A masked hint is masked (`•••• 4242`), and the engine replaces any hint with
more than ten unmasked characters, so a source that forgets to mask leaks a few characters, not a
card. Request bodies are compared by digest in memory and never shown, not even as a hash, because a
hash of a short body — a PIN, an amount — is one guess from it, and no finding id hashes one.
Idempotency keys are held as digests. A transport failure is evidence by its exception class only,
because the message can carry a URL with its query. `FindingsLeakTest` plants secrets in headers,
query, bodies and an error message and finds none in the REST reply, the MCP text, any export's
findings or the accumulators' fields; it was shown red with the raw key put in a hint and the error
message put in a note, and it fails if nothing was planted.

**Payment rules compare whole request bodies byte for byte, and claim nothing about a body capture
did not keep.** Whole is the diff's `BodyEvidence` answer, and a body with a redacted path counts as
unknown, because equal after redaction is not equal. Byte equality rather than parsed equality,
because the same code path serialises a resend the same way, and a canonical JSON form would be a
second parser to agree with; a body carrying a client nonce is therefore never a duplicate here,
which is a known miss. An idempotency header redacted at capture counts as present with its value
unknown. "Retry" is route stats' retry, through `RedirectHops`, so a 307 that re-sends a POST is a
hop of one call. A failure that never reached a server (`UnknownHostException`, `ConnectException`
and three more, by class) is not an ambiguous resubmit. `PaymentRulesTest` pins each trigger and
boundary.

**A daemon class decoded from outside input keeps its pre-move serial name.** kotlinx-serialization
quotes a class's serial name in `MissingFieldException`, and a serial name defaults to the fully
qualified class name. The replay and signal-pull routes echo that message into their 400 body, and
the `config.json` reader prints it to stderr, so moving `ReplayRequest`, `SignalPullRequest` or
`DaemonConfig.FileConfig` to another package would change what a caller reads, with no other
symptom. Each carries `@SerialName` with the name it had before phase D, and a comment saying not to
"correct" it to the current package. They are the only three daemon-owned types ever decoded;
encode-only types never write their serial name. `SerialNameTest` pins all three.
