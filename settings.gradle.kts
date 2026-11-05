pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Yomukai"
// The Docker image builds only the server: it has no Android SDK.
if (System.getenv("YOMUKAI_SERVER_ONLY") != "true") include(":app")
include(":shared")
include(":server")
include(":worker")
