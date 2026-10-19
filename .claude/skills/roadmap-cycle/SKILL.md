---
name: roadmap-cycle
description: Run Inspector's agent team over the roadmap — take in a new plan, or carry one roadmap item from intake through design, build, verification, usability review and tracking, with agent-team phases where reviewers should debate. Use when asked to "work the roadmap", "take this plan", "build <item>", "debug <defect>", or "where are we".
---

# Roadmap cycle

The team lives in `.claude/agents/`; `docs/TEAM.md` explains each role. **You (the main session)
are the conductor**: call each role with the Agent tool, pass the previous role's output forward
verbatim, and stop at the gates below. Subagents *can* spawn subagents now, but do not let a role
chain to the next one itself: subagents cannot ask the human anything, so every gate below would be
skipped, and QA run by the engineer it is checking is no longer independent.

## Tracks — pick one before Mode B starts, and say which

The process scales to the item. Every role costs a full context window (150–220k tokens and 10–20
minutes a call on `item:web-transactions-paged`), and three reviewers reading one diff mostly
duplicate each other. Pick the **lightest** track that fits, from the item's roadmap size and what
it touches:

| Track | When | Design | Tickets cut by | Verify | UX |
|---|---|---|---|---|---|
| **Small** | An S item or a bug, on one surface | The `engineer` writes a short plan into the ticket before building | `delivery-tracker` | One `qa-verifier` | Never as its own role |
| **Standard** | An M item, or an S item that has to be designed | `architect` design note | `architect` | One `qa-verifier` | Never as its own role |
| **Large** | An L item, or an item that crosses surfaces, touches public API, schema or the security boundary, or changes loading, paging or layout across several views | design-council team phase | `architect` | verify-panel team phase, first round only | Yes |

Rules every track keeps:

- **The `architect` is called only when needed.** On the Small track that is when the engineer's
  plan touches public API, schema, the security boundary or a recorded decision in
  `docs/decisions/`. On any track, it reviews the diff only when the code is restructured (a file
  is split, a module boundary moves) or a decision file is rewritten. A bug fix is neither.
- **QA renders what a user sees.** For a change to the overlay or web UI, the QA brief requires
  QA to render the page at the widths the criteria name, live and in an export where both
  exist, and to attach screenshots. On `item:web-transactions-paged` both UI blockers passed every
  text check and showed only in a rendered page, so rendering is what matters, not a separate
  reviewer.
- **The `ux-reviewer` is for new screens and layout redesigns** (1a, 1b, the kind of item that
  gets the Large track). It is not for the wording of one count or a single CSS rule.
- **A fix round is re-checked by one fresh `qa-verifier`, always.** A round expands only when QA
  reports something it cannot judge, and the report names the role that can.
- **An item does not grow once its design gate has passed.** A defect found during the build or a
  panel gets its own roadmap item through the `product-owner`, unless it fails one of this item's
  criteria or is a regression this item introduced. Before the gate, folding in new criteria is the
  human's call, and the gate states what folding them would do to the size.
- **Moving up a track is a gate.** If the work turns out bigger than its track (for example, a
  Small plan that touches a recorded decision), stop and ask the human before going up a track.

## Mode A — a new plan arrives (`$ARGUMENTS` is a plan or a path to one)

1. `product-owner`: fold the plan into `docs/ROADMAP.md` as named items with acceptance criteria.
2. `architect`: size each new item (module, public-API impact, risks) — one line each.
3. **Gate:** show the human the items, anything marked "overturns a recorded decision", and the
   open questions. Do not start building until they pick what goes first.

## Mode B — build one item (`$ARGUMENTS` names a roadmap item)

Tickets live in `backlog/` (Backlog.md). A roadmap item is the *what and why*; its tickets are the
*how*, each labelled `item:<slug>` with the slug the item names. `docs/TEAM.md` → "Tickets" has
the commands and who may change what; `.claude/hooks/backlog-guard.js` enforces it.

Pick the track first (see "Tracks" above), and name it to the human with the reason.

1. **Design.**
   - **Small:** the `delivery-tracker` files one ticket per change from the roadmap item's own
     criteria. The `engineer` writes a short plan into each ticket's notes (files, the check that
     proves it, how that check is shown red) and builds from it. The `architect` is called only
     when the plan touches public API, schema, the security boundary or a recorded decision, and
     that call moves the item to the Standard track.
   - **Standard:** `architect` → design note (files, tests, risks).
   - **Large:** the design-council team phase.
2. **Gate** when the note or plan says public API, schema, the security boundary or a recorded
   decision changes, when it re-sizes the item, or when it proposes folding new criteria in. A
   council's note always gates. Name the human's open questions here, before any build, rather than
   leaving them for a later round.
