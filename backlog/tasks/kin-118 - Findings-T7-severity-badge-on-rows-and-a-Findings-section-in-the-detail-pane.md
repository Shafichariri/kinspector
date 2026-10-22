---
id: KIN-118
title: 'Findings T7: severity badge on rows and a Findings section in the detail pane'
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-22 08:24'
updated_date: '2026-10-22 13:11'
labels:
  - 'item:findings-payment-safety'
dependencies:
  - KIN-116
type: feature
ordinal: 118000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Order row 26 (26a for Shared body evidence), docs/ROADMAP.md §6 → Findings. Built to the architect's design note for item:findings-payment-safety (questions answered 2026-10-22 by the maintainer's proxy: the findings key is read live; Q1 keep both notions of duplicate; Q2 six default payment paths; Q3 resubmitWindowMs 60000 beside duplicateWindowMs 3000; Q4 every mutation plus an empty ignorePaths; Q5 not in this item). PR B or C (web). Q1 answered 2026-10-22: keep both; the badge never says duplicate or repeat and cites no repeat group. web/js/97-findings.js, 25-traffic-list.js, 35-detail.js, css/90-findings.css, scripts/lib/findings-probes.js, scripts/measure-web-ui.js, docs/decisions/daemon-and-mcp.md, docs/decisions/web-ui.md.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 On the payments session exactly the 12 flagged rows carry .finding-badge with the severity of their first finding in words, no hue, and never "duplicate" or "repeat"
- [ ] #2 At 400 px path tails and byte counts stay whole, with the badge short
- [ ] #3 A flagged row's detail shows a Findings section with title, message, this row's evidence and open in Findings; unflagged rows show none; #/txn/<id> shows it live and in an export
- [ ] #4 A reply that changes a badge redraws the list without refetching the detail's bodies
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
1. 97-findings.js: findingsWanted widened to Traffic (a call's page included) and Findings; byTxn built in reply order skipping dismissed findings; after a reply renderList() only when txn->first severity changed, findingsPaintDetail only when the shown call's findings changed (never renderDetail). Traffic loads after the first list draw (init) and on entering Traffic; in an export from the embedded report.
2. findingBadgeFor(txn): span.finding-badge.sev-<s> with .fb-long '⚑ <severity>' and .fb-short '⚑' below 520px; title names severities and counts only (Q1: never duplicate/repeat). One line in 25-traffic-list.js after the dup badge.
3. 35-detail.js: div.detail-findings after .detail-head, filled by findingsPaintDetail(txn): 'Findings (n)', per finding badge, rule title, message, this row's evidence lines, 'open in Findings'; nothing when none.
4. 90-findings.css: row badge (no hue), fb-short media rule, detail section.
5. Probes in scripts/lib/findings-probes.js (render-web-ui.js unchanged at 2354): findings badge (12 rows, severity of first finding, no duplicate/repeat), findings detail, findings reply redraws (stubbed reply changes a badge: list redrawn, no body refetch), findings txn page; archive: findings badge in export, findings detail in export. Chrome: room 400 payments rows (tails, bytes, badge short, badge colour no hue) in list-room-cases.js.
6. daemon-and-mcp.md Q1 paragraph; web-ui.md findings paragraph gains the badge and detail.
7. Red proofs per §8.2 (badge every row, skip findingsPaintDetail, drop .fb-short media rule, renderDetail on reply); Pace rule; ci.yml counts before->after.
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Built on fc3296f (PR B), branch shafic/findings-web-c, uncommitted. Ready for QA with KIN-119. Patch alone (scratch kin-118-t7.patch): static 133, live 286, archive 56.

What: 97-findings.js widens findingsWanted to Traffic (a call's page included), keeps byTxn (reply order = severity order, dismissed findings skipped) and findingsApply after every reply: renderList() only when a call's first severity moved, findingsPaintDetail only when the shown call's findings moved, never renderDetail. Traffic asks after its first page of calls (99-init) and on entering Traffic (PAGES traffic enter); in an export it reads the embedded report. findingBadgeFor(txn): span.finding-badge.sev-<s>, .fb-long '⚑ high', .fb-short '⚑' below 520px; title counts severities only. 25-traffic-list.js: two lines after the dup badge, offset by folding the attempt span into one line (591 -> 590 with T7 alone). 35-detail.js: div.detail-findings after .detail-head + findingsPaintDetail(txn). 90-findings.css: row badge in a fixed 14px box, fb-short media rule, detail section. 35-list.css narrow block: host flex-shrink 1000 and path flex 1 1 auto (see deviations). Q1 paragraph in daemon-and-mcp.md; web-ui.md findings paragraph extended, narrow-row paragraph extended.

How each criterion is shown (render-web-ui.js on the CI fixture, port 8102; archive with INSPECTOR_UI_ARCHIVE_PAYMENTS; measure-web-ui.js --only room):
#1 'findings badge' (12 of 18 rows, severity of first finding, no s*/m-* class, no duplicate/repeat in text or title), archive 'findings badge in export'; Chrome 'room 400 payments rows' compares the badge's colour with the row's text (muted for info).
#2 Chrome 'room 400 payments rows': every tail and byte count whole inside its path box, badge short, all rows 30px.
#3 'findings detail' (b0000001: Findings (2), titles, messages, this row's evidence lines, open in Findings marks #/findings?id and Back returns; unflagged row: none; no planted value in the section), 'findings txn page', archive 'findings detail in export'.
#4 'findings reply redraws' (stubbed reply moves b0000001's badge to info: row redrawn, section repainted, .detail-head node kept, 0 body reads; an unchanged reply redraws nothing). Static 'findings refresh traffic' (20 owed calls on Traffic send 1 request).

Red proofs (each restored):
- badge every row -> 'findings badge: NO - 18 rows badged, 12 due (the fixture's 12); b000000b badged, with no finding; ...'
- 'Duplicate mutation' in the badge title -> 'findings badge: NO - ... b0000001 "⚑ high⚑ 2 findings on this call (high, medium); open the call to read them (Duplicate mutation)" ...'
- byTxn built from txnIds.slice(1) -> 'findings badge: NO - 5 rows badged, 12 due' and 'findings badge in export: NO - 5 rows badged, 12 due'
- findingsPaintDetail call removed from renderDetail -> 'findings detail: NO - found no finding in the detail, so this checked nothing'; 'findings txn page: NO - at #/txn/b0000001: heading "" ...'; 'findings detail in export: NO - ...'
- renderDetail on a reply -> 'findings reply redraws: NO - row redrawn true, head kept false, ...; 2 body reads (0 due)'
- renderList on every reply -> 'findings reply redraws: NO - ...; an unchanged reply left the row false'
- Traffic dropped from findingsUsed -> 'findings refresh traffic: NO - 20 calls owed on Traffic sent 0 request(s); 1 was due'
- .fb-short media rule removed -> 'FAIL room 400 payments rows: cut (...; cut: badge of payments ...)'
- .sev-high { color: var(--err) } -> 'FAIL room 400 payments rows: cut (...; badge of payments (rgb(255, 107, 111), rgb(230, 232, 235)) ...)'
- badge at a 16px line -> 'FAIL room 400 payments rows: uneven (...; row heights 31/30px)'
- tail judged in the row only (old check) passed 'transi' for transfers; judged in its path box: base 'cut' on 6 rows, head before the 35-list.css fix 'cut' on 12.
- Pace rule: with these lines present, endpoint chip's active toggle forced off -> 'chip marks active : false'.

Deviations: (1) 35-list.css narrow block changed: on a session with no scope bar (the payments session draws its host on every row) base already cut 6 tails at 400px and the badge made it 12; the host now gives way by weight. 'room 400 rows' unchanged; measure vs fc3296f moved only 'room 400 payments rows: cut -> whole'. (2) The badge title counts severities and does not name rules (design §6.3's example named 'Duplicate mutation'; Q1 says the badge never says duplicate). (3) findingsWanted/Used: the design's 'drop the report otherwise' is not built; a report is kept per session and only applied when it is the selected session's. (4) 'room 400 rows' tail check now measures inside the path box. (5) Probes in a new scripts/lib/findings-traffic-probes.js (findings-probes.js would have passed 600); render-web-ui.js unchanged at 2354.

Seen (sheet, scratch findings-c/sheet): served and exported, dark and light, 1440/1000/600/400 'flagged list' and 'flagged detail' shots (new in sheet-cases.js). At 1440 with the detail column open, rows that are both repeats and flagged draw no path at all (host 180px, ·repeated 2x and the badge take the row); not covered by any criterion; suggested follow-up: the same host-gives-way rule at every width.

Not verified: CI runners; 'first' group (160k session) not run, although Traffic now asks for findings after its first page.
<!-- SECTION:NOTES:END -->
