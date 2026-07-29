package org.cosmicide.plugins.cmake

import org.cosmicide.plugin.api.CosmicPlugin
import org.cosmicide.plugin.api.PluginContext
import org.cosmicide.plugin.api.PluginSetupAction
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

class CMakePlugin : CosmicPlugin {
    override val setupActions = listOf(
        PluginSetupAction(
            id = "org.cosmicide.plugins.cmake.installTools",
            label = "Install CMake tools",
            command = CMAKE_INSTALL_COMMAND,
            description = "Install CMake and Make."
        )
    )

    override fun activate(context: PluginContext) {
        val owner = context.descriptor.id

        listOf(
            context.extensions.register(
                point = ProjectExtensionPoints.TYPE_PROVIDER,
                extension = CMakeProjectTypeProvider,
                ownerPluginId = owner,
                priority = 350
            ),
            context.extensions.register(
                point = ProjectExtensionPoints.CREATION_PROVIDER,
                extension = CMakeProjectCreationProvider,
                ownerPluginId = owner,
                priority = 350
            ),
            context.extensions.register(
                point = ProjectExtensionPoints.COMMAND_PROVIDER,
                extension = CMakeProjectCommandProvider,
                ownerPluginId = owner,
                priority = 350
            )
        ).forEach(context::registerDisposable)

        context.logger.info("CMake project support registered")
    }
}

private object CMakeProjectTypeProvider : ProjectTypeProvider {
    override val id = "org.cosmicide.plugins.cmake.projectType"
    override val displayName = "CMake projects"
    override val description = "Recognizes projects containing CMakeLists.txt"
    override val languageName = "CMake"
    override val fileExtension = "cmake"

    override fun supports(projectRoot: File): Boolean {
        return projectRoot.resolve("CMakeLists.txt").isFile
    }
}

private object CMakeProjectCreationProvider : ProjectCreationProvider {
    override val id = "org.cosmicide.plugins.cmake.createProject"
    override val displayName = "New CMake project"
    override val description = "Create a C or C++ CMake project"
    override val actionLabel = "Create"

    override val fields = listOf(
        PluginFormField(
            id = "name",
            label = "Project name",
            placeholder = "hello-cmake",
            required = true
        ),
        PluginFormField(
            id = "language",
            label = "Language",
            type = PluginFormFieldType.CHOICE,
            defaultValue = "cpp",
            options = listOf(
                PluginFormOption("cpp", "C++"),
                PluginFormOption("c", "C")
            )
        ),
        PluginFormField(
            id = "kind",
            label = "Target type",
            type = PluginFormFieldType.CHOICE,
            defaultValue = "executable",
            options = listOf(
                PluginFormOption("executable", "Executable"),
                PluginFormOption("library", "Static library")
            )
        )
    )

    override suspend fun create(
        request: ProjectCreationRequest,
        reporter: OperationReporter
    ): ProjectCreationResult {
        val name = request.values["name"].orEmpty().trim()
        require(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]*"))) {
            "Project name may contain letters, numbers, underscores, and hyphens"
        }

        val language = request.values["language"].orEmpty().ifBlank { "cpp" }
        require(language == "c" || language == "cpp") {
            "Unsupported CMake project language"
        }

        val kind = request.values["kind"].orEmpty().ifBlank { "executable" }
        require(kind == "executable" || kind == "library") {
            "Unsupported CMake target type"
        }

        val projectsRoot = request.projectsDirectory.canonicalFile
        val root = projectsRoot.resolve(name).canonicalFile
        require(root.parentFile == projectsRoot) { "Invalid project name" }
        require(!root.exists()) { "A project with this name already exists" }

