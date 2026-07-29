package org.cosmicide.plugins.go

import org.cosmicide.editor.EditorExtensionPoints
import org.cosmicide.editor.EditorLanguageProvider
import org.cosmicide.editor.EditorLanguageRequest
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.DefaultGrammarDefinition
import org.cosmicide.plugin.api.CosmicPlugin
import org.cosmicide.plugin.api.PluginContext
import org.cosmicide.plugin.api.PluginSetupAction
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
import org.eclipse.tm4e.core.registry.IGrammarSource
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL

class GoPlugin : CosmicPlugin {
    override val setupActions = listOf(
        PluginSetupAction(
            id = "org.cosmicide.plugins.go.installToolchain",
            label = "Install Go",
            command = GO_INSTALL_COMMAND,
            description = "Install Go in Cosmic's private environment."
        )
    )

    override fun activate(context: PluginContext) {
        val commandService = context.services.require(IdeServices.COMMAND_EXECUTION)
        val owner = context.descriptor.id

        listOf(
            context.extensions.register(
                point = EditorExtensionPoints.LANGUAGE_PROVIDER,
                extension = GoTextMateLanguageProvider,
                ownerPluginId = owner,
                priority = 350
            ),
            context.extensions.register(
                point = ProjectExtensionPoints.TYPE_PROVIDER,
                extension = GoProjectTypeProvider,
                ownerPluginId = owner,
                priority = 350
            ),
            context.extensions.register(
                point = ProjectExtensionPoints.CREATION_PROVIDER,
                extension = GoProjectCreationProvider(commandService),
                ownerPluginId = owner,
                priority = 350
            ),
            context.extensions.register(
                point = ProjectExtensionPoints.COMMAND_PROVIDER,
                extension = GoProjectCommandProvider,
                ownerPluginId = owner,
                priority = 350
            )
        ).forEach(context::registerDisposable)

        context.logger.info("Go syntax, module, and workspace support registered")
    }
}

private object GoTextMateLanguageProvider : EditorLanguageProvider {
    override val id = "org.cosmicide.plugins.go.textmate"
    override val displayName = "Go syntax highlighting"
    override val description = "Go syntax highlighting powered by a TextMate grammar"
    override val priority = 350

    override fun supports(request: EditorLanguageRequest): Boolean {
        return request.file.extension.equals("go", ignoreCase = true)
    }

    override fun configure(request: EditorLanguageRequest): Boolean {
        val cacheFile = request.editor.context.cacheDir
            .resolve(TEXTMATE_CACHE_DIRECTORY)
            .also(File::mkdirs)
            .resolve("go.tmLanguage.json")
        val grammarText = if (cacheFile.isFile) {
            cacheFile.readText()
        } else {
            downloadGrammar().also(cacheFile::writeText)
        }
        val grammarSource = IGrammarSource.fromString(
            IGrammarSource.ContentType.JSON,
            grammarText.removePrefix("\uFEFF")
        )
        val grammarDefinition = DefaultGrammarDefinition.withGrammarSource(
            grammarSource,
            GO_TEXTMATE_SCOPE,
            null
        )
        val themeRegistry = ThemeRegistry.getInstance()
        val grammarRegistry = GrammarRegistry(GrammarRegistry.getInstance()).apply {
            setTheme(themeRegistry.currentThemeModel)
        }
        request.editor.setEditorLanguage(
            TextMateLanguage.create(
                grammarDefinition,
                grammarRegistry,
                themeRegistry,
                false
            )
        )
        return true
    }

    private fun downloadGrammar(): String {
        val connection = URL(GO_TEXTMATE_GRAMMAR).openConnection().apply {
            connectTimeout = GRAMMAR_CONNECT_TIMEOUT_MILLIS
            readTimeout = GRAMMAR_READ_TIMEOUT_MILLIS
        }
        return connection.getInputStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var totalBytes = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                totalBytes += count
                require(totalBytes <= MAX_GRAMMAR_BYTES) {
                    "Go TextMate grammar is larger than the supported cache limit"
                }
                output.write(buffer, 0, count)
            }
            output.toString(Charsets.UTF_8.name()).also {
                require(it.isNotBlank()) { "Go TextMate grammar is empty" }
            }
        }
    }
}

private object GoProjectTypeProvider : ProjectTypeProvider {
    override val id = "org.cosmicide.plugins.go.projectType"
    override val displayName = "Go projects"
    override val description = "Recognizes Go modules and workspaces"
    override val languageName = "Go"
    override val fileExtension = "go"

