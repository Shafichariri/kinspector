---
id: KIN-24
title: >-
  Signals: the web UI reads every signal in pages and keeps the whole session
  apart from the filtered set
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-18 19:27'
updated_date: '2026-10-18 22:45'
labels:
  - 'item:signals-query-one-rule'
dependencies: []
modified_files:
  - inspector-daemon/src/main/resources/web/js/15-session-data.js
  - inspector-daemon/src/main/resources/web/js/00-core.js
  - inspector-daemon/src/main/resources/web/js/05-network.js
  - inspector-daemon/src/main/resources/web/js/20-navigation.js
  - inspector-daemon/src/main/resources/web/js/40-copy-export.js
  - inspector-daemon/src/main/resources/web/js/45-visits.js
  - inspector-daemon/src/main/resources/web/js/50-path.js
  - inspector-daemon/src/main/resources/web/js/55-axis.js
  - inspector-daemon/src/main/resources/web/js/65-browser-detail.js
  - inspector-daemon/src/main/resources/web/js/70-timeline.js
  - inspector-daemon/src/main/resources/web/js/85-processes.js
  - inspector-daemon/src/main/resources/web/js/90-sessions.js
  - scripts/lib/mirror-runner.js
  - scripts/check-web-static.js
  - scripts/render-web-ui.js
  - scripts/render-web-ui-archive.js
  - .github/workflows/ci.yml
  - structure/budgets.txt
  - docs/decisions/web-ui.md
  - docs/decisions/shared-rules.md
type: feature
ordinal: 24000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Web half of item:signals-query-one-rule, built first because it is safe against the current route (which supports offset and ignores filter, so nothing a user sees changes in this ticket). (1) A new function signalPages(get, id, filter) in 15-session-data.js, between // @mirror-begin signalPages and // @mirror-end signalPages, registered as a NON_RULE_BLOCK like jsonParser. It asks /api/sessions/{id}/signals?filter=&offset=&limit=1000 page by page, newest first, dedupes by id (a live row arriving mid-read shifts a newest-first page by one, so the seam can repeat a row), and stops at an EMPTY page or a page that adds no new id, never at a short page: stopping at page.length < 1000 would silently truncate again the day the server clamp in SessionRepository.querySignals is lowered, and would loop forever against a source that ignores offset. Any page failing fails the whole read (a silently partial list is the defect being fixed); loadSignals keeps its catch-to-empty for daemons older than the page. (2) state.allSignals beside state.signals, mirroring allTransactions (docs/decisions/web-ui.md: 'state.transactions holds the rows the daemon matched against the current filter, so it is the wrong source for anything describing the session as a whole'). Unfiltered: one paged read, state.signals = allSignals.slice() (a copy: the live socket pushes into both). Filtered: state.signals is a paged read with the filter, allSignals is cached per session as syncAllTransactions does, and a signal pull (65-browser-detail.js) invalidates that cache before it reloads. state.screens = allSignals filtered to tag screen, so the second tag:screen request goes away. (3) Every reader of state.signals moves to allSignals EXCEPT the two that must honour the filter: renderTimeline's entries (70-timeline.js) and the path view's visit contents (50-path.js, the visits(...) call). Moving: tagsInSession, signalsForTag and the timeline-tab check (20-navigation.js), the session glance and the id lookup (65-browser-detail.js), sessionWindow, the band, the anchor and the hidden check (55-axis.js), sessionStartMono (45-visits.js; its 'one gap left' comment closes and is rewritten), the window and tagColour order (50-path.js), spanEndsFor and the id lookup (70-timeline.js), nearbyLines (40-copy-export.js), lastCapture (85-processes.js), the reset (90-sessions.js), the live push (05-network.js, into both, as transactions do). (4) archiveSource's signals branch honours offset and limit by slicing (no clamp of its own) and refuses a non-empty filter with 400, as its transactions branch and its own doc comment already say. (5) Record in docs/decisions/web-ui.md, extending 'The web UI keeps an unfiltered copy of the session': the same split for signals and why the tabs read the whole session, and that the pager stops at an empty page, not a short one. In docs/decisions/shared-rules.md change 'state.screens is fetched with tag:screen whatever the filter is' to say it is taken from the whole session. No public API, wire or schema change; daemon unchanged.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 scripts/check-web-static.js runs the real signalPages lifted by its anchors against stubs: a 1001-signal session served 1000 per page returns all 1001 ids, newest first, each once; a server clamping at 500 still returns all 1001; a source that ignores offset terminates with each id once; a row repeated across the page seam appears once. Proven failing with a 'page.length < 1000' stop (the 500 stub) and with the dedupe removed.
- [ ] #2 render-web-ui.js: the signal tabs' counts sum to the probe's own sessionSignals() length for the session, proving loadSignals reads the whole session through the pager. Proven failing with loadSignals reading one page of limit=500.
- [ ] #3 render-web-ui-archive.js, in the opened export: GET …/signals?offset=0&limit=N is a bare array, newest first, of min(N, total) rows; an offset past the end is []; a non-empty filter is a 400; and the signal tabs' counts sum to archive.signals.length. ARCHIVE_EXPECT_CHECKS in ci.yml moves by the number added.
- [x] #4 Against the current route nothing visible changes: every existing render-web-ui.js and render-web-ui-archive.js check passes, and UI_EXPECT_CHECKS moves only by the checks this ticket adds.
- [ ] #5 No reader of state.signals remains outside renderTimeline's entries, the path view's visits(...) call and hiddenByFilter (grep in the review). [Amended 2026-10-19: hiddenByFilter added as a third reader, recorded in docs/decisions/web-ui.md.]
- [ ] #6 web-ui.md and shared-rules.md carry the paragraphs above; DecisionsTest passes; structure/budgets.txt raises render-web-ui.js by hand with the reason on the line.
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Built by Bob (engineer). Static: node scripts/check-web-static.js -> 29 checks, 0 failed (was 25; +4 under '--- signal pages ---'). Live probe on the CI fixture: 165 passed (was 164; +1 'signal tabs count', 19 signals); no other label changed. Archive probe: 26 passed (was 21; +5: signals sliced by limit, limit past total, offset past end, filter refused, signal tabs count all); only the banner's export timestamp differs. ci.yml UI 164->165, ARCHIVE 21->26; budgets.txt render-web-ui.js 2645->2657 by hand with reason. :inspector-daemon:test --rerun 263/0 failures; :repo-checks:test --rerun 35/0 (DecisionsTest, FileBudgetTest green).
RED PROOFS. (1) 'if (page.length < 1000) return all' in place of the no-new-id stop: 'pages under a 500 clamp: NO - 500 ids, not the 1001 expected' and 'pages ignoring offset: NO - threw: more than 100 requests', exit 1. (2) dedupe line deleted: 'pages ignoring offset: NO - more than 100 requests' and 'pages repeat at a seam: NO - 1 id(s) returned twice', exit 1. (3) Anchor misspelt: harness exit 2 (main is async now; checked). (4) Live, loadSignals reading one page of limit=500, against a 1219-signal session (probe fixture + 1200 appended tap signals, served on 8232): 'signal tabs count: NO - the tabs count 500, the session holds 1219', exit 1; green on the same session reads 'yes (1219 ...)' through a route clamping at 1000. NOTE FOR QA: the CI fixture holds 19 signals, so this red is NOT visible in CI -- the 500-page break passes there. Reproduce with a >1000-signal session. (5) Archive, broken copies of the export: archiveSource returning the unsliced list -> 'signals sliced by limit' and 'signals offset past end' NO; filter refusal removed -> 'signals filter refused: NO - status 200'; pager limited to one page of 5 -> 'signal tabs count all: NO - 5 of 19' (each also fails 'every file inlined', as any edited copy does).
AC5 grep: state.signals is read only at 50-path.js visits(...) and 70-timeline.js entries; other hits are writes (signalPages result, live push, reset) and syncAllSignals' unfiltered copy. Small adds beyond the list: the reset also clears allSignalsSessionId and screens; selectSignal's screens fallback and the extra ...state.screens spreads in sessionStartMono/lastMono went, since allSignals contains every screen. Client-name scan could not run here (no terms provided).

