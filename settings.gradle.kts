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
        maven("https://central.sonatype.com/repository/maven-snapshots/")
    }
}

rootProject.name = "CosmicIDEPlugins"

include(":plugins:java-support")
include(":plugins:rust-support")
include(":plugins:gleam-support")
include(":plugins:go-support")
include(":plugins:gradle-support")
include(":plugins:lua-support")
include(":plugins:python-support")
include(":plugins:clangd-support")
include(":plugins:cmake-support")
include(":plugins:maven-support")
include(":plugins:scala-support")
include(":plugins:web-support")
include(":plugins:md-support")
include(":plugins:json-support")
include(":plugins:bun-support")
include(":plugins:vue-support")
include(":plugins:android-support")
include(":plugins:swift-support")
include(":plugins:ant-support")
include(":plugins:zig-support")
include(":plugins:diff-support")
