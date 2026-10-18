plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

application {
    mainClass = "dev.inspector.daemon.MainKt"
    applicationName = "inspector"
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":inspector-model"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.ktor.client.websockets)
    // The round-trip test drives the real sink against the real daemon.
    testImplementation(project(":inspector-stream"))
}

tasks.test {
    useJUnitPlatform()
}

/*
 * The daemon is not published, but its runtime classpath is what ships in the release zip, so it
 * is locked like a published module's (see the root build). `distZip` resolves it during `build`,
 * which is what checks this lock; the test classpath stays unlocked, because nothing ships it.
 */
dependencyLocking { lockMode = LockMode.STRICT }
configurations.runtimeClasspath { resolutionStrategy.activateDependencyLocking() }

// So the running daemon can name its own version — the HAR export reports it as the creator, and
// "which build produced this file" is the first question asked of any exported capture. Absent
// from a classes directory, which is why the reader falls back rather than failing.
tasks.jar {
    manifest {
        attributes("Implementation-Version" to project.version)
    }
}

// The web UI's agent guide sits beside the files it describes, so it lands in `resources/web/`.
// Nothing would serve it — every route names its file — but Gradle follows the `CLAUDE.md` symlink
// and copies both into the jar, which would change the shipped artefact for a note to agents.
tasks.processResources {
    exclude("web/AGENTS.md", "web/CLAUDE.md")
}

/*
 * Passes the mirror-cases folder to the test JVM *as a declared input*, path-sensitive relative to
 * the folder rather than absolute, so the test re-runs when a case changes and the build cache can
 * still be shared between checkouts at different paths. An absolute `systemProperty` would be an
 * input that differs per machine, and a bare `inputs.dir` without it was what kept a changed case
 * from being seen at all.
 */
class MirrorCasesArgument(
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val dir: File,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> = listOf("-Dinspector.mirrorCases=${dir.absolutePath}")
}

/*
 * The mirror cases are read from the repository root at test time, so Gradle has to be told they
 * are an input; see `inspector-model/build.gradle.kts` for why that is load-bearing.
 */
tasks.test {
    jvmArgumentProviders += MirrorCasesArgument(rootProject.file("mirror-cases"))
}

/*
 * Writes the session CI points the web UI probes at. A test-classpath `main`, so it is never part
 * of the published daemon; see `ProbeFixture.kt` for why the fixture is written rather than
 * committed.
 *
 *   ./gradlew :inspector-daemon:writeProbeFixture -Pinspector.probeDir=/tmp/probe
 */
tasks.register<JavaExec>("writeProbeFixture") {
    description = "Writes the web UI probe session into -Pinspector.probeDir (default build/probe-fixture)."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "dev.inspector.daemon.ProbeFixtureKt"
    val dir = providers.gradleProperty("inspector.probeDir")
        .orElse(layout.buildDirectory.dir("probe-fixture").map { it.asFile.path })
    argumentProviders += CommandLineArgumentProvider { listOf(dir.get()) }
}
