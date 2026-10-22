plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    id("maven-publish")
}

kotlin {
    jvmToolchain(21)

    android {
        namespace = "dev.inspector.ui.noop"
        compileSdk = 36
        minSdk = 24
        withHostTest {}
    }
    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":inspector-noop"))
            implementation(compose.runtime)
        }
        // The only tests this module has are the parity guard: a release stand-in has no
        // behaviour of its own to assert, only a surface it must keep matching.
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(kotlin("reflect"))
        }
    }

    // Shared with :inspector-ui so both modules assert against the same golden surface.
    sourceSets.named("jvmTest") {
        kotlin.srcDir(rootProject.layout.projectDirectory.dir("api-parity/shared/src"))
        kotlin.srcDir(rootProject.layout.projectDirectory.dir("api-parity/ui/src"))
    }
    // The same parity tests, run again on the Android host against the JVM golden file: Android
    // output is JVM bytecode, and every split that reaches Android also reaches the JVM. Read-only;
    // see docs/decisions/twins-and-api.md.
    sourceSets.named("androidHostTest") {
        kotlin.srcDir(rootProject.layout.projectDirectory.dir("api-parity/shared/src"))
        kotlin.srcDir(rootProject.layout.projectDirectory.dir("api-parity/ui/src"))
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
    inputs.files(rootProject.layout.projectDirectory.file("api/inspector-ui-public-api.txt"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

// Registered by AGP after this script runs, so matched by name rather than looked up.
tasks.withType<Test>().named { it == "testAndroidHostTest" }.configureEach {
    // The same golden file as jvmTest, read and never written: see the regenerate property below.
    inputs.files(rootProject.layout.projectDirectory.file("api/inspector-ui-public-api.txt"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

// Gradle's own -D lands on the daemon, not the forked test JVM, so forward it explicitly. To jvmTest
// only: the Android host test asserts the same golden file and must never rewrite it.
private val regenerateApi = providers.systemProperty("inspector.api.regenerate").orElse("false")

tasks.named<Test>("jvmTest") {
    systemProperty("inspector.api.regenerate", regenerateApi.get())
}
