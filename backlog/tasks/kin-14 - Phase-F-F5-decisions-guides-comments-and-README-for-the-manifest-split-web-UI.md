---
id: KIN-14
title: >-
  Phase F F5: decisions, guides, comments and README for the manifest-split web
  UI
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-18 13:31'
updated_date: '2026-10-18 14:21'
labels:
  - 'item:structure-phase-f'
dependencies:
  - KIN-12
  - KIN-13
modified_files:
  - docs/decisions/web-ui.md
  - docs/decisions/shared-rules.md
  - docs/decisions/signals.md
  - inspector-model/src/commonMain/kotlin/dev/inspector/model/Endpoints.kt
  - inspector-model/src/commonMain/kotlin/dev/inspector/model/PathScope.kt
  - inspector-model/src/commonMain/kotlin/dev/inspector/model/Timeline.kt
  - inspector-model/src/commonMain/kotlin/dev/inspector/model/Visits.kt
  - inspector-model/src/commonMain/kotlin/dev/inspector/model/SignalGroups.kt
  - inspector-core/src/commonMain/kotlin/dev/inspector/Inspector.kt
  - inspector-ui/src/commonMain/kotlin/dev/inspector/ui/InspectorSignalDetail.kt
  - scripts/lib/mirror-runner.js
  - inspector-model/src/jvmTest/kotlin/dev/inspector/model/MirrorCasesTest.kt
  - inspector-daemon/src/test/kotlin/dev/inspector/daemon/HopByHopMirrorTest.kt
  - mirror-cases/README.md
  - inspector-daemon/src/main/resources/web/AGENTS.md
  - AGENTS.md
  - README.md
  - site/index.html
  - .claude/agents/architect.md
  - repo-checks/src/test/kotlin/dev/inspector/repochecks/FileBudgetTest.kt
type: task
ordinal: 14000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Phase F, commit F5 (design note §3 "Comments and docs that name the files", §7 "Decisions whose wording changes", §5 F5). Approved by the maintainer on 2026-10-18 as written, including that phase F updates the stale `app.js` mention at `.claude/agents/architect.md:39` and that the engineer may edit it in this commit. The ROADMAP §6 line ("`fetch` is still called once in `app.js`") is the product-owner's and is not part of this ticket. `STATUS.md` and `implementation-plan.md` are records and are left alone. Read first: `docs/decisions/web-ui.md`, `docs/decisions/shared-rules.md`, `docs/decisions/signals.md`, `docs/decisions/repository.md` ("each decision lives once, in `docs/decisions/`" — `DecisionsTest` fails on a second copy, so the new paragraphs go in web-ui.md only and guides point at them).

Already landed earlier in the item: exports.md (F1) and the mirror wording in shared-rules.md and web-ui.md (F3). Comments name the `@mirror-begin <name>` block, never a file.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 web-ui.md gains the paragraph **"The web UI is ordered classic scripts listed in `web/scripts.txt`, and the daemon is the only thing that turns the list into served tags."** with the reason (the shared lexical scope and the load-time rule), the sentence that jsdom does not enforce `'use strict'` so the static check is the guard, and its pins: `WebManifestTest`, static checks 1–3, per-file probe loading and `every file inlined`
- [ ] #2 web-ui.md gains the paragraph **"`web/styles.txt` order is the cascade; an `@media` block stays in the file of the rules it overrides, after them."**, pinned by static check 4 and the concatenation proof
- [ ] #3 shared-rules.md line 98: "it reads `style.css`" → "it reads `web/css/00-tokens.css`, declared as a test input so a CSS-only change re-runs it, and fails if a token it checks is declared in any other file `web/styles.txt` lists"
- [ ] #4 signals.md lines 30, 133 and 145: "`app.js`" → "the web UI" and "`style.css`" → "the web stylesheets"; line 25 is history and stays
- [ ] #5 Comments naming the files are updated in `Endpoints.kt`, `PathScope.kt`, `Timeline.kt`, `Visits.kt`, `SignalGroups.kt`, `Inspector.kt` and `InspectorSignalDetail.kt`, and the mirror comments in `mirror-runner.js`, `MirrorCasesTest`, `HopByHopMirrorTest` and `mirror-cases/README.md` name the `@mirror-begin <name>` block, never a file
- [ ] #6 The web `AGENTS.md`, the root `AGENTS.md` index line, README:376 and `site/index.html:9` no longer name `app.js` or `style.css`
- [ ] #7 `.claude/agents/architect.md:39` no longer names `app.js` as the place a mirror lives
- [ ] #8 `FileBudgetTest`'s KDoc wording no longer names `app.js` as a file awaiting a split
- [ ] #9 `STATUS.md` and `implementation-plan.md` are unchanged
- [ ] #10 `./gradlew :repo-checks:test` passes (`DecisionsTest`, `AgentGuideTest`), and `./gradlew :inspector-model:jvmTest :inspector-daemon:test :inspector-ui:jvmTest` passes
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Maintainer, 2026-10-18 (via the conductor): the stale 'model <-> app.js mirrors' wording at docs/TEAM.md:17 is in scope here, under the same approval as .claude/agents/architect.md:39.

