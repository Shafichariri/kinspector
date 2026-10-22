---
id: KIN-116
title: 'Findings T5: the Findings page'
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-22 08:24'
updated_date: '2026-10-22 11:21'
labels:
  - 'item:findings-payment-safety'
dependencies:
  - KIN-115
  - KIN-66
  - KIN-70
  - KIN-82
  - KIN-84
type: feature
ordinal: 116000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Order row 26 (26a for Shared body evidence), docs/ROADMAP.md §6 → Findings. Built to the architect's design note for item:findings-payment-safety (questions answered 2026-10-22 by the maintainer's proxy: the findings key is read live; Q1 keep both notions of duplicate; Q2 six default payment paths; Q3 resubmitWindowMs 60000 beside duplicateWindowMs 3000; Q4 every mutation plus an empty ignorePaths; Q5 not in this item). PR B (web). ProbeFixture payments session, web/js/97-findings.js, web/css/90-findings.css, scripts.txt, styles.txt, index.html, 08-routes.js, 05-network.js, 90-sessions.js, 99-init.js, scripts/lib/findings-probes.js, scripts/lib/findings-refresh-checks.js, render-web-ui.js, render-web-ui-archive.js, check-web-static.js, measure-web-ui.js, ci.yml, structure/budgets.txt, docs/decisions/web-ui.md.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 A Findings rail entry after Stats opens #/findings
- [ ] #2 The probe session reads "No findings in 16 calls in this session" and lists six rules as checked
- [ ] #3 The payments session shows the fixture counts under "Payment safety" (3 high, 6 medium, 1 info; 10 findings in 18 calls; 12 flagged rows; coverage 13 mutation calls, 2 bodies unknown — corrected from the design, which said 14 and 15; PaymentRulesTest asserts 13 and 12), with its coverage line
- [ ] #4 A call link opens #/txn/<id> and Back returns; #/findings?id=<id> scrolls to and marks that finding
- [ ] #5 The page text holds no planted key
- [ ] #6 Live, it asks at most once a second, never twice at once, only while Traffic or Findings is shown with live tail on (static findings refresh lines, each red with its line removed)
- [ ] #7 In an export it draws from the embedded report, refuses a category, and keeps the rail link
- [ ] #8 No sideways scroll at 400, 600, 1000 or 1440 px
- [ ] #9 Counts are stated before → after, and the ci.yml numbers raised to them
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
1. ProbeFixture.kt: the payments session (design §8.3 rows, started 2025-12-31T08:00Z, host api.example.invalid), ProbeFixtureTest extended; counts per PR A: 13 mutation calls, 12 flagged rows, R1 1 R2 1 R3 1 R4 1 R5 5 R6 1, 3 high 6 medium 1 info, 10 findings in 18 calls.
2. web: js/97-findings.js (state, load via api(), oweFindings refresh rule, page draw, anchor), css/90-findings.css, manifests, index.html (#page-findings, rail link after Stats), 08-routes.js PAGES row + optional id, 05-network.js (archiveSource /findings, oweFindings in the txn branch, onopen), 90-sessions.js, 99-init.js.
3. scripts: lib/findings-probes.js (findings none/payments/coverage/txn link/anchor/no value/restored), lib/findings-refresh-checks.js (static refresh lines), route parse lines, archive probes, measure shell rows; page keys checks updated for the real g f row.
4. ci.yml counts before -> after; web-ui.md paragraph (T5 parts only; badge/detail/flagged wording is T7/T8).
5. Red proofs per §8.2 and the Pace rule; check-web-static, render-web-ui on 8102, archive probe, measure, daemon and repo-checks tests, contact sheet.
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Built on f1801ed (polish A3, PR #155 unmerged), branch shafic/findings-pr-b, uncommitted. Ready for QA with KIN-117.

Fixture: ProbeFixture.kt writes a third session, 2025-12-31T08-00-00_probe-payments_ci_debug (paymentsSessionCalls, the design's §8.3 rows; PaymentRulesTest now reads the same list instead of its own copy). Counts as PR A found them, not the design's: 10 findings in 18 calls, 3 high 6 medium 1 info, R1..R6 = 1,1,1,1,5,1, 12 flagged rows, coverage '13 mutation calls looked at; 6 request bodies compared, 2 unknown (2 truncated).' ProbeFixtureTest asserts them; no existing probe moved with the third session (live 268 + 13 new = 281).

How each criterion is shown (daemon on the CI fixture, INSPECTOR_UI_EXPECT_CHECKS=281 / archive 54):
#1 render-web-ui 'findings rail'. #2 'findings none'. #3 'findings payments', 'findings coverage'. #4 'findings txn link', 'findings anchor'. #5 'findings no value'. #6 check-web-static 'findings refresh burst/one flight/paused/hidden/stale dropped' and live 'findings live asks'. #7 archive 'findings rail in export', 'findings offline drawn', 'findings txn in export', 'findings category refused', 'keys in export'. #8 measure-web-ui 'shell <w> page findings fits' and 'findings <w> payments fits' at 1440/1000/600/400. #9 static 127 -> 132, live 268 -> 281 (T5 alone 277), archive 49 -> 54; ci.yml raised, and CI now also exports the payments session (INSPECTOR_UI_ARCHIVE_PAYMENTS).

Red proofs (each restored after):
- findings refresh burst: timer check removed -> 'NO - 100 owed calls over 1278 ms cost 22 requests; at most 2 were due'
- one flight: inFlight check removed -> 'NO - 3 out at once, 4 requests'
- paused: liveTail dropped from findingsMayRefresh -> 'NO - 20 calls owed with live tail paused sent 1 request(s)'
- hidden: findingsShown dropped from findingsWanted -> 'NO - 20 calls owed with the page not shown sent 1 request(s)'
- stale dropped: stands() check removed -> 'NO - an answer from before the session changed was drawn; an answer from before live tail was paused was drawn'
- route parse: id dropped from routeHash -> 'NO - #/findings?id=x came back #/findings; ...'
- page keys (updated honestly: g f now reaches the real row; 'g f with the findings row taken out' keeps the no-row case): PAGES row removed -> 'NO - g f gave {page:null}, not findings; g f in an export gave {page:null}'
- findings rail: rail link renamed -> 'NO - found no rail link for findings, so this checked nothing'
- findings none: zero-count rules omitted -> 'NO - found no rule section, so this checked nothing'
- findings payments: R4's findings dropped from the page -> 'NO - ... 9 findings; rules drawn otherwise: payment.ambiguous-resubmit; 10 rows linked, 12 due'
- findings coverage: coverage line not drawn -> 'NO - drew []; the daemon says ...'
- findings txn link: navigate with replace -> 'NO - opened #/txn/b0000001 ...; Back to Findings false (#/traffic)'
- findings anchor: findingsTarget always null -> 'NO - ?id=ca2f6bc5e91d marked nothing, scrolled nothing; an unknown id: ""'
- findings no value: evidence drew the row's raw header value -> 'NO - the page shows PLANTED, SECRET, k-PLANTED-7a1f'
- findings live asks: oweFindings() removed from the socket -> 'NO - 0 findings requests after a live call (1 due)'
- findings restored: probe left live tail paused -> 'NO - ... live false'
- Pace rule: with the findings lines present, chip marks active red with the endpoint chip's active toggle forced off -> 'chip marks active : false'
- archive: /findings branch removed -> 'findings offline drawn: NO - "not in this export: …/findings"'; live: true on the row -> 'findings rail in export: NO - shown false' and 'keys in export: NO - g f left traffic shown'; link to a wrong id -> 'findings txn in export: NO - at #/txn/b0000001-x, the call drawn false'; refusal skipped -> 'findings category refused: NO - category: 200'
- Chrome: .finding { white-space: nowrap } -> 'FAIL findings 1000/600/400 payments fits: overflows (… 10 wider than the page …)'; 1440 stayed fits.

Deviations: findingsWanted is the Findings page only (AC #6 is an upper bound); T7 widens it to Traffic when badges need the report, and adds byTxn, the renderList call after a reply and findingsPaintDetail. Resume reload is in 99-init's live button handler, not setLiveTail, to keep 25-traffic-list.js at 591 for T7. Call-link labels fall back to the id when the session's rows are not held yet (design's fallback; nothing redraws the page when the whole copy lands). PaymentRulesTest edited to read ProbeFixture's list (one copy of the fixture).

Not verified: iOS; render-web-ui on a CI runner (counts measured locally on the CI fixture, port 8102).
<!-- SECTION:NOTES:END -->
