import org.jetbrains.compose.ExperimentalComposeLibrary

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
        namespace = "dev.inspector.ui"
        compileSdk = 36
        minSdk = 24
    }
    jvm()
    // No iosX64: Compose Multiplatform 1.11+ dropped the Intel simulator, so no CMP app can
    // target it. Keeping it here would advertise a platform the UI module cannot serve.
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":inspector-core"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            // Test-only, and never published: ImageComposeScene needs skiko's native runtime to
            // render the list off-screen. `compose.ui` alone brings the API but not the binary.
            implementation(compose.desktop.currentOs)
            // Also test-only. The order toggle and the freeze control are state held inside the
            // composable, so the only honest way to test them is to tap them — a render test can
            // photograph one state and never the transition between two.
            @OptIn(ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
            // For the parity guard: ApiSurface asks Kotlin metadata what is public.
            implementation(kotlin("reflect"))
        }
        androidMain.dependencies {
            // Only for InspectorBackHandler. Every Compose Android app already has this — it is
            // what provides ComponentActivity.setContent.
            implementation(libs.androidx.activity.compose)
        }
    }

    // Shared with :inspector-noop-ui so both modules assert against the same golden surface.
    sourceSets.named("jvmTest") {
        kotlin.srcDir(rootProject.layout.projectDirectory.dir("api-parity/shared/src"))
        kotlin.srcDir(rootProject.layout.projectDirectory.dir("api-parity/ui/src"))
    }
}

/*
 * Passes the web stylesheets to the test JVM *as declared inputs*, on the pattern of
 * `MirrorCasesArgument` in `inspector-model/build.gradle.kts`. `PaletteParityTest` reads them from
 * another module, so nothing else tells Gradle they matter: measured before this existed, drifting
 * `--m-get` in the stylesheet left `jvmTest` UP-TO-DATE and green, and only `--rerun` failed it.
 * Relative path sensitivity keeps the cache entry independent of where the checkout lives.
 *
 * The folder, not `styles.txt`: the test reads every stylesheet in it, and `WebManifestTest` in the
 * daemon already holds the folder and the manifest equal, so parsing the manifest here as well would
 * be a second reader of it beside `WebPage`.
 */
class WebCssArgument(
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val css: File,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> = listOf("-Dinspector.webCss=${css.absolutePath}")
}

tasks.named<Test>("jvmTest") {
    jvmArgumentProviders += WebCssArgument(rootProject.file("inspector-daemon/src/main/resources/web/css"))
    // Read at run time from outside this module, like the stylesheets above, so nothing else
    // tells Gradle it matters: a hand-edited golden file would leave jvmTest UP-TO-DATE, or a
    // build-cache hit, and green. `files`, not `file`: an input file must exist, and the first
    // regeneration runs before it does.
    inputs.files(rootProject.layout.projectDirectory.file("api/inspector-ui-public-api.txt"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

// Gradle's own -D lands on the daemon, not the forked test JVM, so forward it explicitly.
private val regenerateApi = providers.systemProperty("inspector.api.regenerate").orElse("false")

tasks.withType<Test>().configureEach {
    systemProperty("inspector.api.regenerate", regenerateApi.get())
}
