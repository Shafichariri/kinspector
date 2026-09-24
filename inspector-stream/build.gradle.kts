plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    id("maven-publish")
}

kotlin {
    jvmToolchain(21)

    android {
        namespace = "dev.inspector.stream"
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
            api(project(":inspector-core"))
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
            // The physical-iPhone route accepts a USB-bridged connection; see UsbListenerTransport.
            implementation(libs.ktor.network)
            implementation(libs.kotlinx.serialization.json)
        }
        androidMain.dependencies { implementation(libs.ktor.client.okhttp) }
        jvmMain.dependencies { implementation(libs.ktor.client.cio) }
        iosMain.dependencies { implementation(libs.ktor.client.darwin) }
        commonTest.dependencies { implementation(kotlin("test")) }
        jvmTest.dependencies { implementation(kotlin("reflect")) }
    }

    // Shared with :inspector-noop-stream so both modules assert against the same golden surface.
    sourceSets.named("jvmTest") {
        kotlin.srcDir(rootProject.layout.projectDirectory.dir("api-parity/shared/src"))
        kotlin.srcDir(rootProject.layout.projectDirectory.dir("api-parity/stream/src"))
    }
}

// Gradle's own -D lands on the daemon, not the forked test JVM, so forward it explicitly.
private val regenerateApi = providers.systemProperty("inspector.api.regenerate").orElse("false")

tasks.withType<Test>().configureEach {
    systemProperty("inspector.api.regenerate", regenerateApi.get())
}
