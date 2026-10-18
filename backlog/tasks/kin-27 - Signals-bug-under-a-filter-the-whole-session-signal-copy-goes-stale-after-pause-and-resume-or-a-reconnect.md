---
id: KIN-27
title: >-
  Signals bug: under a filter the whole-session signal copy goes stale after
  pause-and-resume or a reconnect
status: To Do
assignee:
  - '@engineer'
created_date: '2026-10-18 20:21'
labels:
  - 'item:signals-query-one-rule'
dependencies:
  - KIN-25
type: bug
ordinal: 27000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Found by the verify panel (Doug) on KIN-24 AC4, head 5f6b610. Blocking. syncAllSignals (web/js/15-session-data.js:149) reuses its cached copy whenever the session matches. Neither setLiveTail(on) (25-traffic-list.js:409) nor the wasAway branch of ws.onopen (05-network.js:224) clears it. Repro: load the fixture and filter path:/v3; setLiveTail(false); append two screen signals and one signal with a new tag to signals.jsonl; setLiveTail(true). The path map and tabs miss them until the filter is cleared. Base showed them, because it fetched tag:screen on every load. Harnesses: $TMPDIR/doug-sig/harness/stale.js, and stale.js under the scratchpad's h/.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Both setLiveTail(on) and the wasAway branch of ws.onopen clear allSignalsSessionId and allSessionId; the latter also fixes the older identical staleness of the endpoint chips
- [ ] #2 A probe or static check reproduces the scenario: red with either clear removed, green with both in place
- [ ] #3 The syncAllSignals failure fallback no longer caches the filtered set as the whole session
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
