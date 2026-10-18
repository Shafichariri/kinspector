#!/usr/bin/env node
// SubagentStart, SubagentStop and PostToolUse(Agent): keeps the live-teammate registry that
// team-guard counts against INSPECTOR_TEAM_MAX. lib/teammate.js explains why the hooks keep
// their own: the team directory has no member list to read.
//
//   SubagentStart     a named agent (a teammate) turns its pending entry live, matched by the
//                     spawning call's toolUseId from the agent's .meta.json
//   SubagentStop      removes that teammate's entry
//   PostToolUse Agent removes the entry for a foreground call that has returned, in case the stop
//                     was missed; a background launch returns at once and is left alone
//
// It never blocks anything: every path exits 0.
const { metaFor, writeEntry, removeWhere } = require('./lib/teammate');

let input = '';
process.stdin.on('data', (c) => (input += c));
process.stdin.on('end', () => {
  try {
    if (process.env.CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS !== '1') return;
    const call = JSON.parse(input);
    const sid = call.session_id;
    const event = call.hook_event_name;
    if (event === 'SubagentStart') {
      const meta = metaFor(call);
      if (!meta || !meta.name) return;
      writeEntry(sid, {
        toolUseId: meta.toolUseId ?? call.agent_id,
        name: meta.name,
        type: meta.agentType ?? call.agent_type,
        agentId: call.agent_id,
        state: 'live',
        at: Date.now(),
      });
    } else if (event === 'SubagentStop') {
      if (call.agent_id) removeWhere(sid, (e) => e.agentId === call.agent_id);
    } else if (event === 'PostToolUse' && call.tool_name === 'Agent') {
      const status = call.tool_response?.status;
      if (status && status !== 'completed') return; // a background launch, still running
      if (call.tool_use_id) removeWhere(sid, (e) => e.toolUseId === call.tool_use_id);
    }
  } catch {
    // bookkeeping only; never in the way
  } finally {
    process.exit(0);
  }
});
