#!/usr/bin/env node
// PreToolUse hook on file-writing tools: team phases are read-only.
//
// Teammates share one working tree and nothing isolates them, so the team-guard rosters hold
// only reviewing roles — but `architect` carries Edit for recording decisions in AGENTS.md, and
// a debate is exactly when an agent decides to "just fix it". A teammate that wants a file
// changed sends the proposed text to the lead, who hands it to the owning role as a subagent
// after the phase. Subagents (the engineer above all) are not team members and pass through.
//
// Like block-repo-writes.js, it cannot see a write made through Bash; the phase prompt forbids it.
const { findTeammate } = require('./lib/teammate');

let input = '';
process.stdin.on('data', (c) => (input += c));
process.stdin.on('end', () => {
  if (process.env.CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS !== '1') process.exit(0);
  let call;
  try {
    call = JSON.parse(input);
  } catch {
    process.exit(0); // an extra layer: an unreadable payload is left to the permission system
  }
  const teammate = findTeammate(call);
  if (!teammate) process.exit(0);

  process.stdout.write(
    JSON.stringify({
      hookSpecificOutput: {
        hookEventName: 'PreToolUse',
        permissionDecision: 'deny',
        permissionDecisionReason:
          `team-read-only: ${teammate.name} is a teammate, and team phases do not write files — ` +
          'teammates share one working tree. Send the exact proposed change to the lead with ' +
          'SendMessage; the lead applies it after the phase through the role that owns the file.',
      },
    }),
  );
  process.exit(0);
});
