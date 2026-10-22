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
    // The same parity tests, run again on the Android host against the JVM golden file: Android
    // output is JVM bytecode, and every split that reaches Android also reaches the JVM. Read-only;
    // see docs/decisions/twins-and-api.md.
    sourceSets.named("androidHostTest") {
        kotlin.srcDir(rootProject.layout.projectDirectory.dir("api-parity/shared/src"))
        kotlin.srcDir(rootProject.layout.projectDirectory.dir("api-parity/stream/src"))
        dependencies {
            implementation(kotlin("test"))
            implementation(kotlin("reflect"))
        }
    }
}

tasks.named<Test>("jvmTest") {
    // Read at run time from outside this module, so nothing else tells Gradle it matters: a
    // hand-edited golden file would leave jvmTest UP-TO-DATE, or a build-cache hit, and green.
    // `files`, not `file`: an input file must exist, and the first regeneration runs before it does.
    inputs.files(rootProject.layout.projectDirectory.file("api/inspector-stream-public-api.txt"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

// Registered by AGP after this script runs, so matched by name rather than looked up.
tasks.withType<Test>().named { it == "testAndroidHostTest" }.configureEach {
    // The same golden file as jvmTest, read and never written: see the regenerate property below.
    inputs.files(rootProject.layout.projectDirectory.file("api/inspector-stream-public-api.txt"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

// Gradle's own -D lands on the daemon, not the forked test JVM, so forward it explicitly. To jvmTest
// only: the Android host test asserts the same golden file and must never rewrite it.
private val regenerateApi = providers.systemProperty("inspector.api.regenerate").orElse("false")

tasks.named<Test>("jvmTest") {
    systemProperty("inspector.api.regenerate", regenerateApi.get())
}
