package dev.inspector

import kotlin.test.Test

/**
 * Asserts this module's public surface matches the golden file shared with its twin.
 *
 * Runs in both `:inspector-core` and `:inspector-noop`. If either drifts from
 * `api/inspector-public-api.txt`, its build fails — so `-Pinspector=off` can never silently
 * change what compiles in a consuming app.
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

    private companion object {
        const val GOLDEN = "api/inspector-public-api.txt"
        const val REGENERATE = "./gradlew :inspector-core:jvmTest -Dinspector.api.regenerate=true"
        const val HEADER = "# Generated. Public API shared by :inspector-core and :inspector-noop.\n" +
            "# Regenerate: ./gradlew :inspector-core:jvmTest -Dinspector.api.regenerate=true\n\n"
    }
}
