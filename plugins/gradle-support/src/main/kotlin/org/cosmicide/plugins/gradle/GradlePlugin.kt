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
import kotlin.collections.setOf as setOf

class GradlePlugin : CosmicPlugin {
    override val setupActions = listOf(
        PluginSetupAction(
            id = "org.cosmicide.plugins.gradle.installGradle",
            label = "Install Gradle",
            command = GRADLE_INSTALL_COMMAND,
            description = "Install Gradle build tool."
        )
    )

    // Store reference to task provider for cache management
    private var taskProvider: GradleProjectTaskProvider? = null

    override fun activate(context: PluginContext) {
        val commandService = context.services.require(IdeServices.COMMAND_EXECUTION)
        val owner = context.descriptor.id

        // Create task provider instance and store it
        val gradleTaskProvider = GradleProjectTaskProvider.getInstance(commandService)
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

    /**
     * Get the task provider instance for cache management.
     */
    fun getTaskProvider(): GradleProjectTaskProvider? = taskProvider
}

private object GradleProjectTypeProvider : ProjectTypeProvider {
    override val id = "org.cosmicide.plugins.gradle.projectType"
    override val displayName = "Gradle projects"
    override val description = "Recognizes projects containing Gradle wrapper or build files"
    override val languageName = "Java/Kotlin/Groovy/Scala"
    override val fileExtension = "java"

