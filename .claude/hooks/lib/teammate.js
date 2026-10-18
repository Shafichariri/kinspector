// Shared by the team hooks: is the agent this hook fired in an agent-team teammate, and how many
// teammates are live?
//
// Measured 2026-10-18 (Claude Code desktop, in-process teammates). A teammate's hook payload is
// indistinguishable from a plain subagent's: the lead's session_id, its own agent_id, its
// agent_type, and no name or team field. ~/.claude/teams/<session>/ holds only inboxes/ — there
// is no config.json, which the first version of these hooks read, so every check built on it
// passed everything. What does tell them apart is the agent's metadata file beside its
// transcript, <transcript dir>/<session>/subagents/agent-<agent_id>.meta.json: it carries the
// spawn's `name` (absent for an unnamed subagent), `toolUseId` and `requestShape`. With teams on,
// a named agent is a teammate, so the name is the test.
//
// The live count cannot come from the team directory either, so the hooks keep their own
// registry: one file per teammate in a per-session directory under the OS temp dir. team-guard
// adds a pending entry when it allows a spawn; team-registry.js marks it live on SubagentStart
// and removes it on SubagentStop or when the foreground Agent call returns. Entries expire, so a
// crash cannot hold a slot forever.
const fs = require('fs');
const os = require('os');
const path = require('path');

const PENDING_TTL_MS = 10 * 60 * 1000; // a spawn that never started (refused, cancelled)
const LIVE_TTL_MS = 3 * 60 * 60 * 1000; // a teammate whose stop was never seen

function metaFor(call) {
  const { agent_id: agentId, session_id: sid, transcript_path: transcript } = call;
  if (!agentId || !sid) return null;
  const dirs = [];
  if (transcript) dirs.push(path.join(path.dirname(transcript), sid, 'subagents'));
  for (const dir of dirs) {
    try {
      return JSON.parse(fs.readFileSync(path.join(dir, `agent-${agentId}.meta.json`), 'utf8'));
    } catch {
      // not there, or not readable yet
    }
  }
  return null;
}

// The teammate this hook fired in, as { name, agentType }, or null for the lead or a subagent.
function findTeammate(call) {
  const meta = metaFor(call);
  if (!meta || typeof meta.name !== 'string' || !meta.name) return null;
  return { name: meta.name, agentType: meta.agentType ?? call.agent_type ?? null, toolUseId: meta.toolUseId };
}

function registryDir(sid) {
  const safe = String(sid || 'unknown').replace(/[^A-Za-z0-9-]/g, '');
  return path.join(os.tmpdir(), `inspector-team-${safe}`);
}

// Live and pending teammates, expired entries removed.
function liveTeammates(sid, now = Date.now()) {
  const dir = registryDir(sid);
  let files;
  try {
    files = fs.readdirSync(dir).filter((f) => f.endsWith('.json'));
  } catch {
    return [];
  }
  const out = [];
  for (const f of files) {
    const file = path.join(dir, f);
    let e;
    try {
      e = JSON.parse(fs.readFileSync(file, 'utf8'));
    } catch {
      continue;
    }
    const ttl = e.state === 'live' ? LIVE_TTL_MS : PENDING_TTL_MS;
    if (now - (e.at ?? 0) > ttl) {
      fs.rmSync(file, { force: true });
      continue;
    }
    out.push({ ...e, file });
  }
  return out;
}

function keyFile(sid, toolUseId) {
  return path.join(registryDir(sid), `${String(toolUseId).replace(/[^A-Za-z0-9_-]/g, '')}.json`);
}

function writeEntry(sid, entry) {
  fs.mkdirSync(registryDir(sid), { recursive: true });
  fs.writeFileSync(keyFile(sid, entry.toolUseId), JSON.stringify(entry));
}

function removeWhere(sid, pred) {
  for (const e of liveTeammates(sid)) if (pred(e)) fs.rmSync(e.file, { force: true });
}

// Spawns in one message run their hooks concurrently; check-and-add must not interleave.
function withLock(sid, fn) {
  const dir = registryDir(sid);
  fs.mkdirSync(dir, { recursive: true });
  const lock = path.join(dir, '.lock');
  const deadline = Date.now() + 5000;
  for (;;) {
    try {
      fs.mkdirSync(lock);
      break;
    } catch {
      let stale = false;
      try {
        stale = Date.now() - fs.statSync(lock).mtimeMs > 10000;
      } catch {}
      if (stale) fs.rmSync(lock, { recursive: true, force: true });
      else if (Date.now() > deadline) break; // proceed unlocked rather than hang the spawn
      else Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 25);
    }
  }
  try {
    return fn();
  } finally {
    fs.rmSync(lock, { recursive: true, force: true });
  }
}

module.exports = { findTeammate, metaFor, liveTeammates, writeEntry, removeWhere, withLock, registryDir };
