package dev.inspector.daemon

import dev.inspector.model.SignalTags
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `config.json` plumbing, with most of the weight on `signalCaps`.
 *
 * The mechanism it drives is already covered by `SignalStorageTest`, which builds the caps map
 * in-process. What was untested — and silently broken — is the path from the file to that map:
 * `ignoreUnknownKeys` meant a `signalCaps` block parsed, was discarded, and was never mentioned,
 * so the setting looked configured and was not.
 */
class DaemonConfigTest {

    private lateinit var tmp: Path

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("inspector-config")
    }

    @AfterTest
    fun tearDown() {
        Files.walk(tmp).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    private fun configFile(json: String) = tmp.resolve("config.json").writeText(json)

    @Test
    fun a_config_file_without_signal_caps_leaves_the_defaults_alone() {
        configFile("""{ "port": 9001, "maxSessions": 4 }""")

        val config = DaemonConfig.load(dataDir = tmp)

        assertEquals(9001, config.port)
        assertEquals(4, config.maxSessions)
        assertEquals(DaemonConfig.DEFAULT_SIGNAL_CAPS, config.signalCaps)
    }

    @Test
    fun a_cap_in_the_config_file_is_honoured() {
        // The regression this whole test class exists for: this used to parse and vanish.
        configFile("""{ "signalCaps": { "cache": 50 } }""")

        assertEquals(50, DaemonConfig.load(dataDir = tmp).signalCaps[SignalTags.CACHE])
    }

    @Test
    fun an_override_merges_over_the_defaults_rather_than_replacing_them() {
        // Naming one tag must not silently uncap the others — that failure is quiet, and you
        // find it when the disk fills rather than when you edit the file.
        configFile("""{ "signalCaps": { "cache": 50 } }""")

        val caps = DaemonConfig.load(dataDir = tmp).signalCaps

        assertEquals(50, caps[SignalTags.CACHE])
        assertEquals(
            DaemonConfig.DEFAULT_SIGNAL_CAPS[SignalTags.STATE],
            caps[SignalTags.STATE],
            "a tag the file never mentions keeps its default",
        )
    }

    @Test
    fun an_explicit_null_uncaps_a_tag() {
        // Once merging is the rule this is the only way to say "keep every row of this tag".
        configFile("""{ "signalCaps": { "cache": null } }""")

        val caps = DaemonConfig.load(dataDir = tmp).signalCaps

        assertFalse(caps.containsKey(SignalTags.CACHE), "an uncapped tag has no entry at all")
        assertEquals(DaemonConfig.DEFAULT_SIGNAL_CAPS[SignalTags.STATE], caps[SignalTags.STATE])
    }

    @Test
    fun a_cap_of_zero_is_honoured_because_not_archiving_a_tag_is_a_real_request() {
        configFile("""{ "signalCaps": { "state": 0 } }""")

        assertEquals(0, DaemonConfig.load(dataDir = tmp).signalCaps[SignalTags.STATE])
    }

    @Test
    fun a_negative_cap_is_ignored_and_the_default_survives() {
        configFile("""{ "signalCaps": { "cache": -1 } }""")

        assertEquals(
            DaemonConfig.DEFAULT_SIGNAL_CAPS[SignalTags.CACHE],
            DaemonConfig.load(dataDir = tmp).signalCaps[SignalTags.CACHE],
        )
    }

    @Test
    fun a_tag_is_matched_case_insensitively_so_an_override_replaces_rather_than_duplicates() {
        // Retention reads tags case-insensitively; if merging did not, "CACHE" would sit beside
        // "cache" and whichever Retention found first would win.
        configFile("""{ "signalCaps": { "CACHE": 7 } }""")

        val caps = DaemonConfig.load(dataDir = tmp).signalCaps

        assertEquals(DaemonConfig.DEFAULT_SIGNAL_CAPS.size, caps.size)
        assertEquals(7, caps[SignalTags.CACHE])
    }

    @Test
    fun a_tag_this_build_has_never_heard_of_can_be_capped() {
        // The tag set is open, so the config must be too.
        configFile("""{ "signalCaps": { "bluetooth": 3 } }""")

        val caps = DaemonConfig.load(dataDir = tmp).signalCaps

        assertEquals(3, caps["bluetooth"])
        assertEquals(DaemonConfig.DEFAULT_SIGNAL_CAPS[SignalTags.CACHE], caps[SignalTags.CACHE])
    }

    @Test
    fun an_explicit_argument_wins_over_the_config_file() {
        configFile("""{ "signalCaps": { "cache": 50 } }""")

        val caps = DaemonConfig.load(dataDir = tmp, signalCaps = mapOf(SignalTags.CACHE to 9)).signalCaps

        assertEquals(9, caps[SignalTags.CACHE])
    }

    @Test
    fun a_malformed_signal_caps_block_is_ignored_the_way_a_malformed_file_already_was() {
        // `signalCaps` as a number, not an object: the whole file fails to decode, and a daemon
        // that will not start because of its own settings file is worse than one on defaults.
        configFile("""{ "port": 9001, "signalCaps": 12 }""")

        val config = DaemonConfig.load(dataDir = tmp)

        assertEquals(DaemonConfig.DEFAULT_PORT, config.port)
        assertEquals(DaemonConfig.DEFAULT_SIGNAL_CAPS, config.signalCaps)
    }

    @Test
    fun a_missing_config_file_is_not_an_error() {
        val config = DaemonConfig.load(dataDir = tmp)

        assertEquals(DaemonConfig.DEFAULT_PORT, config.port)
        assertEquals(DaemonConfig.DEFAULT_SIGNAL_CAPS, config.signalCaps)
    }

    @Test
    fun a_cap_from_the_config_file_reaches_retention_and_actually_trims() {
        // Parsing is half of it. This is the other half: the loaded map is the one that prunes.
        configFile("""{ "signalCaps": { "cache": 3 } }""")
        val config = DaemonConfig.load(dataDir = tmp)
        val repository = SessionRepository(config)

        val writer = SessionWriter(
            config.sessionsDir.resolve("s1"),
            clientInfo().toSessionMeta("s1", "2026-08-16T10:14:02.311Z"),
        )
        repeat(10) { i ->
            writer.append(
                signal(id = "c%02d".format(i), tag = SignalTags.CACHE, name = "entry", mono = i.toLong()),
                """{"i":$i}""".toByteArray(),
            )
        }
        writer.close()

        val dropped = Retention(config, repository).pruneSignals(writer.sessionDir)

        assertEquals(7, dropped)
        val rows = repository.readSignals(writer.sessionDir)
        assertEquals(listOf("c07", "c08", "c09"), rows.map { it.id })
        assertNull(repository.readSignalPayload(writer.sessionDir, "c00"), "dropped payloads go too")
    }

    @Test
    fun the_default_cache_cap_holds_a_session_that_emits_a_row_per_entry() {
        // Why the default moved off 20: the documented recipe emits a row per entry write, removal
        // and scope invalidation, so a modest cache exceeds 20 rows on its first pass through the
        // keys — and past the cap the browser's latest-per-key view loses whole keys, not history.
        val caps = DaemonConfig.load(dataDir = tmp).signalCaps

        assertTrue(
            caps.getValue(SignalTags.CACHE) >= 100,
            "a per-entry cache stream needs room for more keys than a snapshot had moments",
        )
    }
}
