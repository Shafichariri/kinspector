package dev.inspector.model

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SerializationTest {

    @Test
    fun transaction_round_trips() {
        val original = txn(
            query = "page=2",
            reqHeaders = mapOf("content-type" to listOf("application/json")),
            resHeaders = mapOf("set-cookie" to listOf("‹redacted›")),
            reqBodyRef = "bodies/7f3a0001.req",
            resBodyRef = "bodies/7f3a0001.res",
            resBodyTruncated = true,
            redacted = listOf("header:set-cookie", "query:token"),
        )
        val json = InspectorJson.encodeToString(original)
        assertEquals(original, InspectorJson.decodeFromString<NetworkTransaction>(json))
    }

    @Test
    fun failed_transaction_round_trips_with_null_status() {
        val original = txn(status = null, error = "SocketTimeoutException: after 10000ms", ms = 10_000)
        val decoded = InspectorJson.decodeFromString<NetworkTransaction>(
            InspectorJson.encodeToString(original)
        )
        assertEquals(original, decoded)
        assertEquals(null, decoded.status)
        assertEquals("error", decoded.statusClass)
    }

    @Test
    fun marker_and_session_meta_round_trip() {
        val m = marker("tapped checkout", mono = 5_000, source = MarkerSource.AGENT)
        assertEquals(m, InspectorJson.decodeFromString<Marker>(InspectorJson.encodeToString(m)))

        val meta = SessionMeta(
            sessionId = "2026-08-16T10-14-02_ProjectX_iPhone16Pro_debug",
            appId = "com.example.projectx",
            appVersion = "1.4.2",
            platform = Platforms.IOS_SIMULATOR,
            device = "iPhone 16 Pro",
            osVersion = "26.0",
            buildType = "debug",
            startedAt = "2026-08-16T10:14:02.311Z",
            endedAt = "2026-08-16T10:41:55.002Z",
            txnCount = 412,
            errorCount = 17,
        )
        assertEquals(meta, InspectorJson.decodeFromString<SessionMeta>(InspectorJson.encodeToString(meta)))
    }

    @Test
    fun schema_version_is_always_encoded_even_though_defaults_are_not() {
        val json = InspectorJson.encodeToString(txn())
        assertContains(json, "\"v\":1")
    }

    @Test
    fun nulls_and_defaults_are_omitted_to_keep_index_lines_small() {
        // Line size is a token budget for agents, not just a disk concern.
        val json = InspectorJson.encodeToString(txn(query = null))
        assertFalse(json.contains("\"query\""), "null fields must not be encoded: $json")
        assertFalse(json.contains("\"attempt\""), "default fields must not be encoded: $json")
        assertFalse(json.contains("\"redacted\""), "empty defaults must not be encoded: $json")
    }

    @Test
    fun unknown_keys_are_ignored_so_a_newer_device_can_talk_to_an_older_daemon() {
        val json = """{"v":1,"id":"7f3a0001","ts":"2026-08-16T10:14:02.311Z","mono":1000,
            |"method":"GET","scheme":"https","host":"api.example.com","path":"/x","callId":"c1",
            |"futureField":{"nested":true}}""".trimMargin()
        val decoded = InspectorJson.decodeFromString<NetworkTransaction>(json)
        assertEquals("7f3a0001", decoded.id)
    }

    @Test
    fun wire_messages_round_trip_through_the_sealed_hierarchy() {
        val messages: List<WireMsg> = listOf(
            Hello(
                client = ClientInfo(
                    appId = "com.example.projectx",
                    appVersion = "1.4.2",
                    platform = Platforms.ANDROID_EMULATOR,
                    device = "Pixel 8 API 35",
                    osVersion = "15",
                    buildType = "debug",
                ),
                resumeSessionId = "2026-08-16T10-14-02_ProjectX_Pixel8_debug",
            ),
            HelloAck(sessionId = "s1", resumed = true),
            Txn(txn = txn(), resBody = "{\"ok\":true}"),
            Txn(txn = txn(id = "beef0002"), resBody = "AAEC", resBodyB64 = true),
            MarkerMsg(marker("login", mono = 500)),
            Bye,
        )
        for (msg in messages) {
            val encoded = InspectorJson.encodeToString<WireMsg>(msg)
            assertEquals(msg, InspectorJson.decodeFromString<WireMsg>(encoded), "round trip: $encoded")
        }
    }

    @Test
    fun wire_discriminator_is_type() {
        val encoded = InspectorJson.encodeToString<WireMsg>(Bye)
        assertContains(encoded, "\"type\":\"bye\"")
    }

    @Test
    fun txn_message_carries_bodies_inline_and_leaves_refs_for_the_daemon_to_fill() {
        val msg = Txn(txn = txn(), reqBody = "{\"a\":1}", resBody = "{\"b\":2}")
        val decoded = InspectorJson.decodeFromString<WireMsg>(InspectorJson.encodeToString<WireMsg>(msg))
        assertIs<Txn>(decoded)
        assertEquals("{\"a\":1}", decoded.reqBody)
        assertEquals(null, decoded.txn.reqBodyRef)
    }

    @Test
    fun client_info_projects_into_session_meta() {
        val info = ClientInfo(
            appId = "com.example.projectx",
            appVersion = "1.4.2",
            platform = Platforms.DESKTOP,
            device = "MacBook Pro",
            osVersion = "26.4",
            buildType = "debug",
        )
        val meta = info.toSessionMeta("sess-1", "2026-08-16T10:14:02.311Z")
        assertEquals("sess-1", meta.sessionId)
        assertEquals(info.appId, meta.appId)
        assertEquals(info.device, meta.device)
        assertEquals(0, meta.txnCount)
        assertEquals(null, meta.endedAt)
    }

    @Test
    fun derived_helpers_are_consistent() {
        assertEquals("2xx", txn(status = 200).statusClass)
        assertEquals("4xx", txn(status = 404).statusClass)
        assertEquals("5xx", txn(status = 503).statusClass)
        assertEquals("error", txn(status = null, error = "boom").statusClass)

        assertFalse(txn(status = 200).isError)
        assertFalse(txn(status = 302).isError)
        assertTrue(txn(status = 404).isError)
        assertTrue(txn(status = 500).isError)
        assertTrue(txn(status = null, error = "boom").isError)

        assertEquals("https://api.example.com/v2/users/me", txn().url)
        assertEquals("https://api.example.com/v2/users/me?page=2", txn(query = "page=2").url)
    }
}

