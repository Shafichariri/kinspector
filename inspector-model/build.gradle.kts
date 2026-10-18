plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.serialization)
    id("maven-publish")
}

kotlin {
    jvmToolchain(21)

    android {
        namespace = "dev.inspector.model"
        compileSdk = 36
        minSdk = 24
        withHostTest {}
    }
    jvm()
    // No iosX64: Compose Multiplatform 1.11+ dropped the Intel simulator, so no CMP app can
    // target it. Keeping it here would advertise a platform the UI module cannot serve.
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // `api`, not `implementation`: `Signal.data` is a public `JsonElement`, so every
            // consumer of this module needs the type on its own compile classpath.
            api(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

/*
 * Passes the mirror-cases folder to the test JVM *as a declared input*, path-sensitive relative to
 * the folder rather than absolute, so the test re-runs when a case changes and the build cache can
 * still be shared between checkouts at different paths. An absolute `systemProperty` would be an
 * input that differs per machine, and a bare `inputs.dir` without it was what kept a changed case
 * from being seen at all.
 */
class MirrorCasesArgument(
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val dir: File,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> = listOf("-Dinspector.mirrorCases=${dir.absolutePath}")
}

/*
 * `MirrorCasesTest` reads the shared cases from the repository root. Declaring the folder as an
 * input is load-bearing: without it a changed JSON case leaves `jvmTest` up to date, and the run
 * meant to prove a case fails reports green instead — measured, not assumed.
 */
tasks.named<Test>("jvmTest") {
    jvmArgumentProviders += MirrorCasesArgument(rootProject.file("mirror-cases"))
}
