# docs/ — what each document is for

Decisions are in `decisions/`, one file per area, indexed from the root `AGENTS.md`. The state of
the project — counts, Verified, Not verified — is in `STATUS.md`.

| Path | Why it matters |
|---|---|
| `docs/schema.md` | The data contract. Read before touching `:inspector-model`. |
| `docs/ACCESS.md` | Who can get Inspector and how, and the honest answer for someone who cannot. Update it if the distribution story changes — it is the only doc that answers "am I blocked". |
| `docs/DAEMON.md` | Running the daemon: start, stop, restart, kill, the CLI, archive layout, troubleshooting. Update it when a flag or command changes. |
| `docs/TEAM.md` | The agent team in `.claude/agents/` — product owner, architect, engineer, QA, UX, tracker, release steward, business strategist — and the `roadmap-cycle` skill that runs them. It adds no tracking document of its own: the backlog stays here in `ROADMAP.md`, state stays in "Where we are". |
| `docs/implementation-plan.md` | Full build order, phases, acceptance criteria. Phases 0–4 shipped, signals in 0.3.0; of the capture mechanisms, 4a shipped and 4b/4c have not started. A record of the original build — do not rewrite it to match later work. |
| `docs/SIGNALS.md` | Why signals are shaped the way they are — app state on the traffic timeline. **Built and released in 0.3.0**; kept as the design record, so where it and the code disagree, the code won. Read it before changing anything signal-shaped: it carries the decisions, the traps, and what was deliberately left out. Its build order is numbered in *stages* so it does not collide with the plan's phases. |
| `docs/SIGNALS-CHECKLIST.md` | What must be true of a build that records signals — assertions only, no rationale. The live companion to the spec above; update this one when the bar moves. |
| `docs/REPLAY.md` | Replay, on-device re-signing and daemon control. Steps 1–3 are built; the template generators (step 4) are specified and not written. Read it before touching replay — the signing constraint decides the whole architecture. |
