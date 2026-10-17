#!/usr/bin/env node
// PreToolUse hook for agents whose output must never land in this repository — today the
// business-strategist. Its confidentiality rule used to be prose only, and `memory:` switches on
// Write and Edit for it, so a role that was text-only became one that can write files. This turns
// "never write into the repo" from a request into a refusal.
//
// Exit 2 blocks the call and hands stderr back to the agent. Anything outside the project
// (its own memory under ~/.claude/agent-memory/, a private notes folder) is allowed.
//
// It cannot see a write made through Bash (`echo > file`); the agent prompt still forbids that.
const fs = require('fs');
const path = require('path');

let input = '';
process.stdin.on('data', (c) => (input += c));
process.stdin.on('end', () => {
  let call;
  try {
    call = JSON.parse(input);
  } catch {
    // An unreadable payload is not evidence the write is safe.
    process.stderr.write('block-repo-writes: could not parse the hook payload; refusing.\n');
    process.exit(2);
  }
  const target = call.tool_input?.file_path ?? call.tool_input?.notebook_path;
  if (!target) process.exit(0);

  const project = process.env.CLAUDE_PROJECT_DIR || call.cwd || process.cwd();
  const real = (p) => {
    // Resolve symlinks on the deepest existing ancestor: the file itself may not exist yet.
    let dir = path.resolve(project, p);
    const rest = [];
    while (!fs.existsSync(dir) && path.dirname(dir) !== dir) {
      rest.unshift(path.basename(dir));
      dir = path.dirname(dir);
    }
    return path.join(fs.realpathSync(dir), ...rest);
  };
  const root = real(project);
  const file = real(target);
  if (file === root || file.startsWith(root + path.sep)) {
    process.stderr.write(
      `Refused: ${target} is inside this public repository. Commercial work never goes in it — ` +
        'deliver it in your reply, or write to a location outside the repository the human named.\n',
    );
    process.exit(2);
  }
  process.exit(0);
});
