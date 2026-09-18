package dev.inspector.ui

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import dev.inspector.model.Marker
import dev.inspector.model.Signal
import dev.inspector.model.SignalTrigger
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import dev.inspector.model.NetworkTransaction
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders the real list off-screen at phone size and writes a PNG.
 *
 * The row was designed against an HTML mock, which cannot be wrong about colour but can be wrong
 * about everything Compose actually decides — baseline alignment between two type sizes on one
 * line, whether `weight` leaves the fixed columns room, whether a drawn stripe lands inside the
 * row. None of that shows up in a unit test, and all of it shows up here.
 *
 * `Density(1f)` makes 1dp one pixel, so the output is directly comparable to the 360dp mock.
 *
 * The assertion is deliberately weak — this exists to produce something to look at, and to fail
 * loudly if composing the list throws at all. The images land in `build/screenshots/`.
 */
class ListRenderTest {

    private var seq = 0

    private fun txn(
        method: String,
        path: String,
        status: Int?,
        ms: Long?,
        resBytes: Long,
        host: String = "api.example.com",
        attempt: Int = 1,
        callId: String? = null,
        error: String? = null,
    ): NetworkTransaction {
        val id = (seq++).toString(16).padStart(8, '0')
        return NetworkTransaction(
            id = id,
            ts = "2026-09-15T10:00:0${seq % 10}.000Z",
            mono = seq * 100L,
            method = method,
            scheme = "https",
            host = host,
            path = path,
            status = status,
            error = error,
            ms = ms,
            attempt = attempt,
            callId = callId ?: id,
            reqBytes = 0,
            resBytes = resBytes,
        )
    }

    /** A session shaped like the one this layout was designed for: one host, one long prefix. */
    private fun session(): List<NetworkTransaction> = listOf(
        txn("GET", "/v3/some-service/client-dashboard", 200, 342, 12_700),
        // Same request again, by a different call — this is what lights up "repeated".
        txn("GET", "/v3/some-service/client-dashboard", 200, 118, 12_700),
        txn("POST", "/v3/some-service/orders/submit", 201, 890, 1_840),
        txn("GET", "/v3/some-service/portfolio/holdings", 200, 1_200, 49_400),
        txn("GET", "/v3/some-service/accounts/1299651", 200, 96, 4_500),
        txn("PATCH", "/v3/some-service/client-preferences", 204, 210, 410),
        txn("GET", "/v3/some-service/market/quotes", 500, 2_400, 920, attempt = 2),
        txn("GET", "/v1/oauth/token", 200, 505, 1_100, host = "auth.example.com"),
        txn("GET", "/v3/some-service/notifications", null, null, 0, error = "SocketTimeoutException"),
    )

    /**
     * Markers placed inside the session above, at the two `mono` values that put a divider between
     * rows rather than at either end — a divider that only ever renders at the top of the list
     * would not show whether it sits correctly *between* two rows, which is the part that can go
     * wrong. `session()` numbers rows from `mono = 100` upward in steps of 100.
     */
    private fun markers(): List<Marker> = listOf(
        Marker(ts = "2026-09-15T10:00:02.000Z", mono = 250, label = "opened dashboard", source = "app"),
        Marker(ts = "2026-09-15T10:00:06.000Z", mono = 650, label = "tapped submit", source = "user"),
    )

    /**
     * Signals shaped like a real session: a screen change, a cache read, and a state holder that
     * fires four times in a row — which is what the run collapsing exists for and the only way the
     * screenshot shows whether a collapsed run reads as one.
     */
    private fun signals(): List<Signal> {
        var n = 0
        fun sig(tag: String, name: String, mono: Long) = Signal(
            id = "s${n++}".padStart(8, '0'),
            ts = "2026-09-15T10:00:0${(mono / 100) % 10}.000Z",
            mono = mono, tag = tag, name = name,
        )
        return listOf(
            sig("screen", "dashboard", 150),
            sig("cache", "portfolio:1299651", 450),
            sig("state", "OrderFormViewModel", 610),
            sig("state", "OrderFormViewModel", 620),
            sig("state", "OrderFormViewModel", 640),
            sig("state", "OrderFormViewModel", 690),
        )
    }

