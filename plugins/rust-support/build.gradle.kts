plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "org.cosmicide.plugins.rust"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "org.cosmicide.plugins.rust"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            vcsInfo.include = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    flavorDimensions += "environment"
    productFlavors {
        create("dev") {
            dimension = "environment"
        }
        create("prod") {
            dimension = "environment"
            isDefault = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    compileOnly(projects.ideApi)
    compileOnly(kotlin("stdlib"))
}

configurations.named("implementation") {
    withDependencies {
        removeIf {
            it.group == "org.jetbrains.kotlin" && it.name == "kotlin-stdlib"
        }
    }
}

tasks.register<Zip>("packageProdReleasePlugin") {
    group = "distribution"
    description = "Builds the installable Cosmic IDE Rust plugin bundle."
    dependsOn("assembleProdRelease")

    from(layout.buildDirectory.dir("outputs/apk/prod/release")) {
        include("*.apk")
        rename { "plugin.apk" }
    }
    from("src/main/plugin/plugin.json")

    archiveFileName.set("rust-support-1.0.0.zip")
    destinationDirectory.set(rootProject.layout.projectDirectory.dir("artifacts"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
