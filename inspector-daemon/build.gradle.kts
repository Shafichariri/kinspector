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

// So the running daemon can name its own version — the HAR export reports it as the creator, and
// "which build produced this file" is the first question asked of any exported capture. Absent
// from a classes directory, which is why the reader falls back rather than failing.
tasks.jar {
    manifest {
        attributes("Implementation-Version" to project.version)
    }
}

/*
 * The mirror cases are read from the repository root at test time, so Gradle has to be told they
 * are an input. Without `inputs.dir` a changed JSON case leaves the task up to date and the run that
 * was meant to prove it fails reports green from the cache instead.
 */
tasks.test {
    systemProperty("inspector.mirrorCases", rootProject.file("mirror-cases").path)
    inputs.dir(rootProject.file("mirror-cases"))
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
