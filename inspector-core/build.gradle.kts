plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    id("maven-publish")
}

kotlin {
    jvmToolchain(21)

    android {
        namespace = "dev.inspector.core"
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
        // OkHttp capture lives in this module rather than its own so it can reuse `Recorder`,
        // `Redactor` and the id/clock helpers as internals. A separate module would have forced
        // either a public recording API into existence or a second copy of the redaction logic,
        // and a second copy is how two capture paths quietly stop agreeing.
        val jvmAndAndroidMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                // compileOnly: a consuming app keeps whatever OkHttp it already has, and an app
                // with no OkHttp is unaffected because it never references the interceptor.
                compileOnly(libs.okhttp)
            }
        }
        jvmMain.get().dependsOn(jvmAndAndroidMain)
        androidMain.get().dependsOn(jvmAndAndroidMain)

        commonMain.dependencies {
            // `api`, not `implementation`: consumers call Inspector.install inside their own
            // HttpClient { } block, and read NetworkTransaction off the exposed StateFlows.
            api(project(":inspector-model"))
            api(libs.ktor.client.core)
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        commonMain.dependencies {
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.json)
        }
        jvmTest.dependencies {
            implementation(kotlin("reflect"))
            // A real server, not MockEngine: redirect and retry behaviour is exactly what the
            // per-attempt contract hinges on, and a mock would let us assert our own fiction.
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.server.compression)
            implementation(libs.ktor.client.cio)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.okhttp)
        }
    }

    // Shared with :inspector-noop so both modules assert against the same golden API surface.
    sourceSets.named("jvmTest") {
        kotlin.srcDir(rootProject.layout.projectDirectory.dir("api-parity/shared/src"))
        kotlin.srcDir(rootProject.layout.projectDirectory.dir("api-parity/core/src"))
    }
}

// Gradle's own -D lands on the daemon, not the forked test JVM, so forward it explicitly.
private val regenerateApi = providers.systemProperty("inspector.api.regenerate").orElse("false")

tasks.withType<Test>().configureEach {
    systemProperty("inspector.api.regenerate", regenerateApi.get())
}
