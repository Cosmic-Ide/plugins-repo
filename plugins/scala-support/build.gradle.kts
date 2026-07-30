plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "org.cosmicide.plugins.scala"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "org.cosmicide.plugins.scala"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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
    testImplementation(projects.ideApi)
    testImplementation(kotlin("stdlib"))
    testImplementation("junit:junit:4.13.2")
}

configurations.named("implementation") {
    withDependencies {
        removeIf {
            it.group == "org.jetbrains.kotlin" && it.name == "kotlin-stdlib"
        }
    }
}