        reporter.report(OperationUpdate("Creating CMake project…\n"))
        try {
            check(root.mkdirs()) { "Could not create the project directory" }

            val sourceExtension = if (language == "c") "c" else "cpp"
            val languageName = if (language == "c") "C" else "CXX"
            val sourceFile = if (kind == "executable") {
                "src/main.$sourceExtension"
            } else {
                "src/$name.$sourceExtension"
            }
            val targetCommand = if (kind == "executable") {
                "add_executable($name $sourceFile)"
            } else {
                """
                    add_library($name STATIC $sourceFile)
                    target_include_directories($name PUBLIC include)
                """.trimIndent()
            }

            root.resolve("src").mkdirs()
            root.resolve("CMakeLists.txt").writeText(
                cmakeLists(name, languageName, targetCommand)
            )
            root.resolve(sourceFile).writeText(
                sourceTemplate(name, language, kind)
            )

            if (kind == "library") {
                root.resolve("include").mkdirs()
                root.resolve("include/$name.h").writeText(headerTemplate(name))
            }

            root.resolve(".gitignore").writeText("build/\ncompile_commands.json\n")
        } catch (failure: Throwable) {
            if (root.exists()) root.deleteRecursively()
            throw failure
        }

        return ProjectCreationResult(
            project = CMakeProjectTypeProvider.project(root),
            message = "CMake project created successfully"
        )
    }

    private fun cmakeLists(
        name: String,
        language: String,
        targetCommand: String
    ): String = """
        cmake_minimum_required(VERSION 3.16)
        project($name VERSION 0.1.0 LANGUAGES $language)

        set(CMAKE_EXPORT_COMPILE_COMMANDS ON)

        $targetCommand
    """.trimIndent() + "\n"

    private fun sourceTemplate(
        name: String,
        language: String,
        kind: String
    ): String {
        if (kind == "library") {
            return """
                #include "$name.h"

                int ${identifier(name)}_answer(void) {
                    return 42;
                }
            """.trimIndent() + "\n"
        }

        return if (language == "c") {
            """
                #include <stdio.h>

                int main(void) {
                    puts("Hello from $name!");
                    return 0;
                }
            """.trimIndent() + "\n"
        } else {
            """
                #include <iostream>

                int main() {
                    std::cout << "Hello from $name!\n";
                    return 0;
                }
            """.trimIndent() + "\n"
        }
    }

    private fun headerTemplate(name: String): String {
        val guard = "${identifier(name).uppercase()}_H"
        return """
            #ifndef $guard
            #define $guard

            int ${identifier(name)}_answer(void);

            #endif
        """.trimIndent() + "\n"
    }

    private fun identifier(name: String): String {
        return name.replace('-', '_')
    }
}

private object CMakeProjectCommandProvider : ProjectCommandProvider {
    override val id = "org.cosmicide.plugins.cmake.commands"
    override val displayName = "CMake commands"
    override val description = "Configure, build, run, test, install, and clean CMake projects"

