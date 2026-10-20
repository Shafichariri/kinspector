package dev.inspector.daemon.mcp

import dev.inspector.daemon.archive.DaemonConfig
import dev.inspector.daemon.archive.SessionRepository
import dev.inspector.daemon.archive.SessionWriter
import dev.inspector.daemon.clientInfo
import dev.inspector.daemon.marker
import dev.inspector.daemon.mcpCallTool
import dev.inspector.daemon.mcpExchange
import dev.inspector.daemon.mcpSession
import dev.inspector.daemon.signal
import dev.inspector.daemon.txn
import dev.inspector.model.SignalTags
import dev.inspector.model.SignalTrigger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.assertTrue

/**
 * What every MCP test file shares: one session whose marker splits a failing call from what led
 * to it, recording fakes for the two tools that reach the daemon, and the frame helpers.
 *
 * The tests were one file, `McpTest.kt`, until it was split along the tool groups in `mcp/`.
 * The fixture moved with them unchanged, so each file's tests still read the same session.
 */
abstract class McpToolsFixture {

    protected lateinit var tmp: Path
    protected lateinit var config: DaemonConfig
    protected lateinit var tools: McpTools

    /** Records what would have been posted, so no daemon has to run. */
    protected class RecordingPoster : MarkerPoster {
        val posted = mutableListOf<Pair<String, String>>()
        override fun post(session: String, label: String): ToolOutcome {
            posted += session to label
            return ToolOutcome.Ok("Marker '$label' added to session '$session'.")
        }
    }

    protected val poster = RecordingPoster()

    /** Stands in for a live app, so the pull path is exercised without a socket. */
    protected class RecordingPuller : SignalPuller {
        var attached = true
        val pulled = mutableListOf<Triple<String, String, String>>()
        override fun pull(session: String, tag: String, name: String): ToolOutcome {
            pulled += Triple(session, tag, name)
            return if (attached) {
                ToolOutcome.Ok("""{"tag":"$tag","name":"$name","trigger":"request"}""")
            } else {
                ToolOutcome.Failed("the pull failed (409): no app is attached for session $session")
            }
        }
    }

    protected val puller = RecordingPuller()

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("inspector-mcp")
        config = DaemonConfig(port = 8099, dataDir = tmp)
        Files.createDirectories(config.sessionsDir)

        val dir = config.sessionsDir.resolve("2026-08-16T10-14-02_app_dev_debug")
        val meta = clientInfo(appId = "com.example.checkout")
            .toSessionMeta("2026-08-16T10-14-02_app_dev_debug", "2026-08-16T10:14:02.000Z")
        val writer = SessionWriter(dir, meta)

        // Before the marker.
        writer.append(txn("aaaa1111", path = "/v2/session", mono = 100), null, null)
        writer.append(txn("bbbb2222", path = "/v2/cart", mono = 200, status = 500), null, """{"early":true}""".toByteArray())
        writer.append(
            signal(id = "s1a", tag = SignalTags.SCREEN, name = "Cart", mono = 150),
            null,
        )
        writer.append(marker("tapped checkout", mono = 300))
        writer.append(
            signal(id = "s2b", tag = SignalTags.SCREEN, name = "KycSubmit", mono = 320),
            """{"step":"review"}""".toByteArray(),
        )
        writer.append(
            signal(id = "s3c", tag = SignalTags.STATE, name = "KycViewModel", mono = 350),
            """{"isSubmitting":true,"attempts":2}""".toByteArray(),
        )
        writer.append(
            signal(
                id = "s4d",
                tag = SignalTags.CACHE,
                name = "response",
                mono = 360,
                trigger = SignalTrigger.Request,
                requestId = "r-1",
            ),
            """{"entries":0}""".toByteArray(),
        )
        // After it — this is the failure the question is about.
        writer.append(
            txn("cccc3333", path = "/v2/orders", mono = 400, status = 502, method = "POST"),
            """{"cart":"c-1"}""".toByteArray(),
            """{"error":"upstream_timeout","retryAfter":30}""".toByteArray(),
        )
        writer.append(txn("dddd4444", path = "/v2/telemetry", mono = 500), null, null)
        writer.close()
        SessionWriter.updateLatestLink(config.dataDir, dir)

        tools = McpTools(config, SessionRepository(config), poster, puller)
    }

    @AfterTest
    fun tearDown() {
        Files.walk(tmp).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    /** Builds a tool argument object. Values are Strings or Ints. */
    protected fun args(vararg pairs: Pair<String, Any>): JsonObject = buildJsonObject {
        pairs.forEach { (key, value) ->
            when (value) {
                is Int -> put(key, value)
                else -> put(key, value.toString())
            }
        }
    }

    /** The text of a successful outcome; fails loudly rather than returning an error string. */
    protected fun ToolOutcome.text(): String = when (this) {
        is ToolOutcome.Ok -> text
        is ToolOutcome.Failed -> throw AssertionError("tool failed: $message")
    }



    /** Feeds lines through a real [McpServer] and returns the response frames. */
    protected fun exchange(vararg requests: String, version: String? = null): List<JsonObject> =
        mcpExchange(tools, *requests, version = version)

    protected fun callTool(name: String, args: String = "{}"): String = mcpCallTool(tools, name, args)

    /** Like [callTool] but asserts the call failed, and returns the message. */
    protected fun callToolExpectingError(name: String, args: String): String {
        val frames = exchange(
            """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$name","arguments":$args}}"""
        )
        val result = frames.single().getValue("result").jsonObject
        assertTrue(
            result.getValue("isError").jsonPrimitive.content.toBoolean(),
            "tool $name was expected to fail but succeeded",
        )
        return result.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
    }

    /** A second session, so a test can shape the exact case it is about. */
    protected fun session(id: String, build: (SessionWriter) -> Unit): String = mcpSession(config, id, build)

    /**
     * Every same-millisecond tie the ordering rule has an opinion about, appended in an order a
     * sort of the daemon's own would rearrange: a marker sharing a call's instant, appended after
     * it, and two signals sharing an instant, appended against their `seq`.
     */
    protected fun tieSession(): String = session("2026-08-17T09-00-06_ties_dev_debug") { writer ->
        writer.append(txn("tiea0001", path = "/a", mono = 100), null, null)
        writer.append(signal(id = "s8", tag = SignalTags.STATE, name = "Eight", mono = 300).copy(seq = 8), null)
        writer.append(signal(id = "s7", tag = SignalTags.STATE, name = "Seven", mono = 300).copy(seq = 7), null)
        writer.append(txn("tiec0001", path = "/c", mono = 500).copy(seq = 9), null, null)
        writer.append(marker("m", mono = 500))
    }
}

internal fun kotlinx.serialization.json.JsonPrimitive.int(): Int = content.toInt()
