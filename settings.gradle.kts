pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // auto-provisions the JDK toolchain (see gradle/libs.versions.toml: java) if it is not installed locally
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "misarch-kotlin"

include(":libs:common")

// Every directory services/<name> that contains a build.gradle.kts becomes the project :services:<name>.
// (No central list: port agents add their service without editing this file.)
file("services").listFiles()
    ?.filter { it.isDirectory && it.resolve("build.gradle.kts").exists() }
    ?.sortedBy { it.name }
    ?.forEach { include(":services:${it.name}") }
