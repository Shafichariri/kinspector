pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "inspector"

include(":inspector-model")
include(":inspector-core")
include(":inspector-noop")
include(":inspector-ui")
include(":inspector-noop-ui")
include(":inspector-stream")
include(":inspector-noop-stream")
include(":inspector-daemon")
include(":sample:desktop")
include(":sample:android")
