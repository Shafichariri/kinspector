package dev.inspector.model

import kotlinx.serialization.json.Json

/**
 * The single Json configuration used everywhere: device, wire, on-disk `index.jsonl`, REST,
 * CLI and MCP. Consumers must not construct their own — the settings below are part of the
 * schema contract.
 *
 * - `encodeDefaults = false` and `explicitNulls = false` keep `index.jsonl` lines short, which
 *   directly reduces how many tokens an agent burns reading a session.
 * - `ignoreUnknownKeys = true` lets a newer device talk to an older daemon without exploding;
 *   version gating is done explicitly against [SCHEMA_VERSION], not by parse failure.
 */
val InspectorJson: Json = Json {
    encodeDefaults = false
    explicitNulls = false
    ignoreUnknownKeys = true
    classDiscriminator = "type"
}

/** Pretty variant for human-facing output (CLI `--json`, REST when a browser asks). */
val InspectorJsonPretty: Json = Json(InspectorJson) {
    prettyPrint = true
}