3. **Tickets.** On the Standard and Large tracks, the `architect` (subagent) splits the agreed note
   into tickets: one per change a single build and a single QA pass can close, with acceptance
   criteria copied from the note and `--dep` where order matters. Tickets come after the gate, so a
   rejected design leaves no orphans. On the Small track, the tickets from step 1 stand.
4. `ux-reviewer` → review the design, **Large track only**. Run it in parallel with the start of
   step 5 only if the design is already agreed.
5. `engineer` → build ticket by ticket on the session's branch, moving each to In Progress. It
   reports "ready for QA" with the ticket IDs; it never marks one Done.
6. **Verify**, always in a **fresh** call, never a continued engineer conversation, which would
   review its own reasoning instead of the diff.
   - **Small and Standard:** one `qa-verifier`. For a UI change, it renders the page as "Tracks"
     says. The `architect` reviews the diff in parallel only when the code is restructured or a
     decision file is rewritten.
   - **Large:** the verify-panel team phase for the first round.

   QA reports per ticket ID. Any **Failed** or blocking finding goes to the `delivery-tracker`,
   which files it as a `bug` ticket under the same item. Then back to step 5. **Every fix round is
   re-checked by one fresh `qa-verifier`, on every track.** Loop until clean, with two checks on
   every round:
   - **Scope review before the build.** Read the new bug tickets' criteria for scope yourself; the
     `architect` does it only on the Large track. A criterion that needs another item's work, or a
     defect that predates the diff, goes to the `product-owner`, not into the round.
   - **Stop rule.** If QA finds a blocking regression that the previous fix round introduced, gate
     with the human before another round: narrow the ticket that caused it, revert it, or continue.
     Do not start a third round on your own.
7. `ux-reviewer` → render and look, **Large track only**. On the other tracks, QA's rendering in
   step 6 replaces it.
8. `delivery-tracker` → close each ticket with the QA evidence, then the roadmap item once every
   ticket under it is Done, then `docs/STATUS.md` and the phase table in `AGENTS.md` "Where we are".
9. `release-steward` → if consumer-visible: INTEGRATION.md bump + changelog; always: hygiene scan,
   which covers `backlog/` — ticket files are committed and public.
10. Commit (as the maintainer, no attribution trailers — see `AGENTS.md`) and push only if the human
    asked for it; report the QA table and what stays unverified.

## Mode C — status (`where are we`)

Call `delivery-tracker` for its status report and relay it. It reads the board too
(`backlog task list --plain`), so the report covers tickets as well as roadmap items.

## Mode D — commercial (`make money`, `pricing`, `monetise`)

Call `business-strategist`. Relay its interview questions to the human and wait for answers. Any
paid feature it proposes goes through Mode A via the `product-owner`. **Never write its output into
the repository** — it is public.

## Mode E — root cause (`debug <defect>`, "why does …")

Run the **root-cause** team phase below, then take the surviving explanation into Mode B as the
item to build. No code changes inside the phase.

## Team phases (agent teams)

Agent teams are on in this repository's settings. A teammate is a full Claude Code session that can
message the others, which is worth its cost only where reviewers should **argue**: a subagent can
only report to you, so two subagents never find out they disagree. You stay the lead and the
conductor; every gate above still stops for the human.

`.claude/hooks/team-guard.js` enforces the limits, and its refusal tells you what to do instead:

- A named Agent call launches a teammate. Spawn teammates **only** for a phase below, with
  `[team-phase: <phase>]` on the first line of each prompt; everything else is a subagent — call
  Agent without `name`.
- Spawn every member of a phase **in one message, each with `run_in_background: false`**. A
  backgrounded teammate gets no shared task list and receives messages only after its turn ends;
  the guard refuses it. Tell each member the others' names, that a message reaches a member at its
  next tool round, and not to poll with `sleep`: the harness blocks chained sleeps (seven refusals
  in one session). Long commands go in `run_in_background`.
- Tell every member to copy revisions with `scripts/qa-snapshot.sh` (`docs/TEAM.md`,
  "Snapshots"), and run that command once yourself before spawning a phase you will leave
  unattended, so any approval prompt surfaces while the human is there.
- Rosters: **design-council** — `architect`, `ux-reviewer`, `qa-verifier`; **verify-panel** —
  `qa-verifier`, `architect`, `ux-reviewer` (UI items only); **root-cause** — `architect`,
  `qa-verifier` (one `qa-verifier` per hypothesis is fine).
- Never teammates: `engineer`, `product-owner`, `delivery-tracker`, `release-steward`,
  `business-strategist`. At most 3 live teammates (`INSPECTOR_TEAM_MAX`).
- Team phases are read-only (`.claude/hooks/team-read-only.js`), tickets included
  (`backlog-guard.js`). A teammate that wants a file or ticket changed sends you the exact text;
  after the phase, hand it to the owning role as a subagent.

