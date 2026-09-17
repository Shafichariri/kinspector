# Inspector schema

The contract between the device, the host daemon, the web UI, the CLI and the MCP server.
Everything here lives in `:inspector-model` and is compiled into all of them, so there is one
definition rather than four reimplementations.

Every serialized object carries `"v"`, currently `1`. Consumers reject versions they do not
understand rather than guessing. Additive optional fields do not bump the version; removing or
repurposing a field does.

JSON settings are fixed by `InspectorJson` and are part of the contract:

| Setting | Value | Why |
|---|---|---|
| `encodeDefaults` | `false` | Keeps `index.jsonl` lines short — a token budget for agents, not just disk. |
| `explicitNulls` | `false` | Same; absent means null. |
| `ignoreUnknownKeys` | `true` | A newer device can talk to an older daemon. |
| `classDiscriminator` | `"type"` | Wire message discriminator. |

---

## NetworkTransaction

One **attempt**, not one call. A logical call that redirects or retries emits several
transactions sharing `callId`, distinguished by `attempt`. This is deliberate — the bugs worth
catching usually live in the attempts a "one row per call" view would hide.

| Field | Type | Notes |
|---|---|---|
| `v` | int | Always present. |
| `id` | string | 8-char lowercase hex, unique within a session. |
| `ts` | string | ISO-8601 UTC with millis, device wall clock. **Display only.** |
| `mono` | long | Device monotonic ms. **Ordering authority.** |
| `method` | string | Uppercase. |
| `scheme` / `host` / `path` | string | `path` excludes the query. `host` is the hostname alone, so `host:` filters match it directly. |
| `port` | int? | Only when it is not the default for `scheme`. Null means the default — and also means a row written before this field existed, so older archives still read. Capture used to drop the port entirely, which made `url` and every copied cURL address the wrong one. |
| `query` | string? | Raw query without `?`, post-redaction. |
| `status` | int? | Null means the call failed before a response — see `error`. |
| `error` | string? | Exception class and message when `status` is null. |
| `ms` | long? | Total duration; null while in flight. |
| `attempt` | int | 1-based. |
| `callId` | string | Groups attempts of one logical call. |
| `reqBytes` / `resBytes` | long | **True** body size, counted even when the body was not captured. |
| `reqHeaders` / `resHeaders` | map<string, list<string>> | Post-redaction. |
| `reqBodyRef` / `resBodyRef` | string? | Host-relative, e.g. `bodies/7f3a.req`. Null on the wire; the daemon fills it in — **and must be filled in before the row is broadcast to live viewers**, or they see the body as absent. |
| `reqBodyTruncated` / `resBodyTruncated` | bool | Body hit `bodyCaptureMaxBytes`. |
| `reqBodyOmitted` / `resBodyOmitted` | string? | Why a body is absent: `contentType`, `streaming`. Null when the body was captured or there was none. Consumers must render this rather than guessing a reason. |
| `reqContentType` / `resContentType` | string? | For requests, read from the outgoing body when the header is absent — Ktor keeps it there, not in the builder's headers. |
| `redacted` | list<string> | e.g. `header:authorization`, `query:token`, `body:$.password`. |

### Redaction is off by default

Capture is verbatim unless configured otherwise: `InspectorConfig.redaction` defaults to
`Redaction.Off`, so headers, query parameters and bodies arrive exactly as they went over the
wire, credentials included. This is a debugging tool for debuggable builds whose capture code
cannot reach production (see the canary guard), and a debugger that hides the auth header is
worse than useless when the bug *is* the auth header.

Opt in per-run when a session should not carry secrets:

```kotlin
Inspector.init(InspectorConfig(redaction = Redaction.On()))                  // default denylists
Inspector.init(InspectorConfig(redaction = Redaction.On(
    headers = listOf("authorization", "x-customer-ssn"),
)))
```

Redaction, when enabled, happens **at capture time** — before the transaction reaches the ring
buffer, the daemon, or disk. There is no render-time redaction: whatever a sink receives is what
was captured.

Worth knowing: with redaction off, captured traffic is archived to disk on the host and is
readable by any agent pointed at it.

### Why `redacted` matters

