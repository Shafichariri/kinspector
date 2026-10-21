package dev.inspector.daemon.http

import dev.inspector.daemon.archive.ConfigFile
import dev.inspector.daemon.archive.ConfigFileState
import dev.inspector.daemon.archive.ConfigMergeResult
import dev.inspector.daemon.archive.DaemonConfig
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * `config.json`, read and written over HTTP for the Settings page.
 *
 * A save writes the file and changes nothing in this daemon: a port cannot move under a listening
 * socket, `Retention` holds the config it was built with, and one rule for every key is easier to
 * say on the page than a list of which keys apply live. So `effective` is what this daemon runs on
 * and stays so until it restarts, `file` is what the next start will read, and `flags` names the
 * keys a command-line flag overrides, whatever the file says.
 */
internal fun Route.configRoutes(config: DaemonConfig, file: ConfigFile) {
    get("/api/config") {
        call.respondJson(configReply(config, file, file.read()))
    }

    controlGated {
        put("/api/config") {
            val patch = call.receiveJsonObject() ?: return@put
            when (val result = file.merge(patch)) {
                is ConfigMergeResult.Written ->
                    call.respondJson(configReply(config, file, ConfigFileState.Found(result.json)))
                is ConfigMergeResult.Invalid -> call.respondError(HttpStatusCode.BadRequest, result.message)
                is ConfigMergeResult.Unreadable -> call.respondError(HttpStatusCode.Conflict, result.message)
            }
        }
    }
}

private fun configReply(config: DaemonConfig, file: ConfigFile, state: ConfigFileState): String =
    buildJsonObject {
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
        }
        put("flags", buildJsonArray { config.flags.sorted().forEach { add(JsonPrimitive(it)) } })
    }.toString()
