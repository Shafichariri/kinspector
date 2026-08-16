package dev.inspector.daemon

import dev.inspector.model.FilterParseException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StorageTest {

    private lateinit var tmp: Path
    private lateinit var config: DaemonConfig
    private lateinit var repo: SessionRepository

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("inspector-test")
        config = DaemonConfig(dataDir = tmp)
        Files.createDirectories(config.sessionsDir)
        repo = SessionRepository(config)
    }

    @AfterTest
    fun tearDown() {
        Files.walk(tmp).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    // --- layout ------------------------------------------------------------------------------

    @Test
    fun session_id_is_a_readable_timestamped_folder_name() {
        val id = SessionLayout.sessionId(clientInfo(), java.time.Instant.parse("2026-08-16T10:14:02Z"))
        assertEquals("2026-08-16T10-14-02_projectx_iPhone-16-Pro_debug", id)
    }

    @Test
    fun path_traversal_ids_are_rejected() {
        // Session ids arrive from HTTP and become filesystem paths.
        for (evil in listOf("../../etc/passwd", "..", ".", "a/b", "a\\b", "with space")) {
            assertFalse(SessionLayout.isValidSessionId(evil), "'$evil' must be rejected")
            assertNull(repo.resolve(evil), "'$evil' must not resolve to a directory")
        }
    }

    // --- writing -----------------------------------------------------------------------------

    @Test
    fun a_written_session_round_trips_through_the_repository() {
        val dir = writeSession(
            config, "2026-08-16T10-14-02_app_dev_debug", "2026-08-16T10:14:02.000Z",
            transactions = listOf(txn("aaaa1111"), txn("bbbb2222", status = 500)),
            markers = listOf(marker("tapped checkout")),
        )

        val meta = repo.readMeta(dir)!!
        assertEquals(2, meta.txnCount)
        assertEquals(1, meta.errorCount, "only the 500 counts as an error")
        assertTrue(meta.endedAt != null, "a closed session records when it ended")

        assertEquals(listOf("aaaa1111", "bbbb2222"), repo.readTransactions(dir).map { it.id })
        assertEquals(listOf("tapped checkout"), repo.readMarkers(dir).map { it.label })
    }

    @Test
    fun bodies_are_written_to_files_and_the_row_points_at_them() {
        val dir = config.sessionsDir.resolve("s1")
        val writer = SessionWriter(dir, clientInfo().toSessionMeta("s1", "2026-08-16T10:14:02.000Z"))
        writer.append(txn("7f3a0001"), "req-body".toByteArray(), "res-body".toByteArray())
        writer.close()

        val stored = repo.readTransactions(dir).single()
        assertEquals("bodies/7f3a0001.req", stored.reqBodyRef)
        assertEquals("bodies/7f3a0001.res", stored.resBodyRef)
        assertEquals("res-body", repo.readBody(dir, "7f3a0001", "res")!!.decodeToString())
        assertEquals("req-body", repo.readBody(dir, "7f3a0001", "req")!!.decodeToString())
    }

    @Test
    fun index_is_one_json_object_per_line_so_it_can_be_grepped() {
        // The archive is meant to be usable from a terminal, not only through the API.
        val dir = writeSession(
            config, "s2", "2026-08-16T10:14:02.000Z",
            transactions = listOf(txn("aaaa1111"), txn("bbbb2222", status = 500)),
        )
        val lines = SessionLayout.indexFile(dir).readText().trim().lines()
        assertEquals(2, lines.size)
        assertTrue(lines.all { it.startsWith("{") && it.endsWith("}") })
        assertEquals(1, lines.count { it.contains("\"status\":500") })
    }

    @Test
    fun meta_is_written_atomically_leaving_no_temp_file_behind() {
        val dir = writeSession(config, "s3", "2026-08-16T10:14:02.000Z", listOf(txn("aaaa1111")))
        assertTrue(SessionLayout.metaFile(dir).exists())
        assertFalse(dir.resolve("meta.json.tmp").exists(), "the temp file must be renamed, not left")
    }

    @Test
    fun latest_link_points_at_the_named_session() {
        val dir = writeSession(config, "s4", "2026-08-16T10:14:02.000Z")
        SessionWriter.updateLatestLink(config.dataDir, dir)

        val resolved = repo.resolve("latest")
        assertEquals(dir.toRealPath(), resolved)
    }

    // --- querying ----------------------------------------------------------------------------

    @Test
    fun queries_use_the_same_filter_grammar_as_the_app() {
        val dir = writeSession(
            config, "s5", "2026-08-16T10:14:02.000Z",
            transactions = listOf(
                txn("aaaa1111", path = "/v2/users/me", status = 200, mono = 100),
                txn("bbbb2222", path = "/v2/orders", status = 500, mono = 200),
                txn("cccc3333", path = "/v2/orders", status = 404, mono = 300, ms = 900),
            ),
        )

        assertEquals(2, repo.queryTransactions(dir, "status>=400").matched)
        assertEquals(1, repo.queryTransactions(dir, "status:500").matched)
        assertEquals(2, repo.queryTransactions(dir, "path:/v2/orders").matched)
        assertEquals(1, repo.queryTransactions(dir, "slower:500ms").matched)
        assertEquals(3, repo.queryTransactions(dir, "").matched)
    }

    @Test
    fun results_are_newest_first_by_monotonic_clock() {
        val dir = writeSession(
            config, "s6", "2026-08-16T10:14:02.000Z",
            transactions = listOf(
                txn("aaaa1111", mono = 100),
                txn("bbbb2222", mono = 300),
                txn("cccc3333", mono = 200),
            ),
        )
        assertEquals(
            listOf("bbbb2222", "cccc3333", "aaaa1111"),
            repo.queryTransactions(dir).items.map { it.id },
        )
    }

    @Test
    fun a_bad_filter_throws_with_the_parsers_own_message() {
        val dir = writeSession(config, "s7", "2026-08-16T10:14:02.000Z")
        val error = assertFailsWith<FilterParseException> {
            repo.queryTransactions(dir, "bogus:1")
        }
        assertContains(error.message!!, "Unknown filter key")
    }

    @Test
    fun pagination_reports_totals_alongside_the_page() {
        val dir = writeSession(
            config, "s8", "2026-08-16T10:14:02.000Z",
            transactions = (1..10).map { txn("id%06d".format(it).take(8), mono = it.toLong()) },
        )
        val page = repo.queryTransactions(dir, limit = 3, offset = 0)
        assertEquals(10, page.total)
        assertEquals(10, page.matched)
        assertEquals(3, page.items.size)
    }

    @Test
    fun summary_stays_small_and_answers_the_common_questions() {
        val dir = writeSession(
            config, "s9", "2026-08-16T10:14:02.000Z",
            transactions = listOf(
                txn("aaaa1111", status = 200, ms = 100),
                txn("bbbb2222", status = 500, ms = 2000, path = "/v2/pay"),
                txn("cccc3333", status = 404, ms = 50),
                txn("dddd4444", status = null, error = "SocketTimeout", ms = 10_000),
            ),
            markers = listOf(marker("tapped checkout")),
        )

        val summary = repo.summarize(dir)!!
        assertEquals(4, summary.txnCount)
        assertEquals(mapOf("2xx" to 1, "4xx" to 1, "5xx" to 1, "error" to 1), summary.byStatusClass)
        assertEquals(3, summary.errors.size, "4xx, 5xx and the transport failure")
        assertEquals("dddd4444", summary.slowest.first().id)
        assertEquals(listOf("tapped checkout"), summary.markers)

        val encoded = dev.inspector.model.InspectorJson.encodeToString(
            SessionSummary.serializer(), summary
        )
        assertTrue(encoded.length < 2000, "summary should stay ~1 KB, was ${encoded.length}")
    }

    // --- retention ---------------------------------------------------------------------------

    @Test
    fun prunes_oldest_first_when_the_session_count_ceiling_is_exceeded() {
        val tight = config.copy(maxSessions = 2)
        val repository = SessionRepository(tight)
        repeat(4) { i ->
            writeSession(tight, "s$i", "2026-08-1${i}T10:00:00.000Z", listOf(txn("aaaa111$i")))
        }

        val result = Retention(tight, repository).prune()

        assertEquals(2, repository.listSessions().size)
        assertEquals(listOf("s0", "s1"), result.prunedSessionIds, "oldest two must go")
        assertEquals(setOf("s2", "s3"), repository.listSessions().map { it.sessionId }.toSet())
    }

    @Test
    fun prunes_when_the_byte_ceiling_is_exceeded_even_if_the_count_is_fine() {
        val tight = config.copy(maxSessions = 100, maxTotalBytes = 30_000)
        val repository = SessionRepository(tight)
        repeat(4) { i ->
            writeSession(
                tight, "s$i", "2026-08-1${i}T10:00:00.000Z",
                transactions = listOf(txn("aaaa111$i")),
                padBytes = 10_000,
            )
        }

        val result = Retention(tight, repository).prune()

        assertTrue(result.prunedSessionIds.isNotEmpty(), "byte ceiling must force pruning")
        assertTrue(
            result.remainingBytes <= 30_000,
            "must end under the byte ceiling, was ${result.remainingBytes}"
        )
        assertTrue(repository.listSessions().size < 4)
    }

    @Test
    fun never_prunes_the_active_session_even_when_it_is_the_oldest() {
        // Deleting the folder currently being written would corrupt an open writer and lose the
        // traffic the user is looking at right now.
        val tight = config.copy(maxSessions = 1)
        val repository = SessionRepository(tight)
        repeat(3) { i ->
            writeSession(tight, "s$i", "2026-08-1${i}T10:00:00.000Z", listOf(txn("aaaa111$i")))
        }

        val result = Retention(tight, repository).prune(activeSessionId = "s0")

        val remaining = repository.listSessions().map { it.sessionId }.toSet()
        assertTrue("s0" in remaining, "the active session survived, kept $remaining")
        assertFalse("s0" in result.prunedSessionIds)
    }

    @Test
    fun pruning_an_archive_already_within_limits_does_nothing() {
        writeSession(config, "s1", "2026-08-16T10:00:00.000Z", listOf(txn("aaaa1111")))
        val result = Retention(config, repo).prune()
        assertTrue(result.prunedSessionIds.isEmpty())
        assertEquals(1, repo.listSessions().size)
    }
}
