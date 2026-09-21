package dev.inspector.stream

import dev.inspector.ApiSurface
import dev.inspector.GoldenSurface
import kotlin.test.Test

/**
 * Asserts `:inspector-stream` and `:inspector-noop-stream` expose the same surface to app code.
 *
 * This pair was unguarded until `lastError` was found present in the real module and absent from
 * the noop — a call site that collected it compiled in a debug build and failed to compile under
 * `-Pinspector=off`, which is the one failure the noop twins exist to prevent. The core pair had
 * a golden file since Phase 0; this pair had nothing, so the drift was invisible until somebody
 * read both files side by side.
 *
 * **The two are not identical, and the golden file is the part that must be.** The real module
 * necessarily exposes Ktor types the noop cannot name — `defaultStreamClient(): HttpClient`, and
 * the `engineFactory` parameter — because a release build must not carry a Ktor dependency at
 * all. So the contract is every public member whose signature names **no** Ktor type, and each
 * module asserts equality against it: the real one with Ktor-typed members filtered out, the noop
 * one as it stands. Equality in both directions is load-bearing. A subset check on the real
 * module would let it grow a member the noop lacks and report nothing, which is exactly the hole
 * this test was written to close.
 *
 * **The JVM file facade is part of what this asserts, and it was the second drift.** Top-level
 * functions compile into a class named for their file, so `defaultDaemonHost` and
 * `defaultClientInfo` were `Platform_jvmKt`/`Platform_androidKt` in the real module and
 * `StreamSinkKt` in the noop — identical Kotlin API, different JVM class. A consumer compiled
 * against one and linked against the other got a `NoSuchMethodError` naming a class that is not
 * in the artifact, which is a swap that typechecks and then fails at runtime. Reported from a
 * consuming app; Kotlin/Native has no facades, so iOS never saw it.
 *
 * The earlier version of this guard **could not have caught it**: it dumped both facades under one
 * shared label precisely so the names would stop differing, which erased the only fact that did.
 * `StreamSinkKt` is now named in `STREAM_CONTRACT_CLASSES` like any other type, both modules pin
 * it with `@file:JvmName`, and renaming either file fails this test with an explanation rather
 * than shipping.
 *
 * Constructors are outside this guard, as they are for the core pair — `ApiSurface` reflects
 * functions and properties only.
 *
 * To change the API deliberately: update both modules, then regenerate with
 * `./gradlew :inspector-stream:jvmTest -Dinspector.api.regenerate=true`.
 */
class StreamApiParityTest {

    @Test
    fun public_api_matches_the_golden_surface() {
        GoldenSurface.assertMatches(
            actual = ApiSurface.dump(
                classes = ApiSurface.STREAM_CONTRACT_CLASSES,
                skipKtorTyped = IS_REAL_MODULE,
            ),
            goldenPath = GOLDEN,
            header = HEADER,
            why = "Both :inspector-stream and :inspector-noop-stream must expose this surface, " +
                "or -Pinspector=off would change what compiles in consuming apps.",
            regenerateWith = REGENERATE,
        )
    }

    private companion object {
        /**
         * Whether this run is the real module rather than the noop.
         *
         * Asked of the classpath rather than of a Gradle flag, so it cannot be set wrongly: only
         * `:inspector-stream` brings Ktor, and only it therefore has Ktor-typed members to skip.
         * If this ever answered wrongly for the noop, the skip would hide a genuinely missing
         * member instead of an unmirrorable one.
         */
        val IS_REAL_MODULE: Boolean = runCatching {
            Class.forName("io.ktor.client.HttpClient")
        }.isSuccess

        const val GOLDEN = "api/inspector-stream-public-api.txt"
        const val REGENERATE = "./gradlew :inspector-stream:jvmTest -Dinspector.api.regenerate=true"
        const val HEADER =
            "# Generated. Public API shared by :inspector-stream and :inspector-noop-stream.\n" +
                "# Ktor-typed members are excluded: a release build carries no Ktor, so the noop\n" +
                "# twin cannot name them. See StreamApiParityTest.\n" +
                "# Regenerate: ./gradlew :inspector-stream:jvmTest -Dinspector.api.regenerate=true\n\n"
    }
}
