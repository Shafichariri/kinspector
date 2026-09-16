package dev.inspector.ui

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
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

    private fun shoot(name: String, dark: Boolean) {
        val out = File("build/screenshots").apply { mkdirs() }.resolve("$name.png")
        val scene = ImageComposeScene(width = 360, height = 720, density = Density(1f)) {
            InspectorTheme(dark = dark) {
                InspectorList(
                    transactions = session(),
                    markers = emptyList(),
                    onSelect = {},
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

    @Test
    fun renders_the_list_dark() = shoot("list-dark", dark = true)

    @Test
    fun renders_the_list_light() = shoot("list-light", dark = false)
}
