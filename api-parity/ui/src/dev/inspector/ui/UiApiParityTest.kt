package dev.inspector.ui

import dev.inspector.ApiSurface
import dev.inspector.GoldenSurface
import kotlin.test.Test

/**
 * Asserts `:inspector-ui` and `:inspector-noop-ui` expose the same surface to app code.
 *
 * This pair had no golden file until phase I, and had already diverged: the real module exposed
 * `InspectorTheme`, `InspectorColors` and `LocalInspectorColors`, the noop only `InspectorOverlay`,
 * so an app theming against them compiled in debug and failed under `-Pinspector=off` — the
 * `lastError` failure again. Mirroring the palette would have shipped it in release builds as
 * dead data, so the three became `internal` (decided 2026-10-18) and `InspectorOverlay` is the
 * module's whole public API.
 *
 * Two tests, because the golden file alone is not enough. [public_api_matches_the_golden_surface]
 * compares the contract classes, as the core and stream pairs do: the `InspectorOverlayKt` facade
 * name, the declaration, and the JVM descriptor. [every_public_declaration_is_in_the_contract]
 * fails when any *other* class in the module holds public API. Without it, the theme types could
 * go public again, or a public composable land in a new file, with the golden test still green,
 * because `ApiSurface` only sees classes it is named.
 *
 * `@Composable` functions: the `fun` line is the Kotlin declaration and the `jvm` line the full
 * descriptor, Compose's `$composer`/`$changed`/`$default` included, because that is what a
 * consumer's compiled call binds to. The rule is in `ApiSurface.staticJavaSignatures`.
 *
 * To change the API deliberately: update both modules, then regenerate with
 * `./gradlew :inspector-ui:jvmTest -Dinspector.api.regenerate=true`.
 */
class UiApiParityTest {

    @Test
    fun public_api_matches_the_golden_surface() {
        GoldenSurface.assertMatches(
            actual = ApiSurface.dump(classes = ApiSurface.UI_CONTRACT_CLASSES),
            goldenPath = GOLDEN,
            header = HEADER,
            why = "Both :inspector-ui and :inspector-noop-ui must expose this surface, " +
                "or -Pinspector=off would change what compiles, or what links, in consuming apps.",
            regenerateWith = REGENERATE,
        )
    }

    @Test
    fun every_public_declaration_is_in_the_contract() {
        ApiSurface.assertEveryPublicDeclarationListed(
            contract = ApiSurface.UI_CONTRACT_CLASSES,
            listName = "UI_CONTRACT_CLASSES",
            rule = "The public API of the ui pair is InspectorOverlay alone, by decision (docs/decisions/twins-and-api.md).",
            regenerateWith = REGENERATE,
        )
    }

    private companion object {
        const val GOLDEN = "api/inspector-ui-public-api.txt"
        const val REGENERATE = "./gradlew :inspector-ui:jvmTest -Dinspector.api.regenerate=true"
        const val HEADER =
            "# Generated. Public API shared by :inspector-ui and :inspector-noop-ui.\n" +
                "# A @Composable function has two lines: `fun` is its Kotlin declaration, and `jvm` is\n" +
                "# the ABI, with the parameters the Compose compiler adds. See UiApiParityTest.\n" +
                "# Regenerate: ./gradlew :inspector-ui:jvmTest -Dinspector.api.regenerate=true\n\n"
    }
}
