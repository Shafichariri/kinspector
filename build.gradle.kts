import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.android.kmp.library) apply false
}

group = "dev.inspector"

// Overridable so a tagged release can stamp the real version onto the daemon distribution --
// `inspector-0.2.0.zip` rather than `inspector-0.1.0-SNAPSHOT.zip` -- without a commit that edits
// this line every time. `.github/workflows/release.yml` passes the tag with its leading `v`
// stripped; local builds get the snapshot and are unaffected.
version = providers.gradleProperty("inspector.version").getOrElse("0.1.0-SNAPSHOT")

subprojects {
    group = rootProject.group
    version = rootProject.version

    // Publishing is opt-in per module: this configures whichever modules apply `maven-publish`
    // themselves, rather than listing them here. An allowlist in this file would be a second
    // place to keep current, and the failure mode is silent -- :inspector-daemon is a tool and
    // :sample:desktop is a demo, and neither should ever appear in the repository.
    pluginManager.withPlugin("maven-publish") {
        extensions.configure<PublishingExtension> {
            repositories {
                maven {
                    name = "GitHubPackages"
                    url = uri("https://maven.pkg.github.com/Shafichariri/kinspector")
                    // GitHub Packages requires a token for *downloads* too, not only publishing,
                    // even when the package is public -- so every consumer sets these as well.
                    // Properties first so a developer can keep them in ~/.gradle/gradle.properties
                    // and out of their environment; the env vars are what CI provides.
                    credentials {
                        username = providers.gradleProperty("gpr.user")
                            .orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
                        password = providers.gradleProperty("gpr.key")
                            .orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
                    }
                }
            }

            publications.withType<MavenPublication>().configureEach {
                pom {
                    name = "Inspector ${'$'}{this@subprojects.name}"
                    description = "Network debugger for Compose Multiplatform apps that use Ktor."
                    url = "https://github.com/Shafichariri/kinspector"
                    // No <licenses> block on purpose: this project has not chosen a licence, and
                    // stating one here would be the wrong place to decide it. See the README.
                }
            }
        }
    }

    // Print the assertion message and stack trace when a test fails.
    //
    // Gradle's default exception format is SHORT, which logs only
    // "java.lang.AssertionError at PerAttemptTest.kt:72" and discards the message. These tests
    // deliberately carry their evidence in that message — `describe()` prints the offending rows —
    // so the default threw away the only diagnosis available for a CI-only failure, which is
    // exactly the case where re-running locally does not help.
    tasks.withType<AbstractTestTask>().configureEach {
        testLogging {
            events(TestLogEvent.FAILED)
            exceptionFormat = TestExceptionFormat.FULL
            showStackTraces = true
            showCauses = true
        }
    }
}
