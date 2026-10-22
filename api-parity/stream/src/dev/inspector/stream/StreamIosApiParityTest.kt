package dev.inspector.stream

import dev.inspector.GoldenSurface
import dev.inspector.KlibSurface
import kotlin.test.Test

/**
 * The iOS half of [StreamApiParityTest]: `:inspector-stream` and `:inspector-noop-stream` expose
 * the same public declarations in their iOS klibs, Ktor-typed ones excepted.
 *
 * The Ktor rule applies to the real module only, as on the JVM: the noop must never name Ktor, so
 * nothing of its surface is hidden. Native keeps function-type arguments, so the real constructor's
 * `Function0<HttpClient>` is read as the noop's `Function0<Any?>`; a source-level match, decided
 * 2026-10-22 (`docs/decisions/twins-and-api.md`). Runs only in `iosApiTest`; see [KlibSurface].
 */
class StreamIosApiParityTest {

    @Test
    fun ios_api_matches_the_golden_surface() {
        GoldenSurface.assertMatches(
            actual = KlibSurface.dump(ktorRule = System.getProperty("inspector.klib.module") == REAL_MODULE),
            goldenPath = GOLDEN,
            header = HEADER,
            why = "Both :inspector-stream and :inspector-noop-stream must expose this surface on iOS, " +
                "or -Pinspector=off would change what compiles in consuming iOS apps.",
            regenerateWith = REGENERATE,
        )
    }

    private companion object {
        const val REAL_MODULE = "inspector-stream"
        const val GOLDEN = "api/inspector-stream-public-api.ios.txt"
        const val REGENERATE = "./gradlew :inspector-stream:iosApiTest -Dinspector.api.regenerate=true"
        const val HEADER = "# Generated. Public iOS API shared by :inspector-stream and :inspector-noop-stream, read from\n" +
            "# both iOS targets' klibs by `klib dump-abi`. Each member is prefixed with its enclosing declarations;\n" +
            "# IR signatures are dropped. In the real module a Ktor type inside a function type reads as\n" +
            "# kotlin/Any?, and every other Ktor-typed line is excluded. See StreamIosApiParityTest.\n" +
            "# Regenerate: $REGENERATE\n\n"
    }
}
