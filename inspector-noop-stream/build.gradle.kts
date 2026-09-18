plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    id("maven-publish")
}

kotlin {
    jvmToolchain(21)

    android {
        namespace = "dev.inspector.stream.noop"
        compileSdk = 36
        minSdk = 24
    }
    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":inspector-noop"))
        }
        // The only tests this module has are the parity guard: a release stand-in has no
        // behaviour of its own to assert, only a surface it must keep matching.
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(kotlin("reflect"))
        }
    }

    // Shared with :inspector-stream so both modules assert against the same golden surface.
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