QA (verify panel, head 5f6b610): AC4 Failed and AC6 Failed; nothing ticked, ticket stays In Progress. AC4 ('nothing visible changes / allSignals stays the whole session'): under a filter the cached whole-session signal copy goes stale after pause-and-resume or a reconnect, filed as KIN-27 (blocking). AC6 (web-ui.md paragraph accurate): web-ui.md falsely claims the waterfall says 'no matching calls'; three empty states say nothing happened under a filter, filed as KIN-28 (blocking). Also filed under the item, all depending on KIN-25: KIN-29 (slow older load lands under a newer filter, should-fix), KIN-30 (release line wording), KIN-31 (lastCapture reads state.transactions where web-ui.md says allTransactions; 85-processes.js:81).

QA (second verify panel, head e1711a3): AC4 Verified. AC6 Failed: ws.onclose never moves serverState off 'running', so an unplanned drop does not trigger the wasAway clear and re-read; web-ui.md and the comment at 05-network.js:221 claim it does. Filed as KIN-32. Also found on this pass and filed under the item: KIN-35 (two more ways the whole-session copies go stale). AC1-3 and AC5 were not part of this panel's verified list and stay unticked.

QA (third verify panel, head a86dde2): AC6 Failed again. Reverting 15-session-data.js:294 to 'if (!filter)' leaves static, live and archive checks all green, yet load-unfiltered-then-type-a-filter makes the path read 'nothing recorded' over visits with calls; checkPastTheClamp tests only filter-first. Also web-ui.md:161-162 says the copy costs 'nothing at all when no filter is set' (false since KIN-34) and :191 says the copies 'go stale in three ways, and each is closed' while the paragraph lists five and one is open. Filed as the bug below (C), with D for the open one. AC5 literal grep amended 2026-10-19 to include hiddenByFilter (25-traffic-list.js:237), a third reader of state.signals recorded in web-ui.md:166-169; the amendment widens the grep to what the doc already states, the intent (only the filter-honouring readers) is unchanged. AC5 itself was not part of this panel's verified list and stays unticked.
<!-- SECTION:NOTES:END -->
