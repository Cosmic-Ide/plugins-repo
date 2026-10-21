package org.cosmicide.plugins.swift

import org.cosmicide.editor.registerLspServer
import org.cosmicide.plugin.api.CosmicPlugin
import org.cosmicide.plugin.api.PluginContext
import org.cosmicide.plugin.api.PluginSetupAction
import org.cosmicide.plugin.api.plugin
import org.cosmicide.plugin.api.register
import org.cosmicide.project.CommandExecutionService
import org.cosmicide.project.CommandRequest
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

class SwiftPlugin : CosmicPlugin by plugin(
    id = "org.cosmicide.plugins.swift",
    setupActions = listOf(
        PluginSetupAction(
            id = "org.cosmicide.plugins.swift.installToolchain",
            label = "Install Swift toolchain",
            command = SWIFT_INSTALL_COMMAND,
            description = "Install Swift dependencies (libxml2-legacy, libncurses), swiftly, and the latest Swift toolchain."
        )
    )
) {
    override fun activate(context: PluginContext) {
        val commandService = context.services.require(IdeServices.COMMAND_EXECUTION)

        context.registerLspServer(
            id = "org.cosmicide.plugins.swift.lsp",
            displayName = "Swift language support"
        ) {
            command = "sourcekit-lsp"
            registerGrammar(SWIFT_TEXTMATE_GRAMMAR, "swift")
        }

        context.register(
            point = ProjectExtensionPoints.TYPE_PROVIDER,
            extension = SwiftProjectTypeProvider,
            priority = 350
        )
        context.register(
            point = ProjectExtensionPoints.CREATION_PROVIDER,
            extension = SwiftProjectCreationProvider(commandService),
            priority = 350
        )
        context.register(
            point = ProjectExtensionPoints.COMMAND_PROVIDER,
            extension = SwiftProjectCommandProvider,
            priority = 350
        )

        context.logger.info("Swift language and Package Manager support registered")
    }
}

private object SwiftProjectTypeProvider : ProjectTypeProvider {
    override val id = "org.cosmicide.plugins.swift.projectType"
    override val displayName = "Swift packages"
    override val description = "Recognizes Swift packages with Package.swift"
    override val languageName = "Swift"
    override val fileExtension = "swift"

    override fun supports(projectRoot: File): Boolean {
        return projectRoot.resolve("Package.swift").isFile
    }
}

private class SwiftProjectCreationProvider(
    private val commands: CommandExecutionService
) : ProjectCreationProvider {
    override val id = "org.cosmicide.plugins.swift.createProject"
    override val displayName = "New Swift package"
    override val description = "Create a Swift executable or library package"
    override val actionLabel = "Create"

    override val fields = listOf(
        PluginFormField(
            id = "name",
            label = "Package name",
            placeholder = "MySwiftApp",
            required = true
        ),
        PluginFormField(
            id = "type",
            label = "Package type",
            type = PluginFormFieldType.CHOICE,
            defaultValue = "executable",
            options = listOf(
                PluginFormOption("executable", "Executable (Application)"),
                PluginFormOption("library", "Library")
            )
        )
    )

    override suspend fun create(
        request: ProjectCreationRequest,
        reporter: OperationReporter
    ): ProjectCreationResult {
        val name = request.values["name"].orEmpty().trim()
        require(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]*"))) {
            "Package name may contain letters, numbers, underscores, and hyphens"
        }

        val projectsRoot = request.projectsDirectory.canonicalFile
        val root = projectsRoot.resolve(name).canonicalFile
        require(root.parentFile == projectsRoot) { "Invalid package name" }
        require(!root.exists()) { "A project with this name already exists" }

        val type = request.values["type"].orEmpty().ifBlank { "executable" }
        require(type == "executable" || type == "library") { "Unsupported package type" }

        root.mkdirs()
        reporter.report(OperationUpdate("Creating Swift package…\n"))
        val result = commands.execute(
            CommandRequest(
                command = "swift",
                arguments = listOf("package", "init", "--name", name, "--type", type),
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

        if (!result.successful) {
            if (root.exists()) root.deleteRecursively()
            error(
                result.output.lineSequence().lastOrNull { it.isNotBlank() }
                    ?: "swift package init failed with exit code ${result.exitCode}"
            )
        }

        return ProjectCreationResult(
            project = SwiftProjectTypeProvider.project(root),
            message = "Swift package created successfully"
        )
    }
}

private object SwiftProjectCommandProvider : ProjectCommandProvider {
    override val id = "org.cosmicide.plugins.swift.commands"
    override val displayName = "Swift commands"
    override val description = "Swift build, run, test, and package management commands"

    override fun commands(project: Project): List<ProjectCommand> {
        if (!SwiftProjectTypeProvider.supports(project.root)) return emptyList()

        return buildList {
            add(
                ProjectCommand(
                    id = "$id.build",
                    label = "Swift build",
                    command = "swift build",
                    description = "Build the Swift package",
                    kind = ProjectCommandKind.BUILD
                )
            )
            add(
                ProjectCommand(
                    id = "$id.run",
                    label = "Swift run",
                    command = "swift run",
                    description = "Build and run the Swift executable",
                    kind = ProjectCommandKind.RUN
                )
            )
            add(
                ProjectCommand(
                    id = "$id.test",
                    label = "Swift test",
                    command = "swift test",
                    description = "Run package unit tests",
                    kind = ProjectCommandKind.OTHER
                )
            )
            add(
                ProjectCommand(
                    id = "$id.package",
                    label = "Package Manager",
                    children = listOf(
                        ProjectCommand(
                            id = "$id.package.resolve",
                            label = "Resolve dependencies",
                            command = "swift package resolve",
                            description = "Resolve and fetch package dependencies",
                            kind = ProjectCommandKind.SYNC
                        ),
                        ProjectCommand(
                            id = "$id.package.update",
                            label = "Update dependencies",
                            command = "swift package update",
                            description = "Update package dependencies to newest compatible versions",
                            kind = ProjectCommandKind.SYNC
                        ),
                        ProjectCommand(
                            id = "$id.package.clean",
                            label = "Clean build artifacts",
                            command = "swift package clean",
                            description = "Delete build artifacts"
                        ),
                        ProjectCommand(
                            id = "$id.package.reset",
                            label = "Reset package",
                            command = "swift package reset",
                            description = "Reset the complete cache and build directory"
                        )
                    )
                )
            )
        }
    }
}

private const val SWIFT_INSTALL_COMMAND =
    "pacman -S --needed --noconfirm libxml2-legacy curl && " +
            "curl -sL https://download.swift.org/swiftly/linux/swiftly-\$(uname -m).tar.gz | tar -xzf - -C /usr/bin swiftly && " +
            "swiftly init -y && " +
            "swiftly install latest -y"

private const val SWIFT_TEXTMATE_GRAMMAR =
    "https://raw.githubusercontent.com/microsoft/vscode/main/extensions/swift/syntaxes/swift.tmLanguage.json"
