package dev.inspector.daemon

/**
 * Phase 2/3. Host-side daemon: session archive, REST/live API, web UI, CLI and MCP server.
 *
 * It is a JVM Ktor server specifically so it can depend on `:inspector-model` — the schema types
 * and the filter parser are then literally the same code on the device, in the web UI backend,
 * the CLI and the MCP tools. One grammar, one set of shapes, no reimplementation drift.
 *
 * Retention, per the agreed configuration: prune oldest sessions until at most 100 sessions and
 * 300 MB remain, never pruning the active session, applied on session close and daemon start.
 */
internal object DaemonPlaceholder
