package dev.inspector.daemon.mcp

import kotlin.test.Test
import kotlin.test.assertEquals

/** `add_marker`, from [MarkerTools]. */
class MarkerToolsTest : McpToolsFixture() {

    @Test
    fun add_marker_targets_the_live_session_through_the_daemon() {
        callTool("add_marker", """{"label":"agent looked here"}""")
        assertEquals(listOf("latest" to "agent looked here"), poster.posted)
    }
}
