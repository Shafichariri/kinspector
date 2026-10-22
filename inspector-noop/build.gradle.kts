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
        kotlin.srcDir(rootProject.layout.projectDirectory.dir("api-parity/shared/src"))
        kotlin.srcDir(rootProject.layout.projectDirectory.dir("api-parity/core/src"))
    }
    // The same parity tests, run again on the Android host against the JVM golden file: Android
    // output is JVM bytecode, and every split that reaches Android also reaches the JVM. Read-only;
    // see docs/decisions/twins-and-api.md.
    sourceSets.named("androidHostTest") {
        kotlin.srcDir(rootProject.layout.projectDirectory.dir("api-parity/shared/src"))
        kotlin.srcDir(rootProject.layout.projectDirectory.dir("api-parity/core/src"))
        dependencies {
            implementation(kotlin("test"))
            implementation(kotlin("reflect"))
            // As in jvmTest: the parity test reflects over the facade, which names okhttp3.Interceptor.
            implementation(libs.okhttp)
        }
    }
}

tasks.named<Test>("jvmTest") {
    // Read at run time from outside this module, so nothing else tells Gradle it matters: a
    // hand-edited golden file would leave jvmTest UP-TO-DATE, or a build-cache hit, and green.
    // `files`, not `file`: an input file must exist, and the first regeneration runs before it does.
    inputs.files(rootProject.layout.projectDirectory.file("api/inspector-public-api.txt"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

// Registered by AGP after this script runs, so matched by name rather than looked up.
tasks.withType<Test>().named { it == "testAndroidHostTest" }.configureEach {
    // The same golden file as jvmTest, read and never written: see the regenerate property below.
    inputs.files(rootProject.layout.projectDirectory.file("api/inspector-public-api.txt"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

// Gradle's own -D lands on the daemon, not the forked test JVM, so forward it explicitly. To jvmTest
// only: the Android host test asserts the same golden file and must never rewrite it.
private val regenerateApi = providers.systemProperty("inspector.api.regenerate").orElse("false")

tasks.named<Test>("jvmTest") {
    systemProperty("inspector.api.regenerate", regenerateApi.get())
}
