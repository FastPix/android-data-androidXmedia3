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
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://muxinc.jfrog.io/artifactory/default-maven-release-local")
        }
    }
}

rootProject.name = "Media3Data"
include(":app", ":media3", ":android-data-core")
project(":android-data-core").projectDir = file("android-data-core/android-data-core-sdk")