When redaction is enabled, this field records that a value was **stripped**, not that it was
**absent**. An agent told nothing will otherwise report the request carried no credentials — a
confidently wrong answer. Consumers should surface it explicitly ("the authorization header was
redacted at capture"). With `Redaction.Off` the list is simply empty.

### Clock skew

Sort by `mono`, display `ts`. Never mix them. The device and host clocks are unrelated; the
daemon additionally records its own receive time.

### Derived helpers

- `isError` — `status >= 400` or `error != null`. Backs `has:error`.
- `statusClass` — `2xx`, `4xx`, … or `error`. Backs session summaries.
- `url` — reassembled from scheme/host/port/path/query. The port is included only when it is not the default for the scheme.
- `duplicateGroups(transactions, windowMs)` — separate calls that asked the same question inside a
  window. Requires **different `callId`s**, so retries and redirect hops are never reported; keys on
  method + url + status + byte counts, deliberately **excluding headers**, because a signed request
  carries a fresh nonce every time. Times with `mono`, per the rule above.

---

## Marker

A named point in the session timeline, from app code, the user, or an agent.

| Field | Type | Notes |
|---|---|---|
| `v` | int | |
| `ts` | string | ISO-8601 UTC. |
| `mono` | long | Ordering authority, same clock as transactions. |
| `label` | string | Free text; may contain spaces. |
| `source` | string | `app`, `agent`, or `user`. |

Markers are what turn "what happened when I tapped checkout" into a lookup instead of timestamp
arithmetic, and they back `since:marker("…")`.

## Signal

An app-defined observation on the same clock as `NetworkTransaction`: which screen was up, what a
state holder held, what was in a cache. One type covers all three, because all three are *a named
thing, under a category, at a moment, optionally with a payload*.

| Field | Type | Notes |
|---|---|---|
| `v` | int | |
| `id` | string | 8-char lowercase hex, unique within a session. |
| `ts` | string | ISO-8601 UTC. Display only. |
| `mono` | long | Ordering authority, **the same clock transactions use**. This is what makes a merged timeline possible. |
| `tag` | string | App-defined category. Conventionally `screen`, `state`, `cache`, `session` — but the set is open. |
| `name` | string | App-defined identity within the tag. `(tag, name)` is the grouping key for last-wins queries. |
| `data` | JsonElement? | In-memory only. **Null on the wire and null in the archive** — the payload travels beside the row and lands on disk at `dataRef`. |
| `dataRef` | string? | Host-relative, e.g. `signals/5c1a.json`. Null on the wire; the daemon fills it in on ingest. |
| `dataTruncated` | bool | Payload exceeded `SignalPolicy.maxPayloadBytes` and was cut. |
| `bytes` | long | **True** payload size, counted even when truncated. |
| `redacted` | list<string> | Reserved. **Always empty** — see below. |
| `trigger` | enum | `app` — the app pushed this. `request` — the host pulled it. |
| `requestId` | string? | Set when `trigger` is `request`, correlating to the pull that caused it. |

### `tag` is an open set

Inspector ships *conventions*, not an enum, for the same reason `SessionMeta.platform` is a plain
string: the archive outlives the binary that wrote it. An app emitting `tag = "bluetooth"` gets a
row that archives, filters, merges into the timeline and reads over MCP exactly like a `screen` row
does — it simply renders in a generic lane rather than a bespoke one. **A daemon or UI that has
never heard of a tag must treat it as ordinary and must never drop it.**

### `trigger` is load-bearing

An agent handed a cache snapshot with no provenance reports it as the current state of the cache.
If that snapshot was pushed at app start and the session is now twenty minutes old, the agent has
just given a confidently wrong answer about live state — the exact failure `redacted` exists to
prevent for credentials. Every consumer must surface it, and the MCP tool descriptions say so.

### Point observations and interval claims

A `screen` signal is true *at an instant*. A `cache` snapshot claims to be true *from its `mono`
until the next observation of the same `(tag, name)`*. That difference is deliberately **not** in
the schema, because it is derivable: consecutive last-wins rows for a key define the intervals. It
is a rendering and query rule — the web UI draws `cache` as spans and `screen` as points, and
`current` reports each observation along with how old it is.

### Signal payloads are never redacted

**Read this before using a session to answer a question about credentials.**

- `Signal.redacted` exists in the schema and is **always empty**.
- `Redaction.On` covers headers, query parameters and JSON body keys. It does **not** touch
  `Signal.data`.
- Therefore a session recorded with `Redaction.On` still archives signal payloads **verbatim**. A
  cache snapshot or a state dump carries whatever the app put in it — customer records, form
  contents, tokens held in state.

This is consistent with the project's stance that capture is verbatim and cannot reach production,
and the archive has always been readable by any agent pointed at it. What is new is that signals
carry *domain* data rather than *wire* data, so the blast radius is larger.

The practical consequence: an agent asked "were there any credentials in this session" that answers
from a redacted transaction set will miss an unredacted state dump sitting beside it. The
`get_signal` and `list_signals` tool descriptions say this too, because that is where an agent
actually reads.

Extending `Redactor` to `Signal.data` with the existing `bodyKeyPattern` is the obvious next move
and should be cheap when wanted — the schema field is already reserved for it.

---

## SessionMeta

Written to `meta.json` in the session folder. Deliberately flat rather than nesting client info,
because this file is meant to be opened and read in a folder listing.

| Field | Type | Notes |
|---|---|---|
| `v` | int | |
| `sessionId` | string | Also the session folder name. |
| `appId` / `appVersion` | string | |
| `platform` | string | `ios-simulator`, `ios-device`, `android-emulator`, `android-device`, `desktop` — the `Platforms` constants. A plain string, not an enum, so a value an older daemon does not know does not break it. Archives written before 2026-09-17 record every Android emulator session as `android-device`; the detection was wrong, not the writer. |
| `device` / `osVersion` / `buildType` | string | |
| `startedAt` / `endedAt` | string / string? | |
| `txnCount` / `errorCount` | int | |

`ClientInfo` carries the same fields minus `sessionId` and the counts, and is what the device
sends in `Hello`; `ClientInfo.toSessionMeta(...)` projects it.

---

## Wire protocol

JSON text frames over `WS /ingest`, discriminated by `"type"`.

| Type | Direction | Payload |
|---|---|---|
| `hello` | device → daemon | `ClientInfo`, optional `resumeSessionId` |
| `helloAck` | daemon → device | `sessionId`, `resumed` |
| `txn` | device → daemon | `NetworkTransaction` + inline bodies |
| `marker` | device → daemon | `Marker` |
| `signal` | device → daemon | `Signal` + inline payload |
| `signalReq` | daemon → device | `requestId`, `tag`, `name` |
| `signalErr` | device → daemon | `requestId`, `error` |
| `bye` | device → daemon | none |

### Rules

- **The device never blocks on the daemon.** After the hello exchange everything is
  fire-and-forget. A dead or slow daemon costs dropped transactions, never backpressure into
  the app.
- **Bodies ride inline** on `txn` — UTF-8 when the captured bytes are valid UTF-8, otherwise
  base64 with `reqBodyB64` / `resBodyB64` set. The daemon writes them to `bodies/` and fills in
  `reqBodyRef` / `resBodyRef`. This keeps the device free of file I/O entirely.
- **Reconnect** with exponential backoff, 250 ms up to 5 s.
- **Resume** by sending `resumeSessionId`; accepted within `SESSION_RESUME_GRACE_MS`
  (5 minutes) of the previous disconnect, otherwise a new session folder is created.
- **Signal payloads ride inline** on `signal`, exactly as bodies do, and `Signal.data` is null
  on the wire. The daemon writes the payload to `signals/` and fills in `dataRef`. Carrying the
  payload on the row as well would ship it twice.
- **A pull is one request, one reply.** `signalReq` names both `tag` and `name`, so there is no
  completion ambiguity. The device answers with an ordinary `signal` frame carrying
  `trigger = request` and the same `requestId`, or with `signalErr`. There is no provider
  advertisement in v1: discovery is the error path, so `signalErr` must name what *is* registered
  — `no provider for cache/orders; registered: cache/response, cache/prefs`.
- **A failed pull leaves no row.** Errors are replies, never archived rows.
- **Transactions recorded while disconnected are not replayed** in v1. They remain visible in
  the device ring buffer only. Candidate for v2.
- An abrupt socket close must be handled identically to `bye`.

---

## Filter grammar

Parsed by `FilterParser`, evaluated by `Filter`. Frozen for v1 — it is not a query language.

```
status:404      status>=400     status<500      also > <= <
method:POST                                     exact, case-insensitive
host:api.example.com                            substring, case-insensitive
path:/v2/users                                  substring
path:/v2/users*                                 glob when it contains a star
slower:500ms    slower:2s       slower:500      bare number = ms
larger:10kb     larger:2mb      larger:500b     bare number = bytes
has:error                                       status >= 400 or transport failure
text:refund                                     host + path + query — never bodies
since:marker("tapped checkout")                 at or after the last marker with that label
attempt>1                                       retried or redirected attempts
tag:screen                                      exact, case-insensitive — signals only
name:Checkout                                   substring, case-insensitive — signals only
```

- Whitespace-separated terms are **ANDed**.
- `|` **ORs**, and binds more loosely, so `a b | c d` is `(a AND b) OR (c AND d)`.
- Matching is case-insensitive throughout.

### Deliberate behaviours

- **`path` is dual-mode**: glob when the pattern contains a star, plain substring otherwise. A
  bare `path:/v2/users` typed in a hurry should find `/v2/users/me`, which a strict glob would
  not.
- **Bodies and signal payloads are never scanned.** Content search needs an index; keeping
  filters to metadata is what lets the web UI stay smooth at 10k rows. `text:` reads host, path
  and query on a transaction, and tag and name on a signal.
- **A term whose field does not exist on a row type excludes that row type.** This is what lets
  one grammar span both streams. Because terms are ANDed, `status:500 tag:screen` therefore
  matches **nothing at all** — correct and consistent, not a bug. Use `|` to span types:
  `status:500 | tag:screen`. Markers are a row type too, carrying only `mono` and a label, so
  every typed term drops them while `since:` and `text:` still reach them — a timeline cut at a
  marker keeps the marker it was cut at.
- **`since:` with an unknown label matches nothing**, not everything. A typo'd label silently
  becoming "no filter at all" is the more dangerous failure while debugging.
- **`status:` never matches a transport failure**, which has no status. Use `has:error`.
- **`slower:` never matches an in-flight transaction**, which has no duration yet.
- **`since:` with duplicate labels uses the latest** marker carrying that label.

### Errors

`FilterParser.parse` returns `Result` rather than throwing, because every caller has a UI
affordance for the error and none want a try/catch on the keystroke path. Messages are written
to be shown verbatim — the in-app filter bar, the web UI, REST 400 bodies and MCP tool errors
all surface them unchanged — so each names both the problem and the fix:

```
Unknown filter key 'stat'. Valid keys: status, method, host, path, slower, larger, has, text, since, attempt.
'status' expects a number, got 'abc'. Try 'status>=400'.
'method' does not support '>='. Use 'method:POST'.
'has' only supports 'has:error', got 'has:body'.
'since' expects 'since:marker("label")', got 'since:login'.
Missing operator in 'status'. Use 'key:value', or 'key>=value' for status and attempt.
```

---

## On-disk layout (daemon, Phase 2)

```
~/.inspector/
  config.json
  sessions/
    2026-08-16T10-14-02_ProjectX_iPhone16Pro_debug/
      meta.json
      index.jsonl        one NetworkTransaction per line, append-only
      markers.jsonl
      bodies/7f3a.req    raw bytes as captured
      bodies/7f3a.res
      signals.jsonl      one Signal per line, append-only
      signals/5c1a.json  payload as captured
  latest -> sessions/<newest>
```

Session folder name: `<yyyy-MM-dd'T'HH-mm-ss>_<appId last segment>_<device slug>_<buildType>`.

Retention: after each session close and on daemon start, prune oldest sessions until at most
**100 sessions** and **300 MB** remain. Both configurable. The active session is never pruned.

Signals are additionally trimmed **per tag** within a session, because the size distribution
across tags spans orders of magnitude: a session may reasonably keep every `screen` row for its
whole life while holding only the last few `cache` snapshots. Defaults are `cache` 20 and
`state` 500, with every other tag — including one this build has never heard of — kept in full.
Trimming rewrites `signals.jsonl`, so it runs on session close, never against an open writer.

The split between `index.jsonl` and `bodies/` is the main affordance for agents: the index is
small enough to read or grep whole, and bodies are fetched only for the few transactions that
warrant it. `signals.jsonl` and `signals/` repeat the split for the same reason.

Signals are a **separate stream** rather than merged into `index.jsonl`, so every existing grep,
REST route, UI query and MCP tool keeps working untouched. Merging happens on read, by `mono`.
