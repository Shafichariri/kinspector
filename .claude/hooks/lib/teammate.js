// Shared by the team hooks: is the agent this hook fired in an agent-team teammate?
//
// In-process teammates fire with the lead's session_id and their own agent_id; split-pane
// teammates are separate processes with their own session_id. Either is matched against the
// members of every team config under ~/.claude/teams/. The team config's schema is not
// documented, so the field names are read defensively.
const fs = require('fs');
const os = require('os');
const path = require('path');

function findTeammate(call) {
  const agentId = call.agent_id;
  const sid = call.session_id;
  if (!agentId && !sid) return null;
  const teamsDir = path.join(os.homedir(), '.claude', 'teams');
  let teams;
  try {
    teams = fs.readdirSync(teamsDir);
  } catch {
    return null; // no teams directory: nobody is a teammate
  }
  for (const team of teams) {
    let members;
    try {
      members = JSON.parse(fs.readFileSync(path.join(teamsDir, team, 'config.json'), 'utf8')).members ?? [];
    } catch {
      continue;
    }
    for (const m of members) {
      const type = m.agentType ?? m.agent_type;
      if (type === 'team-lead' || m.name === 'team-lead') continue;
      const ids = [m.agentId, m.agent_id, m.id].filter(Boolean);
      if ((agentId && ids.includes(agentId)) || (sid && (m.sessionId ?? m.session_id) === sid)) return m;
    }
  }
  return null;
}

module.exports = { findTeammate };
