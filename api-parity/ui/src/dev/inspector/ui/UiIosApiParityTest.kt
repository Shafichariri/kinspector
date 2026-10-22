package dev.inspector.ui

import dev.inspector.GoldenSurface
import dev.inspector.KlibSurface
import kotlin.test.Test

/**
 * The iOS half of [UiApiParityTest]: `:inspector-ui` and `:inspector-noop-ui` expose the same
 * public declarations in their iOS klibs, which is `InspectorOverlay` alone.
 *
 * The Compose compiler's `$stableprop` markers are dropped: it emits them public for internal
 * classes, 26 lines in the real module and none in the noop. Runs only in `iosApiTest`; see
 * [KlibSurface].
 */
class UiIosApiParityTest {

    @Test
    fun ios_api_matches_the_golden_surface() {
        GoldenSurface.assertMatches(
            actual = KlibSurface.dump(ktorRule = false),
            goldenPath = GOLDEN,
            header = HEADER,
            why = "Both :inspector-ui and :inspector-noop-ui must expose this surface on iOS, " +
                "or -Pinspector=off would change what compiles in consuming iOS apps.",
            regenerateWith = REGENERATE,
        )
    }

    private companion object {
        const val GOLDEN = "api/inspector-ui-public-api.ios.txt"
        const val REGENERATE = "./gradlew :inspector-ui:iosApiTest -Dinspector.api.regenerate=true"
        const val HEADER = "# Generated. Public iOS API shared by :inspector-ui and :inspector-noop-ui, read from both\n" +
            "# iOS targets' klibs by `klib dump-abi`. IR signatures are dropped, and so are the Compose\n" +
            "# compiler's \$stableprop markers, which it makes public for internal classes. See UiIosApiParityTest.\n" +
            "# Regenerate: $REGENERATE\n\n"
    }
}
