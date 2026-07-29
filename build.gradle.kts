import org.cosmicide.buildlogic.CosmicPluginPackagingPlugin

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlinx.serialization) apply false
}

subprojects {
    if (path.startsWith(":plugins:")) {
        apply<CosmicPluginPackagingPlugin>()
    }
}
