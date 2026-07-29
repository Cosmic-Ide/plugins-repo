plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    google()
    mavenCentral()
}

gradlePlugin {
    plugins {
        create("cosmicPluginPackaging") {
            id = "org.cosmicide.plugin-packaging"
            implementationClass = "org.cosmicide.buildlogic.CosmicPluginPackagingPlugin"
        }
    }
}
