import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.android.kmp.library) apply false
    // Declared here, applied only by :sample:android. AGP is already on the build classpath via
    // the KMP library plugin above, so a module asking for it *with a version* fails resolution —
    // "already on the classpath with an unknown version, so compatibility cannot be checked".
    alias(libs.plugins.android.application) apply false
}

/*
 * The publishing namespace, and deliberately not `dev.inspector`.
 *
 * Maven Central verifies a `dev.*` namespace by DNS on the matching domain, and `inspector.dev` is
 * registered to somebody else — so `dev.inspector` was never claimable there however long it had
 * been in use here. `io.github.<user>` is verified by GitHub account ownership instead, which is
 * the route for a project without a domain.
 *
 * Changed **before** going to Central rather than as part of it: the coordinate change is the
 * breaking half and the repository move is not, so doing them together would make one event out
 * of two, and a consumer would have no way to tell which had broken them. Artifact ids and the
 * Kotlin package are untouched — `dev.inspector.Inspector` still imports exactly as it did.
 */
group = "io.github.shafichariri"

// Overridable so a tagged release can stamp the real version onto the daemon distribution --
// `inspector-0.2.0.zip` rather than `inspector-0.1.0-SNAPSHOT.zip` -- without a commit that edits
// this line every time. `.github/workflows/release.yml` passes the tag with its leading `v`
// stripped; local builds get the snapshot and are unaffected.
version = providers.gradleProperty("inspector.version").getOrElse("0.1.0-SNAPSHOT")

