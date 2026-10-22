---
id: KIN-124
title: >-
  Sensitive data T5: the page — dismiss, dismissed fold, no-hosts hint,
  first-party hosts in Settings, probes
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-22 09:40'
updated_date: '2026-10-22 15:02'
labels:
  - 'item:sensitive-data-detection'
dependencies:
  - KIN-122
  - KIN-123
  - KIN-116
  - KIN-117
type: feature
ordinal: 124000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Order row 30, docs/ROADMAP.md §6 → Sensitive-data detection — detect, never redact. Built to the architect's design note for item:sensitive-data-detection (questions answered 2026-10-22 by the maintainer's proxy). Plugs into Findings (item:findings-payment-safety) as one FindingSource; no public API, no schema, no change to what capture records. Web, after findings PR B. Files: web/js/98-findings-dismiss.js, web/css/92-findings-dismiss.css, web/js/80-settings.js, web/scripts.txt, web/styles.txt, ProbeFixture.kt, ProbeFixtureTest.kt, scripts/lib/sensitive-probes.js, scripts/render-web-ui.js, scripts/render-web-ui-archive.js, scripts/lib/measure-cases.js, .github/workflows/ci.yml, structure/budgets.txt, docs/decisions/web-ui.md.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 The per-width spec holds at 1440, 1000, 600 and 400, live and in an export
- [ ] #2 A dismissal needs a reason, reads the config afresh and sends only findingDismissals through controlFetch
- [ ] #3 The finding moves to dismissed (n) only after the daemon's reply; restore removes the entry
- [ ] #4 Dismiss and restore are hidden in an export; the fold is drawn there
- [ ] #5 The page's Findings subtree never shows a planted raw value, live or exported
- [ ] #6 Settings edits findings.sensitive.firstPartyHosts through Findings' configPatch(), which carries the rest of findings.sensitive as the file holds it
- [ ] #7 The sensitive coverage line and every sensitive rule's line show on the Findings page; with no firstPartyHosts the page says nothing is raised for where data was sent and links Settings
- [ ] #8 Probe fixture plants without new rows; every existing probe count unchanged except the new lines
- [ ] #9 Each probe line was shown red, and the Pace neighbour too
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
Plan (bob):
1. Fixture: plant three published test values in the probe session, no new rows (ProbeFixture.kt), assert each in ProbeFixtureTest. The design's ids are off by one: the POST checkout is a0000008, not a0000007.
2. Page: js/98-findings-dismiss.js and css/92-findings-dismiss.css. Dismiss form (a reason is required; reads GET /api/config afresh; sends only findingDismissals through controlFetch), the dismissed (n) fold with restore, Escape stays inside the form, the no-hosts hint. Hooks in 97-findings.js: includeDismissed=true live, dismissed findings go to the fold, evidence line laid out per width.
3. Settings: a first-party hosts textarea in the Findings fieldset; findingsConfigPatch replaces sensitive.firstPartyHosts only.
4. Probes: scripts/lib/sensitive-probes.js, wired through findings-probes.js probeFindings so render-web-ui.js stays at 2354; archive lines; Chrome cases for masked whole and the dismiss form fitting.
5. Existing probes and tests that took the probe session as the no-findings session move that check to the long session, with their counts unchanged.
6. ci.yml counts, web-ui.md paragraph, red/green proofs, Pace neighbour (chip marks active).
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Built by bob, not committed. Tree: branch shafic/sensitive-data-t5 off f4bbfbf.

Counts (CI replay on port 8102): static 133 -> 133; live 289 -> 299 (+10: 9 sensitive lines + 'sensitive dismiss reach'); archive 56 -> 58 (+2). ci.yml updated. render-web-ui.js unchanged at 2354 (wired through findings-probes.js probeFindings), so structure/budgets.txt was not touched.

Fixture: three published test values in rows the probe session already had (16 rows, ids unchanged): card 4000056655665556 in a0000008's request body beside cvc (the design said a0000007, which is the PNG), api_key=probe-key-not-real on a0000004, and IBAN GB82WEST12345698765432 in a list in a000000b's response ({accounts:[{iban}]}), so the group path $.accounts[*].iban differs from the call's $.accounts[0].iban. Result: 4 findings (1 high, 3 medium). The card is not the design's 4000123412341234: the run rules want published test values, so it is a widely published test card that is not on the daemon's list of eleven, and still medium.
Tests and probes that took the probe session as the no-findings session now read the long session, with their counts unchanged: findings none, flagged none, FindingsRouteTest, HarFindingsTest (the byte-for-byte HAR); FindingsCliTest compares the default report with REST's.

Red proofs (each its own run, file restored byte for byte):
- sensitive masked on page: a stub with the raw card in masked -> 'NO - the page shows 4000056655665556, 400005665566, 0566556655; 3 of 4 masked hints drawn'
- sensitive coverage line: line removed from SensitiveSource's coverage -> 'NO - the daemon sent no sensitive coverage line, so this checked nothing'
- sensitive rules listed: rules from findings -> 'NO - drawn otherwise or missing: sensitive.jwt-in-url, sensitive.credentials-over-http, sensitive.email, ...'
- sensitive no hosts hint: condition inverted -> 'NO - hint "", link null, under the coverage line false; drawn with hosts set true'
- sensitive dismiss reach: the reach line removed -> 'NO - found no reach line in the form, so this checked nothing'
- sensitive dismiss: reason check removed -> 'NO - empty: disabled true, 2 config requests; ...'; key from the evidence path -> '... sent ... "path":"$.accounts[0].iban" ...; due ... "$.accounts[*].iban"'
- sensitive dismissed fold: dismissed drawn in the group -> 'NO - in its group true, ...'
- sensitive escape: stopPropagation removed -> 'NO - ... the page heard 1 Escape ...'
- sensitive hosts patch: sensitive replaced whole -> 'NO - ... {"findings":{"sensitive":{"firstPartyHosts":[...]}}},{"findings":null}'
- sensitive restored: probe leaves Settings -> 'NO - Traffic false, ... #/settings'
- Pace neighbour chip marks active: active mark never set -> 'chip marks active  : false'
- archive sensitive in export masked: raw card embedded -> 'NO - the page shows 4000056655665556, ...'
- archive dismiss hidden in export: body.archive rule removed -> 'NO - 4 visible (dismiss…, dismiss…, dismiss…, restore)'

Reach wording (PO ruling, PR #167), in the form above the reason, and in the control's tooltip: 'Dismissing hides every "<rule title>" finding on <route> at <location> <path>, in every session, not only this one, until it is restored.'

Chrome (measure-web-ui.js --only polish,shell,room,layout,first --base f4bbfbf): 236 readings on each side, head 0 failed. The only moves are the 12 new readings, from 'found nothing' on base to whole/fits. first rows huge 414 ms.
Chrome reds: removing flex:none alone from .ev-masked stays green, because a no-wrap flex item's minimum is its whole text (the decision text says so). Removing flex:none and nowrap together -> 'findings 1440/1000/600 masked whole: cut (... not whole: •••• 5556)', served and export. 400 stacking rule removed -> 'findings 400 dismiss form fits: overflows (... stacked false ...)'.
Daemon test 532/532 and repo-checks 36/36, both run with --rerun.
<!-- SECTION:NOTES:END -->
