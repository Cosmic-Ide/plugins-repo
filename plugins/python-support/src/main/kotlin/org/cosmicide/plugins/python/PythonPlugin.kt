package org.cosmicide.plugins.python

import org.cosmicide.editor.EditorExtensionPoints
import org.cosmicide.editor.LspServerConnection
import org.cosmicide.editor.LspServerDefinition
import org.cosmicide.editor.LspServerProvider
import org.cosmicide.editor.LspServerRequest
import org.cosmicide.plugin.api.CosmicPlugin
import org.cosmicide.plugin.api.PluginContext
import org.cosmicide.plugin.api.PluginLogger
import org.cosmicide.plugin.api.PluginSetupAction
import org.cosmicide.project.CommandRequest
import org.cosmicide.project.IdeServices
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
import org.cosmicide.project.ToolProcessService
import java.io.File
import java.io.InputStream
import java.io.OutputStream

class PythonPlugin : CosmicPlugin {
    override val setupActions = listOf(
        PluginSetupAction(
            id = "org.cosmicide.plugins.python.installToolchain",
            label = "Install Python tools",
            command = PYTHON_INSTALL_COMMAND,
            description = "Install Python, python-lsp-server, Ruff, pytest, pip, and build."
        )
    )

    override fun activate(context: PluginContext) {
        val processService = context.services.require(IdeServices.TOOL_PROCESS)
        val owner = context.descriptor.id

        listOf(
            context.extensions.register(
                point = EditorExtensionPoints.LSP_SERVER_PROVIDER,
                extension = PythonLanguageServerProvider(processService, context.logger),
                ownerPluginId = owner,
                priority = 350
            ),
            context.extensions.register(
                point = ProjectExtensionPoints.TYPE_PROVIDER,
                extension = PythonProjectTypeProvider,
                ownerPluginId = owner,
                priority = 350
            ),
            context.extensions.register(
                point = ProjectExtensionPoints.CREATION_PROVIDER,
                extension = PythonProjectCreationProvider,
                ownerPluginId = owner,
                priority = 350
            ),
            context.extensions.register(
                point = ProjectExtensionPoints.COMMAND_PROVIDER,
                extension = PythonProjectCommandProvider,
                ownerPluginId = owner,
                priority = 350
            )
        ).forEach(context::registerDisposable)

        context.logger.info("Python language, project, and pylsp support registered")
    }
}

private class PythonLanguageServerProvider(
    private val processes: ToolProcessService,
    private val logger: PluginLogger
) : LspServerProvider {
    override val id = "org.cosmicide.plugins.python.pylsp"
    override val displayName = "Python language support"
    override val description = "Python editing powered by python-lsp-server"
    override val priority = 350

    override fun supports(request: LspServerRequest): Boolean {
        return request.extension.equals("py", ignoreCase = true) ||
            request.extension.equals("pyi", ignoreCase = true)
    }

    override fun createDefinition(request: LspServerRequest): LspServerDefinition {
        return LspServerDefinition(
            id = id,
            fileExtensions = PYTHON_FILE_EXTENSIONS,
            displayName = "python-lsp-server",
            connectionFactory = {
                PythonLanguageServerConnection(processes, it, logger)
            },
            textMateGrammarLink = PYTHON_TEXTMATE_GRAMMAR,
            enableInlayHints = true,
            enableSignatureHelp = true,
            initializationTimeoutMillis = 120_000
        )
    }
}

private class PythonLanguageServerConnection(
    private val processes: ToolProcessService,
    private val request: LspServerRequest,
    private val logger: PluginLogger
) : LspServerConnection {
    @Volatile
    private var process: Process? = null

    @Synchronized
    override fun start() {
        check(process == null) { "python-lsp-server connection has already started" }
        process = processes.start(
            CommandRequest(
                command = "python",
                arguments = listOf("-m", "pylsp"),
                workingDirectory = request.project.root,
                environment = mapOf(
                    "COSMIC_PROJECT_ROOT" to request.project.root.absolutePath
                )
            ),
            redirectErrorStream = false
        ).also { started ->
            drainStderr(started.errorStream)
            logger.info("python-lsp-server started for ${request.project.name}")
        }
    }

    override val outputStream: OutputStream
        get() = checkNotNull(process) { "python-lsp-server has not started" }.outputStream

    override val inputStream: InputStream
        get() = checkNotNull(process) { "python-lsp-server has not started" }.inputStream

    override val isClosed: Boolean
        get() = process?.isAlive != true

    @Synchronized
    override fun close() {
        val running = process ?: return
        process = null
        runCatching { running.outputStream.close() }
        runCatching { running.inputStream.close() }
        runCatching { running.errorStream.close() }
        if (running.isAlive) running.destroy()
    }

    private fun drainStderr(stderr: InputStream) {
        Thread {
            runCatching {
                stderr.bufferedReader().useLines { lines ->
                    lines.forEach(logger::debug)
                }
            }.onFailure {
                logger.warn("python-lsp-server stderr logger stopped", it)
            }
        }.apply {
            name = "Python-Language-Server-Stderr"
            isDaemon = true
            start()
        }
    }
}