subprojects {
    group = rootProject.group
    version = rootProject.version

    /*
     * Captured here, in the `subprojects` body, where `this` is the Project.
     *
     * Two receivers have already got this wrong. Inside `pom {}` it is the POM, which is why the
     * original escaped the dollar so hard that the interpolation never ran — every POM from
     * 0.3.0 to 0.9.1 carries the placeholder text verbatim in its <name>. Inside
     * `pluginManager.withPlugin("maven-publish") {}` it is the applied plugin, so a plain `name`
     * there reads `Inspector maven-publish`, which is what the first attempt at this fix
     * published to the local repository. Neither mistake fails the build; both are only visible
     * by reading the generated POM.
     */
    // The `inspector-` prefix is dropped, or every name stutters: the artifact id already says
    // `inspector-core`, so `Inspector inspector-core` is what the original would have produced
    // had it ever interpolated. `Inspector core` and `Inspector noop-ui` are what a dependency
    // report wants beside the coordinate.
    val moduleLabel = "Inspector ${project.name.removePrefix("inspector-")}"

    // Publishing is opt-in per module: this configures whichever modules apply `maven-publish`
    // themselves, rather than listing them here. An allowlist in this file would be a second
    // place to keep current, and the failure mode is silent -- :inspector-daemon is a tool and
    // :sample:desktop is a demo, and neither should ever appear in the repository.
    pluginManager.withPlugin("maven-publish") {
        /*
         * A javadoc jar, because Maven Central rejects a publication without one.
         *
         * Kotlin has no javadoc, and Central checks only that the artifact is *present* -- an
         * empty jar satisfies it and is what most Kotlin libraries ship. Empty is also exactly
         * what a Dokka run that silently failed would produce, so this carries one line of text
         * instead: somebody who unzips it learns where the documentation actually is rather than
         * concluding the build dropped it. Dokka is the upgrade if real API docs are ever wanted;
         * it is a new toolchain across seven KMP modules, which is more than "the jar must exist"
         * is asking for.
         */
        val javadocStub = layout.buildDirectory.file("javadoc-jar/README.md")
        val writeJavadocStub = tasks.register("writeJavadocStub") {
            outputs.file(javadocStub)
            doLast {
                javadocStub.get().asFile.apply {
                    parentFile.mkdirs()
                    writeText(
                        "Inspector is written in Kotlin and ships no javadoc.\n\n" +
                            "Source, API notes and the integration guide:\n" +
                            "https://github.com/Shafichariri/kinspector\n"
                    )
                }
            }
        }
        val javadocJar = tasks.register<Jar>("javadocJar") {
            archiveClassifier = "javadoc"
            from(writeJavadocStub)
        }
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
                // Attached to every publication, including each target's: Central wants a
                // javadoc artifact beside every coordinate, not only the root one.
                artifact(javadocJar)

                pom {
                    // Computed in the subprojects body; see the note on `moduleLabel`.
                    name = moduleLabel
                    description = "Network debugger for Compose Multiplatform apps that use Ktor."
                    url = "https://github.com/Shafichariri/kinspector"
                    // Consumers' dependency scanners read this, not the LICENSE file, so an
                    // unlicensed-looking POM gets a library flagged inside companies that check.
                    licenses {
                        license {
                            name = "The Apache License, Version 2.0"
                            url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                        }
                    }
                    // Both required by Maven Central, and useful before it: a dependency report
                    // that cannot say who publishes a library or where its source is gets the
                    // library queried inside companies that check.
                    developers {
                        developer {
                            id = "Shafichariri"
                            name = "Chafic El Hariri"
                            url = "https://github.com/Shafichariri"
                        }
                    }
                    scm {
                        url = "https://github.com/Shafichariri/kinspector"
                        connection = "scm:git:https://github.com/Shafichariri/kinspector.git"
                        developerConnection = "scm:git:ssh://git@github.com/Shafichariri/kinspector.git"
                    }
                }
            }
        }

        /*
         * Signing, which Maven Central requires as a detached .asc beside every artifact.
         *
         * Wired to be completely inert without a key, and that is the load-bearing part. GitHub
         * Packages does not want signatures -- 1.0.0 went there unsigned -- so `build`,
         * `publishToMavenLocal` and the existing release job all have to keep working on a
         * machine that has never held a GPG key. Rather than declaring the signing tasks and
         * setting `isRequired = false`, no Sign task is created at all when no key is configured:
         * a task that exists and quietly signs nothing is the shape that ends with an unsigned
         * publication reaching Central and being rejected at the far end of a ten-minute job.
         *
         * The key is read in memory rather than from a keyring file. CI has no keyring, and a
         * secret that has to arrive as a file is one more moving part on the runner. Provide it
         * as the ASCII-armoured private key: `signingKey`/`signingPassword` in
         * ~/.gradle/gradle.properties locally, or SIGNING_KEY/SIGNING_PASSWORD in the
         * environment. Neither belongs in this repository.
         */
        // `filter` on blankness, not merely on presence. An unset repository secret arrives as an
        // *empty* environment variable rather than an absent one, so the obvious `isPresent`
        // check turns "no key configured" into "sign with the empty string" the moment a
        // workflow passes `${{ secrets.SIGNING_KEY }}` before the secret exists -- a failure
        // at publish time, in CI, on a tag, which is the worst place to discover it.
        val signingKey = providers.gradleProperty("signingKey")
            .orElse(providers.environmentVariable("SIGNING_KEY"))
            .filter { it.isNotBlank() }
        val signingPassword = providers.gradleProperty("signingPassword")
            .orElse(providers.environmentVariable("SIGNING_PASSWORD"))
            .filter { it.isNotBlank() }

        if (signingKey.isPresent) {
            apply(plugin = "signing")
            extensions.configure<SigningExtension> {
                useInMemoryPgpKeys(signingKey.get(), signingPassword.getOrElse(""))
                sign(extensions.getByType<PublishingExtension>().publications)
            }
            // Gradle does not infer this for a Kotlin Multiplatform publication: each publish
            // task consumes the matching Sign task's output without declaring the dependency, so
            // the build warns and, with a build cache or parallel execution, can publish before
            // the signature exists.
            tasks.withType<AbstractPublishToMaven>().configureEach {
                dependsOn(tasks.withType<Sign>())
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