Teammates do not see this conversation. Every spawn prompt carries the item's text and acceptance
criteria verbatim, the phase rules below, and the names of the other teammates. Name them
by their handles — `charles` (architect), `simon` (ux-reviewer), `doug` (qa-verifier), or
`doug-<hypothesis>` for one per hypothesis — so they can address each other. A handle reused in a
later phase resolves to the newest agent, so add a suffix (`doug-2`) when an earlier one may still
be messaged. When you report to the human, call each role by its name (docs/TEAM.md lists them).

**design-council** (replaces Mode B step 1)
1. Create the tasks: design note (`architect`); design review (`ux`, blocked by the note, only if the
   item touches the overlay or web UI); verification plan — how each criterion will be *proven*
   (`qa`, blocked by the note).
2. `architect` drafts; `ux` and `qa` challenge it by message; `architect` revises. Two rounds, then
   you take what is still disputed to the human rather than letting the debate run.
3. Merge the note and QA's verification plan into one design note, shut the teammates down, and
   **always** gate with the human — a council exists because the item is big enough to need one.

**verify-panel** (replaces Mode B steps 5–6)
1. Each member writes its verdict **before** reading anyone else's — QA's table, the architect's
   ranked diff findings, UX's rendered review. Independence first, then cross-examination: each one
   tries to break the others' "Verified" claims.
2. `qa-verifier`'s table is the only source of Verified; a disagreement it cannot settle with
   evidence is reported as Not verified, never averaged away.
3. Any Failed → `engineer` (subagent) fixes, then message the **same** panel to re-verify
   (`SendMessage` resumes a member whose turn ended); spawn fresh members only for a new item, so
   the panel keeps its harnesses and does not re-read every ticket. Re-verifying means re-running
   the commands, not re-reading the earlier output. Mode B step 6's scope review and stop rule
   apply to every round. Shut down when clean.

**root-cause**
1. Write 2–3 competing hypotheses yourself; spawn one teammate per hypothesis.
2. Each tries to prove its own and **disprove the others'** with evidence (a failing test, a log line,
   a reproduction). The hypothesis that survives goes to the human and then into Mode B.

Skip teams for small single-surface items, docs-only changes, status, release and commercial work.
Teams cost a full context window per member — each loads the project context on its own.

## Rules for the conductor

- Proxy capture (4c) never starts without the human explicitly choosing it.
- Never report a role's claim as fact without the `qa-verifier`'s evidence.
- Every ticket hangs under a roadmap item. A defect found outside any item goes to the
  `product-owner` first; it gets tickets once it is on the roadmap.
- Wait for teammates to finish before acting on a phase; don't do their part yourself.
- The repository is public: no client, employer or product names anywhere, including prompts that
  end up in commit messages.

### Pace — what the signals item (2026-10-18/19) cost, and the rules it left

- **Decide a round's scope before you spawn its engineer.** A message reaches a running agent only
  at its next tool round, so a scope change mid-build means undone work. Put a large ticket (one
  that changes loading, paging or layout across views) in its own round; never bundle it with
  small fixes, so the small ones can close while the large one is still open.
- **A fresh engineer per item, and per round once its report runs long.** The engineer's context
  per call grew from about 118k to 227k tokens across one item, and every step slowed with it.
  Brief a new one with the ticket paths and the previous round's notes rather than resuming.
- **Size the review to the item. "Tracks" above is the rule, and it replaces the old "full panel
  for any round that moves layout".** Single-QA re-checks (KIN-21, KIN-22, KIN-26, KIN-41, KIN-50)
  took about 25 minutes each against 30 for a panel, and found what mattered. On
  `item:web-transactions-paged` (2026-10-19), the architect's place in the verify panel found
  nothing QA did not also find, a second full panel was spent on a round whose code was already
  right, and the item grew from S to M through criteria added after it was ordered, before any
  role had touched it.
- **One owner per finding in a panel.** Give each member its lens in the spawn prompt (QA: the
  criteria and red proofs; architect: the diff and the rules; UX: what a user sees) and tell them to
  cross-check each other's claims by message, not to reproduce each other's findings. In panel 4
  four agents independently established the same two defects.
- **A panel ends with a close verdict.** Each member says plainly whether the item can close, and a
  finding blocks only if it fails a criterion of this item or is a regression this item introduced.
  Everything older goes to the `product-owner` as a list, never into the round.
- **Heuristics are documented as heuristics from the first draft.** A check that is not a parser
  is described as "a heuristic; among what it misses: …", never with a complete list. Two rounds
  (KIN-21, KIN-22) went to doc text that claimed a complete list of misses.
- **Leave nothing waiting on the human unattended.** Before leaving a phase to run alone, run its
  snapshot command once yourself (see Team phases) and check no member is blocked on an approval.
