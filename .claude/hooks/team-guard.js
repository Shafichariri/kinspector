#!/usr/bin/env node
// PreToolUse hook on the Agent tool: the spawning limits for agent teams.
//
// With CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS=1, any Agent call that carries a `name` launches a
// teammate instead of a subagent — and Claude names subagents on its own, so without this a plain
// roadmap-cycle step can quietly become a team that costs a full context window per member. This
// turns "teams only where the playbook says so" from a request into a refusal:
//
//   1. A teammate is spawned only for a declared phase: its prompt carries `[team-phase: <phase>]`.
//   2. Each phase has a roster. Roles that write files, own a human gate, or depend on frontmatter
//      a teammate does not receive (hooks, memory, omitClaudeMd) are never teammates.
//   3. At most INSPECTOR_TEAM_MAX live teammates (default 3).
//
// Unnamed calls, forks and calls passing `isolation` are subagents and pass straight through;
// CLAUDE_CODE_MAX_CONCURRENT_SUBAGENTS bounds those. The JSON decision goes to stdout with exit 0.
const fs = require('fs');
const os = require('os');
const path = require('path');

const PHASES = {
  'design-council': ['architect', 'ux-reviewer', 'qa-verifier'],
  'verify-panel': ['qa-verifier', 'architect', 'ux-reviewer'],
  'root-cause': ['architect', 'qa-verifier'],
};

const NEVER = {
  engineer:
    'it writes code, and teammates share one working tree with no worktree isolation — two writers ' +
    'overwrite each other',
  'business-strategist':
    'a teammate does not receive its `hooks`, `memory` or `omitClaudeMd` frontmatter, so the ' +
    'block-repo-writes hook that keeps commercial work out of this public repository would not run',
  'release-steward':
    'its user-scope memory holds the private term list for the hygiene scan, and a teammate does ' +
    'not receive `memory`',
  'product-owner':
    'it owns docs/ROADMAP.md and the human gate at intake; a teammate cannot ask the human anything',
  'delivery-tracker':
    'it writes roadmap state and "Where we are" from QA evidence, which only exists after a phase ends',
};

const deny = (reason) => {
  process.stdout.write(
    JSON.stringify({
      hookSpecificOutput: {
        hookEventName: 'PreToolUse',
        permissionDecision: 'deny',
        permissionDecisionReason: `team-guard: ${reason}`,
      },
    }),
  );
  process.exit(0);
};

// A member that has shut down may stay listed; only count the ones that look alive.
const isLive = (m) => {
  const type = m.agentType ?? m.agent_type;
  if (type === 'team-lead' || m.name === 'team-lead') return false;
  if (m.isActive === false || m.active === false) return false;
  return !/shut|stop|exit|terminat|dead|fail/i.test(String(m.status ?? m.state ?? ''));
};

let input = '';
process.stdin.on('data', (c) => (input += c));
process.stdin.on('end', () => {
  if (process.env.CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS !== '1') process.exit(0);

  let call;
  try {
    call = JSON.parse(input);
  } catch {
    deny('could not parse the hook payload; refusing rather than spawning an unchecked agent.');
  }
  const ti = call.tool_input ?? {};
  const name = typeof ti.name === 'string' ? ti.name.trim() : '';
  const fork = ti.subagent_type === 'fork' || ti.fork === true;
  if (!name || ti.isolation || fork) process.exit(0); // a subagent, not a teammate
  if (call.agent_id) process.exit(0); // only the lead can form a team; the CLI refuses the rest

  const type = ti.subagent_type || 'general-purpose';
  const retry = 'Call Agent again without `name` to run it as an ordinary subagent.';

  if (NEVER[type]) deny(`\`${type}\` is never a teammate: ${NEVER[type]}. ${retry}`);

  const tag = /\[team-phase:\s*([a-z-]+)\s*\]/i.exec(String(ti.prompt ?? ''));
  if (!tag) {
    deny(
      `named Agent calls launch teammates here, and teammates are only for a declared phase. ` +
        `If this is one of the roadmap-cycle team phases (${Object.keys(PHASES).join(', ')}), ` +
        `put \`[team-phase: <phase>]\` on the first line of the prompt. Otherwise: ${retry}`,
    );
  }
  const phase = tag[1].toLowerCase();
  const roster = PHASES[phase];
  if (!roster) deny(`unknown phase \`${phase}\`; the phases are ${Object.keys(PHASES).join(', ')}.`);
  if (!roster.includes(type)) {
    deny(`\`${type}\` is not on the ${phase} roster (${roster.join(', ')}). ${retry}`);
  }

  // The Agent tool backgrounds by default, and a backgrounded named agent is not an in-process
  // teammate: measured 2026-10-18, it gets no TaskList/TaskGet/TaskUpdate however its `tools`
  // reads, and messages reach it only by resuming it after its turn ends. The foreground spawn of
  // the same definition got the task tools and live messaging. So a teammate must say so.
  if (ti.run_in_background !== false) {
    deny(
      'a teammate must be spawned in the foreground: pass `run_in_background: false`. The Agent ' +
        'tool backgrounds by default, and a background agent gets no shared task list and receives ' +
        'messages only after its turn ends. Spawn every member of the phase in one message so ' +
        'they run at once.',
    );
  }

  const max = Number.parseInt(process.env.INSPECTOR_TEAM_MAX ?? '3', 10) || 3;
  const sid = String(call.session_id ?? '');
  const configPath = path.join(os.homedir(), '.claude', 'teams', `session-${sid.slice(0, 8)}`, 'config.json');
  let members = [];
  try {
    members = JSON.parse(fs.readFileSync(configPath, 'utf8')).members ?? [];
  } catch {
    // No team yet: this spawn is the first member.
  }
  const live = members.filter(isLive);
  if (live.some((m) => m.name === name)) process.exit(0); // re-addressing an existing teammate
  if (live.length >= max) {
    deny(
      `the team already has ${live.length} live teammates (${live.map((m) => m.name).join(', ')}) ` +
        `and the limit is ${max}. Shut one down first, or wait for the phase to finish.`,
    );
  }
  process.exit(0);
});
