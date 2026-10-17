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
 * `MirrorCasesTest` reads the shared cases from the repository root. `inputs.dir` is load-bearing:
 * without it a changed JSON case leaves `jvmTest` up to date, and the run meant to prove a case
 * fails reports green from the build cache instead.
 */
tasks.named<Test>("jvmTest") {
    systemProperty("inspector.mirrorCases", rootProject.file("mirror-cases").path)
    inputs.dir(rootProject.file("mirror-cases"))
}
