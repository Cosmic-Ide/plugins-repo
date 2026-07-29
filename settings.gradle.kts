enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
        maven("https://s01.oss.sonatype.org/content/repositories/snapshots")
    }
}

rootProject.name = "CosmicIDEPlugins"

val cosmicIdeDir: File = providers.gradleProperty("cosmicIdeDir")
    .orElse(providers.environmentVariable("COSMIC_IDE_DIR"))
    .orElse("../Cosmic-IDE")
    .get()
    .let(::file)
    .canonicalFile

require(cosmicIdeDir.resolve("plugin-api").isDirectory) {
    "Cosmic IDE checkout not found at $cosmicIdeDir. Set -PcosmicIdeDir=/path/to/Cosmic-IDE."
}

include(":plugin-api")
project(":plugin-api").projectDir = cosmicIdeDir.resolve("plugin-api")

include(":common")
project(":common").projectDir = cosmicIdeDir.resolve("common")

include(":feature:project")
project(":feature").projectDir = cosmicIdeDir.resolve("feature")
project(":feature:project").projectDir = cosmicIdeDir.resolve("feature/project")

include(":ide-api")
project(":ide-api").projectDir = cosmicIdeDir.resolve("ide-api")

include(":plugins:rust-support")
include(":plugins:gleam-support")
include(":plugins:go-support")
include(":plugins:lua-support")
include(":plugins:python-support")