Built in 90618e2 (local, not pushed). Docs and comments only; no code path changed.

Runs: ./gradlew :repo-checks:test :inspector-model:jvmTest :inspector-daemon:test :inspector-ui:jvmTest --rerun -> BUILD SUCCESSFUL; 35 / 159 / 259 / 134 tests, 0 failures (counted from the XML results). node scripts/check-web-static.js -> '24 checks, 0 failed'. render-web-ui.js line count unchanged at its 2645 budget; site/index.html unchanged at 723.

Done differently, for QA to judge:
- AC #2: written in the conductor's restated terms (KIN-13 notes), not the ticket's title wording. The paragraph is headed 'web/styles.txt order is the cascade.' and states: contiguous slices in original order; an @media block never loads before a rule it overrides; same file where it can be (every prefers-reduced-motion block, the .pv-visit and --stop-bg pairs); the max-width 1199px block in 35-list.css named as the one that spans files (overrides #panes / #panes.panes-wide in 10-panes.css). The concatenation proof is described as done at the split, since it is a one-off script and not committed.
- New web-ui.md paragraphs are placed first in the file, before the existing ones.
- architect.md: line 3 (the description frontmatter, 'model/app.js mirrors') was also changed, to 'model/web UI mirrors', besides line 39 — same stale mention under the same approval. Line 39 now says the mirror is an @mirror-begin block in a file scripts.txt lists, checked by a case file in mirror-cases/.
- Also reworded outside the ticket's file list: docs/TEAM.md:17 (approved in notes), scripts/check-web-static.js:13, scripts/lib/web-sources.js:28 and WebPage.kt:41 (examples now js/00-core.js), scripts/render-web-ui.js:2636 ('99-init.js sets a 1s interval').
- FileBudgetTest KDoc: not every budget entry awaits a split (site/index.html is 'by design'), so it says each entry is over for the reason it gives, most often a split planned in ROADMAP §5.

grep -rnI -E 'app\.js|style\.css' (excluding .git, build, node_modules, backlog, .gradle, .claude/worktrees, STATUS.md, implementation-plan.md, ROADMAP.md) -> 5 hits, each kept on purpose:
- RestApiTest.kt:211-212 — asserts /app.js and /style.css now 404; the old paths are the subject.
- WebManifestTest.kt:108 — explains why a leftover app.js/style.css in web/ must fail.
- WebUiRoutes.kt:20 — history: PUT /app.js used to answer 405.
- docs/decisions/signals.md:25 — history (runIdOf in app.js, v0.8.0-v1.3.0); the ticket says it stays.
.claude/worktrees/ holds other checkouts (git-ignored) and is not this tree.

STATUS.md, implementation-plan.md, ROADMAP.md: git diff empty. Client-name scan: NOT RUN — the terms are not on this machine.
<!-- SECTION:NOTES:END -->
