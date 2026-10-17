---
name: roadmap-cycle
description: Run Inspector's agent team over the roadmap — take in a new plan, or carry one roadmap item from intake through design, build, verification, usability review and tracking. Use when asked to "work the roadmap", "take this plan", "build <item>", or "where are we".
---

# Roadmap cycle

The team lives in `.claude/agents/`; `docs/TEAM.md` explains each role. Subagents cannot spawn
each other, so **you (the main session) are the conductor**: call each role with the Agent tool,
pass the previous role's output forward verbatim, and stop at the gates below.

## Mode A — a new plan arrives (`$ARGUMENTS` is a plan or a path to one)

1. `product-owner`: fold the plan into `docs/ROADMAP.md` as named items with acceptance criteria.
2. `architect`: size each new item (module, public-API impact, risks) — one line each.
3. **Gate:** show the human the items, anything marked "overturns a recorded decision", and the
   open questions. Do not start building until they pick what goes first.

## Mode B — build one item (`$ARGUMENTS` names a roadmap item)

1. `architect` → design note (files, tests, risks).
2. **Gate** only if the note says public API, schema, security boundary, or a recorded decision
   changes — confirm with the human first.
3. `ux-reviewer` → review the design if it touches the overlay or the web UI (run in parallel with
   step 4's start only if the design is already agreed).
4. `engineer` → build it with tests, on the session's branch.
5. `qa-verifier` and `architect` (diff review) → in parallel. Any **Failed** or blocking finding
   goes back to step 4; loop until clean.
6. `ux-reviewer` → render and look, for UI items.
7. `delivery-tracker` → update `docs/ROADMAP.md` state and `AGENTS.md` "Where we are".
8. `release-steward` → if consumer-visible: INTEGRATION.md bump + changelog; always: hygiene scan.
9. Commit (as the maintainer, no attribution trailers — see `AGENTS.md`) and push only if the human asked for it; report the QA table and what stays unverified.

## Mode C — status (`where are we`)

Call `delivery-tracker` for its status report and relay it.

## Mode D — commercial (`make money`, `pricing`, `monetise`)

Call `business-strategist`. Relay its interview questions to the human and wait for answers. Any
paid feature it proposes goes through Mode A via the `product-owner`. **Never write its output into
the repository** — it is public.

## Rules for the conductor

- Proxy capture (4c) never starts without the human explicitly choosing it.
- Never report a role's claim as fact without the `qa-verifier`'s evidence.
- The repository is public: no client, employer or product names anywhere, including prompts that
  end up in commit messages.
