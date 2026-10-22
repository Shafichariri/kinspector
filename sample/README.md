# The sample app

A small Compose Multiplatform app wired the way a consuming app is: the same three lines, on
desktop, Android and iOS. It brings its own server, so it needs no network and no account. It is a
demo, not a tool; the products are the in-app overlay and the web UI the daemon serves.

The agent guide for this folder is `AGENTS.md`. This file is for a person running the demo.

## See the findings in five minutes

The sample makes two mistakes on purpose, the kind the daemon's findings exist to catch:

- **A payment submitted twice.** `POST /checkout/payments` is sent two times about a quarter of a
  second apart, with the same body and no `Idempotency-Key` header. That is a double tap on a Pay
  button, and a server with no key to go on charges twice.
- **A token in a URL.** `GET /profile?access_token=…` puts a JWT in the query string, where every
  proxy and server log on the way keeps it.

Both are sent to the sample's own server on `127.0.0.1:9090`. The JWT is fake (its signature is
the words `not-a-real-signature`, its issuer `sample.invalid`) and the card number in the payment
body is a published test number.

You need JDK 21 and a clone of this repository. From the repository root:

1. **Build and start the daemon.** It archives what the app sends and serves the web UI.

   ```bash
   ./gradlew :inspector-daemon:installDist
   inspector-daemon/build/install/inspector/bin/inspector serve
   ```

   Leave it running. It listens on `127.0.0.1:8099`. `docs/DAEMON.md` has the rest.

2. **Run the sample with its scripted run**, in a second terminal:

   ```bash
   ./gradlew :sample:desktop:run -Dinspector.sample.autofire=true
   ```

   A window opens and, after a second and a half, the sample fires its traffic on its own: every
   endpoint, a short walk through Home, Product, Cart and Checkout, and at the Checkout "pay" step
   the double submit and the token. The terminal prints `inspector-sample: autofire complete` when
   it is done. Without `-Dinspector.sample.autofire=true`, press **pay twice + token** in the
   window instead; that button is on the Android and iOS samples too.

3. **Open the Findings page** at <http://127.0.0.1:8099/#/findings>. It shows the newest session,
   which is the one you just made.

To keep the demo out of an archive you use day to day, run a second daemon on another port and
point the sample at it: `inspector serve --port 8112 --data /tmp/inspector-demo` and
`./gradlew :sample:desktop:run -Dinspector.sample.autofire=true -Dinspector.sample.port=8112`,
then open <http://127.0.0.1:8112/#/findings>.

## What each finding should show

With the daemon's default config (no `config.json`, or one with no `findings` key), the scripted run
gives seven findings, two of them high.

| Rule | Severity | What it says |
|---|---|---|
| Duplicate mutation | high | `POST …/checkout/payments was sent 2 times in 0.3 s as separate calls with the same request body and no idempotency key.` It cites both calls; the evidence says neither carried `Idempotency-Key` or `X-Idempotency-Key`, and that call 2's body is the same as call 1's, by size. The body itself is never shown. |
| JWT in a URL | high | `JWT in a URL in the URL query.` The evidence names the parameter, `access_token`, and shows the token as `•••• HS256`: its algorithm and nothing else. |
| Key missing on a payment path | medium, once per call | The path matches the default payment pattern `payment`, and the call carried no idempotency key. |
| Card number | info | In the request body at `$.cardNumber`, shown as `•••• 4242`, noted as a published test card number. A real card number would be medium. |
| Credentials over http | info | The token went over plain `http`. Info, because the host is loopback; to any other host it would be higher. |
| Secret field | info | The `/secret` endpoint's response has a `password` field. That one is from the sample's older traffic, not the demo flow. |

The token's value appears nowhere in a finding: not on the page, not in
`GET /api/sessions/{id}/findings`, not in the CLI or MCP answer. The archive keeps it, unredacted,
because the archive is a full recording of what the app sent; a finding is what you paste into a
ticket, so it carries a masked hint instead.

The same findings, without the browser:

```bash
inspector findings                                       # the newest session, as JSON
curl -s http://127.0.0.1:8099/api/sessions/<id>/findings # the REST endpoint the page reads
```

and the `list_findings` tool from `inspector mcp`, for an agent.

## When a finding does not appear

- **No token finding:** the sample's `InspectorConfig()` leaves redaction off. With
  `Redaction.On()`, the `access_token` value is redacted at capture, before anything reaches the
  daemon, and there is nothing left to flag. That is redaction working, not detection failing.
- **No duplicate:** the two submits must land inside `findings.payment.duplicateWindowMs` (3 s by
  default). A `config.json` that shortens the window, turns the rule off or adds `checkout` to
  `ignorePaths` changes what you see. The config file is `config.json` in the daemon's data
  directory (`~/.inspector` unless you passed `--data`); the Settings page shows what is in effect.
- **An older session on the page:** the page opens on the newest session. Pick the sample's latest
  one in the session picker at the top.
