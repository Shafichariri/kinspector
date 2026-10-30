---
id: KIN-172
title: >-
  Producer JS: the WebSocket send buffer is a second, unbounded queue when the
  daemon is attached but not reading
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-30 14:05'
updated_date: '2026-10-30 14:27'
labels:
  - 'item:producer-react-native'
dependencies: []
type: bug
ordinal: 172000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
KIN-162 efficiency contract, Memory row ('worst case about 16 MB', 'never meaningfully allocates'); bears on AC #4. Cause: flush() in producers/js/inspector-producer.js moves up to 256 Ki chars per tick into ws.send and never looks at ws.bufferedAmount, so an open but stalled socket becomes an unbounded queue with no drop count. Repro: connect the producer to a daemon, kill -STOP <daemon pid>, emit 2000 signals of 10 KB every 100 ms for 1.5 s. ws.bufferedAmount reaches 73.9 MB and RSS grows 197 MB while stats().state stays connected. Script: /private/tmp/claude-501/-Users-shafichariri-development-tools-kinspector--claude-worktrees-structure-phase-h-f7799d/31c55d58-a574-485f-9da6-5d3b1e073318/scratchpad/live/stall.js. Measured on Node's WebSocket only; React Native not measured. The fix design is the architect's (RN's WebSocket may not maintain bufferedAmount; being checked).
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 With a stalled daemon, the producer's memory held for the socket stays within the ring's bound
- [x] #2 Frames that cannot be sent stay in the ring and are counted when dropped
- [x] #3 A test shows this red without the fix
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [x] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
flush() checks backedUp(ws) before every frame: bufferedAmount > SOCKET_HIGH_WATER (1 MiB) re-arms flush after MIN_BACKOFF_MS and leaves frames in the ring. Undefined bufferedAmount (RN 0.79.6) never gates. signRes/signalErr bypass. Tests in transport.test.js: "the socket's buffer stops at the high-water mark" (red without the check: 8322822 bytes held) and "an undefined bufferedAmount never holds the queue" (red with !(b <= SOCKET_HIGH_WATER)). fake-ws.js harness({ bufferedAmount: true }) + sock.drain(). Live stall.js vs a HEAD-snapshot daemon: bufferedAmount 1.0 MB every round, RSS growth 49.9 -> 50.6 MB flat, dropped 29330, 0.0 MB after CONT. Decision sentence in capture.md 'JavaScript producers'.

QA round 2: replies.test.js 'a socket above the high-water mark still sends signRes and signalErr at once' pins the bypass; red when either reply is gated on backedUp(sock) (separately), green restored.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
#1 Verified on Node by a live kill -STOP: bufferedAmount held at 1.0 MB, retained heap flat at +6.3 MB, against 73.9 MB and +197 MB RSS before. On React Native accepted by the maintainer's 2026-10-30 ruling: the bound is the platform socket's (okhttp 16 MiB on Android; iOS to be measured in KIN-170; ping/pong is roadmap row 43a). #2 Verified. #3 Verified: test A red '8322822 bytes held by the socket'; test B red 'every frame went out, over 100 ticks'. Suites: node --test 44/44. Name scan: no match for 8 terms (QA round 1); rerun before commit. Not verified: Node 22, Hermes, devices.
<!-- SECTION:FINAL_SUMMARY:END -->
