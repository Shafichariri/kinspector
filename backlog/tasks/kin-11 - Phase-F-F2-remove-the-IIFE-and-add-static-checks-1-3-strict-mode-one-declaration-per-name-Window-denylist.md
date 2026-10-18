---
id: KIN-11
title: >-
  Phase F F2: remove the IIFE and add static checks 1-3 (strict mode, one
  declaration per name, Window denylist)
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-18 13:30'
updated_date: '2026-10-18 13:59'
labels:
  - 'item:structure-phase-f'
dependencies:
  - KIN-10
modified_files:
  - inspector-daemon/src/main/resources/web/js/00-app.js
  - scripts/check-web-static.js
  - scripts/lib/web-audits.js
  - structure/budgets.txt
type: task
ordinal: 11000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Phase F, commit F2 (design note §1 "Shared state", §3 `check-web-static.js` checks 1–3, §5 F2). Approved by the maintainer on 2026-10-18 as written. Read first: `docs/decisions/web-ui.md`, `docs/decisions/exports.md`, `docs/decisions/shared-rules.md`.

The web UI's shared state becomes top-level declarations in classic scripts, not a namespace object. Classic scripts share one global lexical scope, so a call across files resolves when it runs, just as inside today's IIFE; a namespace object would mean rewriting every cross-file reference and the move would no longer be checkable as pure relocation. This commit removes the IIFE from `js/00-app.js` while it is still one file, so F3 is a pure move.

The costs, each held by a check added here: about 230 `function`s become properties of `window`, so a duplicate across files silently wins and a name can shadow a browser `Window` member; and `'use strict'` applies per file. QA measured that jsdom 29.1.1 does **not** throw on an implicit global under `'use strict'` (neither through `vm.Script` nor a real `<script>`), so static check 1 and Chrome are the only things that can see a file missing the directive, and the red proof for it must be the static check itself.

- Indent −2, a file-level `'use strict'`, the IIFE's three lines removed. There are no multi-line template literals, so removing the indent changes no string.
- Three checks in `scripts/check-web-static.js`, the denylist in `scripts/lib/web-audits.js`. Static count 20 → 23, unpinned in CI.
- `structure/budgets.txt`: the `js/00-app.js` budget is tightened to the new size, because the ratchet fails a file smaller than its budget.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 `js/00-app.js` has no IIFE: indent −2, a file-level `'use strict'`, three lines removed; re-indenting gives back the F1 file byte for byte
- [ ] #2 Static check 1: `'use strict'` is the first statement of every manifest file — after stripping leading comments and whitespace the next statement is exactly the directive; a `'use strict'` after a statement is only a string expression and fails
- [ ] #3 Static check 2: no top-level name is declared in two files, which also catches a stale copy of a function that has no anchors
- [ ] #4 Static check 3: no top-level name is on a named denylist of browser `Window` members (`top`, `parent`, `self`, `window`, `document`, `location`, `history`, `name`, `status`, `stop`, `close`, `open`, `print`, `focus`, `blur`, `find`, `scroll`, `event`, `origin`, `external`, `screen`, `navigator`, `frames`, `length`, `opener`, `closed`, `alert`, `confirm`, `prompt`, `fetch`, `toolbar`, `menubar`, `crypto`, `performance`, `INSPECTOR_ARCHIVE`, …); the list lives in `web-audits.js`
- [ ] #5 Checks 1–3 must each find at least one name, so none can pass by scanning nothing
- [ ] #6 Red proofs, each seen failing in the static check and then restored: `'use strict'` dropped; `'use strict'` placed after a statement; `function tagOf` declared in a second file; `function stop` declared
- [ ] #7 Static count 23, the first 20 lines byte-identical to base `f574bda` (case counts, 95 in all, network counts and the min-text selector list included)
- [ ] #8 Probes 164 and 21, compared per label (label plus verdict) against base
- [ ] #9 `./gradlew :inspector-daemon:test :repo-checks:test` passes
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Built in local commit 266f7bd (not pushed). Ready for QA. Scratch and logs: $TMPDIR/kin11/.

