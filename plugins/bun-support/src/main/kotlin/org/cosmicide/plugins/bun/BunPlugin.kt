package org.cosmicide.plugins.bun

import org.cosmicide.plugin.api.CosmicPlugin
import org.cosmicide.plugin.api.PluginContext
import org.cosmicide.plugin.api.PluginSetupAction
import org.cosmicide.project.CommandExecutionService
import org.cosmicide.project.CommandRequest
import org.cosmicide.project.CommandResult
import org.cosmicide.project.IdeServices
import org.cosmicide.project.OperationMessageKind
import org.cosmicide.project.OperationReporter
import org.cosmicide.project.OperationUpdate
import org.cosmicide.project.PluginFormField
import org.cosmicide.project.PluginFormFieldType
import org.cosmicide.project.PluginFormOption
import org.cosmicide.project.Project
import org.cosmicide.project.ProjectCommand
import org.cosmicide.project.ProjectCommandKind
import org.cosmicide.project.ProjectCommandProvider
import org.cosmicide.project.ProjectCreationProvider
import org.cosmicide.project.ProjectCreationRequest
import org.cosmicide.project.ProjectCreationResult
import org.cosmicide.project.ProjectExtensionPoints
import org.cosmicide.project.ProjectTypeProvider
import java.io.File

class BunPlugin : CosmicPlugin {
    override val setupActions = listOf(
        PluginSetupAction(
            id = "org.cosmicide.plugins.bun.installToolchain",
            label = "Install Bun",
            command = INSTALL_COMMAND,
            description = "Install the Bun runtime via the official install script and upgrade to canary."
        )
    )

    override fun activate(context: PluginContext) {
        val commandService = context.services.require(IdeServices.COMMAND_EXECUTION)
        val owner = context.descriptor.id

        listOf(
            context.extensions.register(
                point = ProjectExtensionPoints.TYPE_PROVIDER,
                extension = BunProjectTypeProvider,
                ownerPluginId = owner,
                priority = 360
            ),
            context.extensions.register(
                point = ProjectExtensionPoints.CREATION_PROVIDER,
                extension = BunProjectCreationProvider(commandService),
                ownerPluginId = owner,
                priority = 360
            ),
            context.extensions.register(
                point = ProjectExtensionPoints.COMMAND_PROVIDER,
                extension = BunProjectCommandProvider,
                ownerPluginId = owner,
                priority = 360
            )
        ).forEach(context::registerDisposable)

        context.logger.info("Bun project creation and command support registered")
    }
}

private object BunProjectTypeProvider : ProjectTypeProvider {
    override val id = "org.cosmicide.plugins.bun.projectType"
    override val displayName = "Bun projects"
    override val description = "Recognizes Bun JavaScript/TypeScript projects"
    override val languageName = "JavaScript / TypeScript"
    override val fileExtension = "ts"

    override fun supports(projectRoot: File): Boolean {
        return projectRoot.resolve("package.json").isFile ||
                projectRoot.resolve("bun.lockb").isFile ||
                projectRoot.resolve("bun.lock").isFile
    }
}

