# inspector-noop-stream — agent guide

The release-build twin of `:inspector-stream`: the stream sink, with the same public API and no behaviour.
`-Pinspector=off` swaps it in. Change it only together with its real module.

Read before changing anything here: `docs/decisions/twins-and-api.md`.

Also: `api-parity/AGENTS.md` for the golden files and how to regenerate them.

@../docs/decisions/twins-and-api.md
