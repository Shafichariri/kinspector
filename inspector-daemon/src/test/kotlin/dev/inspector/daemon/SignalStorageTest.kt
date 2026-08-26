package dev.inspector.daemon

import dev.inspector.model.InspectorJson
import dev.inspector.model.Signal
import dev.inspector.model.SignalTags
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Signals on disk: their own append-only stream beside the transactions, with payloads moved out
 * of the row exactly as bodies are.
 */
class SignalStorageTest {

    private lateinit var tmp: Path
    private lateinit var config: DaemonConfig
    private lateinit var repository: SessionRepository

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("inspector-signals")
        config = DaemonConfig(port = ServerSocket(0).use { it.localPort }, dataDir = tmp)
        repository = SessionRepository(config)
    }

    @AfterTest
    fun tearDown() {
        Files.walk(tmp).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    private fun writer(): SessionWriter {
        val meta = clientInfo().toSessionMeta("s1", "2026-08-16T10:14:02.311Z")
        return SessionWriter(config.sessionsDir.resolve("s1"), meta)
    }

    @Test
    fun the_payload_moves_to_disk_and_the_stored_row_points_at_it() {
        val w = writer()
        val payload = """{"open":true}"""
        val stored = w.append(signal(id = "aaaa1111"), payload.toByteArray())
        w.close()

        assertEquals("signals/aaaa1111.json", stored.dataRef)
        assertNull(stored.data, "the row must not also carry the payload")
        assertEquals(payload, repository.readSignalPayload(w.sessionDir, "aaaa1111")!!.decodeToString())
    }

    @Test
    fun signals_jsonl_is_one_row_per_line_and_greppable_from_a_terminal() {
        // The archive is meant to be read with grep, not only through the API.
        val w = writer()
        w.append(signal(id = "aaaa1111", tag = SignalTags.SCREEN, name = "Checkout"), null)
        w.append(signal(id = "bbbb2222", tag = SignalTags.STATE, name = "CheckoutViewModel"), null)
        w.close()

        val lines = SessionLayout.signalsFile(w.sessionDir).readText().trim().lines()
        assertEquals(2, lines.size)
        assertTrue(lines.all { it.contains("\"v\":1") }, "every row carries its schema version")
        assertEquals(1, lines.count { it.contains("\"tag\":\"screen\"") })
    }

    @Test
    fun signals_live_beside_transactions_not_inside_them() {
        // Separate streams are what keep every existing grep, route and MCP tool working.
        val w = writer()
        w.append(txn("t1"), null, null)
        w.append(signal(id = "aaaa1111"), null)
        w.close()

        assertEquals(1, repository.readTransactions(w.sessionDir).size)
        assertEquals(1, repository.readSignals(w.sessionDir).size)
        assertFalse(
            SessionLayout.indexFile(w.sessionDir).readText().contains("\"tag\""),
            "the transaction index must not have gained signal rows",
        )
    }

    @Test
    fun an_unknown_tag_is_archived_and_served_like_any_other() {
        // The tag set is open by design; a daemon that has never heard of one must not drop it.
        val w = writer()
        w.append(signal(id = "aaaa1111", tag = "bluetooth", name = "pairing"), null)
        w.close()

        assertEquals("bluetooth", repository.readSignals(w.sessionDir).single().tag)
    }

    @Test
    fun current_reports_the_last_observation_per_tag_and_name() {
        val w = writer()
        w.append(signal(id = "a1", tag = SignalTags.SCREEN, name = "Route", mono = 100), null)
        w.append(signal(id = "a2", tag = SignalTags.SCREEN, name = "Route", mono = 300), null)
        w.append(signal(id = "a3", tag = SignalTags.CACHE, name = "response", mono = 200), null)
        w.close()

        val current = repository.currentSignals(w.sessionDir)
        assertEquals(2, current.size, "one row per (tag, name)")
        assertEquals("a2", current.first { it.tag == SignalTags.SCREEN }.id, "the latest wins")

        assertEquals(1, repository.currentSignals(w.sessionDir, tag = SignalTags.CACHE).size)
    }

    @Test
    fun a_reopened_session_appends_rather_than_truncating() {
        // Resume continues one session folder; a truncating reopen would lose the run so far.
        val first = writer()
        first.append(signal(id = "aaaa1111"), null)
        first.close()

        val meta = repository.readMeta(first.sessionDir)!!
        val second = SessionWriter.reopen(first.sessionDir, meta)
        second.append(signal(id = "bbbb2222"), null)
        second.close()

        assertEquals(
            listOf("aaaa1111", "bbbb2222"),
            repository.readSignals(first.sessionDir).map { it.id },
        )
    }

    @Test
    fun a_row_written_by_a_newer_build_does_not_break_the_reader() {
        // ignoreUnknownKeys is the contract that lets a newer device talk to an older daemon.
        val w = writer()
        w.append(signal(id = "aaaa1111"), null)
        w.close()

        val file = SessionLayout.signalsFile(w.sessionDir)
        Files.writeString(
            file,
            file.readText() + """{"v":1,"id":"cccc3333","ts":"2026-08-16T10:14:02.311Z","mono":9,""" +
                """"tag":"screen","name":"X","futureField":{"nested":true}}""" + "\n",
        )

        val rows = repository.readSignals(w.sessionDir)
        assertEquals(2, rows.size, "the unknown key must be ignored, not fatal")
        assertEquals("cccc3333", rows.last().id)
    }

    @Test
    fun retention_never_prunes_the_session_being_written() {
        val w = writer()
        w.append(signal(id = "aaaa1111"), null)

        val retention = Retention(config.copy(maxSessions = 0), repository)
        retention.prune(activeSessionId = "s1")

        assertTrue(
            SessionLayout.signalsFile(w.sessionDir).toFile().exists(),
            "the active session must survive any ceiling",
        )
        w.close()
    }

    @Test
    fun a_signal_round_trips_through_the_stored_line() {
        val w = writer()
        val stored = w.append(signal(id = "aaaa1111", bytes = 42), "x".toByteArray())
        w.close()

        val line = SessionLayout.signalsFile(w.sessionDir).readText().trim()
        assertEquals(stored, InspectorJson.decodeFromString<Signal>(line))
    }

    @Test
    fun retention_trims_per_tag_and_deletes_the_payloads_it_drops() {
        val w = writer()
        repeat(30) { i ->
            w.append(
                signal(id = "c%02d".format(i), tag = SignalTags.CACHE, name = "response", mono = i.toLong()),
                """{"i":$i}""".toByteArray(),
            )
        }
        repeat(30) { i ->
            w.append(signal(id = "s%02d".format(i), tag = SignalTags.SCREEN, name = "Route", mono = i.toLong()), null)
        }
        w.close()

        val retention = Retention(config.copy(signalCaps = mapOf(SignalTags.CACHE to 5)), repository)
        val dropped = retention.pruneSignals(w.sessionDir)

        val rows = repository.readSignals(w.sessionDir)
        assertEquals(25, dropped)
        assertEquals(5, rows.count { it.tag == SignalTags.CACHE }, "cache is capped")
        assertEquals(30, rows.count { it.tag == SignalTags.SCREEN }, "an uncapped tag is kept in full")
        // The newest survive: an old snapshot is worth less than the current one.
        assertEquals(listOf("c25", "c26", "c27", "c28", "c29"), rows.filter { it.tag == SignalTags.CACHE }.map { it.id })
        // And the orphaned payloads are gone, not left behind as dead bytes.
        assertNull(repository.readSignalPayload(w.sessionDir, "c00"))
        assertEquals("""{"i":29}""", repository.readSignalPayload(w.sessionDir, "c29")!!.decodeToString())
    }

    @Test
    fun retention_keeps_a_tag_it_has_never_heard_of() {
        // Dropping an unknown tag would silently discard whatever an app chose to record.
        val w = writer()
        repeat(10) { i ->
            w.append(signal(id = "b%02d".format(i), tag = "bluetooth", name = "pairing", mono = i.toLong()), null)
        }
        w.close()

        Retention(config.copy(signalCaps = mapOf(SignalTags.CACHE to 1)), repository).pruneSignals(w.sessionDir)

        assertEquals(10, repository.readSignals(w.sessionDir).size)
    }

    @Test
    fun trimming_preserves_append_order_because_the_file_is_read_as_a_timeline() {
        val w = writer()
        listOf(300L, 100L, 200L).forEachIndexed { i, mono ->
            w.append(signal(id = "z$i", tag = SignalTags.CACHE, name = "c", mono = mono), null)
        }
        w.close()

        Retention(config.copy(signalCaps = mapOf(SignalTags.CACHE to 2)), repository).pruneSignals(w.sessionDir)

        // Kept by mono (300, 200), but written back in the order they were appended.
        assertEquals(listOf("z0", "z2"), repository.readSignals(w.sessionDir).map { it.id })
    }

}
