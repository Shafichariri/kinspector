plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    id("maven-publish")
}

kotlin {
    jvmToolchain(21)

    android {
        namespace = "dev.inspector.noop"
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
        // Mirrors :inspector-core's jvm+android source set so `Inspector.okHttpInterceptor()`
        // still resolves under -Pinspector=off, returning a pass-through interceptor.
        val jvmAndAndroidMain by creating {
            dependsOn(commonMain.get())
            dependencies { compileOnly(libs.okhttp) }
        }
        jvmMain.get().dependsOn(jvmAndAndroidMain)
        androidMain.get().dependsOn(jvmAndAndroidMain)

        commonMain.dependencies {
            // Must mirror :inspector-core's exposed dependencies exactly, or swapping the two
            // would change what compiles in the consuming app.
            api(project(":inspector-model"))
            api(libs.ktor.client.core)
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation(kotlin("reflect"))
            // OkHttp is compileOnly for consumers, but ApiParityTest reflects over the facade
            // class and needs okhttp3.Interceptor loadable at test runtime.
            implementation(libs.okhttp)
        }
    }

    sourceSets.named("jvmTest") {
        kotlin.srcDir(rootProject.layout.projectDirectory.dir("api-parity/src"))
    }
}

// Gradle's own -D lands on the daemon, not the forked test JVM, so forward it explicitly.
private val regenerateApi = providers.systemProperty("inspector.api.regenerate").orElse("false")

tasks.withType<Test>().configureEach {
    systemProperty("inspector.api.regenerate", regenerateApi.get())
}
