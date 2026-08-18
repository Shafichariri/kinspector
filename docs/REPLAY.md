# Replay, re-signing, and daemon control — design

Status: **design agreed, implementation in progress.** Step 1 (daemon control) is being built
first; steps 2–4 are specified here but not yet written.

This document exists because the replay feature has one constraint that determines its whole
architecture, and that constraint is not obvious from the feature request.

---

## 0. The constraint that decides everything

The consuming app signs **every** outgoing request with four device-proof headers, three of which
are single-use. The full wire contract is in the app team's `device-proof-replay-spec.md`; the
parts that bind this design are quoted inline below.

**Therefore: verbatim replay is dead on arrival.** A replayed request reuses a captured timestamp
and nonce, and the server rejects it. A "Run again" button that always returns 401 is worse than no
button, because it looks like a backend failure.

Replay and re-signing are one feature, not two. They ship together or not at all.

---

## 1. Why the signing callback is nearly free

The private key lives in AndroidKeyStore or the Secure Enclave. It is non-exportable by
construction — the only exposed operation is "sign these bytes". So the daemon cannot sign on its
own, and the app team's spec offers three strategies:

| | A: app callback | B: software key | C: unsigned |
|---|---|---|---|
| Works offline / app closed | ✗ | ✓ | ✓ |
| Valid against verifying backend | ✓ | ✓ | ✗ |
| Requires app-side change | small | medium | none |
| Reproduces production exactly | ✓ | ✗ (different key) | ✗ |

**We take Strategy A.** The decisive reason is that the transport it needs already exists and is
already proven in both directions:

- The daemon already pushes frames to the app — `HelloAck` on `WS /ingest`.
- The app already runs a receive loop on that socket. In `StreamSink.connectAndPump`:

  ```kotlin
  val watcher = launch {
      runCatching { for (frame in incoming) Unit }   // reads every frame, then discards it
  }
  ```

That loop exists for connection liveness (a sender parked in `receive()` never notices a dead
daemon). Turning it into a dispatcher is a `when` block, not an architecture.

Strategy A also sidesteps three traps the other strategies walk into:

- **No DER-wrapping bug.** The app's existing signer emits DER already. WebCrypto's ECDSA emits raw
  `r‖s`, which the server rejects as malformed despite being mathematically valid — a trap we
  simply never enter.
- **No MFA pre-registration.** The backend learns a device's public key during MFA verification.
  Strategy B's fresh software key would need one MFA run before any replay could pass.
- **No debug key to keep inert in release.** Strategy B adds a key that must never ship; this
  project already spends real effort on exactly that class of guarantee and does not need another.

The cost of Strategy A is that replay requires the app running and connected. For a debugging tool
whose entire premise is a live capture session, that is not much of a cost.

**A release build must never expose a signing oracle.** The callback is gated behind the same
artifact swap as everything else: `:inspector-noop` has no signer hook at all.

---

## 2. What the signature covers, and why it changes the UI

The canonical string is pipe-separated ASCII:

```
v1|<deviceId>|<timestamp>|<nonce>|<METHOD>|<path>
```

Deliberately **excluded**: the query string, the request body, the host, and every other header.

Two consequences the edit UI has to respect:

- Editing the **body** or the **query** does *not* invalidate the signature.
- Editing the **method** or the **path** does.

So re-signing must happen **after** edits are applied, never before. Get this wrong and it works on
every unedited replay and fails the first time somebody edits a path — the worst possible failure
distribution, because it passes testing.

`path` is the encoded path without query: for
`https://api.example.com/v1/auth/verify/mfa?x=1` the signed field is `/v1/auth/verify/mfa`.

### Device id mismatch

The server maps `deviceId → public key`, learned at MFA verification. The device id is itself a
fingerprint of the key — `base64url(SHA-256(0x04 || X || Y))`.

So the app must return **its current `deviceId` alongside the signature**, and the UI must hard-warn
when that differs from the captured one. Reusing a captured device id while signing with a
different key produces a rejection that *looks like a malformed signature*, which sends you
debugging the wrong thing entirely. A changed device id means the key was regenerated and every
capture for the old id is permanently unreplayable — worth saying in those words in the UI.

---

## 3. Replay: five things that break it

Rebuilding the request is easy — `method`, `scheme`, `host`, `path`, `query` and `reqHeaders` are
all on `NetworkTransaction`, and the body is already served by
`GET /api/sessions/{id}/transactions/{txnId}/body/{side}`. The problems are elsewhere.

1. **Truncated bodies.** `bodyCaptureMaxBytes` defaults to 256 KB. When `reqBodyTruncated` is true
   the archive holds a prefix, not the body. Replay must **refuse**, not warn and send — sending a
   silently corrupt body produces a server-side error that looks like an app bug.
2. **Omitted bodies.** Streamed uploads are never buffered (`BodyOmission.STREAMING`), by explicit
   design. Those requests are unreplayable; say so rather than sending an empty body.
