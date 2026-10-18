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

## Mode A — a new plan arrives (`$ARGUMENTS` is a plan or a path to one)

1. `product-owner`: fold the plan into `docs/ROADMAP.md` as named items with acceptance criteria.
2. `architect`: size each new item (module, public-API impact, risks) — one line each.
3. **Gate:** show the human the items, anything marked "overturns a recorded decision", and the
   open questions. Do not start building until they pick what goes first.

## Mode B — build one item (`$ARGUMENTS` names a roadmap item)

Tickets live in `backlog/` (Backlog.md). A roadmap item is the *what and why*; its tickets are the
*how*, each labelled `item:<slug>` with the slug the item names. `docs/TEAM.md` → "Tickets" has
the commands and who may change what; `.claude/hooks/backlog-guard.js` enforces it.

1. `architect` → design note (files, tests, risks). **Use the design-council team phase instead**
   when the item touches two or more surfaces, or public API, schema or the security boundary.
2. **Gate** only if the note says public API, schema, security boundary, or a recorded decision
   changes — confirm with the human first. (A council's note always gates.)
3. `architect` (subagent) → split the agreed note into tickets: one per change a single build and
   a single QA pass can close, with acceptance criteria copied from the note and `--dep` where
   order matters. Tickets come after the gate, so a rejected design leaves no orphans.
4. `ux-reviewer` → review the design if it touches the overlay or the web UI (run in parallel with
   step 5's start only if the design is already agreed).
5. `engineer` → build ticket by ticket on the session's branch, moving each to In Progress. It
   reports "ready for QA" with the ticket IDs; it never marks one Done.
6. `qa-verifier` and `architect` (diff review) — as the **verify-panel** team phase when the item
   was designed by a council or touches the overlay or web UI, otherwise as subagents in parallel,
   each in a **fresh** call, never a continued engineer conversation, which would review its own
   reasoning instead of the diff. QA reports per ticket ID. Any **Failed** or blocking finding:
   `delivery-tracker` files it as a `bug` ticket under the same item, then back to step 5; loop
   until clean.
7. `ux-reviewer` → render and look, for UI items.
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
  the guard refuses it. Tell each member the others' names, and that if a first message is refused
  as unreachable, the other member has not started yet: wait briefly (`sleep 10` in Bash) and retry.
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
3. Any Failed → `engineer` (subagent) fixes, then message the panel to re-verify. Re-verifying means
   re-running the commands, not re-reading the earlier output. Shut down when clean.

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
