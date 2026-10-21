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
| `scripts/measure-web-ui.js`, `lib/measure-*.js` | The web UI in headless Chrome: the scope bar on top, path stations landing below the sticky box, a run toggle or a live row leaving a scrolled pane where it is, the top bar's count staying whole at 975, 800, 600 and 400 px (`lib/topbar-cases.js`), the shell at 1440, 1000, 600 and 400 px — the bar, its controls, the picker's start time, every view reachable, the rail and each page fitting (`lib/shell-cases.js`) — the list's room: the closed drawer out of Tab's reach and the page's width (`lib/list-room-cases.js`) — and first rows on a 160,000-call session. These are what jsdom cannot see. Run it once per web fix round; `--base <dir>` prints what moved against another tree. Needs Chrome, JDK 21 and a built daemon; it serves each tree's `web/` ahead of the jar's, so a web edit needs no rebuild. |
| `scripts/qa-snapshot.sh` | Copies committed revisions into a fresh temp directory for side-by-side review, deleting nothing. Reviewers use it instead of a hand-written loop, which needed an approval click even in bypass mode; see `docs/TEAM.md`, "Snapshots". |
| `scripts/render-web-ui-archive.js` | Opens an exported session's HTML with the network forbidden and checks it works offline. Run it after touching `web/`: any new request that bypasses `source` fails it. |
