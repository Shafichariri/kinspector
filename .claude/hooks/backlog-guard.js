#!/usr/bin/env node
// PreToolUse hook on Bash and on file-writing tools: who may change tickets in backlog/.
//
// Tickets are the "how" under a roadmap item, and the same rule that keeps the roadmap honest
// applies to them: a ticket's state is a claim, and claims get checked. So the hook enforces the
// role table in docs/TEAM.md rather than leaving it to prose:
//
//   - read-only on tickets: qa-verifier, ux-reviewer, product-owner, release-steward,
//     business-strategist, and every agent-team teammate. They report; someone else files.
//   - engineer and architect may create and edit tickets but never set Done or archive —
//     "done" belongs to the delivery-tracker, with QA evidence.
//   - delivery-tracker and the main session: anything.
//   - Ticket files change only through the `backlog` CLI, except for the delivery-tracker and the
//     main session, so the CLI rules above cannot be sidestepped with Edit.
//
// agent_type identifies the subagent a hook fired in; it is absent in the main session.
// Like the other hooks here it reads the command text, so it stops honest mistakes, not an
// agent determined to write a file some other way.
const path = require('path');
const fs = require('fs');
const { findTeammate } = require('./lib/teammate');

const READ_ONLY = new Set(['qa-verifier', 'ux-reviewer', 'product-owner', 'release-steward', 'business-strategist']);
const NO_DONE = new Set(['engineer', 'architect']);
const FULL = new Set(['delivery-tracker']);

// Any backlog CLI invocation that writes: tasks, drafts, milestones, decisions, docs, config,
// cleanup, exports, and the web UI (which edits from the browser).
const CLI = String.raw`(?:^|[\s;&|(\x60])(?:npx\s+(?:-y\s+)?backlog\.md|bunx\s+backlog\.md|backlog)\s+`;
const WRITE = new RegExp(
  CLI +
    String.raw`(?:task\s+(?:create|edit|archive|demote|promote|delete|complete)|draft\b|milestone\b|decision\b|doc\s+create|init\b|config\s+set|cleanup\b|board\s+export|browser\b|sequence\b)`,
);
const FINISH = new RegExp(
  CLI + String.raw`(?:task\s+(?:edit\b[^\n]*?(?:-s|--status)[\s=]+["']?done\b|archive\b|complete\b|delete\b)|cleanup\b)`,
  'i',
);

const deny = (reason) => {
  process.stdout.write(
    JSON.stringify({
      hookSpecificOutput: {
        hookEventName: 'PreToolUse',
        permissionDecision: 'deny',
        permissionDecisionReason: `backlog-guard: ${reason}`,
      },
    }),
  );
  process.exit(0);
};

let input = '';
process.stdin.on('data', (c) => (input += c));
process.stdin.on('end', () => {
  let call;
  try {
    call = JSON.parse(input);
  } catch {
    process.exit(0); // other hooks and the permission system still apply
  }
  const role = call.agent_type || null;
  const ti = call.tool_input ?? {};
  const teammate = process.env.CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS === '1' ? findTeammate(call) : null;
  const who = teammate ? `teammate ${teammate.name}` : role ? `\`${role}\`` : 'the main session';
  const report =
    'Report the ticket you want — title, item label, acceptance criteria — and the ' +
    'delivery-tracker or the lead files it.';

  if (call.tool_name === 'Bash') {
    const cmd = String(ti.command ?? '');
    if (!WRITE.test(cmd)) process.exit(0);
    if (teammate) deny(`${who} is in a read-only team phase and cannot change tickets. ${report}`);
    if (role && READ_ONLY.has(role)) deny(`${who} is read-only on tickets. ${report}`);
    if (role && NO_DONE.has(role) && FINISH.test(cmd)) {
      deny(
        `${who} cannot mark a ticket Done, archive or delete it — done belongs to the ` +
          'delivery-tracker, with the qa-verifier\'s evidence. Leave it In Progress and say it is ready.',
      );
    }
    process.exit(0);
  }

  // Write / Edit / MultiEdit / NotebookEdit straight into backlog/.
  const target = ti.file_path ?? ti.notebook_path;
  if (!target) process.exit(0);
  const project = process.env.CLAUDE_PROJECT_DIR || call.cwd || process.cwd();
  let root;
  try {
    root = fs.realpathSync(project);
  } catch {
    root = path.resolve(project);
  }
  // Compare both the literal and the symlink-resolved project path: macOS mounts /Users through
  // /private on some setups, and the target file may not exist yet.
  const file = path.resolve(project, target);
  const inBacklog = [root, path.resolve(project)].some((r) => file.startsWith(path.join(r, 'backlog') + path.sep));
  if (!inBacklog) process.exit(0);
  if (!teammate && (!role || FULL.has(role))) process.exit(0);
  deny(
    `${who} cannot edit ticket files directly. Use the \`backlog\` CLI, whose writes this hook checks` +
      (role && READ_ONLY.has(role) ? ` — and ${who} is read-only on tickets. ${report}` : '.'),
  );
});