/**
 * The port, which capture used to drop entirely.
 *
 * A capture of `127.0.0.1:8080` rendered as `http://127.0.0.1`, so the copied cURL and any replay
 * addressed port 80 while reporting the right host. It failed silently and looked like the server
 * misbehaving. Found when replay could not connect to the sample's own demo server.
 */
class PortTest {

    private fun txn(scheme: String, port: Int?) = NetworkTransaction(
        id = "a", ts = "2026-08-18T00:00:00Z", mono = 0, method = "GET",
        scheme = scheme, host = "example.com", port = port, path = "/x", callId = "c",
    )

    @kotlin.test.Test
    fun `a non-default port appears in the url`() {
        kotlin.test.assertEquals("http://example.com:8080/x", txn("http", 8080).url)
        kotlin.test.assertEquals("https://example.com:8443/x", txn("https", 8443).url)
    }

    @kotlin.test.Test
    fun `a default port is left out because it is noise`() {
        kotlin.test.assertEquals("http://example.com/x", txn("http", 80).url)
        kotlin.test.assertEquals("https://example.com/x", txn("https", 443).url)
    }

    /** Archives written before the field existed must still read. */
    @kotlin.test.Test
    fun `a row with no port at all still renders and still parses`() {
        kotlin.test.assertEquals("http://example.com/x", txn("http", null).url)
        val older = """{"v":1,"id":"a","ts":"2026-08-18T00:00:00Z","mono":0,"method":"GET",""" +
            """"scheme":"https","host":"example.com","path":"/x","callId":"c"}"""
        val parsed = InspectorJson.decodeFromString(NetworkTransaction.serializer(), older)
        kotlin.test.assertEquals(null, parsed.port)
        kotlin.test.assertEquals("https://example.com/x", parsed.url)
    }
}
