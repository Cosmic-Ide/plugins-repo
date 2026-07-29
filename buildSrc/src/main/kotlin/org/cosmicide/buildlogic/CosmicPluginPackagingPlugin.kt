package org.cosmicide.buildlogic

import java.security.MessageDigest
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.bundling.Zip

private const val PLUGIN_MANIFEST_PATH = "src/main/plugin/plugin.json"

abstract class UpdatePluginSha256 : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val bundle: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val pluginManifest: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val pluginIndex: RegularFileProperty

    @TaskAction
    fun updateSha256() {
        val bundleFile = bundle.get().asFile
        val manifestFile = pluginManifest.get().asFile
        val indexFile = pluginIndex.get().asFile
        val pluginId = manifestFile.readText().jsonString("id")

        require(bundleFile.isFile) {
            "Plugin bundle does not exist: ${bundleFile.absolutePath}"
        }
        require(indexFile.isFile) {
            "Plugin index does not exist: ${indexFile.absolutePath}"
        }

        val sha256 = MessageDigest.getInstance("SHA-256")
            .digest(bundleFile.readBytes())
            .joinToString("") { byte -> "%02x".format(byte) }
        val indexText = indexFile.readText()
        val pluginStart = indexText.indexOf("\"id\": \"$pluginId\"")
        require(pluginStart >= 0) {
            "Plugin '$pluginId' is missing from plugins.json"
        }

        val nextPlugin = indexText.indexOf("\"id\":", pluginStart + pluginId.length)
            .takeIf { it >= 0 }
            ?: indexText.length
        val shaPrefix = "\"sha256\": \""
        val shaStart = indexText.indexOf(shaPrefix, pluginStart)
        require(shaStart in pluginStart until nextPlugin) {
            "Plugin '$pluginId' has no sha256 field in plugins.json"
        }

        val valueStart = shaStart + shaPrefix.length
        val valueEnd = indexText.indexOf('"', valueStart)
        require(valueEnd in (valueStart + 1)..nextPlugin) {
            "Plugin '$pluginId' has an invalid sha256 field in plugins.json"
        }

        val currentSha256 = indexText.substring(valueStart, valueEnd)
        if (currentSha256 == sha256) {
            logger.lifecycle("$pluginId SHA-256 is already up to date")
        } else {
            indexFile.writeText(indexText.replaceRange(valueStart, valueEnd, sha256))
            logger.lifecycle("Updated $pluginId SHA-256 to $sha256")
        }
    }
}

class CosmicPluginPackagingPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val pluginProjectName = project.name
        val manifest = project.layout.projectDirectory.file(PLUGIN_MANIFEST_PATH)
        val manifestText = project.providers.fileContents(manifest).asText
        val apkDirectory = project.layout.buildDirectory.dir("outputs/apk/prod/release")
        val artifactsDirectory =
            project.rootProject.layout.projectDirectory.dir("artifacts")
        val pluginIndexFile =
            project.rootProject.layout.projectDirectory.file("plugins.json")
        val bundleName = manifestText.map { text ->
            "$pluginProjectName-${text.jsonString("version")}.zip"
        }

        val packagePlugin = project.tasks.register(
            "packageProdReleasePlugin",
            Zip::class.java
        ) {
            group = "distribution"
            description = "Builds the installable Cosmic IDE plugin bundle."
            dependsOn("assembleProdRelease")

            from(apkDirectory) {
                include("*.apk")
                rename { "plugin.apk" }
            }
            from(manifest)

            archiveFileName.set(bundleName)
            destinationDirectory.set(artifactsDirectory)
            isPreserveFileTimestamps = false
            isReproducibleFileOrder = true
        }

        val updateSha256 = project.tasks.register(
            "updatePluginSha256",
            UpdatePluginSha256::class.java
        ) {
            group = "distribution"
            description = "Updates this plugin's SHA-256 in plugins.json."
            mustRunAfter(packagePlugin)
            bundle.set(packagePlugin.flatMap { it.archiveFile })
            pluginManifest.set(manifest)
            pluginIndex.set(pluginIndexFile)
        }

        packagePlugin.configure {
            finalizedBy(updateSha256)
        }
    }
}

private fun String.jsonString(key: String): String {
    val value = Regex(""""${Regex.escape(key)}"\s*:\s*"([^"]+)"""")
        .find(this)
        ?.groupValues
        ?.get(1)
    require(!value.isNullOrBlank()) {
        "Plugin manifest has no non-blank '$key' string"
    }
    return value
}