Pure-removal proof: $TMPDIR/kin11/rewrap.js re-indents every non-empty body line by +2 and swaps the file-level 'use strict' back for the three wrapper lines ('(() => {', "  'use strict';", '})();'); diff of its output against 'git show 8b20dcc:.../js/00-app.js' is empty. git diff -w on the file shows exactly two lines: -(() => { and -})(); . No line has an odd backtick count outside a comment (lines 4517-4518 are a KDoc), so no multi-line template literal; no whitespace-only lines; node --check passes. 5431 -> 5429 lines.

Runs (JDK 21 from the IDE jbr; jsdom 29.1.1 via NODE_PATH=$TMPDIR/kin10/npm/node_modules; probe fixture $TMPDIR/kin10/probe served by a freshly installDist'ed daemon on 8231, stopped after):
- node scripts/check-web-static.js -> '23 checks, 0 failed', exit 0. head -24 (everything above the new '--- scripts ---' header) diffs empty against $TMPDIR/kin10/static-base.txt; head -20 also empty. New lines: 'use strict first : yes (1 file(s))', 'one declaration per name : yes (231 names in 1 file(s))', 'no browser global names : yes (231 names, none of 46 browser globals)'.
- render-web-ui.js -> 'checks: 164 passed, 0 failed, 1 n/a, 0 unclassified, 42 notes', exit 0; per label+verdict vs $TMPDIR/kin10/ui-base.report: 209 lines each, identical sequence.
- render-web-ui-archive.js on a fresh export -> 'checks: 21 passed, 0 failed'; per label vs archive-base.report: 25 lines, identical (base 'app.js inlined' mapped to 'every file inlined').
- ./gradlew :inspector-daemon:test :repo-checks:test :inspector-ui:jvmTest -> BUILD SUCCESSFUL; daemon 259, repo-checks 35, ui 133 (UP-TO-DATE: no input of it changed), 0 failures.
- FileBudgetTest before tightening: '00-app.js: 5429 lines, budget 5431' FAILED; then -Dinspector.budgets.tighten=true -> 5429, reason text updated by hand.

Red proofs, each node scripts/check-web-static.js, exit 1, restored after (restored run: 23 checks, 0 failed; files cmp-identical to backup):
- 'use strict' dropped: "use strict first : NO - js/00-app.js starts with \"const ARCHIVE = window.INSPECTOR_ARCHIVE\", not 'use strict'"
- 'use strict' after a statement (const early = 1; then the directive): "use strict first : NO - js/00-app.js starts with \"const early = 1;\", not 'use strict'"
- function tagOf in a listed js/05-x.js: 'one declaration per name : NO - declared more than once: tagOf in js/00-app.js:932 and js/05-x.js:4'
- function stop appended: 'no browser global names : NO - a browser global declared: stop at js/00-app.js:5431'
Also: 'let a, b;' appended -> exit 2 'harness: js/00-app.js:5431: cannot read the names declared by: let a, b;'. Function-level ($TMPDIR/kin11/unit-proofs.txt): empty list -> 'NO - no scripts to read'; no top-level declarations -> 'NO - found no top-level declarations, so this checked nothing' for checks 2 and 3; duplicate within one file -> NO; destructuring, 'let a = 1, b = 2', 'const a = f(1, 2), b = 3' refused; 'constant = 2' / 'letter(..)' / 'classify()' at column 0 not read as declarations.

Done differently, for QA to judge:
- Top-level names are read by layout (column 0), not a parse, because the static check runs with nothing installed. Limit, stated in the KDoc: a top-level declaration written indented is not seen. Forms whose names it cannot read are refused (exit 2) rather than skipped.
- Check 2 also fails a name declared twice in the same file (ticket title: 'one declaration per name').
- Denylist is the ticket's 35 names plus 11 globals this page itself calls (localStorage, setTimeout, clearTimeout, setInterval, clearInterval, WebSocket, URL, URLSearchParams, TextDecoder, TextEncoder, console) = 46.
- Check 1 accepts 'use strict' or "use strict", optional semicolon, alone on its line.
- New checks print under a '--- scripts ---' header after the mirror cases, before the summary line.
- Backlog ticket files are not in the commit.
Not done here: Chrome (only it and check 1 can see strict mode), CI. Client-name scan: 'NOT RUN — no terms' on this machine.
<!-- SECTION:NOTES:END -->
