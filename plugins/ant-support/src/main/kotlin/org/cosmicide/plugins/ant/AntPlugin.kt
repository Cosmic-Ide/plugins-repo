package org.cosmicide.plugins.ant

import org.cosmicide.plugin.api.CosmicPlugin
import org.cosmicide.plugin.api.PluginContext
import org.cosmicide.plugin.api.PluginSetupAction
import org.cosmicide.plugin.api.plugin
import org.cosmicide.plugin.api.register
import org.cosmicide.project.CommandExecutionService
import org.cosmicide.project.CommandRequest
import org.cosmicide.project.IdeServices
import org.cosmicide.project.Project
import org.cosmicide.project.ProjectCommand
import org.cosmicide.project.ProjectCommandKind
import org.cosmicide.project.ProjectCommandProvider
import org.cosmicide.project.ProjectExtensionPoints
import org.cosmicide.project.ProjectTask
import org.cosmicide.project.ProjectTaskProvider
import org.cosmicide.project.ProjectTypeProvider
import java.io.File

class AntPlugin : CosmicPlugin by plugin(
    id = "org.cosmicide.plugins.ant",
    setupActions = listOf(
        PluginSetupAction(
            id = "org.cosmicide.plugins.ant.installAnt",
            label = "Install Ant",
            command = ANT_INSTALL_COMMAND,
            description = "Install Apache Ant through the Android SDK package manager."
        )
    )
) {
    override fun activate(context: PluginContext) {
        val commands = context.services.require(IdeServices.COMMAND_EXECUTION)
        context.register(
            point = ProjectExtensionPoints.TYPE_PROVIDER,
            extension = AntProjectTypeProvider,
            priority = 350
        )
        context.register(
            point = ProjectExtensionPoints.COMMAND_PROVIDER,
            extension = AntProjectCommandProvider,
            priority = 350
        )
        context.register(
            point = ProjectExtensionPoints.TASK_PROVIDER,
            extension = AntProjectTaskProvider(commands),
            priority = 350
        )
        context.logger.info("Ant project support registered")
    }
}

private object AntProjectTypeProvider : ProjectTypeProvider {
    override val id = "org.cosmicide.plugins.ant.projectType"
    override val displayName = "Ant projects"
    override val description = "Recognizes projects containing build.xml"
    override val languageName = "Java"
    override val fileExtension = "xml"

    override fun supports(projectRoot: File): Boolean =
        projectRoot.resolve("build.xml").isFile
}

private object AntProjectCommandProvider : ProjectCommandProvider {
    override val id = "org.cosmicide.plugins.ant.commands"
    override val displayName = "Ant commands"
    override val description = "Build, test, and clean Ant projects"

    override fun commands(project: Project): List<ProjectCommand> {
        if (!AntProjectTypeProvider.supports(project.root)) return emptyList()
        return listOf(
            command(
                "build",
                "ant build",
                "Build",
                "Compile and package the Ant project",
                ProjectCommandKind.BUILD
            ),
            command("test", "ant test", "Test", "Run the Ant test target"),
            command("clean", "ant clean", "Clean", "Remove Ant build outputs")
        )
    }

    private fun command(
        name: String,
        command: String,
        label: String,
        description: String,
        kind: ProjectCommandKind = ProjectCommandKind.OTHER
    ) = ProjectCommand(
        id = "$id.$name",
        label = label,
        command = command,
        description = description,
        kind = kind
    )
}

private class AntProjectTaskProvider(
    private val commands: CommandExecutionService
) : ProjectTaskProvider {
    override val id = "org.cosmicide.plugins.ant.tasks"
    override val displayName = "Ant targets"
    override val description = "Targets discovered from the Ant build file"

    override fun supports(project: Project): Boolean =
        AntProjectTypeProvider.supports(project.root)

    override suspend fun tasks(project: Project): List<ProjectTask> {
        val root = project.root.canonicalFile
        val result = commands.execute(
            CommandRequest(
                command = "ant",
                arguments = listOf("-p"),
                workingDirectory = root
            )
        ) { }
        val targets = if (result.successful) parseAntTargets(result.output) else emptyList()
        return targets.map { target ->
            ProjectTask(
                id = "$id.${target.commandId()}",
                label = target,
                command = "ant $target",
                description = "Run Ant target $target",
                group = "Targets"
            )
        }
    }
}

private fun parseAntTargets(output: String): List<String> =
    output.lineSequence()
        .dropWhile { !it.trim().equals("Main targets:", ignoreCase = true) }
        .drop(1)
        .map { TARGET_LINE_REGEX.matchEntire(it.trim())?.groupValues?.get(1) }
        .filterNotNull()
        .filterNot { it == "Default target:" }
        .distinct()
        .toList()

private fun String.commandId(): String =
    replace(Regex("[^A-Za-z0-9_.-]"), "_")

private val ANT_INSTALL_COMMAND = $$"""
    set -e
    SDKMANAGER="$(command -v sdkmanager || true)"
    if [ -z "$SDKMANAGER" ]; then
        SDKMANAGER="${ANDROID_HOME:-$HOME/Android/sdk}/cmdline-tools/latest/bin/sdkmanager"
    fi
    if [ ! -x "$SDKMANAGER" ]; then
        SDKMANAGER="${ANDROID_HOME:-$HOME/Android/sdk}/tools/bin/sdkmanager"
    fi
    if [ ! -x "$SDKMANAGER" ]; then
        echo "sdkmanager not found. Install Android Command Line Tools first." >&2
        exit 1
    fi
    yes | "$SDKMANAGER" --licenses >/dev/null || true
    "$SDKMANAGER" "ant"
""".trimIndent()

private val TARGET_LINE_REGEX = Regex("""^([A-Za-z0-9_.-]+)\s*(?:.*)$""")
