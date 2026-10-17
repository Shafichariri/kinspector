# The agent team

Seven Claude Code subagents, defined in `.claude/agents/`, that work the backlog in
`docs/ROADMAP.md`. The playbook that runs them in order is the `roadmap-cycle` skill
(`.claude/skills/roadmap-cycle/SKILL.md`).

Every role reads `AGENTS.md` first. The team adds **no new tracking documents**: the backlog stays
in `ROADMAP.md` and the state of the project stays in `AGENTS.md` → "Where we are", so there is
still exactly one copy of each.

| Role | Owns | Writes | Asks for |
|---|---|---|---|
| `product-owner` | What and why: items, priority, acceptance criteria, what is *not* being done | `docs/ROADMAP.md` | Decisions only the human can make |
| `architect` | Structure: modules, twins, golden files, model ↔ `app.js` mirrors, hot path, security boundary | design notes; "Non-obvious decisions" in `AGENTS.md` | Confirmation before any public-API, schema or boundary change |
| `engineer` | Building one designed item, with tests | code and tests | A design note before starting |
| `qa-verifier` | Evidence: each criterion Verified / Not verified / Failed, guards proven both ways | nothing (read-only) | — |
| `ux-reviewer` | Usability of the overlay, the web UI and `INTEGRATION.md`, judged by looking | nothing (read-only) | — |
| `delivery-tracker` | What is actually done | roadmap item state; "Where we are" in `AGENTS.md` | QA evidence before marking anything done |
| `release-steward` | What consumers and the public see; release readiness; public-repo hygiene | `INTEGRATION.md`, `DAEMON.md`, `ACCESS.md`, `README.md` | The private term list for the hygiene scan |

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
the reader panel, the tracker keeps the published record honest, and the release steward decides
what goes to print.

## Using it

- **Hand over a plan:** "take this plan" with the plan pasted or a path → `roadmap-cycle` Mode A.
  It stops after intake and asks what goes first.
- **Build an item:** "build *<roadmap item name>*" → Mode B.
- **Status:** "where are we" → Mode C.
- Any single role can be called directly, e.g. "use the ux-reviewer on the web UI".
