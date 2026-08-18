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

---

## SessionMeta

Written to `meta.json` in the session folder. Deliberately flat rather than nesting client info,
because this file is meant to be opened and read in a folder listing.

| Field | Type | Notes |
|---|---|---|
| `v` | int | |
| `sessionId` | string | Also the session folder name. |
| `appId` / `appVersion` | string | |
| `platform` | string | `ios-simulator`, `android-emulator`, `desktop`. A plain string, not an enum, so adding physical devices later does not break older daemons. |
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
```

- Whitespace-separated terms are **ANDed**.
- `|` **ORs**, and binds more loosely, so `a b | c d` is `(a AND b) OR (c AND d)`.
- Matching is case-insensitive throughout.

### Deliberate behaviours

- **`path` is dual-mode**: glob when the pattern contains a star, plain substring otherwise. A
  bare `path:/v2/users` typed in a hurry should find `/v2/users/me`, which a strict glob would
  not.
- **Bodies are never scanned.** Body search needs an index; keeping filters to metadata is what
  lets the web UI stay smooth at 10k rows.
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
  latest -> sessions/<newest>
```

Session folder name: `<yyyy-MM-dd'T'HH-mm-ss>_<appId last segment>_<device slug>_<buildType>`.

Retention: after each session close and on daemon start, prune oldest sessions until at most
**100 sessions** and **300 MB** remain. Both configurable. The active session is never pruned.

The split between `index.jsonl` and `bodies/` is the main affordance for agents: the index is
small enough to read or grep whole, and bodies are fetched only for the few transactions that
warrant it.