3. **Hop-by-hop headers.** Replaying captured `Host`, `Content-Length`, `Connection` and
   `Accept-Encoding` verbatim fights whatever the replay client sets. Needs an explicit strip-list.
   This is the classic replay bug and it produces confusing, intermittent failures.
4. **Network position.** The daemon runs on the developer's machine; the app runs on an emulator or
   device. An emulator-only host such as `10.0.2.2`, device-only DNS, or a VPN-gated API is
   reachable from one and not the other. The error surface must distinguish "could not connect"
   from "server rejected".
5. **Redaction.** The default is `Redaction.Off`, so captures normally carry credentials verbatim
   and replay works. When redaction is on, `NetworkTransaction.redacted` lists what was masked and
   replay must refuse rather than send `***` as a bearer token.

### Security posture — a real escalation

Today the daemon **reads an archive**. Replay makes it an **arbitrary-request sender**, on a
loopback port with no authentication, holding unredacted bearer tokens by default.

The existing control-header guard is CSRF defence — it forces a preflight this server never
answers — and it is **not** authentication. Any local process can still drive these endpoints.

This is a deliberate widening of what a compromised local process can do, and it is accepted
knowingly for a debug-only tool. It also raises the stakes on the existing rule: the daemon binds
`127.0.0.1` only, and that bind is the security boundary. Do not widen it without adding real
authentication first.

---

## 4. Dynamic header values: two mechanisms, not one

The feature request asked for user-defined functions for dynamic headers. For the signing case
specifically, that is the wrong tool: **no function the daemon can run has access to a
non-exportable hardware key.** Strategy A solves signing without any scripting at all.

What remains is genuinely computable values, and those are better served by a fixed generator set
than by a scripting engine:

```
{{now.epochSeconds}}      {{now.iso8601}}
{{uuid}}                  {{randomBase64Url(16)}}    {{randomHex(32)}}
{{base64(...)}}           {{hmacSha256(key, msg)}}
{{env.NAME}}
```

Fixed generators cover the realistic cases with **zero code-execution surface**.

**Arbitrary user scripting is not planned.** It would mean executing user-supplied code on the
developer's machine, reachable from an unauthenticated loopback port; and since Nashorn is gone on
JDK 21 it would also mean taking on GraalJS. The marginal risk is arguable — you can already run
code on your own machine — but it should be a deliberate decision, not a side effect of a
header-templating feature. Revisit only if something concrete demands it.

---

## 5. curl generation

**Generate the curl when the user asks for it, not when the request is captured.** A curl string
containing a timestamp and nonce goes stale inside the server's skew window, typically a minute or
two. Either regenerate on each copy and label the validity window, or show placeholders and re-sign
behind the Run button.

Single-quote header values, use `--data-raw` rather than `-d` (which mangles newlines and
`@`-prefixed payloads), and escape embedded single quotes as `'\''`.

---

## 6. Error surface

Replay failures are ambiguous by nature, so the UI names the likely cause rather than showing a
bare 401:

| Symptom | Reported cause |
|---|---|
| 401, signature-shaped error | Timestamp/nonce reused instead of regenerated |
| 401 on every request, fresh values | Public key not registered for this device id |
| 401, token-shaped error | Captured `Authorization` expired — **not** a signing failure |
| Rejected despite correct math | Raw `r‖s` sent where DER expected |
| Headers contain `+` `/` `=` | Standard base64 used instead of base64url-unpadded |
| Rejected on some routes only | Method not uppercased, or query wrongly included in `path` |
| Worked, then stopped after reinstall | Device key regenerated → old captures dead |
| Second identical replay fails | Server nonce replay cache — regenerate nonce per attempt |

The `Authorization` row matters most: a captured token simply expiring is the single most likely
replay failure, and it has nothing to do with signing.

---

## 7. Build order

1. **Daemon list + kill.** Independent of everything else, small, immediately useful.
2. **Signing callback + replay.** Must ship together (§0).
3. **Edit before replay.** Builds on 2; respects the re-sign ordering in §2.
4. **Template generators.** Additive once replay exists.

### Work item not visible from the feature request

The daemon keeps **no registry of live ingest sockets** — `ingestRoute` holds `sessionId` as a
connection-local variable. Routing a sign request to the connected app needs a session→socket map
plus a pending-request table with a timeout, so a replay against a disconnected app fails fast with
a clear message instead of hanging.

---

## 8. Open questions for the backend team

Not observable from the client, and each one changes the replay UX:

1. **Clock skew window** — how many seconds of drift are accepted? Sets how long a generated curl
   stays valid, and whether the UI needs a countdown.
2. **Nonce cache TTL** — is a nonce single-use, and for how long? Decides whether "Run" is
   idempotent or whether every attempt must regenerate.
3. **Enforcement per environment** — is the signature actually verified in dev and staging? Decides
   whether an unsigned mode is available at all for early testing.
4. **Canonical-string versioning** — the `v1` prefix implies a `v2` is anticipated. Keep the version
   a single constant rather than an inlined literal.
