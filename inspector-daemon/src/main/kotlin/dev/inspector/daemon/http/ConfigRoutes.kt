package dev.inspector.daemon.http

import dev.inspector.daemon.archive.ConfigFile
import dev.inspector.daemon.archive.ConfigFileState
import dev.inspector.daemon.archive.ConfigMergeResult
import dev.inspector.daemon.archive.DaemonConfig
import dev.inspector.daemon.archive.FindingRuleCatalog
import dev.inspector.daemon.archive.FindingSettingsSource
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * `config.json`, read and written over HTTP for the Settings page.
 *
 * A save writes the file and changes nothing else in this daemon: a port cannot move under a
 * listening socket, and `Retention` holds the config it was built with. The one exception is the
 * `findings` key, which [FindingSettingsSource] reads on the next findings read, so the page and an
 * MCP server agree without a restart. So `effective` is what this daemon runs on, `effective.findings`
 * as of the file's last read, `file` is what the next start will read, and `flags` names the keys a
 * command-line flag overrides, whatever the file says.
 */
internal fun Route.configRoutes(config: DaemonConfig, file: ConfigFile, findings: FindingSettingsSource) {
    get("/api/config") {
        call.respondJson(configReply(config, file, file.read(), findings))
    }

    controlGated {
        put("/api/config") {
            val patch = call.receiveJsonObject() ?: return@put
            when (val result = file.merge(patch)) {
                is ConfigMergeResult.Written ->
                    call.respondJson(configReply(config, file, ConfigFileState.Found(result.json), findings))
                is ConfigMergeResult.Invalid -> call.respondError(HttpStatusCode.BadRequest, result.message)
                is ConfigMergeResult.Unreadable -> call.respondError(HttpStatusCode.Conflict, result.message)
            }
        }
    }
}

private fun configReply(config: DaemonConfig, file: ConfigFile, state: ConfigFileState, findings: FindingSettingsSource): String =
    buildJsonObject {
        val (settings, findingsError) = findings.current()
        put("path", file.path.toString())
        when (state) {
            ConfigFileState.Missing -> put("file", JsonNull)
            is ConfigFileState.Found -> put("file", state.json)
            is ConfigFileState.Malformed -> {
                put("file", JsonNull)
                put("fileError", state.message)
            }
        }
        putJsonObject("effective") {
            put("port", config.port)
            put("maxSessions", config.maxSessions)
            put("maxTotalMb", config.maxTotalBytes / (1024L * 1024L))
            putJsonObject("signalCaps") {
                config.signalCaps.toSortedMap().forEach { (tag, cap) -> put(tag, cap) }
            }
            // What this daemon compiled at start, invalid entries skipped: the file's list is in `file`.
            put("routeTemplates", buildJsonArray { config.routeTemplates.entries.forEach { add(JsonPrimitive(it)) } })
            // Read live, so this is the file as last read, not as at start.
            putJsonObject("findings") {
                putJsonArray("rules") {
                    for (rule in FindingRuleCatalog.ALL) {
                        val override = settings.rules[rule.id]
                        add(buildJsonObject {
                            put("id", rule.id)
                            put("category", rule.category)
                            put("categoryTitle", rule.categoryTitle)
                            put("title", rule.title)
                            put("enabled", override?.enabled ?: true)
                            put("severity", (override?.severity ?: rule.defaultSeverity).key)
                            put("defaultSeverity", rule.defaultSeverity.key)
                        })
                    }
                }
                putJsonObject("payment") {
                    val payment = settings.payment
                    put("idempotencyHeaders", buildJsonArray { payment.idempotencyHeaders.forEach { add(JsonPrimitive(it)) } })
                    put("paymentPaths", buildJsonArray { payment.paymentPaths.forEach { add(JsonPrimitive(it)) } })
                    put("ignorePaths", buildJsonArray { payment.ignorePaths.forEach { add(JsonPrimitive(it)) } })
                    put("duplicateWindowMs", payment.duplicateWindowMs)
                    put("resubmitWindowMs", payment.resubmitWindowMs)
                }
            }
        }
        findingsError?.let { put("findingsConfigError", it) }
        put("flags", buildJsonArray { config.flags.sorted().forEach { add(JsonPrimitive(it)) } })
    }.toString()