    /**
     * The observation the detail shot opens, with a payload and two earlier versions of itself.
     *
     * A cache entry rather than a screen, because it is the case with something in every field the
     * screen draws: a payload worth indenting, a size, a provenance that is not the default, and a
     * history long enough to show the gap column doing its job.
     */
    private fun cacheObservations(): List<Signal> {
        fun entry(mono: Long, items: Int, trigger: SignalTrigger) = Signal(
            id = "c${mono}".padStart(8, '0'),
            // Seconds derived from `mono`, so the clock column and the gap column tell the same
            // story. `(mono / 100) % 10` wrapped — 450 and 4400 both rendered as :04 — and the
            // history then read as though it were out of order, which is a defect in the picture
            // rather than in the screen but is just as misleading to whoever looks at it.
            ts = "2026-09-15T10:00:${((mono / 1000) % 60).toString().padStart(2, '0')}.000Z",
            mono = mono,
            tag = "cache",
            name = "portfolio:1299651",
            data = buildJsonObject {
                put("storage", "disk")
                put("items", items)
                put("expired", false)
                put("key", "portfolio/1299651/holdings")
            },
            bytes = 128 + items * 40L,
            trigger = trigger,
        )
        return listOf(
            entry(450, 3, SignalTrigger.App),
            entry(1_900, 7, SignalTrigger.App),
            entry(4_400, 9, SignalTrigger.Request),
        )
    }

    private fun shoot(name: String, dark: Boolean) {
        val out = File("build/screenshots").apply { mkdirs() }.resolve("$name.png")
        val scene = ImageComposeScene(width = 360, height = 720, density = Density(1f)) {
            InspectorTheme(dark = dark) {
                InspectorList(
                    transactions = session(),
                    markers = markers(),
                    signals = signals(),
                    onSelect = {}, onSelectSignal = {},
                    onClear = {},
                    onMark = {},
                    onClose = {},
                )
            }
        }
        try {
            val bytes = scene.render().encodeToData(EncodedImageFormat.PNG)?.bytes
            assertTrue(bytes != null && bytes.isNotEmpty(), "the list rendered nothing")
            out.writeBytes(bytes)
        } finally {
            scene.close()
        }
        assertTrue(out.length() > 0, "no image written to $out")
    }

    /** The signal detail screen, which is what stage 2 added and what nobody has looked at. */
    private fun shootSignal(name: String, dark: Boolean) {
        val out = File("build/screenshots").apply { mkdirs() }.resolve("$name.png")
        val history = cacheObservations()
        val scene = ImageComposeScene(width = 360, height = 720, density = Density(1f)) {
            InspectorTheme(dark = dark) {
                InspectorSignalDetail(
                    signal = history.last(),
                    signals = history,
                    onSelectSignal = {}, onCopy = {}, onBack = {}, onClose = {},
                )
            }
        }
        try {
            val bytes = scene.render().encodeToData(EncodedImageFormat.PNG)?.bytes
            assertTrue(bytes != null && bytes.isNotEmpty(), "the signal detail rendered nothing")
            out.writeBytes(bytes)
        } finally {
            scene.close()
        }
        assertTrue(out.length() > 0, "no image written to $out")
    }

    @Test
    fun renders_the_list_dark() = shoot("list-dark", dark = true)

    @Test
    fun renders_the_list_light() = shoot("list-light", dark = false)

    @Test
    fun renders_the_signal_detail_dark() = shootSignal("signal-dark", dark = true)

    @Test
    fun renders_the_signal_detail_light() = shootSignal("signal-light", dark = false)
}
