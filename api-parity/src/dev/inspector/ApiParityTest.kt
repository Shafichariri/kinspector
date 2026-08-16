package dev.inspector

import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * Asserts this module's public surface matches the golden file shared with the other module.
 *
 * Runs in both `:inspector-core` and `:inspector-noop`. If either drifts from
 * `api/inspector-public-api.txt`, its build fails — so `-Pinspector=off` can never silently
 * change what compiles in a consuming app.
 *
 * To change the API deliberately: update both modules, then regenerate with
 * `./gradlew :inspector-core:jvmTest -Dinspector.api.regenerate=true`.
 */
class ApiParityTest {

    private val goldenFile: File
        get() {
            // Walk up from the module dir to the repo root, which holds api/.
            var dir: File? = File(System.getProperty("user.dir"))
            while (dir != null) {
                val candidate = File(dir, "api/inspector-public-api.txt")
                if (candidate.exists() || File(dir, "settings.gradle.kts").exists()) return candidate
                dir = dir.parentFile
            }
            fail("could not locate repo root from ${System.getProperty("user.dir")}")
        }

    @Test
    fun public_api_matches_the_golden_surface() {
        val actual = ApiSurface.dump()

        if (System.getProperty("inspector.api.regenerate") == "true") {
            goldenFile.parentFile.mkdirs()
            goldenFile.writeText(HEADER + actual.joinToString("\n") + "\n")
            println("Regenerated ${goldenFile.path} with ${actual.size} entries")
            return
        }

        if (!goldenFile.exists()) {
            fail(
                "Golden API file missing at ${goldenFile.path}. Generate it with:\n" +
                    "  ./gradlew :inspector-core:jvmTest -Dinspector.api.regenerate=true"
            )
        }

        val expected = goldenFile.readLines()
            .filterNot { it.startsWith("#") || it.isBlank() }

        val missing = expected - actual.toSet()
        val extra = actual - expected.toSet()

        if (missing.isNotEmpty() || extra.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("Public API differs from ${goldenFile.path}.")
                    appendLine(
                        "Both :inspector-core and :inspector-noop must expose this surface, " +
                            "or -Pinspector=off would change what compiles in consuming apps."
                    )
                    if (missing.isNotEmpty()) {
                        appendLine("\nMissing from this module (${missing.size}):")
                        missing.forEach { appendLine("  - $it") }
                    }
                    if (extra.isNotEmpty()) {
                        appendLine("\nPresent here but not in the golden file (${extra.size}):")
                        extra.forEach { appendLine("  + $it") }
                    }
                    appendLine(
                        "\nIf this change is intended, mirror it in the other module and run:\n" +
                            "  ./gradlew :inspector-core:jvmTest -Dinspector.api.regenerate=true"
                    )
                }
            )
        }
    }

    private companion object {
        const val HEADER = "# Generated. Public API shared by :inspector-core and :inspector-noop.\n" +
            "# Regenerate: ./gradlew :inspector-core:jvmTest -Dinspector.api.regenerate=true\n\n"
    }
}