private object PythonProjectTypeProvider : ProjectTypeProvider {
    override val id = "org.cosmicide.plugins.python.projectType"
    override val displayName = "Python projects"
    override val description = "Recognizes Python applications and packages"
    override val languageName = "Python"
    override val fileExtension = "py"

    override fun supports(projectRoot: File): Boolean {
        return PYTHON_PROJECT_MARKERS.any { projectRoot.resolve(it).exists() } ||
            projectRoot.listFiles()?.any {
                it.isFile && it.extension.equals("py", ignoreCase = true)
            } == true
    }
}

private object PythonProjectCreationProvider : ProjectCreationProvider {
    override val id = "org.cosmicide.plugins.python.createProject"
    override val displayName = "New Python project"
    override val description = "Create a Python application or installable package"
    override val actionLabel = "Create"

    override val fields = listOf(
        PluginFormField(
            id = "name",
            label = "Project directory",
            placeholder = "hello-python",
            required = true
        ),
        PluginFormField(
            id = "kind",
            label = "Project kind",
            type = PluginFormFieldType.CHOICE,
            defaultValue = "application",
            options = listOf(
                PluginFormOption("application", "Application"),
                PluginFormOption("package", "Package")
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

        val kind = request.values["kind"].orEmpty().ifBlank { "application" }
        require(kind == "application" || kind == "package") {
            "Unsupported Python project kind"
        }

        val projectsRoot = request.projectsDirectory.canonicalFile
        val root = projectsRoot.resolve(name).canonicalFile
        require(root.parentFile == projectsRoot) { "Invalid project directory" }
        require(!root.exists()) { "A project with this name already exists" }
        require(root.mkdirs()) { "Could not create the project directory" }

        try {
            reporter.report(OperationUpdate("Creating Python project…\n"))
            if (kind == "application") {
                root.resolve("main.py").writeText(PYTHON_APPLICATION_TEMPLATE)
            } else {
                createPackage(root, name)
            }
        } catch (error: Throwable) {
            root.deleteRecursively()
            throw error
        }

        return ProjectCreationResult(
            project = PythonProjectTypeProvider.project(root),
            message = "Python project created successfully"
        )
    }

    private fun createPackage(root: File, projectName: String) {
        val packageName = projectName.toPythonPackageName()
        val packageRoot = root.resolve("src").resolve(packageName)
        val testsRoot = root.resolve("tests")
        require(packageRoot.mkdirs()) { "Could not create the package source directory" }
        require(testsRoot.mkdirs()) { "Could not create the test directory" }

        root.resolve("pyproject.toml").writeText(
            PYPROJECT_TEMPLATE
                .replace("{{PROJECT_NAME}}", projectName)
                .replace("{{PACKAGE_NAME}}", packageName)
        )
        packageRoot.resolve("__init__.py").writeText(PYTHON_PACKAGE_TEMPLATE)
        testsRoot.resolve("test_$packageName.py").writeText(
            PYTHON_TEST_TEMPLATE.replace("{{PACKAGE_NAME}}", packageName)
        )
    }
}

private object PythonProjectCommandProvider : ProjectCommandProvider {
    override val id = "org.cosmicide.plugins.python.commands"
    override val displayName = "Python commands"
    override val description = "Run, test, format, lint, build, and sync Python projects"

    override fun commands(project: Project): List<ProjectCommand> {
        if (!PythonProjectTypeProvider.supports(project.root)) return emptyList()

        return buildList {
            findEntryPoint(project.root)?.let { entryPoint ->
                add(
                    command(
                        name = "run",
                        command = pythonCommand(entryPoint),
                        label = "Run $entryPoint",
                        description = "Run the Python application",
                        kind = ProjectCommandKind.RUN
                    )
                )
            }

            if (project.hasTests()) {
                add(
                    command(
                        name = "test",
                        command = pythonModuleCommand("pytest"),
                        label = "Run tests",
                        description = "Run the project test suite"
                    )
                )
            }

            add(
                command(
                    name = "format",
                    command = "ruff format .",
                    label = "Format project",
                    description = "Format Python sources with Ruff"
                )
            )
            add(
                command(
                    name = "lint",
                    command = "ruff check .",
                    label = "Lint project",
                    description = "Check Python sources with Ruff",
                    kind = ProjectCommandKind.BUILD
                )
            )

            if (project.isBuildablePackage()) {
                add(
                    command(
                        name = "build",
                        command = "python -m build",
                        label = "Build package",
                        description = "Build the package source distribution and wheel",
                        kind = ProjectCommandKind.BUILD
                    )
                )
            }

            val dependencyCommands = buildList {
                if (project.root.resolve("requirements.txt").isFile) {
                    add(
                        command(
                            name = "dependencies.runtime",
                            command = venvPipCommand("-r requirements.txt"),
                            label = "Install requirements",
                            description = "Install requirements.txt into the project virtual environment",
                            kind = ProjectCommandKind.SYNC
                        )
                    )
                }
                if (project.root.resolve("requirements-dev.txt").isFile) {
                    add(
                        command(
                            name = "dependencies.development",
                            command = venvPipCommand("-r requirements-dev.txt"),
                            label = "Install development requirements",
                            description = "Install requirements-dev.txt into the project virtual environment",
                            kind = ProjectCommandKind.SYNC
                        )
                    )
                }
                if (project.isBuildablePackage()) {
                    add(
                        command(
                            name = "dependencies.editable",
                            command = venvPipCommand("-e ."),
                            label = "Install package editable",
                            description = "Install this package into the project virtual environment",
                            kind = ProjectCommandKind.SYNC
                        )
                    )
                }
            }
            if (dependencyCommands.isNotEmpty()) {
                add(
                    ProjectCommand(
                        id = "$id.dependencies",
                        label = "Dependencies",
                        children = dependencyCommands
                    )
                )
            }
        }
    }

    private fun findEntryPoint(root: File): String? {
        return when {
            root.resolve("main.py").isFile -> "main.py"
            root.resolve("__main__.py").isFile -> "__main__.py"
            else -> null
        }
    }

    private fun pythonCommand(arguments: String): String {
        return "if [ -x .venv/bin/python ]; then .venv/bin/python $arguments; else python $arguments; fi"
    }

    private fun pythonModuleCommand(module: String): String {
        return pythonCommand("-m $module")
    }

    private fun venvPipCommand(arguments: String): String {
        return "python -m venv --system-site-packages .venv && " +
            ".venv/bin/python -m pip install $arguments"
    }

    private fun Project.hasTests(): Boolean {
        return root.resolve("tests").isDirectory ||
            root.resolve("pytest.ini").isFile ||
            root.resolve("conftest.py").isFile ||
            root.listFiles()?.any {
                it.isFile && it.name.startsWith("test_") && it.extension == "py"
            } == true
    }

    private fun Project.isBuildablePackage(): Boolean {
        if (root.resolve("setup.py").isFile) return true
        val pyproject = root.resolve("pyproject.toml")
        return pyproject.isFile &&
            runCatching { "[build-system]" in pyproject.readText() }.getOrDefault(false)
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

private fun String.toPythonPackageName(): String {
    val normalized = lowercase()
        .map { character ->
            if (character.isLetterOrDigit() || character == '_') character else '_'
        }
        .joinToString("")
        .trim('_')
    return normalized.takeIf { it.firstOrNull()?.isLetter() == true } ?: "package_name"
}

private val PYTHON_FILE_EXTENSIONS = setOf("py", "pyi")
private val PYTHON_PROJECT_MARKERS = setOf(
    "pyproject.toml",
    "requirements.txt",
    "requirements-dev.txt",
    "setup.py",
    "setup.cfg",
    "Pipfile",
    "poetry.lock"
)
private const val PYTHON_INSTALL_COMMAND =
    "pacman -S --needed python python-lsp-server python-lsp-black ruff python-pytest python-build python-pip"
private const val PYTHON_TEXTMATE_GRAMMAR =
    "https://raw.githubusercontent.com/microsoft/vscode/main/extensions/python/syntaxes/MagicPython.tmLanguage.json"
private val PYTHON_APPLICATION_TEMPLATE = """
    def main() -> None:
        print("Hello, Python!")


    if __name__ == "__main__":
        main()
""".trimIndent() + "\n"
private val PYPROJECT_TEMPLATE = """
    [build-system]
    requires = ["setuptools>=77"]
    build-backend = "setuptools.build_meta"

    [project]
    name = "{{PROJECT_NAME}}"
    version = "0.1.0"
    requires-python = ">=3.11"

    [tool.setuptools.packages.find]
    where = ["src"]
    include = ["{{PACKAGE_NAME}}*"]

    [tool.pytest.ini_options]
    pythonpath = ["src"]
""".trimIndent() + "\n"
private val PYTHON_PACKAGE_TEMPLATE = """
    def hello() -> str:
        return "Hello, Python!"
""".trimIndent() + "\n"
private val PYTHON_TEST_TEMPLATE = """
    from {{PACKAGE_NAME}} import hello


    def test_hello() -> None:
        assert hello() == "Hello, Python!"
""".trimIndent() + "\n"
