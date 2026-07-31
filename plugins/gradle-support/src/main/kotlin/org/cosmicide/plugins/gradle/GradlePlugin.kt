package org.cosmicide.plugins.gradle

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
import org.cosmicide.project.ProjectTask
import org.cosmicide.project.ProjectTaskProvider
import org.cosmicide.project.ProjectTypeProvider
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class GradlePlugin : CosmicPlugin {
    override val setupActions = listOf(
        PluginSetupAction(
            id = "org.cosmicide.plugins.gradle.installGradle",
            label = "Install Gradle",
            command = GRADLE_INSTALL_COMMAND,
            description = "Install Gradle build tool."
        )
    )

    private var taskProvider: GradleProjectTaskProvider? = null

    override fun activate(context: PluginContext) {
        val commandService = context.services.require(IdeServices.COMMAND_EXECUTION)
        val owner = context.descriptor.id
        val gradleTaskProvider = GradleProjectTaskProvider(commandService)
        taskProvider = gradleTaskProvider

        listOf(
            context.extensions.register(
                point = ProjectExtensionPoints.TYPE_PROVIDER,
                extension = GradleProjectTypeProvider,
                ownerPluginId = owner,
                priority = 350
            ),
            context.extensions.register(
                point = ProjectExtensionPoints.CREATION_PROVIDER,
                extension = GradleProjectCreationProvider(commandService),
                ownerPluginId = owner,
                priority = 350
            ),
            context.extensions.register(
                point = ProjectExtensionPoints.COMMAND_PROVIDER,
                extension = GradleProjectCommandProvider,
                ownerPluginId = owner,
                priority = 350
            ),
            context.extensions.register(
                point = ProjectExtensionPoints.TASK_PROVIDER,
                extension = gradleTaskProvider,
                ownerPluginId = owner,
                priority = 350
            )
        ).forEach(context::registerDisposable)

        context.logger.info("Gradle project support registered")
    }

    fun getTaskProvider(): GradleProjectTaskProvider? = taskProvider
}

private object GradleProjectTypeProvider : ProjectTypeProvider {
    override val id = "org.cosmicide.plugins.gradle.projectType"
    override val displayName = "Gradle projects"
    override val description = "Recognizes Gradle builds and Gradle Wrapper projects"
    override val languageName = "Java/Kotlin/Groovy/Scala/C/C++/Swift"
    override val fileExtension = "gradle"

    override fun supports(projectRoot: File): Boolean =
        projectRoot.resolve("gradlew").isFile ||
                projectRoot.resolve("build.gradle").isFile ||
                projectRoot.resolve("build.gradle.kts").isFile ||
                projectRoot.resolve("settings.gradle").isFile ||
                projectRoot.resolve("settings.gradle.kts").isFile
}

private enum class GradleProjectGenerator {
    INIT,
    SWIFT_APPLICATION,
    SWIFT_LIBRARY,
    C_APPLICATION,
    C_LIBRARY
}

private data class GradleProjectType(
    val id: String,
    val label: String,
    val generator: GradleProjectGenerator = GradleProjectGenerator.INIT,
    val jvm: Boolean = false,
    val packageSupported: Boolean = false,
    val testFrameworkSupported: Boolean = false,
    val splitProjectSupported: Boolean = false,
    val dslSupported: Boolean = true
)

