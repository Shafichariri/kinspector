# web UI — agent guide

The browser UI the daemon serves, with no build step and no dependencies: `index.html`, the
classic scripts in `js/` listed in load order by `scripts.txt`, and the stylesheets in `css/`
listed in cascade order by `styles.txt`. The daemon expands the two lists into the page's tags,
and the same files are inlined one by one into every HTML export. A file in `js/` or `css/` that
its list does not name fails the build, and so does an entry with no file. Some blocks mirror
rules whose authority is `:inspector-model`, each between `// @mirror-begin <name>` and
`// @mirror-end <name>`. Why it is shaped this way, and the load-time and cascade rules a new file
must keep, are in `web-ui.md`. This guide and its `CLAUDE.md` are kept out of the jar.

Read before changing anything here: `docs/decisions/web-ui.md`, `docs/decisions/shared-rules.md`.

Also: `docs/decisions/exports.md` for the HTML export and its `source` seam, and
`scripts/AGENTS.md` for the probes.

@../../../../../docs/decisions/web-ui.md
@../../../../../docs/decisions/shared-rules.md

## Commands

```bash
npm install --no-save --no-package-lock jsdom@29.1.1 # the version CI pins
node scripts/render-web-ui.js > /tmp/ui.html      # web UI smoke test + snapshot
./gradlew :inspector-daemon:writeProbeFixture -Pinspector.probeDir=/tmp/probe   # the session CI probes; re-running replaces it
inspector-daemon/build/install/inspector/bin/inspector serve --data /tmp/probe --port 8231 &   # ... served off 8099
INSPECTOR_UI_ORIGIN=http://127.0.0.1:8231 INSPECTOR_UI_EXPECT_CHECKS=<UI_EXPECT_CHECKS in ci.yml> \
  node scripts/render-web-ui.js > /tmp/ui.html    # ... judged as CI judges it; the count only holds on that session
INSPECTOR_UI_SESSION=<id> node scripts/render-web-ui.js           # ... against a chosen session
INSPECTOR_UI_ORIGIN=http://127.0.0.1:8231 node scripts/render-web-ui.js   # ... a daemon off 8099
curl -s http://127.0.0.1:8099/api/sessions/latest/export.html -o /tmp/s.html     # an exported session
INSPECTOR_UI_ARCHIVE=/tmp/s.html node scripts/render-web-ui-archive.js        # ... works with no network
```

## Key files

| Path | Why it matters |
|---|---|
| `inspector-daemon/src/main/resources/web/` | The web UI. No build step, no dependencies. |