    override fun supports(projectRoot: File): Boolean {
        return projectRoot.resolve("gradlew").isFile ||
                projectRoot.resolve("gradlew.bat").isFile ||
                projectRoot.resolve("build.gradle").isFile ||
                projectRoot.resolve("build.gradle.kts").isFile ||
                projectRoot.resolve("settings.gradle").isFile ||
                projectRoot.resolve("settings.gradle.kts").isFile
    }
}

private class GradleProjectCreationProvider(
    private val commands: CommandExecutionService
) : ProjectCreationProvider {
    override val id = "org.cosmicide.plugins.gradle.createProject"
    override val displayName = "New Gradle project"
    override val description = "Create a Gradle project using the Gradle wrapper"
    override val actionLabel = "Create"

    override val fields = listOf(
        PluginFormField(
            id = "name",
            label = "Project name",
            placeholder = "my-application",
            required = true
        ),
        PluginFormField(
            id = "language",
            label = "Language",
            type = PluginFormFieldType.CHOICE,
            defaultValue = "java",
            options = listOf(
                PluginFormOption("java", "Java"),
                PluginFormOption("kotlin", "Kotlin"),
                PluginFormOption("groovy", "Groovy"),
                PluginFormOption("scala", "Scala")
            )
        ),
        PluginFormField(
            id = "template",
            label = "Template",
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
        require(name.matches(PROJECT_NAME_REGEX)) {
            "Project name may contain letters, numbers, dots, underscores, and hyphens"
        }

        val language = request.values["language"].orEmpty().ifBlank { "java" }
        require(language in SUPPORTED_LANGUAGES) { "Unsupported language" }

        val template = request.values["template"].orEmpty().ifBlank { "application" }
        require(template in SUPPORTED_TEMPLATES) { "Unsupported template" }

        val projectsRoot = request.projectsDirectory.canonicalFile
        val root = projectsRoot.resolve(name).canonicalFile
        require(root.parentFile == projectsRoot) { "Invalid project name" }
        require(!root.exists()) { "A project with this name already exists" }

        reporter.report(OperationUpdate("Creating Gradle project with Gradle wrapper...\n"))
        
        try {
            check(root.mkdirs()) { "Could not create the project directory" }
            
            // Use Gradle wrapper to create the project
            val result = commands.execute(
                CommandRequest(
                    command = "$GRADLE_WRAPPER_COMMAND",
                    arguments = listOf(
                        "new",
                        when (language) {
                            "java" -> "java"
                            "kotlin" -> "kotlin"
                            "groovy" -> "groovy"
                            "scala" -> "scala"
                            else -> "java"
                        },
                        when (template) {
                            "application" -> "application"
                            "library" -> "library"
                            else -> "application"
                        },
                        "--name", name
                    ),
                    workingDirectory = root
                )
            ) { output ->
                reporter.report(
                    OperationUpdate(
                        message = output,
                        kind = org.cosmicide.project.OperationMessageKind.OUTPUT
                    )
                )
            }
            
            if (!result.successful) {
                if (root.exists()) root.deleteRecursively()
                error(
                    result.output.lineSequence().lastOrNull { it.isNotBlank() }
                        ?: "Gradle project creation failed with exit code ${result.exitCode}"
                )
            }
            
            // Create .gitignore
            root.resolve(".gitignore").writeText(
                "/build/\n" +
                "**/build/\n" +
                ".gradle/\n" +
                "*.iml\n" +
                ".idea/\n"
            )
        } catch (failure: Throwable) {
            if (root.exists()) root.deleteRecursively()
            throw failure
        }

        return ProjectCreationResult(
            project = GradleProjectTypeProvider.project(root),
            message = "Gradle project created successfully with Gradle wrapper"
        )
    }
}

private object GradleProjectCommandProvider : ProjectCommandProvider {
    override val id = "org.cosmicide.plugins.gradle.commands"
    override val displayName = "Gradle commands"
    override val description = "Build, run, test, and maintain Gradle projects"

    override fun commands(project: Project): List<ProjectCommand> {
        if (!GradleProjectTypeProvider.supports(project.root)) return emptyList()

        val hasGradleWrapper = project.root.resolve("gradlew").isFile
        val gradleCommand = if (hasGradleWrapper) "./gradlew" else "gradle"
        val runTargets = gradleRunTargets(project.root)
        
        return buildList {
            add(
                command(
                    name = "sync",
                    command = "$gradleCommand build --refresh-dependencies",
                    label = "Sync project",
                    description = "Sync Gradle project and refresh dependencies",
                    kind = ProjectCommandKind.SYNC
                )
            )
            add(
                command(
                    name = "resolve",
                    command = "$gradleCommand dependencies",
                    label = "Resolve dependencies",
                    description = "Resolve project dependencies",
                    kind = ProjectCommandKind.SYNC
                )
            )
            add(
                command(
                    name = "build",
                    command = "$gradleCommand build",
                    label = "Build",
                    description = "Compile and build the project",
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
                                description = "Build and run ${target.mainClass}",
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

class GradleProjectTaskProvider(
    private val commandService: CommandExecutionService
) : ProjectTaskProvider {
    override val id = "org.cosmicide.plugins.gradle.tasks"
    override val displayName = "Gradle tasks"
    override val description = "All available Gradle tasks from the project"

    // Cache for task lists, keyed by project root path and gradle command
    private val taskCache = mutableMapOf<String, List<ProjectTask>>()

    // Track projects that have been synced and need cache refresh
    private val syncedProjects = mutableSetOf<String>()

    override fun supports(project: Project): Boolean =
        GradleProjectTypeProvider.supports(project.root)

    override suspend fun tasks(project: Project): List<ProjectTask> {
        val hasGradleWrapper = project.root.resolve("gradlew").isFile
        val gradleCommand = if (hasGradleWrapper) "./gradlew" else "gradle"
        val cacheKey = "${project.root.absolutePath}:$gradleCommand"
        
        // Check if this project was recently synced and clear cache if so
        val projectPath = project.root.absolutePath
        if (syncedProjects.contains(projectPath)) {
            taskCache.remove(cacheKey)
            syncedProjects.remove(projectPath)
        }
        
        // Return cached tasks if available
        taskCache[cacheKey]?.let { cachedTasks ->
            return cachedTasks
        }
        
        // Otherwise fetch and cache the tasks
        val tasks = gradleTasks(project.root, gradleCommand, commandService)
        taskCache[cacheKey] = tasks
        return tasks
    }

    /**
     * Clear the task cache for a specific project.
     * This should be called after sync operations or when project files change.
     */
    fun clearCache(projectRoot: File) {
        val cacheKeysToRemove = taskCache.keys.filter { it.startsWith(projectRoot.absolutePath) }
        cacheKeysToRemove.forEach { taskCache.remove(it) }
    }

    /**
     * Mark a project as synced, which will trigger cache invalidation
     * on the next tasks() call.
     */
    fun onProjectSynced(projectRoot: File) {
        syncedProjects.add(projectRoot.absolutePath)
    }

    /**
     * Clear the cache immediately after sync for immediate refresh.
     */
    fun onSyncCompleted(projectRoot: File) {
        clearCache(projectRoot)
    }

    companion object {
        // Singleton instance for easy access from other parts of the plugin
        @Volatile
        private var instance: GradleProjectTaskProvider? = null

        fun getInstance(commandService: CommandExecutionService): GradleProjectTaskProvider {
            return instance ?: synchronized(this) {
                instance ?: GradleProjectTaskProvider(commandService).also { instance = it }
            }
        }
    }
}



internal suspend fun gradleTasks(
    projectRoot: File,
    gradleCommand: String,
    commandService: CommandExecutionService
): List<ProjectTask> {
    val tasks = mutableListOf<ProjectTask>()
    
    // Try to get actual tasks from gradle tasks --all
    val result = commandService.execute(
        CommandRequest(
            command = gradleCommand,
            arguments = listOf("tasks", "--all", "--quiet"),
            workingDirectory = projectRoot
        )
    ) { output ->
        // Ignore output for now, we'll parse the result
    }
    
    if (result.successful && result.output.isNotBlank()) {
        // Parse the output of `gradle tasks --all`
        // Format is typically:
        // Tasks runnable from root project 'xxx'
        // 
        // Build tasks
        // ------------
        // assemble - Assembles the outputs of this project.
        // build - Assembles and tests this project.
        // ...
        
        val lines = result.output.lines()
        var currentGroup = "Other"
        var inTaskSection = false
        
        for (line in lines) {
            val trimmed = line.trim()
            
            // Skip empty lines
            if (trimmed.isEmpty()) {
                inTaskSection = false
                continue
            }
            
            // Detect group headers (e.g., "Build tasks", "Verification tasks")
            if (trimmed.endsWith("tasks") && trimmed != "tasks") {
                currentGroup = trimmed.removeSuffix(" tasks")
                inTaskSection = true
                continue
            }
            
            // Skip separator lines
            if (trimmed.all { it == '-' }) {
                continue
            }
            
            // Skip the header line like "Tasks runnable from root project 'xxx'"
            if (trimmed.startsWith("Tasks runnable from") || 
                trimmed.startsWith("To see all tasks") ||
                trimmed.startsWith("To see more detail")) {
                continue
            }
            
            // Parse task lines: "taskName - Description"
            if (inTaskSection && trimmed.isNotEmpty()) {
                val match = GRADLE_TASK_LINE_REGEX.matchEntire(trimmed)
                if (match != null) {
                    val taskName = match.groupValues[1]
                    val taskDescription = match.groupValues.getOrNull(2) ?: ""
                    
                    tasks.add(
                        ProjectTask(
                            id = "org.cosmicide.plugins.gradle.tasks.${currentGroup.commandId()}.${taskName.commandId()}",
                            label = taskName,
                            command = "$gradleCommand $taskName",
                            description = taskDescription.ifBlank { "Run Gradle task: $taskName" },
                            group = currentGroup
                        )
                    )
                }
            }
        }
    }
    
    // If we couldn't get tasks from gradle, return fallback common tasks
    if (tasks.isEmpty()) {
        tasks.add(
            ProjectTask(
                id = "org.cosmicide.plugins.gradle.tasks.build",
                label = "build",
                command = "$gradleCommand build",
                description = "Assembles and tests this project",
                group = "Build"
            )
        )
        tasks.add(
            ProjectTask(
                id = "org.cosmicide.plugins.gradle.tasks.clean",
                label = "clean",
                command = "$gradleCommand clean",
                description = "Deletes the build directory",
                group = "Build"
            )
        )
        tasks.add(
            ProjectTask(
                id = "org.cosmicide.plugins.gradle.tasks.test",
                label = "test",
                command = "$gradleCommand test",
                description = "Runs the unit tests",
                group = "Verification"
            )
        )
        tasks.add(
            ProjectTask(
                id = "org.cosmicide.plugins.gradle.tasks.dependencies",
                label = "dependencies",
                command = "$gradleCommand dependencies",
                description = "Displays all dependencies declared in the project",
                group = "Help"
            )
        )
        tasks.add(
            ProjectTask(
                id = "org.cosmicide.plugins.gradle.tasks.tasksAll",
                label = "tasks",
                command = "$gradleCommand tasks --all",
                description = "List all available Gradle tasks",
                group = "Help"
            )
        )
    }
    
    return tasks.distinctBy(ProjectTask::id)
}

internal data class GradleRunTarget(
    val modulePath: String,
    val mainClass: String
) {
    val label: String
        get() = if (modulePath == ".") mainClass else "$modulePath — $mainClass"

    val commandId: String
        get() = "$modulePath:$mainClass".commandId()

    fun command(gradleCommand: String): String {
        return if (modulePath == ".") {
            "$gradleCommand run"
        } else {
            val module = modulePath.shellQuote()
            "$gradleCommand -p $module run"
        }
    }
}

internal fun gradleRunTargets(projectRoot: File): List<GradleRunTarget> {
    val targets = mutableListOf<GradleRunTarget>()
    
    // Check build files for mainClass configuration
    val buildFiles = listOf(
        projectRoot.resolve("build.gradle.kts"),
        projectRoot.resolve("build.gradle")
    )
    
    for (buildFile in buildFiles) {
        if (buildFile.isFile) {
            val text = runCatching { buildFile.readText() }.getOrDefault("")
            
            // Look for mainClass in Kotlin DSL: mainClass = "com.example.Main"
            val mainClassMatch = GRADLE_MAIN_CLASS_REGEX.find(text)
            if (mainClassMatch != null) {
                val mainClass = mainClassMatch.groupValues[1]
                targets.add(GradleRunTarget(".", mainClass))
            }
        }
    }
    
    // Scan source directories for main methods
    if (targets.isEmpty()) {
        val sourcePaths = listOf(
            "src/main/java",
            "src/main/kotlin",
            "src/main/groovy",
            "src/main/scala"
        )
        
        for (sourcePath in sourcePaths) {
            val sourceRoot = projectRoot.resolve(sourcePath)
            if (sourceRoot.isDirectory) {
                val mainClasses = findMainClasses(sourceRoot, sourcePath.substringAfterLast("/"))
                mainClasses.forEach { mainClass ->
                    targets.add(GradleRunTarget(".", mainClass))
                }
            }
        }
    }
    
    return targets.distinctBy { "${it.modulePath}:${it.mainClass}" }
}

private fun findMainClasses(root: File, extension: String): List<String> {
    return root.walkTopDown()
        .filter { it.isFile && it.extension == extension }
        .mapNotNull { source ->
            val text = runCatching { source.readText() }.getOrDefault("")
            if (!JAVA_MAIN_METHOD_REGEX.containsMatchIn(text)) return@mapNotNull null
            
            val packageName = JAVA_PACKAGE_DECLARATION_REGEX
                .find(text)
                ?.groupValues
                ?.get(1)
            val className = source.nameWithoutExtension
            
            if (packageName != null) {
                "$packageName.$className"
            } else {
                className
            }
        }
        .distinct()
        .toList()
}

private fun String.shellQuote(): String = "'${replace("'", "'\\''\\'")}'"

private fun String.commandId(): String {
    val readable = replace(Regex("[^A-Za-z0-9_.-]"), "_")
    return "$readable.${hashCode().toUInt().toString(16)}"
}

private const val GRADLE_INSTALL_COMMAND = "pacman -S --needed gradle"
private const val GRADLE_WRAPPER_COMMAND = "gradle"

private val SUPPORTED_LANGUAGES = setOf("java", "kotlin", "groovy", "scala")
private val SUPPORTED_TEMPLATES = setOf("application", "library")

private val PROJECT_NAME_REGEX = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")

private val JAVA_MAIN_METHOD_REGEX = Regex("""\bpublic\s+static\s+void\s+main\s*\(\s*String\s*\(\s*\)""")
private val JAVA_PACKAGE_DECLARATION_REGEX = Regex("""(?m)^\s*package\s+([A-Za-z_][A-Za-z0-9_.]*)\s*;""")

private val GRADLE_TASK_LINE_REGEX = Regex("""^([^\s]+)\s*-\s*(.*)$""")
private val GRADLE_MAIN_CLASS_REGEX = Regex("""mainClass\s*=\s*["']([^"']+)["']""")
