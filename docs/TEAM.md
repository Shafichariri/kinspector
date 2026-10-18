# The agent team

Eight Claude Code subagents, defined in `.claude/agents/`, that work the backlog in
`docs/ROADMAP.md`. The playbook that runs them in order is the `roadmap-cycle` skill
(`.claude/skills/roadmap-cycle/SKILL.md`).

Every role starts from `AGENTS.md`, the index, and reads the `docs/decisions/` files for the
modules it touches. The team adds **no new tracking documents**: the backlog stays in `ROADMAP.md`
and the state of the project stays in `docs/STATUS.md` (it moved there from `AGENTS.md` → "Where
we are" in phase G; the phase table stayed in "Where we are"), so there is still exactly one copy of
each.

| Role | Owns | Writes | Asks for |
|---|---|---|---|
| `product-owner` | What and why: items, priority, acceptance criteria, what is *not* being done | `docs/ROADMAP.md` | Decisions only the human can make |
| `architect` | Structure: modules, twins, golden files, model ↔ `app.js` mirrors, hot path, security boundary | design notes; the decision files in `docs/decisions/` | Confirmation before any public-API, schema or boundary change |
| `engineer` | Building one designed item, with tests | code and tests | A design note before starting |
| `qa-verifier` | Evidence: each criterion Verified / Not verified / Failed, guards proven both ways | nothing (read-only) | — |
| `ux-reviewer` | Usability of the overlay, the web UI and `INTEGRATION.md`, judged by looking | nothing (read-only) | — |
| `delivery-tracker` | What is actually done | roadmap item state; `docs/STATUS.md` and the phase table in `AGENTS.md` | QA evidence before marking anything done |
| `release-steward` | What consumers and the public see; release readiness; public-repo hygiene | `INTEGRATION.md`, `DAEMON.md`, `ACCESS.md`, `README.md` | The private term list for the hygiene scan |
| `business-strategist` | Whether and how the tool makes money: market, competitors, free vs paid, pricing, cheapest demand test | **nothing in this repo** — commercial work stays private | The goal (side income, company, leads), time available; the `architect` for anything needing auth |

## How the definitions are tuned

Each file's frontmatter carries more than a prompt, and each choice has a reason:

- **`AGENTS.md` is never re-read.** `CLAUDE.md` is a symlink to it and Claude Code loads it into
  every subagent, so "read AGENTS.md first" made each role pay for it twice — ~126 KB, before
  phase G made it an index under 15 KB. The prompts point at sections already in context, and name
  the `docs/decisions/` files to Read, because those are not. Each module's `AGENTS.md` imports its
  decision files with `@`, but whether a subagent loads a nested guide at all is unverified, so no
  prompt relies on it.
- **`effort: high` on `architect` and `qa-verifier`** — the two roles whose misses ship.
  **`model: sonnet` on `delivery-tracker`**, which reconciles text against evidence it is handed.
  Everything else inherits the session's model.
- **User-scope `memory` on `release-steward` and `business-strategist`** — the two roles that hold
  knowledge which must never enter this public repository (the private term list; pricing and
  strategy). User scope stores it under `~/.claude/agent-memory/`, outside the repo. In a cloud
  session that directory is discarded with the container, so it persists only on a local machine.
- **`business-strategist` runs with `omitClaudeMd`** and a `PreToolUse` hook,
  `.claude/hooks/block-repo-writes.js`, that refuses any Write or Edit inside the repository —
  memory switches those tools on, so its confidentiality rule needed teeth. Bash writes are outside
  what a hook on those tools can see.
- **`qa-verifier` never restores with `git checkout`.** It runs before the engineer's work is
  committed, so that would revert the very change under test.
- Project-scope memory was considered and left off: it would be a second, unreviewed copy of what
  `AGENTS.md` and `ROADMAP.md` already hold, in a public repository.

## Flow of one item

```
plan ─► product-owner ─► architect (design) ─► [human gate if API/schema/boundary]
                                   │
                                   ▼
               engineer ─► qa-verifier + architect (review) ─┐
                  ▲                                          │ failed → back to engineer
                  └──────────────────────────────────────────┘
                                   │ clean
                                   ▼
               ux-reviewer (UI items) ─► delivery-tracker ─► release-steward
```

Think of it as a newsroom: the product owner is the editor choosing stories, the architect is the
style-and-standards desk, the engineer is the reporter, QA is the fact-checker, the UX reviewer is
the reader panel, the tracker keeps the published record honest, the release steward decides
what goes to print, and the business strategist sells the ads — and keeps the rate card off the
public noticeboard.

## Using it

- **Hand over a plan:** "take this plan" with the plan pasted or a path → `roadmap-cycle` Mode A.
  It stops after intake and asks what goes first.
- **Build an item:** "build *<roadmap item name>*" → Mode B.
- **Status:** "where are we" → Mode C.
- **Money:** "use the business-strategist" — it interviews you first, and keeps its output out of
  the repository.
- Any single role can be called directly, e.g. "use the ux-reviewer on the web UI".