private class BunProjectCreationProvider(
    private val commands: CommandExecutionService
) : ProjectCreationProvider {
    override val id = "org.cosmicide.plugins.bun.createProject"
    override val displayName = "New Bun project"
    override val description =
        "Create an empty Bun project or scaffold one using Vite, Next.js, React, Vue, Svelte, or a custom command."

    override val actionLabel = "Create"

    override val fields = listOf(
        PluginFormField(
            id = "name",
            label = "Project name",
            placeholder = "my-bun-app",
            required = true
        ),
        PluginFormField(
            id = "template",
            label = "Project template",
            type = PluginFormFieldType.CHOICE,
            defaultValue = "bun",
            options = listOf(
                PluginFormOption("bun", "Empty Bun project"),
                PluginFormOption("vite", "Vite (Vanilla TS/JS)"),
                PluginFormOption("react", "React (Vite)"),
                PluginFormOption("next", "Next.js"),
                PluginFormOption("vue", "Vue (Vite)"),
                PluginFormOption("svelte", "Svelte (Vite)"),
                PluginFormOption("custom", "Custom command")
            )
        ),
        PluginFormField(
            id = "command",
            label = "Create command",
            placeholder = "bun create next-app",
            description = "Optional. Used only when Project template is Custom command.",
            visibleWhen = mapOf("template" to "custom")
        )
    )

    override suspend fun create(
        request: ProjectCreationRequest,
        reporter: OperationReporter
    ): ProjectCreationResult {
        val name = request.values["name"].orEmpty().trim()
        require(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]*"))) {
            "Project name may contain letters, numbers, underscores, periods, and hyphens"
        }

        val projectsRoot = request.projectsDirectory.canonicalFile
        val root = projectsRoot.resolve(name).canonicalFile
        require(root.parentFile == projectsRoot) { "Invalid project name" }
        require(!root.exists()) { "A project with this name already exists" }

        val template = request.values["template"].orEmpty().ifBlank { "bun" }

        reporter.report(OperationUpdate("Scaffolding Bun project…\n"))

        val result = if (template == "bun") {
            createEmptyBunProject(root, reporter)
        } else {
            val commandParts = buildScaffoldCommand(template, name, request.values["command"])
            commands.execute(
                CommandRequest(
                    command = commandParts.first(),
                    arguments = commandParts.drop(1),
                    workingDirectory = projectsRoot
                )
            ) { output ->
                reporter.report(
                    OperationUpdate(
                        message = output,
                        kind = OperationMessageKind.OUTPUT
                    )
                )
            }
        }

        if (!result.successful) {
            if (root.exists()) root.deleteRecursively()
            error(
                result.output.lineSequence().lastOrNull { it.isNotBlank() }
                    ?: "Project scaffolding failed with exit code ${result.exitCode}"
            )
        }

        return ProjectCreationResult(
            project = BunProjectTypeProvider.project(root),
            message = "Bun project created successfully"
        )
    }

    private suspend fun createEmptyBunProject(
        root: File,
        reporter: OperationReporter
    ): CommandResult {
        check(root.mkdirs()) { "Failed to create directory ${root.absolutePath}" }
        return commands.execute(
            CommandRequest(
                command = "bun",
                arguments = listOf("init", "-y"),
                workingDirectory = root
            )
        ) { output ->
            reporter.report(
                OperationUpdate(
                    message = output,
                    kind = OperationMessageKind.OUTPUT
                )
            )
        }
    }

    private fun buildScaffoldCommand(
        template: String,
        name: String,
        customCommand: String?
    ): List<String> {
        return when (template) {
            "vite" -> listOf("bun", "create", "vite", name, "--", "--template", "vanilla-ts")
            "react" -> listOf("bun", "create", "vite", name, "--", "--template", "react-ts")
            "next" -> listOf("bun", "create", "next-app", name)
            "vue" -> listOf("bun", "create", "vite", name, "--", "--template", "vue-ts")
            "svelte" -> listOf("bun", "create", "vite", name, "--", "--template", "svelte-ts")
            "custom" -> {
                val raw = customCommand.orEmpty().trim()
                require(raw.isNotBlank()) { "Custom command cannot be empty" }
                parseCommandString(raw, name)
            }

            else -> error("Unsupported Bun template: $template")
        }
    }

    private fun parseCommandString(rawCommand: String, projectName: String): List<String> {
        val replaced = if (rawCommand.contains("{name}")) {
            rawCommand.replace("{name}", projectName)
        } else {
            "$rawCommand $projectName"
        }
        return replaced.split(Regex("\\s+"))
    }
}

private object BunProjectCommandProvider : ProjectCommandProvider {
    override val id = "org.cosmicide.plugins.bun.commands"
    override val displayName = "Bun commands"
    override val description =
        "bun install, bun dev, bun run build, bun start, and bun test commands"

    override fun commands(project: Project): List<ProjectCommand> {
        if (!BunProjectTypeProvider.supports(project.root)) return emptyList()

        return buildList {
            add(
                ProjectCommand(
                    id = "$id.install",
                    label = "bun install",
                    command = "bun install",
                    description = "Install project dependencies",
                    kind = ProjectCommandKind.SYNC
                )
            )
            add(
                ProjectCommand(
                    id = "$id.dev",
                    label = "bun dev",
                    command = "bun dev",
                    description = "Start development server",
                    kind = ProjectCommandKind.RUN
                )
            )
            add(
                ProjectCommand(
                    id = "$id.build",
                    label = "bun run build",
                    command = "bun run build",
                    description = "Build project for production",
                    kind = ProjectCommandKind.BUILD
                )
            )
            add(
                ProjectCommand(
                    id = "$id.start",
                    label = "bun start",
                    command = "bun start",
                    description = "Start production server",
                    kind = ProjectCommandKind.RUN
                )
            )
            add(
                ProjectCommand(
                    id = "$id.test",
                    label = "bun test",
                    command = "bun test",
                    description = "Run Bun tests",
                    kind = ProjectCommandKind.OTHER
                )
            )
        }
    }
}

private const val INSTALL_COMMAND =
    "curl -fsSL https://bun.com/install | bash && source ~/.bash_profile && bun upgrade --canary"