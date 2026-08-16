package dev.inspector.stream

/**
 * Phase 2. Streams captured transactions to the host daemon over `WS /ingest`.
 *
 * The contract this must satisfy, recorded here so Phase 2 does not have to re-derive it:
 * - Owns a bounded outbound queue with DROP_OLDEST. A dead or slow daemon costs dropped
 *   transactions, never backpressure into the app.
 * - Uses its own [io.ktor.client.HttpClient] which must NOT carry the capture plugin. The client
 *   is tagged with an attribute the plugin checks, so a copy-paste mistake cannot create a
 *   capture feedback loop.
 * - Reconnects with exponential backoff (250ms up to 5s) and re-sends `resumeSessionId` so a
 *   brief disconnect continues the same session folder.
 * - Default host is per-platform and fixed for v1: `localhost` on the iOS simulator and desktop,
 *   `10.0.2.2` on the Android emulator. No discovery, because v1 is simulator/emulator only.
 */
internal object StreamSinkPlaceholder
