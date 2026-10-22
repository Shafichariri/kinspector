package dev.inspector

import kotlin.test.Test

/**
 * The iOS half of [ApiParityTest]: `:inspector-core` and `:inspector-noop` expose the same public
 * declarations in their iOS klibs, so a public function in one module's `iosMain` alone fails here
 * instead of in a consumer's iOS release build.
 *
 * Runs only in each module's `iosApiTest` task, which passes the klib paths; see [KlibSurface].
 */
class IosApiParityTest {

    @Test
    fun ios_api_matches_the_golden_surface() {
        GoldenSurface.assertMatches(
            actual = KlibSurface.dump(ktorRule = false),
            goldenPath = GOLDEN,
            header = HEADER,
            why = "Both :inspector-core and :inspector-noop must expose this surface on iOS, " +
                "or -Pinspector=off would change what compiles in consuming iOS apps.",
            regenerateWith = REGENERATE,
        )
    }

    private companion object {
        const val GOLDEN = "api/inspector-public-api.ios.txt"
        const val REGENERATE = "./gradlew :inspector-core:iosApiTest -Dinspector.api.regenerate=true"
        const val HEADER = "# Generated. Public iOS API shared by :inspector-core and :inspector-noop, read from both\n" +
            "# iOS targets' klibs by `klib dump-abi`. Each member is prefixed with its enclosing declarations,\n" +
            "# so moving one between classes shows; IR signatures are dropped. See KlibSurface.\n" +
            "# Regenerate: $REGENERATE\n\n"
    }
}