    override fun supports(projectRoot: File): Boolean {
        return projectRoot.resolve("go.mod").isFile ||
            projectRoot.resolve("go.work").isFile
    }
}

private class GoProjectCreationProvider(
    private val commands: CommandExecutionService
) : ProjectCreationProvider {
    override val id = "org.cosmicide.plugins.go.createProject"
    override val displayName = "New Go project"
    override val description = "Create a Go module"
    override val actionLabel = "Create"

    override val fields = listOf(
        PluginFormField(
            id = "name",
            label = "Project directory",
            placeholder = "hello-go",
            required = true
        ),
        PluginFormField(
            id = "module",
            label = "Module path",
            description = "Usually the repository location, such as example.com/hello.",
            placeholder = "example.com/hello",
            required = true
        ),
        PluginFormField(
            id = "kind",
            label = "Project kind",
            type = PluginFormFieldType.CHOICE,
            defaultValue = "application",
            options = listOf(
                PluginFormOption("application", "Application"),
                PluginFormOption("library", "Library")
            )
        )
    )

    override suspend fun create(
        request: ProjectCreationRequest,
        reporter: OperationReporter
    ): ProjectCreationResult {
        val name = request.values["name"].orEmpty().trim()
        require(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*"))) {
            "Project directory may contain letters, numbers, dots, underscores, and hyphens"
        }

        val modulePath = request.values["module"].orEmpty().trim()
        require(
            modulePath.isNotEmpty() &&
                modulePath.none(Char::isWhitespace) &&
                !modulePath.startsWith("-")
        ) {
            "Module path must not be blank, contain whitespace, or start with '-'"
        }

        val kind = request.values["kind"].orEmpty().ifBlank { "application" }
        require(kind == "application" || kind == "library") {
            "Unsupported Go project kind"
        }

        val projectsRoot = request.projectsDirectory.canonicalFile
        val root = projectsRoot.resolve(name).canonicalFile
        require(root.parentFile == projectsRoot) { "Invalid project directory" }
        require(!root.exists()) { "A project with this name already exists" }
        require(root.mkdirs()) { "Could not create the project directory" }

        val result = try {
            reporter.report(OperationUpdate("Initializing Go module…\n"))
            commands.execute(
                CommandRequest(
                    command = "go",
                    arguments = listOf("mod", "init", modulePath),
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
        } catch (error: Throwable) {
            root.deleteRecursively()
            throw error
        }

        if (!result.successful) {
            root.deleteRecursively()
            error(
                result.output.lineSequence().lastOrNull { it.isNotBlank() }
                    ?: "go mod init failed with exit code ${result.exitCode}"
            )
        }

        try {
            if (kind == "application") {
                root.resolve("main.go").writeText(GO_APPLICATION_TEMPLATE)
            } else {
                root.resolve("${name.toGoPackageName()}.go").writeText(
                    GO_LIBRARY_TEMPLATE.replace("{{PACKAGE}}", name.toGoPackageName())
                )
            }
        } catch (error: Throwable) {
            root.deleteRecursively()
            throw error
        }

        return ProjectCreationResult(
            project = GoProjectTypeProvider.project(root),
            message = "Go project created successfully"
        )
    }
}

private object GoProjectCommandProvider : ProjectCommandProvider {
    override val id = "org.cosmicide.plugins.go.commands"
    override val displayName = "Go commands"
    override val description = "Build, run, test, format, and maintain Go projects"

    override fun commands(project: Project): List<ProjectCommand> {
        if (!GoProjectTypeProvider.supports(project.root)) return emptyList()
        if (!project.root.resolve("go.mod").isFile) {
            return listOf(
                command(
                    name = "work.sync",
                    command = "go work sync",
                    label = "Go workspace sync",
                    description = "Synchronize workspace module dependencies",
                    kind = ProjectCommandKind.SYNC
                )
            )
        }

        return buildList {
            add(
                command(
                    name = "build",
                    command = "go build ./...",
                    description = "Build all packages in the project",
                    kind = ProjectCommandKind.BUILD
                )
            )
            if (project.root.resolve("main.go").isFile) {
                add(
                    command(
                        name = "run",
                        command = "go run .",
                        description = "Compile and run the root package",
                        kind = ProjectCommandKind.RUN
                    )
                )
            }
            add(
                command(
                    name = "test",
                    command = "go test ./...",
                    description = "Test all packages"
                )
            )
            add(
                command(
                    name = "format",
                    command = "go fmt ./...",
                    description = "Format all packages"
                )
            )
            add(
                command(
                    name = "vet",
                    command = "go vet ./...",
                    description = "Report likely mistakes in all packages",
                    kind = ProjectCommandKind.BUILD
                )
            )
            add(
                ProjectCommand(
                    id = "$id.modules",
                    label = "Modules",
                    children = listOf(
                        command(
                            name = "mod.download",
                            command = "go mod download",
                            label = "Download dependencies",
                            description = "Download modules to the local cache",
                            kind = ProjectCommandKind.SYNC
                        ),
                        command(
                            name = "mod.tidy",
                            command = "go mod tidy",
                            label = "Tidy",
                            description = "Synchronize go.mod and go.sum with source imports"
                        ),
                        command(
                            name = "mod.verify",
                            command = "go mod verify",
                            label = "Verify",
                            description = "Verify cached module content"
                        ),
                        command(
                            name = "mod.graph",
                            command = "go mod graph",
                            label = "Show graph",
                            description = "Print the module dependency graph"
                        ),
                        command(
                            name = "mod.vendor",
                            command = "go mod vendor",
                            label = "Vendor",
                            description = "Create a vendor directory for dependencies"
                        )
                    )
                )
            )
            add(
                ProjectCommand(
                    id = "$id.testing",
                    label = "Testing",
                    children = listOf(
                        command(
                            name = "test.race",
                            command = "go test -race ./...",
                            label = "Test with race detector",
                            description = "Run tests with data-race detection"
                        ),
                        command(
                            name = "test.cover",
                            command = "go test -cover ./...",
                            label = "Test with coverage",
                            description = "Run tests and report statement coverage"
                        ),
                        command(
                            name = "test.bench",
                            command = "go test -bench=. ./...",
                            label = "Run benchmarks",
                            description = "Run all package benchmarks"
                        )
                    )
                )
            )
            add(
                ProjectCommand(
                    id = "$id.maintenance",
                    label = "Maintenance",
                    children = listOf(
                        command(
                            name = "generate",
                            command = "go generate ./...",
                            description = "Run source-code generators"
                        ),
                        command(
                            name = "install",
                            command = "go install ./...",
                            description = "Compile and install project packages"
                        ),
                        command(
                            name = "clean",
                            command = "go clean",
                            description = "Remove build artifacts"
                        ),
                        command(
                            name = "clean.cache",
                            command = "go clean -cache",
                            label = "Clean build cache",
                            description = "Remove all entries from the Go build cache"
                        ),
                        command(
                            name = "clean.testcache",
                            command = "go clean -testcache",
                            label = "Clean test cache",
                            description = "Expire all results in the Go test cache"
                        )
                    )
                )
            )
        }
    }

    private fun command(
        name: String,
        command: String,
        description: String,
        label: String = "Go $name",
        kind: ProjectCommandKind = ProjectCommandKind.OTHER
    ) = ProjectCommand(
        id = "$id.$name",
        label = label,
        command = command,
        description = description,
        kind = kind
    )
}

private fun String.toGoPackageName(): String {
    val normalized = lowercase()
        .map { character ->
            if (character.isLetterOrDigit() || character == '_') character else '_'
        }
        .joinToString("")
        .trim('_')
    return normalized.takeIf { it.firstOrNull()?.isLetter() == true } ?: "project"
}

private const val GO_INSTALL_COMMAND = "pacman -S --needed gcc-go"
private const val GO_TEXTMATE_GRAMMAR =
    "https://raw.githubusercontent.com/microsoft/vscode/main/extensions/go/syntaxes/go.tmLanguage.json"
private const val GO_TEXTMATE_SCOPE = "source.go"
private const val TEXTMATE_CACHE_DIRECTORY = "textmate-grammar-cache"
private const val MAX_GRAMMAR_BYTES = 5 * 1024 * 1024
private const val GRAMMAR_CONNECT_TIMEOUT_MILLIS = 15_000
private const val GRAMMAR_READ_TIMEOUT_MILLIS = 30_000
private val GO_APPLICATION_TEMPLATE = """
    package main

    import "fmt"

    func main() {
    	fmt.Println("Hello, Go!")
    }
""".trimIndent() + "\n"
private val GO_LIBRARY_TEMPLATE = """
    package {{PACKAGE}}
""".trimIndent() + "\n"
