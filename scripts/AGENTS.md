# scripts — agent guide

Shell and Node scripts with no shared subject, so this guide imports nothing. Read the decision
file for the script you are changing:

- `check-release-clean.sh` — `docs/decisions/release.md`.
- `check-client-names.sh`, `check-commit-identity.sh` — `docs/decisions/repository.md`, and No
  client names in the root `AGENTS.md`.
- `check-web-static.js`, `lib/` — `docs/decisions/web-ui.md` and `docs/decisions/shared-rules.md`
  (the mirror cases).
- `render-web-ui.js` — `docs/decisions/web-ui.md`.
- `render-web-ui-archive.js` — `docs/decisions/exports.md` and `docs/decisions/web-ui.md`.

## Key files

| Path | Why it matters |
|---|---|
| `scripts/check-release-clean.sh` | Production-safety enforcement. |
| `scripts/check-web-static.js` | The web checks that need nothing running: network call sites, the stylesheet audits, the JSON parser and every mirror case. First step of the CI `web` job. |
| `scripts/render-web-ui.js` | The live web UI's check; run it after touching `web/`, along with the archive probe below. CI runs both against a fixture session and pins how many checks pass. |
| `scripts/render-web-ui-archive.js` | Opens an exported session's HTML with the network forbidden and checks it works offline. Run it after touching `web/`: any new request that bypasses `source` fails it. |
