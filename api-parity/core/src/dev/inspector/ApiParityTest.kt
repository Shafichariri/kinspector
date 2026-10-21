package dev.inspector

import kotlin.test.Test

/**
 * Asserts this module's public surface matches the golden file shared with its twin.
 *
 * Runs in both `:inspector-core` and `:inspector-noop`. If either drifts from
 * `api/inspector-public-api.txt`, its build fails — so `-Pinspector=off` can never silently
 * change what compiles in a consuming app.
 *
 * Two tests, as for the ui pair. The golden test compares only the classes named in
 * `CONTRACT_CLASSES`, so a public class or top-level function anywhere else was invisible to it;
 * [every_public_declaration_is_in_the_contract] fails on one. Measured when it was added, both
 * modules' public holders were exactly the nine contract classes.
 *
 * To change the API deliberately: update both modules, then regenerate with
 * `./gradlew :inspector-core:jvmTest -Dinspector.api.regenerate=true`.
 */
class ApiParityTest {

    @Test
    fun public_api_matches_the_golden_surface() {
        GoldenSurface.assertMatches(
            actual = ApiSurface.dump(),
            goldenPath = GOLDEN,
            header = HEADER,
            why = "Both :inspector-core and :inspector-noop must expose this surface, " +
                "or -Pinspector=off would change what compiles in consuming apps.",
            regenerateWith = REGENERATE,
        )
    }

    @Test
    fun every_public_declaration_is_in_the_contract() {
        ApiSurface.assertEveryPublicDeclarationListed(
            contract = ApiSurface.CONTRACT_CLASSES,
            listName = "CONTRACT_CLASSES",
            rule = "Everything public in the core pair is in its golden file, so that -Pinspector=off cannot change what compiles.",
            regenerateWith = REGENERATE,
        )
    }

    private companion object {
        const val GOLDEN = "api/inspector-public-api.txt"
        const val REGENERATE = "./gradlew :inspector-core:jvmTest -Dinspector.api.regenerate=true"
        const val HEADER = "# Generated. Public API shared by :inspector-core and :inspector-noop.\n" +
            "# Regenerate: ./gradlew :inspector-core:jvmTest -Dinspector.api.regenerate=true\n\n"
    }
}
