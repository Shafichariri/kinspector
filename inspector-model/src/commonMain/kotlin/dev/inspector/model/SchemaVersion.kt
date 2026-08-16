package dev.inspector.model

/**
 * Version stamped onto every serialized object as `"v"`.
 *
 * Consumers (daemon, web UI, CLI, MCP) must reject payloads whose version they do not
 * understand rather than guessing. Bump only for breaking schema changes; additive optional
 * fields do not require a bump.
 */
const val SCHEMA_VERSION: Int = 1
