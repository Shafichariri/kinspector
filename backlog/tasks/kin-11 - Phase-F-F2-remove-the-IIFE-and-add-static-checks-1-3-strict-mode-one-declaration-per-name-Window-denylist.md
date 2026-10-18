---
id: KIN-11
title: >-
  Phase F F2: remove the IIFE and add static checks 1-3 (strict mode, one
  declaration per name, Window denylist)
status: To Do
assignee: []
created_date: '2026-10-18 13:30'
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
