package dev.inspector.daemon.mcp

import dev.inspector.daemon.archive.DaemonConfig
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Posting a marker needs the daemon; split out so tests do not need one running. */
interface MarkerPoster {
    fun post(session: String, label: String): ToolOutcome
}

/**
 * Posts through the daemon's own HTTP API rather than writing the file directly, because the
 * daemon holds the open writer for a recording session and two writers would interleave.
 *
 * Uses the JDK client: adding an HTTP client dependency to reach a loopback port would be
 * disproportionate.
 */
class HttpMarkerPoster(private val config: DaemonConfig) : MarkerPoster {
    override fun post(session: String, label: String): ToolOutcome {
        val client = java.net.http.HttpClient.newHttpClient()
        val body = buildJsonObject { put("label", label) }
        val request = java.net.http.HttpRequest.newBuilder()
            .uri(java.net.URI.create("http://127.0.0.1:${config.port}/api/sessions/$session/markers"))
            .header("Content-Type", "application/json")
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()

        return try {
            val response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() in 200..299) {
                ToolOutcome.Ok("Marker '$label' added to session '$session'.")
            } else {
                ToolOutcome.Failed("daemon refused the marker (${response.statusCode()}): ${response.body()}")
            }
        } catch (e: java.io.IOException) {
            ToolOutcome.Failed(
                "could not reach the daemon on 127.0.0.1:${config.port} — markers can only be " +
                    "added while `inspector serve` is running and the app is recording. (${e.message})"
            )
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            ToolOutcome.Failed("interrupted while posting the marker")
        }
    }
}

/** Seam for [SignalTools.requestSignal], mirroring [MarkerPoster]. Faked in tests. */
interface SignalPuller {
    fun pull(session: String, tag: String, name: String): ToolOutcome
}

/**
 * Pulls through the daemon's HTTP API, for the same reason [HttpMarkerPoster] posts through it:
 * only the daemon holds the socket to the running app, and only it can address one.
 */
class HttpSignalPuller(private val config: DaemonConfig) : SignalPuller {
    override fun pull(session: String, tag: String, name: String): ToolOutcome {
        val client = java.net.http.HttpClient.newHttpClient()
        val body = buildJsonObject { put("tag", tag); put("name", name) }
        val request = java.net.http.HttpRequest.newBuilder()
            .uri(
                java.net.URI.create(
                    "http://127.0.0.1:${config.port}/api/sessions/$session/signals/request"
                )
            )
            .header("Content-Type", "application/json")
            .header("X-Inspector-Control", "1")
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()

        return try {
            val response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString())
            when {
                response.statusCode() in 200..299 -> ToolOutcome.Ok(response.body())
                // The daemon's message already names what is registered, or that nothing is
                // attached. Passing it through beats replacing it with a vaguer one.
                else -> ToolOutcome.Failed("the pull failed (${response.statusCode()}): ${response.body()}")
            }
        } catch (e: java.io.IOException) {
            ToolOutcome.Failed(
                "could not reach the daemon on 127.0.0.1:${config.port} — a pull needs " +
                    "`inspector serve` running and the app still attached. (${e.message})"
            )
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            ToolOutcome.Failed("interrupted while pulling the signal")
        }
    }
}
