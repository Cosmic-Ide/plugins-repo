package org.cosmicide.plugins.ant

import org.cosmicide.plugin.api.CosmicPlugin
import org.cosmicide.plugin.api.PluginContext
import org.cosmicide.plugin.api.PluginSetupAction
import org.cosmicide.plugin.api.plugin
import org.cosmicide.plugin.api.register
import org.cosmicide.project.CommandExecutionService
import org.cosmicide.project.CommandRequest
import org.cosmicide.project.IdeServices
import org.cosmicide.project.PluginFormField
import org.cosmicide.project.ProjectCreationProvider
import org.cosmicide.project.ProjectCreationRequest
import org.cosmicide.project.ProjectCreationResult
import org.cosmicide.project.OperationReporter
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
            description = "Install Java and Apache Ant through SDKMAN."
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
            point = ProjectExtensionPoints.CREATION_PROVIDER,
            extension = AntProjectCreationProvider,
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

private object AntProjectCreationProvider : ProjectCreationProvider {
    override val id = "org.cosmicide.plugins.ant.createProject"
    override val displayName = "New Ant project"
    override val description = "Create a Java application with an Ant build file"
    override val actionLabel = "Create"
    override val fields = listOf(
        PluginFormField(
            id = "name",
            label = "Project name",
            placeholder = "hello-ant",
            required = true
        )
    )

    override suspend fun create(
        request: ProjectCreationRequest,
        reporter: OperationReporter
    ): ProjectCreationResult {
        val name = request.values["name"].orEmpty().trim()
        require(name.matches(PROJECT_NAME_REGEX)) {
            "Project name may contain letters, numbers, underscores, and hyphens"
        }

        val projectsRoot = request.projectsDirectory.canonicalFile
        val root = projectsRoot.resolve(name).canonicalFile
        require(root.parentFile == projectsRoot) { "Invalid project name" }
        require(!root.exists()) { "A project with this name already exists" }

        reporter.report(org.cosmicide.project.OperationUpdate("Creating Ant project…\n"))
        try {
            check(root.mkdirs()) { "Could not create the project directory" }
            root.resolve("src/main/java").mkdirs()
            root.resolve("src/main/java/Main.java").writeText(ANT_MAIN_SOURCE)
            root.resolve("build.xml").writeText(antBuildFile(name))
            root.resolve(".gitignore").writeText("build/\ndist/\n")
        } catch (failure: Throwable) {
            if (root.exists()) root.deleteRecursively()
            throw failure
        }

        return ProjectCreationResult(
            project = AntProjectTypeProvider.project(root),
            message = "Ant project created successfully"
        )
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
    if [ ! -f "$HOME/.sdkman/bin/sdkman-init.sh" ]; then
        curl -s "https://get.sdkman.io" | bash
    fi
    . "$HOME/.sdkman/bin/sdkman-init.sh"
    sdk install java
    sdk install ant
""".trimIndent()

private val TARGET_LINE_REGEX = Regex("""^([A-Za-z0-9_.-]+)\s*(?:.*)$""")
private val PROJECT_NAME_REGEX = Regex("[A-Za-z0-9][A-Za-z0-9_-]*")
private val ANT_MAIN_SOURCE = """
    public final class Main {
        private Main() {}

        public static void main(String[] args) {
            System.out.println("Hello from Ant!");
        }
    }
""".trimIndent() + "\n"

private fun antBuildFile(projectName: String) = $$"""
    <?xml version="1.0" encoding="UTF-8"?>
    <project name="$$projectName" default="jar" basedir=".">
        <property name="src.dir" value="src/main/java"/>
        <property name="build.dir" value="build"/>
        <property name="classes.dir" value="${build.dir}/classes"/>
        <property name="dist.dir" value="dist"/>

        <target name="clean">
            <delete dir="${build.dir}"/>
            <delete dir="${dist.dir}"/>
        </target>

        <target name="compile">
            <mkdir dir="${classes.dir}"/>
            <javac srcdir="${src.dir}" destdir="${classes.dir}" includeantruntime="false"/>
        </target>

        <target name="test" depends="compile"/>

        <target name="jar" depends="compile">
            <mkdir dir="${dist.dir}"/>
            <jar destfile="${dist.dir}/$projectName.jar" basedir="${classes.dir}"/>
        </target>
    </project>
""".trimIndent() + "\n"
