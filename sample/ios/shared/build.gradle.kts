plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

// Swapped by -Pinspector=off exactly as a consuming app would do it.
val inspectorOff = providers.gradleProperty("inspector").orNull == "off"

kotlin {
    // No iosX64: Compose Multiplatform 1.11+ dropped the Intel simulator, so no CMP app can
    // target it — the same reason every library module here omits it.
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        // Debug only, and deliberately. `framework { }` with no argument declares debug *and*
        // release, which puts two optimized Kotlin/Native links of the whole Compose runtime into
        // `./gradlew build` — four minutes and an OutOfMemoryError in the Kotlin daemon, on every
        // build and in CI, for a sample app nobody ships. The Xcode project pins itself to the
        // debug framework to match.
        target.binaries.framework(listOf(org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType.DEBUG)) {
            // The name Xcode imports. Static, so there is no embed-and-sign phase at all.
            baseName = "SampleShared"
            isStatic = true
        }
    }

    sourceSets {
        // The demo server is shared with the desktop and Android samples rather than copied.
        commonMain {
            kotlin.srcDir("../../shared/kotlin")
        }

        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(libs.ktor.client.darwin)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            // Probing whether the shared DemoServer can run inside the app on iOS the way it does
            // on desktop and Android. If ktor-server-cio has no native variant this will not
            // resolve, and the sample talks to a server on the host instead.
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.cio)

            if (inspectorOff) {
                implementation(project(":inspector-noop"))
                implementation(project(":inspector-noop-ui"))
                implementation(project(":inspector-noop-stream"))
            } else {
                implementation(project(":inspector-core"))
                implementation(project(":inspector-ui"))
                implementation(project(":inspector-stream"))
            }
        }
    }
}