    override fun commands(project: Project): List<ProjectCommand> {
        if (!CMakeProjectTypeProvider.supports(project.root)) return emptyList()

        val executableTargets = cmakeExecutableTargets(project.root)
        return buildList {
            add(
                ProjectCommand(
                    id = "$id.configure",
                    label = "Configure",
                    children = listOf(
                        command(
                            name = "configure.debug",
                            command = cmakeConfigureCommand("Debug"),
                            label = "Debug",
                            description = "Configure a Debug build",
                            kind = ProjectCommandKind.SYNC
                        ),
                        command(
                            name = "configure.release",
                            command = cmakeConfigureCommand("Release"),
                            label = "Release",
                            description = "Configure an optimized Release build",
                            kind = ProjectCommandKind.SYNC
                        )
                    )
                )
            )
            add(
                command(
                    name = "build",
                    command = "cmake --build build --parallel",
                    label = "Build",
                    description = "Build the configured CMake project",
                    kind = ProjectCommandKind.BUILD
                )
            )
            if (executableTargets.isNotEmpty()) {
                add(
                    ProjectCommand(
                        id = "$id.run",
                        label = "Build and run",
                        children = executableTargets.map { target ->
                            command(
                                name = "run.${target.commandId}",
                                command = cmakeBuildAndRunCommand(target.name),
                                label = target.name,
                                description = "Build and run the ${target.name} executable",
                                kind = ProjectCommandKind.RUN
                            )
                        }
                    )
                )
            }
            add(
                command(
                    name = "test",
                    command = "ctest --test-dir build --output-on-failure",
                    label = "Run tests",
                    description = "Run tests registered with CTest"
                )
            )
            add(
                command(
                    name = "install",
                    command = "cmake --install build",
                    label = "Install",
                    description = "Install the configured project"
                )
            )
            add(
                command(
                    name = "clean",
                    command = "cmake --build build --target clean",
                    label = "Clean",
                    description = "Clean generated build outputs"
                )
            )
            add(
                command(
                    name = "version",
                    command = "cmake --version && make --version",
                    label = "Tool versions",
                    description = "Show the installed CMake and Make versions"
                )
            )
        }
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

private fun cmakeConfigureCommand(buildType: String): String {
    return "cmake -S . -B build -G \"Unix Makefiles\" " +
        "-DCMAKE_BUILD_TYPE=$buildType -DCMAKE_EXPORT_COMPILE_COMMANDS=ON " +
        "&& ln -sf build/compile_commands.json compile_commands.json"
}

internal fun cmakeBuildAndRunCommand(target: String): String {
    return "if [ ! -f build/CMakeCache.txt ]; then ${cmakeConfigureCommand("Debug")}; fi && " +
        "cmake --build build --target '$target' --parallel && " +
        "executable=\"\$(find build \\( -type f -o -type l \\) -name '$target' " +
        "! -path '*/CMakeFiles/*' | while IFS= read -r candidate; do " +
        "if [ -x \"\$candidate\" ]; then printf '%s\\n' \"\$candidate\"; break; fi; done)\" && " +
        "if [ -z \"\$executable\" ]; then " +
        "echo 'Built $target but could not locate its executable.' >&2; exit 1; fi && " +
        "\"\$executable\""
}

internal data class CMakeExecutableTarget(
    val name: String
) {
    val commandId: String
        get() {
            val readableName = name.replace(Regex("[^A-Za-z0-9_.-]"), "_")
            return "$readableName.${name.hashCode().toUInt().toString(16)}"
        }
}

internal fun cmakeExecutableTargets(projectRoot: File): List<CMakeExecutableTarget> {
    return projectRoot.walkTopDown()
        .onEnter { directory ->
            directory == projectRoot ||
                (
                    directory.name !in CMAKE_SCAN_EXCLUDED_DIRECTORIES &&
                        !directory.name.startsWith("cmake-build-")
                    )
        }
        .filter { it.isFile && it.name == "CMakeLists.txt" }
        .sortedWith(
            compareBy<File>(
                { it.relativeTo(projectRoot).invariantSeparatorsPath.count { char -> char == '/' } },
                { it.relativeTo(projectRoot).invariantSeparatorsPath }
            )
        )
        .flatMap { cmakeLists ->
            runCatching { cmakeLists.readText() }
                .getOrDefault("")
                .let(CMAKE_EXECUTABLE_TARGET_REGEX::findAll)
                .mapNotNull { match ->
                    val name = match.groupValues[1]
                    val arguments = match.groupValues[2].trimStart()
                    name.takeUnless {
                        arguments.startsWith("ALIAS", ignoreCase = true) ||
                            arguments.startsWith("IMPORTED", ignoreCase = true)
                    }
                }
        }
        .distinct()
        .map(::CMakeExecutableTarget)
        .toList()
}

private const val CMAKE_INSTALL_COMMAND = "pacman -S --needed cmake make"
private val CMAKE_SCAN_EXCLUDED_DIRECTORIES = setOf(
    ".git",
    ".gradle",
    ".idea",
    "build",
    "out"
)
private val CMAKE_EXECUTABLE_TARGET_REGEX = Regex(
    pattern = """(?im)^\s*add_executable\s*\(\s*"?([A-Za-z0-9_.+-]+)"?(?:\s+([^)]*))?\)"""
)
