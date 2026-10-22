---
id: KIN-117
title: 'Findings T6: findings settings on the Settings page'
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-22 08:24'
updated_date: '2026-10-22 11:21'
labels:
  - 'item:findings-payment-safety'
dependencies:
  - KIN-113
  - KIN-114
type: feature
ordinal: 117000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Order row 26 (26a for Shared body evidence), docs/ROADMAP.md §6 → Findings. Built to the architect's design note for item:findings-payment-safety (questions answered 2026-10-22 by the maintainer's proxy: the findings key is read live; Q1 keep both notions of duplicate; Q2 six default payment paths; Q3 resubmitWindowMs 60000 beside duplicateWindowMs 3000; Q4 every mutation plus an empty ignorePaths; Q5 not in this item). PR B (web). web/js/80-settings.js, web/index.html, web/css/90-findings.css, scripts/lib/findings-probes.js.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Settings lists every rule of effective.findings.rules with an enabled box and a severity select, and the five payment settings
- [ ] #2 A save sends one findings object holding only non-default rule entries and changed payment fields, carrying other sub-objects through untouched; a rule back at its defaults is removed
- [ ] #3 The daemon's 400 text is shown
- [ ] #4 Success reads "saved — findings use it now"
- [ ] #5 A probe stubs the PUT and checks the bodies (red with the whole effective object sent)
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
1. index.html: #findings-config inside #config-form.
2. 80-settings.js: fieldset generated from effective.findings (rules table: enabled box, severity select with (default); payment: three textareas one per line, two windows in ms); configPatch gains findings built from file.findings, only non-default rule entries and changed payment fields, other sub-objects carried, null when empty; daemon 400 text shown; save message 'saved — findings use it now' (with the restart clause when other keys changed).
3. css/90-findings.css for the fieldset.
4. lib/findings-probes.js: a probe stubbing the PUT and checking bodies, red with the whole effective object sent.
5. Contact sheet look at Settings 1440/400.
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Built with KIN-116 on f1801ed, branch shafic/findings-pr-b, uncommitted. Ready for QA.

What: #findings-config, a fieldset inside #config-form, drawn by findingsConfigRender in 80-settings.js from effective.findings (a row per rule: on box, severity select with its default labelled '(default)'; the five payment settings, lists one per line). configPatch gains findings from findingsConfigPatch: starts from file.findings, carries sub-objects it does not draw, keeps a rule only where it differs from its defaults (one put back is removed), a payment field only where it differs from the file or, with none in the file, the daemon's value; null when empty; nothing sent when it equals the file (key order ignored). Save message: 'saved — findings use it now', or '...; the rest applies when the daemon restarts' with other keys, or the old wording without findings. The daemon's 400 text is shown as before. The config note and the index.html comment now say the findings key is the one read live.

How each criterion is shown (render-web-ui.js on the CI fixture; every PUT stubbed by the probe):
#1 'findings settings drawn' (6 rules with box and select naming the default, 5 payment settings). #2 'findings settings patch' (one PUT; a file holding findings.sensitive, a rule override and a payment field; the override put back to defaults is removed, sensitive carried as written). #3 'findings settings refused'. #4 'findings settings saved'. #5 the patch line, red with the whole effective object sent.

Red proofs (each restored):
- patch: findingsConfigPatch returned effective.findings -> 'NO - 1 PUTs; sent {"findings":{"rules":[{"id":"payment.duplicate-mutation",...}],"payment":{...}}}; due {"findings":{"sensitive":{...},"rules":{"payment.duplicate-mutation":{"severity":"medium"},"payment.retry-with-key":{"enabled":false}},"payment":{"resubmitWindowMs":60000,"paymentPaths":[...,"wallet"]}}}'
- saved: restart wording kept for a findings save -> 'NO - "saved — applies when the daemon restarts"'
- refused: generic text on a refusal -> 'NO - 2 PUTs; status "could not save", error true'
- drawn: '(default)' label removed -> 'NO - 6 of 6 rows whole ...; 0 name the default'
- Pace rule: shown with KIN-116's run (chip marks active red with these lines present).

Looked at: Settings at 1440 and 400, dark and light, fieldset scrolled into view (scratch look/ shots): rows aligned after a label min-width and list boxes sized to their defaults.

Not verified: a save against the real daemon's file from the page (the probe stubs PUT by rule; ConfigFileTest/ConfigRoutesTest hold the daemon side).
<!-- SECTION:NOTES:END -->