private class GradleProjectCreationProvider(
    private val commands: CommandExecutionService
) : ProjectCreationProvider {
    override val id = "org.cosmicide.plugins.gradle.createProject"
    override val displayName = "New Gradle project"
    override val description = "Create JVM, native, Swift, C, or Gradle plugin projects"
    override val actionLabel = "Create"

    override val fields = listOf(
        PluginFormField(
            id = "name",
            label = "Project name",
            placeholder = "my-application",
            required = true
        ),
        PluginFormField(
            id = "type",
            label = "Project type",
            type = PluginFormFieldType.CHOICE,
            defaultValue = "java-application",
            options = GRADLE_PROJECT_TYPES.map { PluginFormOption(it.id, it.label) }
        ),
        PluginFormField(
            id = "dsl",
            label = "Build script DSL",
            type = PluginFormFieldType.CHOICE,
            defaultValue = "kotlin",
            options = listOf(
                PluginFormOption("kotlin", "Kotlin DSL"),
                PluginFormOption("groovy", "Groovy DSL")
            )
        ),
        PluginFormField(
            id = "package_name",
            label = "Package",
            description = "Used by JVM project types; ignored by native projects.",
            placeholder = "com.example.app"
        ),
        PluginFormField(
            id = "java_version",
            label = "Java version",
            description = "Used by JVM project types.",
            defaultValue = "17",
            placeholder = "17"
        ),
        PluginFormField(
            id = "test_framework",
            label = "Test framework",
            type = PluginFormFieldType.CHOICE,
            defaultValue = "default",
            options = listOf(
                PluginFormOption("default", "Project default"),
                PluginFormOption("junit-jupiter", "JUnit Jupiter"),
                PluginFormOption("junit", "JUnit 4"),
                PluginFormOption("testng", "TestNG"),
                PluginFormOption("spock", "Spock"),
                PluginFormOption("scalatest", "ScalaTest")
            )
        ),
        PluginFormField(
            id = "project_structure",
            label = "Project structure",
            type = PluginFormFieldType.CHOICE,
            defaultValue = "single",
            options = listOf(
                PluginFormOption("single", "Single project"),
                PluginFormOption("split", "Application and library")
            )
        )
    )

    override suspend fun create(
        request: ProjectCreationRequest,
        reporter: OperationReporter
    ): ProjectCreationResult {
        val name = request.values["name"].orEmpty().trim()
        require(name.matches(PROJECT_NAME_REGEX)) {
            "Project name may contain letters, numbers, dots, underscores, and hyphens"
        }

        val typeId = request.values["type"].orEmpty().ifBlank { "java-application" }
        val projectType = requireNotNull(GRADLE_PROJECT_TYPES_BY_ID[typeId]) {
            "Unsupported Gradle project type"
        }

        val dsl = request.values["dsl"].orEmpty().ifBlank { "kotlin" }
        require(dsl in GRADLE_DSLS) { "Unsupported Gradle build script DSL" }

        val packageName = request.values["package_name"].orEmpty().trim()
        if (packageName.isNotEmpty()) {
            require(packageName.matches(JAVA_PACKAGE_REGEX)) {
                "Package must be a dot-separated identifier"
            }
        }

        val javaVersion = request.values["java_version"].orEmpty().trim().ifBlank { "17" }
        if (projectType.jvm) {
            require(javaVersion.matches(JAVA_VERSION_REGEX)) {
                "Java version must be a positive major version"
            }
        }

        val testFramework = request.values["test_framework"].orEmpty().ifBlank { "default" }
        require(testFramework in GRADLE_TEST_FRAMEWORKS) { "Unsupported test framework" }

        val splitProject = when (
            request.values["project_structure"].orEmpty().ifBlank { "single" }
        ) {
            "single" -> false
            "split" -> true
            else -> error("Unsupported project structure")
        }

        val projectsRoot = request.projectsDirectory.canonicalFile
        val root = projectsRoot.resolve(name).canonicalFile
        require(root.parentFile == projectsRoot) { "Invalid project name" }
        require(!root.exists()) { "A project with this name already exists" }

        reporter.report(OperationUpdate("Creating ${projectType.label}…\n"))

        try {
            check(root.mkdirs()) { "Could not create the project directory" }

            when (projectType.generator) {
                GradleProjectGenerator.INIT -> createWithGradleInit(
                    root = root,
                    name = name,
                    projectType = projectType,
                    dsl = dsl,
                    packageName = packageName,
                    javaVersion = javaVersion,
                    testFramework = testFramework,
                    splitProject = splitProject,
                    reporter = reporter
                )
                GradleProjectGenerator.SWIFT_APPLICATION ->
                    createSwiftProject(root, name, dsl, application = true)
                GradleProjectGenerator.SWIFT_LIBRARY ->
                    createSwiftProject(root, name, dsl, application = false)
                GradleProjectGenerator.C_APPLICATION ->
                    createCProject(root, name, dsl, application = true)
                GradleProjectGenerator.C_LIBRARY ->
                    createCProject(root, name, dsl, application = false)
            }

            check(
                root.resolve("settings.gradle").isFile ||
                        root.resolve("settings.gradle.kts").isFile
            ) { "Project generation completed without creating Gradle settings" }

            root.resolve(".gitignore").mergeGitignoreEntries(
                listOf(
                    ".gradle/",
                    "build/",
                    "**/build/",
                    ".idea/",
                    "*.iml",
                    ".DS_Store"
                )
            )
        } catch (failure: Exception) {
            if (root.exists()) root.deleteRecursively()
            throw failure
        }

        return ProjectCreationResult(
            project = GradleProjectTypeProvider.project(root),
            message = "${projectType.label} created successfully"
        )
    }

    private suspend fun createWithGradleInit(
        root: File,
        name: String,
        projectType: GradleProjectType,
        dsl: String,
        packageName: String,
        javaVersion: String,
        testFramework: String,
        splitProject: Boolean,
        reporter: OperationReporter
    ) {
        val result = commands.execute(
            CommandRequest(
                command = GRADLE_COMMAND,
                arguments = gradleInitArguments(
                    projectType = projectType,
                    projectName = name,
                    dsl = dsl,
                    packageName = packageName,
                    javaVersion = javaVersion,
                    testFramework = testFramework,
                    splitProject = splitProject
                ),
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
            error(
                result.output.lineSequence().lastOrNull(String::isNotBlank)
                    ?: "Gradle project creation failed with exit code ${result.exitCode}"
            )
        }
    }
}

private object GradleProjectCommandProvider : ProjectCommandProvider {
    override val id = "org.cosmicide.plugins.gradle.commands"
    override val displayName = "Gradle commands"
    override val description = "Build, run, test, refresh, and maintain Gradle projects"

    override fun commands(project: Project): List<ProjectCommand> {
        if (!GradleProjectTypeProvider.supports(project.root)) return emptyList()

        val gradleCommand = gradleShellCommand(project.root)
        val runTargets = gradleRunTargets(project.root)

        return buildList {
            add(
                command(
                    name = "refresh",
                    command = "$gradleCommand help --refresh-dependencies",
                    label = "Refresh project",
                    description = "Configure the build and refresh dependencies",
                    kind = ProjectCommandKind.SYNC
                )
            )
            add(
                command(
                    name = "dependencies",
                    command = "$gradleCommand dependencies",
                    label = "Dependencies",
                    description = "Display dependencies for the root project",
                    kind = ProjectCommandKind.SYNC
                )
            )
            add(
                command(
                    name = "build",
                    command = "$gradleCommand build",
                    label = "Build",
                    description = "Compile, test, and assemble the project",
                    kind = ProjectCommandKind.BUILD
                )
            )
            if (runTargets.isNotEmpty()) {
                add(
                    ProjectCommand(
                        id = "$id.run",
                        label = "Build and run",
                        children = runTargets.map { target ->
                            command(
                                name = "run.${target.commandId}",
                                command = target.command(gradleCommand),
                                label = target.label,
                                description = target.description,
                                kind = ProjectCommandKind.RUN
                            )
                        }
                    )
                )
            }
            add(
                command(
                    name = "test",
                    command = "$gradleCommand test",
                    label = "Test",
                    description = "Run all tests"
                )
            )
            add(
                command(
                    name = "clean",
                    command = "$gradleCommand clean",
                    label = "Clean",
                    description = "Clean build outputs"
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

private data class GradleTaskCacheEntry(
    val fingerprint: String,
    val tasks: List<ProjectTask>
)

class GradleProjectTaskProvider(
    private val commandService: CommandExecutionService
) : ProjectTaskProvider {
    override val id = "org.cosmicide.plugins.gradle.tasks"
    override val displayName = "Gradle tasks"
    override val description = "All available tasks discovered from the Gradle build"

    private val taskCache = ConcurrentHashMap<String, GradleTaskCacheEntry>()

    override fun supports(project: Project): Boolean =
        GradleProjectTypeProvider.supports(project.root)

    override suspend fun tasks(project: Project): List<ProjectTask> {
        val root = project.root.canonicalFile
        val cacheKey = root.path
        val fingerprint = gradleBuildFingerprint(root)

        taskCache[cacheKey]
            ?.takeIf { it.fingerprint == fingerprint }
            ?.let { return it.tasks }

        val tasks = gradleTasks(
            projectRoot = root,
            gradleCommand = if (root.resolve("gradlew").isFile) root.resolve("gradlew").absolutePath else GRADLE_COMMAND,
            commandService = commandService
        )
        taskCache[cacheKey] = GradleTaskCacheEntry(fingerprint, tasks)
        return tasks
    }

    fun clearCache(projectRoot: File) {
        taskCache.remove(projectRoot.canonicalFile.path)
    }

    fun onProjectSynced(projectRoot: File) = clearCache(projectRoot)

    fun onSyncCompleted(projectRoot: File) = clearCache(projectRoot)
}

internal suspend fun gradleTasks(
    projectRoot: File,
    gradleCommand: String,
    commandService: CommandExecutionService
): List<ProjectTask> {
    val initScript = File.createTempFile("cosmic-gradle-tasks-", ".gradle", projectRoot)
    return try {
        initScript.writeText(GRADLE_TASK_DISCOVERY_INIT_SCRIPT)

        val wrapper = projectRoot.resolve("gradlew")
        val executable = if (wrapper.isFile && !wrapper.canExecute()) "sh" else gradleCommand
        val arguments = buildList {
            if (wrapper.isFile && !wrapper.canExecute()) add("./gradlew")
            add("--quiet")
            add("--init-script")
            add(initScript.absolutePath)
            add("cosmicListTasks")
        }
        val result = commandService.execute(
            CommandRequest(
                command = executable,
                arguments = arguments,
                workingDirectory = projectRoot
            )
        ) { }

        val shellCommand = gradleShellCommand(projectRoot)
        val tasks = if (result.successful) {
            result.output.lineSequence()
                .mapNotNull { line -> parseDiscoveredGradleTask(line, shellCommand) }
                .distinctBy(ProjectTask::id)
                .sortedWith(compareBy(ProjectTask::group, ProjectTask::label))
                .toList()
        } else {
            emptyList()
        }

        tasks.ifEmpty { fallbackGradleTasks(gradleShellCommand(projectRoot)) }
    } finally {
        initScript.delete()
    }
}

private fun parseDiscoveredGradleTask(line: String, gradleCommand: String): ProjectTask? {
    if (!line.startsWith(GRADLE_TASK_MARKER)) return null
    val fields = line.removePrefix(GRADLE_TASK_MARKER).split('\t', limit = 4)
    if (fields.size < 4) return null

    val taskPath = fields[0].trim()
    if (!taskPath.matches(GRADLE_TASK_PATH_REGEX)) return null

    val group = fields[1].trim().ifBlank { "Other" }
    val description = fields[2].trim().ifBlank { "Run Gradle task: $taskPath" }
    val projectPath = fields[3].trim().ifBlank { ":" }

    return ProjectTask(
        id = "org.cosmicide.plugins.gradle.tasks.${taskPath.commandId()}",
        label = taskPath,
        command = "$gradleCommand ${taskPath.shellQuote()}",
        description = if (projectPath == ":") description else "$description ($projectPath)",
        group = group
    )
}

private fun fallbackGradleTasks(gradleCommand: String): List<ProjectTask> = listOf(
    ProjectTask(
        id = "org.cosmicide.plugins.gradle.tasks.build",
        label = "build",
        command = "$gradleCommand build",
        description = "Assemble and test the project",
        group = "Build"
    ),
    ProjectTask(
        id = "org.cosmicide.plugins.gradle.tasks.clean",
        label = "clean",
        command = "$gradleCommand clean",
        description = "Delete build outputs",
        group = "Build"
    ),
    ProjectTask(
        id = "org.cosmicide.plugins.gradle.tasks.test",
        label = "test",
        command = "$gradleCommand test",
        description = "Run tests",
        group = "Verification"
    ),
    ProjectTask(
        id = "org.cosmicide.plugins.gradle.tasks.dependencies",
        label = "dependencies",
        command = "$gradleCommand dependencies",
        description = "Display project dependencies",
        group = "Help"
    ),
    ProjectTask(
        id = "org.cosmicide.plugins.gradle.tasks.tasksAll",
        label = "tasks",
        command = "$gradleCommand tasks --all",
        description = "List all available Gradle tasks",
        group = "Help"
    )
)

internal data class GradleRunTarget(
    val projectPath: String,
    val mainClass: String?,
    val taskPath: String
) {
    val label: String
        get() = when {
            mainClass != null && projectPath == ":" -> mainClass
            mainClass != null -> "$projectPath — $mainClass"
            else -> taskPath
        }

    val description: String
        get() = mainClass?.let { "Build and run $it" } ?: "Run Gradle task $taskPath"

    val commandId: String
        get() = "$projectPath:$taskPath:${mainClass.orEmpty()}".commandId()

    fun command(gradleCommand: String): String =
        "$gradleCommand ${taskPath.shellQuote()}"
}

internal fun gradleRunTargets(projectRoot: File): List<GradleRunTarget> {
    val root = projectRoot.canonicalFile
    val modules = gradleModuleRoots(root)

    return modules.flatMap { (projectPath, moduleRoot) ->
        val buildFiles = listOf(
            moduleRoot.resolve("build.gradle.kts"),
            moduleRoot.resolve("build.gradle")
        ).filter(File::isFile)

        val configuredMainClasses = buildFiles.flatMap { buildFile ->
            runCatching { buildFile.readText() }
                .getOrDefault("")
                .let { text -> GRADLE_MAIN_CLASS_REGEX.findAll(text).map { it.groupValues[1] }.toList() }
        }

        val hasApplicationPlugin = buildFiles.any { buildFile ->
            val text = runCatching { buildFile.readText() }.getOrDefault("")
            APPLICATION_PLUGIN_REGEX.containsMatchIn(text)
        }

        val taskPath = if (projectPath == ":") ":run" else "$projectPath:run"
        val sourceMainClasses = if (configuredMainClasses.isEmpty()) {
            findMainClasses(moduleRoot)
        } else {
            emptyList()
        }

        when {
            configuredMainClasses.isNotEmpty() -> configuredMainClasses.map { mainClass ->
                GradleRunTarget(projectPath, mainClass, taskPath)
            }
            sourceMainClasses.isNotEmpty() -> sourceMainClasses.map { mainClass ->
                GradleRunTarget(projectPath, mainClass, taskPath)
            }
            hasApplicationPlugin -> listOf(GradleRunTarget(projectPath, null, taskPath))
            moduleRoot.resolve("src/main/swift/main.swift").isFile ->
                listOf(GradleRunTarget(projectPath, null, taskPath))
            moduleRoot.resolve("src/main/c/main.c").isFile ->
                listOf(GradleRunTarget(projectPath, null, taskPath))
            else -> emptyList()
        }
    }.distinctBy { "${it.projectPath}:${it.taskPath}:${it.mainClass}" }
}

private fun gradleModuleRoots(projectRoot: File): List<Pair<String, File>> {
    val modules = linkedMapOf(":" to projectRoot)
    val settingsFile = listOf(
        projectRoot.resolve("settings.gradle.kts"),
        projectRoot.resolve("settings.gradle")
    ).firstOrNull(File::isFile) ?: return modules.toList()

    val text = runCatching { settingsFile.readText() }.getOrDefault("")
    GRADLE_INCLUDE_REGEX.findAll(text).forEach { match ->
        val body = match.groupValues[1].ifBlank { match.groupValues[2] }
        QUOTED_VALUE_REGEX.findAll(body).forEach { valueMatch ->
            val raw = valueMatch.groupValues[1]
            val projectPath = if (raw.startsWith(':')) raw else ":${raw.replace(':', ':')}"
            val relativePath = projectPath.trim(':').replace(':', File.separatorChar)
            val moduleRoot = projectRoot.resolve(relativePath).canonicalFile
            if (moduleRoot.startsWith(projectRoot) && moduleRoot.isDirectory) {
                modules[projectPath] = moduleRoot
            }
        }
    }
    return modules.toList()
}

private fun findMainClasses(moduleRoot: File): List<String> = buildList {
    addAll(findJavaMainClasses(moduleRoot.resolve("src/main/java")))
    addAll(findKotlinMainClasses(moduleRoot.resolve("src/main/kotlin")))
    addAll(findGroovyMainClasses(moduleRoot.resolve("src/main/groovy")))
    addAll(findScalaMainClasses(moduleRoot.resolve("src/main/scala")))
}.distinct()

private fun findJavaMainClasses(root: File): List<String> =
    sourceFiles(root, "java").mapNotNull { source ->
        val text = source.readTextOrNull() ?: return@mapNotNull null
        if (!JAVA_MAIN_METHOD_REGEX.containsMatchIn(text.withoutSimpleComments())) return@mapNotNull null
        qualifiedClassName(text, source.nameWithoutExtension, JAVA_PACKAGE_DECLARATION_REGEX)
    }.toList()

private fun findKotlinMainClasses(root: File): List<String> =
    sourceFiles(root, "kt").mapNotNull { source ->
        val text = source.readTextOrNull() ?: return@mapNotNull null
        if (!KOTLIN_MAIN_FUNCTION_REGEX.containsMatchIn(text.withoutSimpleComments())) return@mapNotNull null
        qualifiedClassName(text, "${source.nameWithoutExtension}Kt", KOTLIN_PACKAGE_DECLARATION_REGEX)
    }.toList()

private fun findGroovyMainClasses(root: File): List<String> =
    sourceFiles(root, "groovy").mapNotNull { source ->
        val text = source.readTextOrNull() ?: return@mapNotNull null
        val stripped = text.withoutSimpleComments()
        if (!JAVA_MAIN_METHOD_REGEX.containsMatchIn(stripped) &&
            !GROOVY_MAIN_METHOD_REGEX.containsMatchIn(stripped)
        ) return@mapNotNull null
        qualifiedClassName(text, source.nameWithoutExtension, GROOVY_PACKAGE_DECLARATION_REGEX)
    }.toList()

private fun findScalaMainClasses(root: File): List<String> =
    sourceFiles(root, "scala").flatMap { source ->
        val text = source.readTextOrNull() ?: return@flatMap emptyList()
        val stripped = text.withoutSimpleComments()
        val packageName = SCALA_PACKAGE_DECLARATION_REGEX.find(text)?.groupValues?.get(1)
        val objects = SCALA_MAIN_OBJECT_REGEX.findAll(stripped).map { it.groupValues[1] }
        val annotated = SCALA_ANNOTATED_MAIN_REGEX.findAll(stripped).map { it.groupValues[1] }
        (objects + annotated).map { className ->
            listOfNotNull(packageName, className).joinToString(".")
        }.toList()
    }.toList()

private fun sourceFiles(root: File, extension: String): Sequence<File> =
    if (!root.isDirectory) emptySequence()
    else root.walkTopDown().filter { it.isFile && it.extension == extension }

private fun qualifiedClassName(
    text: String,
    className: String,
    packageRegex: Regex
): String {
    val packageName = packageRegex.find(text)?.groupValues?.get(1)
    return listOfNotNull(packageName, className).joinToString(".")
}

private fun gradleInitArguments(
    projectType: GradleProjectType,
    projectName: String,
    dsl: String,
    packageName: String,
    javaVersion: String,
    testFramework: String,
    splitProject: Boolean
): List<String> = buildList {
    add("init")
    add("--type")
    add(projectType.id)
    add("--project-name")
    add(projectName)

    if (projectType.dslSupported) {
        add("--dsl")
        add(dsl)
    }
    if (projectType.packageSupported && packageName.isNotEmpty()) {
        add("--package")
        add(packageName)
    }
    if (projectType.jvm) {
        add("--java-version")
        add(javaVersion)
    }
    if (projectType.testFrameworkSupported && testFramework != "default") {
        add("--test-framework")
        add(testFramework)
    }
    if (projectType.splitProjectSupported) {
        add(if (splitProject) "--split-project" else "--no-split-project")
    }

    add("--use-defaults")
    add("--no-incubating")
    add("--no-comments")
}

private fun createSwiftProject(root: File, name: String, dsl: String, application: Boolean) {
    writeGradleSettings(root, name, dsl)
    val plugin = if (application) "swift-application" else "swift-library"
    val buildFile = root.resolve(if (dsl == "kotlin") "build.gradle.kts" else "build.gradle")
    buildFile.writeText(
        if (dsl == "kotlin") {
            """
            plugins {
                `$plugin`
            }
            """.trimIndent() + "\n"
        } else {
            """
            plugins {
                id '$plugin'
            }
            """.trimIndent() + "\n"
        }
    )

    val sourceRoot = root.resolve("src/main/swift")
    check(sourceRoot.mkdirs()) { "Could not create Swift source directory" }
    if (application) {
        sourceRoot.resolve("main.swift").writeText("print(\"Hello from $name!\")\n")
    } else {
        sourceRoot.resolve("Greeting.swift").writeText(
            """
            public struct Greeting {
                public static func message() -> String {
                    "Hello from $name!"
                }
            }
            """.trimIndent() + "\n"
        )
    }
}

private fun createCProject(root: File, name: String, dsl: String, application: Boolean) {
    writeGradleSettings(root, name, dsl)
    val buildFile = root.resolve(if (dsl == "kotlin") "build.gradle.kts" else "build.gradle")
    buildFile.writeText(
        if (dsl == "kotlin") cKotlinBuildScript(name, application)
        else cGroovyBuildScript(name, application)
    )

    val sourceRoot = root.resolve("src/main/c")
    val headerRoot = root.resolve("src/main/headers")
    check(sourceRoot.mkdirs()) { "Could not create C source directory" }
    if (!application) check(headerRoot.mkdirs()) { "Could not create C header directory" }

    if (application) {
        sourceRoot.resolve("main.c").writeText(
            """
            #include <stdio.h>

            int main(void) {
                puts("Hello from $name!");
                return 0;
            }
            """.trimIndent() + "\n"
        )
    } else {
        headerRoot.resolve("greeting.h").writeText(
            """
            #ifndef COSMIC_GREETING_H
            #define COSMIC_GREETING_H

            const char *cosmic_greeting(void);

            #endif
            """.trimIndent() + "\n"
        )
        sourceRoot.resolve("greeting.c").writeText(
            """
            #include "greeting.h"

            const char *cosmic_greeting(void) {
                return "Hello from $name!";
            }
            """.trimIndent() + "\n"
        )
    }
}

private fun cKotlinBuildScript(name: String, application: Boolean): String =
    if (application) {
        """
        import org.gradle.api.tasks.Exec

        val outputDirectory = layout.buildDirectory.dir("bin")
        val executable = outputDirectory.map { it.file("$name") }

        val compileDebug by tasks.registering(Exec::class) {
            group = "build"
            description = "Compile the C application"
            inputs.files(fileTree("src/main/c") { include("**/*.c") })
            outputs.file(executable)
            doFirst { outputDirectory.get().asFile.mkdirs() }
            commandLine(
                "cc", "-std=c17", "-Wall", "-Wextra", "-g",
                "-o", executable.get().asFile.absolutePath,
                "src/main/c/main.c"
            )
        }

        tasks.register<Exec>("run") {
            group = "application"
            description = "Run the C application"
            dependsOn(compileDebug)
            commandLine(executable.get().asFile.absolutePath)
        }

        tasks.register("build") {
            group = "build"
            dependsOn(compileDebug)
        }
        """.trimIndent() + "\n"
    } else {
        """
        import org.gradle.api.tasks.Exec

        val outputDirectory = layout.buildDirectory.dir("lib")
        val library = outputDirectory.map { it.file("lib$name.a") }
        val objectFile = layout.buildDirectory.file("obj/greeting.o")

        val compileDebug by tasks.registering(Exec::class) {
            group = "build"
            description = "Compile the C library"
            inputs.files(fileTree("src/main/c") { include("**/*.c") })
            inputs.files(fileTree("src/main/headers") { include("**/*.h") })
            outputs.file(objectFile)
            doFirst { objectFile.get().asFile.parentFile.mkdirs() }
            commandLine(
                "cc", "-std=c17", "-Wall", "-Wextra", "-g", "-fPIC",
                "-Isrc/main/headers", "-c", "src/main/c/greeting.c",
                "-o", objectFile.get().asFile.absolutePath
            )
        }

        tasks.register<Exec>("archive") {
            group = "build"
            description = "Archive the C static library"
            dependsOn(compileDebug)
            outputs.file(library)
            doFirst { outputDirectory.get().asFile.mkdirs() }
            commandLine(
                "ar", "rcs", library.get().asFile.absolutePath,
                objectFile.get().asFile.absolutePath
            )
        }

        tasks.register("build") {
            group = "build"
            dependsOn("archive")
        }
        """.trimIndent() + "\n"
    }

private fun cGroovyBuildScript(name: String, application: Boolean): String =
    if (application) {
        """
        def outputDirectory = layout.buildDirectory.dir('bin')
        def executable = outputDirectory.map { it.file('$name') }

        tasks.register('compileDebug', Exec) {
            group = 'build'
            description = 'Compile the C application'
            inputs.files(fileTree('src/main/c') { include '**/*.c' })
            outputs.file(executable)
            doFirst { outputDirectory.get().asFile.mkdirs() }
            commandLine 'cc', '-std=c17', '-Wall', '-Wextra', '-g',
                '-o', executable.get().asFile.absolutePath, 'src/main/c/main.c'
        }

        tasks.register('run', Exec) {
            group = 'application'
            description = 'Run the C application'
            dependsOn 'compileDebug'
            commandLine executable.get().asFile.absolutePath
        }

        tasks.register('build') {
            group = 'build'
            dependsOn 'compileDebug'
        }
        """.trimIndent() + "\n"
    } else {
        """
        def outputDirectory = layout.buildDirectory.dir('lib')
        def library = outputDirectory.map { it.file('lib$name.a') }
        def objectFile = layout.buildDirectory.file('obj/greeting.o')

        tasks.register('compileDebug', Exec) {
            group = 'build'
            description = 'Compile the C library'
            inputs.files(fileTree('src/main/c') { include '**/*.c' })
            inputs.files(fileTree('src/main/headers') { include '**/*.h' })
            outputs.file(objectFile)
            doFirst { objectFile.get().asFile.parentFile.mkdirs() }
            commandLine 'cc', '-std=c17', '-Wall', '-Wextra', '-g', '-fPIC',
                '-Isrc/main/headers', '-c', 'src/main/c/greeting.c',
                '-o', objectFile.get().asFile.absolutePath
        }

        tasks.register('archive', Exec) {
            group = 'build'
            description = 'Archive the C static library'
            dependsOn 'compileDebug'
            outputs.file(library)
            doFirst { outputDirectory.get().asFile.mkdirs() }
            commandLine 'ar', 'rcs', library.get().asFile.absolutePath,
                objectFile.get().asFile.absolutePath
        }

        tasks.register('build') {
            group = 'build'
            dependsOn 'archive'
        }
        """.trimIndent() + "\n"
    }

private fun writeGradleSettings(root: File, name: String, dsl: String) {
    val settings = root.resolve(if (dsl == "kotlin") "settings.gradle.kts" else "settings.gradle")
    settings.writeText("rootProject.name = ${name.gradleStringLiteral()}\n")
}

private fun File.mergeGitignoreEntries(entries: List<String>) {
    val existing = if (isFile) readLines() else emptyList()
    val merged = (existing + entries).map(String::trimEnd).distinct()
    writeText(merged.joinToString("\n", postfix = "\n"))
}

private fun gradleShellCommand(projectRoot: File): String {
    val wrapper = projectRoot.resolve("gradlew")
    return when {
        wrapper.isFile && wrapper.canExecute() -> "./gradlew"
        wrapper.isFile -> "sh ./gradlew"
        else -> GRADLE_COMMAND
    }
}

private fun gradleBuildFingerprint(projectRoot: File): String {
    val relevantFiles = projectRoot.walkTopDown()
        .onEnter { directory ->
            directory == projectRoot || directory.name !in GRADLE_SCAN_EXCLUDED_DIRECTORIES
        }
        .filter { file ->
            file.isFile && (
                    file.name == "settings.gradle" ||
                            file.name == "settings.gradle.kts" ||
                            file.name == "build.gradle" ||
                            file.name == "build.gradle.kts" ||
                            file.name == "gradle.properties" ||
                            file.invariantSeparatorsPath.endsWith("gradle/wrapper/gradle-wrapper.properties")
                    )
        }
        .sortedBy { it.relativeTo(projectRoot).invariantSeparatorsPath }

    return relevantFiles.joinToString("|") { file ->
        val relative = file.relativeTo(projectRoot).invariantSeparatorsPath
        "$relative:${file.length()}:${file.lastModified()}"
    }
}

private fun File.readTextOrNull(): String? = runCatching { readText() }.getOrNull()

private fun String.withoutSimpleComments(): String =
    replace(BLOCK_COMMENT_REGEX, " ").replace(LINE_COMMENT_REGEX, " ")

private fun String.gradleStringLiteral(): String =
    "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

private fun String.shellQuote(): String = "'${replace("'", "'\"'\"'")}'"

private fun String.commandId(): String {
    val readable = replace(Regex("[^A-Za-z0-9_.-]"), "_")
    return "$readable.${hashCode().toUInt().toString(16)}"
}

private const val GRADLE_INSTALL_COMMAND = "pacman -S --needed --noconfirm gradle"
private const val GRADLE_COMMAND = "gradle"
private const val GRADLE_TASK_MARKER = "__COSMIC_GRADLE_TASK__\t"

private val GRADLE_PROJECT_TYPES = listOf(
    GradleProjectType("basic", "Basic Gradle build"),
    GradleProjectType("java-application", "Java application", jvm = true, packageSupported = true, testFrameworkSupported = true, splitProjectSupported = true),
    GradleProjectType("java-library", "Java library", jvm = true, packageSupported = true, testFrameworkSupported = true),
    GradleProjectType("java-gradle-plugin", "Java Gradle plugin", jvm = true, packageSupported = true, testFrameworkSupported = true),
    GradleProjectType("kotlin-application", "Kotlin application", jvm = true, packageSupported = true, testFrameworkSupported = true, splitProjectSupported = true),
    GradleProjectType("kotlin-library", "Kotlin library", jvm = true, packageSupported = true, testFrameworkSupported = true),
    GradleProjectType("kotlin-gradle-plugin", "Kotlin Gradle plugin", jvm = true, packageSupported = true, testFrameworkSupported = true),
    GradleProjectType("groovy-application", "Groovy application", jvm = true, packageSupported = true, testFrameworkSupported = true, splitProjectSupported = true),
    GradleProjectType("groovy-library", "Groovy library", jvm = true, packageSupported = true, testFrameworkSupported = true),
    GradleProjectType("groovy-gradle-plugin", "Groovy Gradle plugin", jvm = true, packageSupported = true, testFrameworkSupported = true),
    GradleProjectType("scala-application", "Scala application", jvm = true, packageSupported = true, testFrameworkSupported = true, splitProjectSupported = true),
    GradleProjectType("scala-library", "Scala library", jvm = true, packageSupported = true, testFrameworkSupported = true),
    GradleProjectType("cpp-application", "C++ application", testFrameworkSupported = true),
    GradleProjectType("cpp-library", "C++ library", testFrameworkSupported = true),
    GradleProjectType("swift-application", "Swift application", GradleProjectGenerator.SWIFT_APPLICATION),
    GradleProjectType("swift-library", "Swift library", GradleProjectGenerator.SWIFT_LIBRARY),
    GradleProjectType("c-application", "C application", GradleProjectGenerator.C_APPLICATION),
    GradleProjectType("c-library", "C library", GradleProjectGenerator.C_LIBRARY)
)

private val GRADLE_PROJECT_TYPES_BY_ID = GRADLE_PROJECT_TYPES.associateBy(GradleProjectType::id)
private val GRADLE_DSLS = setOf("kotlin", "groovy")
private val GRADLE_TEST_FRAMEWORKS = setOf(
    "default",
    "junit-jupiter",
    "junit",
    "testng",
    "spock",
    "scalatest"
)
private val GRADLE_SCAN_EXCLUDED_DIRECTORIES = setOf(
    ".git",
    ".gradle",
    ".idea",
    "build",
    "target",
    "out",
    "node_modules"
)

private val PROJECT_NAME_REGEX = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
private val JAVA_PACKAGE_REGEX = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")
private val JAVA_VERSION_REGEX = Regex("[1-9][0-9]*")
private val GRADLE_TASK_PATH_REGEX = Regex(":?[A-Za-z0-9_.-]+(?::[A-Za-z0-9_.-]+)*")
private val GRADLE_MAIN_CLASS_REGEX = Regex("""mainClass(?:\.set)?\s*\(?\s*["']([^"']+)["']\s*\)?""")
private val APPLICATION_PLUGIN_REGEX = Regex(
    """(?:id\s*\(?\s*["']application["']|`application`|apply\s+plugin:\s*["']application["'])"""
)
private val GRADLE_INCLUDE_REGEX = Regex("""include\s*\((.*?)\)|include\s+([^\n]+)""", setOf(RegexOption.DOT_MATCHES_ALL))
private val QUOTED_VALUE_REGEX = Regex("""["']([^"']+)["']""")
private val JAVA_MAIN_METHOD_REGEX = Regex(
    """\bpublic\s+static\s+void\s+main\s*\(\s*(?:final\s+)?String\s*(?:\[\s*]\s*\w+|\w+\s*\[\s*]|\.\.\.\s*\w+)\s*\)"""
)
private val KOTLIN_MAIN_FUNCTION_REGEX = Regex("""\bfun\s+main\s*\(""")
private val GROOVY_MAIN_METHOD_REGEX = Regex("""\bstatic\s+(?:void\s+)?main\s*\(""")
private val SCALA_MAIN_OBJECT_REGEX = Regex(
    """\bobject\s+([A-Za-z_][A-Za-z0-9_]*)\s+(?:extends\s+App|\{[^}]*\bdef\s+main\s*\()""",
    RegexOption.DOT_MATCHES_ALL
)
private val SCALA_ANNOTATED_MAIN_REGEX = Regex("""@main\s+def\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
private val JAVA_PACKAGE_DECLARATION_REGEX = Regex("""(?m)^\s*package\s+([A-Za-z_][A-Za-z0-9_.]*)\s*;""")
private val KOTLIN_PACKAGE_DECLARATION_REGEX = Regex("""(?m)^\s*package\s+([A-Za-z_][A-Za-z0-9_.]*)\s*$""")
private val GROOVY_PACKAGE_DECLARATION_REGEX = Regex("""(?m)^\s*package\s+([A-Za-z_][A-Za-z0-9_.]*)\s*;?\s*$""")
private val SCALA_PACKAGE_DECLARATION_REGEX = Regex("""(?m)^\s*package\s+([A-Za-z_][A-Za-z0-9_.]*)\s*$""")
private val BLOCK_COMMENT_REGEX = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
private val LINE_COMMENT_REGEX = Regex("""(?m)//.*$""")

private val GRADLE_TASK_DISCOVERY_INIT_SCRIPT = """
    gradle.projectsLoaded {
        gradle.rootProject {
            tasks.register('cosmicListTasks') {
                group = 'help'
                description = 'Lists Gradle tasks for Cosmic IDE'
                doLast {
                    rootProject.allprojects.each { p ->
                        p.tasks.each { t ->
                            def groupName = (t.group ?: 'Other').replace('\\t', ' ').replace('\\n', ' ')
                            def descriptionText = (t.description ?: '').replace('\\t', ' ').replace('\\n', ' ')
                            println('__COSMIC_GRADLE_TASK__\\t' + t.path + '\\t' + groupName + '\\t' + descriptionText + '\\t' + p.path)
                        }
                    }
                }
            }
        }
    }
""".trimIndent()

