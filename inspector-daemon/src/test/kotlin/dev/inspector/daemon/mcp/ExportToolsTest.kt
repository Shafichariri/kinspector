package dev.inspector.daemon.mcp

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `export_session`, from [ExportTools]. */
class ExportToolsTest : McpToolsFixture() {

    @Test
    fun export_session_writes_every_format_and_names_the_files() {
        val out = tmp.resolve("handoff")
        val text = callTool("export_session", """{"dir":"$out"}""")
        for (ext in listOf("html", "md", "json")) {
            val file = out.resolve("2026-08-16T10-14-02_app_dev_debug.$ext")
            assertTrue(Files.isRegularFile(file), "$ext was not written: $text")
            assertContains(text, file.toAbsolutePath().toString())
        }
        assertContains(text, "4 calls")
        assertContains(Files.readString(out.resolve("2026-08-16T10-14-02_app_dev_debug.md")), "upstream_timeout")
    }

    @Test
    fun export_session_defaults_beside_the_sessions_not_inside_one() {
        callTool("export_session", """{"format":"json"}""")
        // Inside a session folder it would count towards that session's size and be pruned with it.
        assertTrue(Files.isRegularFile(tmp.resolve("exports/2026-08-16T10-14-02_app_dev_debug.json")))
        assertFalse(Files.exists(config.sessionsDir.resolve("2026-08-16T10-14-02_app_dev_debug/exports")))
    }

    @Test
    fun export_session_refuses_a_format_it_does_not_write() {
        assertContains(callToolExpectingError("export_session", """{"format":"pdf"}"""), "Accepted: html, md, json, all")
    }
}
