package org.cosmicide.plugins.maven

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

class MavenPlugin : CosmicPlugin {
    override val setupActions = listOf(
        PluginSetupAction(
            id = "org.cosmicide.plugins.maven.installMaven",
            label = "Install Maven",
            command = MAVEN_INSTALL_COMMAND,
            description = "Install Apache Maven."
        )
    )

    // Store reference to task provider for cache management
    private var taskProvider: MavenProjectTaskProvider? = null

    override fun activate(context: PluginContext) {
        val commandService = context.services.require(IdeServices.COMMAND_EXECUTION)
        val owner = context.descriptor.id

        // Create task provider instance using singleton
        val mavenTaskProvider = MavenProjectTaskProvider.getInstance()
        taskProvider = mavenTaskProvider

        listOf(
            context.extensions.register(
                point = ProjectExtensionPoints.TYPE_PROVIDER,
                extension = MavenProjectTypeProvider,
                ownerPluginId = owner,
                priority = 350
            ),
            context.extensions.register(
                point = ProjectExtensionPoints.CREATION_PROVIDER,
                extension = MavenProjectCreationProvider(commandService),
                ownerPluginId = owner,
                priority = 350
            ),
            context.extensions.register(
                point = ProjectExtensionPoints.COMMAND_PROVIDER,
                extension = MavenProjectCommandProvider,
                ownerPluginId = owner,
                priority = 350
            ),
            context.extensions.register(
                point = ProjectExtensionPoints.TASK_PROVIDER,
                extension = mavenTaskProvider,
                ownerPluginId = owner,
                priority = 350
            )
        ).forEach(context::registerDisposable)

        context.logger.info("Maven project support registered")
    }

    /**
     * Get the task provider instance for cache management.
     */
    fun getTaskProvider(): MavenProjectTaskProvider? = taskProvider
}

private object MavenProjectTypeProvider : ProjectTypeProvider {
    override val id = "org.cosmicide.plugins.maven.projectType"
    override val displayName = "Maven projects"
    override val description = "Recognizes projects containing pom.xml"
    override val languageName = "Java"
    override val fileExtension = "java"

    override fun supports(projectRoot: File): Boolean {
        return projectRoot.resolve("pom.xml").isFile
    }
}

private class MavenProjectCreationProvider(
    private val commands: CommandExecutionService
) : ProjectCreationProvider {
    override val id = "org.cosmicide.plugins.maven.createProject"
    override val displayName = "New Maven project"
    override val description =
        "Generate a Java quickstart, simple JAR, web app, or multi-module build"
    override val actionLabel = "Create"

    override val fields = listOf(
        PluginFormField(
            id = "artifact_id",
            label = "Artifact ID",
            placeholder = "my-application",
            required = true
        ),
        PluginFormField(
            id = "group_id",
            label = "Group ID",
            defaultValue = "com.example",
            placeholder = "com.example",
            required = true
        ),
        PluginFormField(
            id = "version",
            label = "Version",
            defaultValue = "1.0-SNAPSHOT",
            required = true
        ),
        PluginFormField(
            id = "package_name",
            label = "Java package",
            description = "Leave blank to use the group ID.",
            placeholder = "com.example.app"
        ),
        PluginFormField(
            id = "kind",
            label = "Project type",
            type = PluginFormFieldType.CHOICE,
            defaultValue = "application",
            options = listOf(
                PluginFormOption("application", "Java quickstart"),
                PluginFormOption("library", "Simple JAR project"),
                PluginFormOption("webapp", "Web application"),
                PluginFormOption("multi-module", "Multi-module application")
            )
        )
    )

    override suspend fun create(
        request: ProjectCreationRequest,
        reporter: OperationReporter
    ): ProjectCreationResult {
        val artifactId = request.values["artifact_id"].orEmpty().trim()
        require(artifactId.matches(ARTIFACT_ID_REGEX)) {
            "Artifact ID may contain letters, numbers, dots, underscores, and hyphens"
        }

        val groupId = request.values["group_id"].orEmpty().trim()
        require(groupId.matches(JAVA_PACKAGE_REGEX)) {
            "Group ID must be a dot-separated Java identifier"
        }

        val version = request.values["version"].orEmpty().trim()
        require(version.matches(VERSION_REGEX)) {
            "Version contains unsupported characters"
        }

        val packageName = request.values["package_name"].orEmpty().trim().ifBlank { groupId }
        require(packageName.matches(JAVA_PACKAGE_REGEX)) {
            "Package must be a dot-separated Java identifier"
        }

        val kind = request.values["kind"].orEmpty().ifBlank { "application" }
        require(kind in MAVEN_PROJECT_KINDS) { "Unsupported Maven project type" }

        val projectsRoot = request.projectsDirectory.canonicalFile
        val root = projectsRoot.resolve(artifactId).canonicalFile
        require(root.parentFile == projectsRoot) { "Invalid artifact ID" }
        require(!root.exists()) { "A project with this artifact ID already exists" }

        reporter.report(OperationUpdate("Creating Maven project…\n"))
        try {
            when (kind) {
                "multi-module" -> {
                    check(root.mkdirs()) { "Could not create the project directory" }
                    createMultiModule(root, groupId, artifactId, version, packageName)
                }
                else -> generateFromArchetype(
                    projectsRoot = projectsRoot,
                    root = root,
                    kind = kind,
                    groupId = groupId,
                    artifactId = artifactId,
                    version = version,
                    packageName = packageName,
                    reporter = reporter
                )
            }
            check(root.resolve("pom.xml").isFile) {
                "Maven project generation completed without creating pom.xml"
            }
            root.resolve(".gitignore").writeText("target/\n**/target/\n")
        } catch (failure: Throwable) {
            if (root.exists()) root.deleteRecursively()
            throw failure
        }

        return ProjectCreationResult(
            project = MavenProjectTypeProvider.project(root),
            message = "Maven project created successfully"
        )
    }

    private suspend fun generateFromArchetype(
        projectsRoot: File,
        root: File,
        kind: String,
        groupId: String,
        artifactId: String,
        version: String,
        packageName: String,
        reporter: OperationReporter
    ) {
        val archetype = checkNotNull(MAVEN_ARCHETYPES[kind]) {
            "No Maven archetype configured for project type '$kind'"
        }
        reporter.report(
            OperationUpdate(
                "Generating ${archetype.label} with Maven Archetype…\n"
            )
        )
        val result = commands.execute(
            CommandRequest(
                command = "mvn",
                arguments = mavenArchetypeArguments(
                    archetype = archetype,
                    groupId = groupId,
                    artifactId = artifactId,
                    version = version,
                    packageName = packageName
                ),
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
        if (!result.successful) {
            if (root.exists()) root.deleteRecursively()
            error(
                result.output.lineSequence().lastOrNull { it.isNotBlank() }
                    ?: "mvn archetype:generate failed with exit code ${result.exitCode}"
            )
        }
    }

    private fun createMultiModule(
        root: File,
        groupId: String,
        artifactId: String,
        version: String,
        packageName: String
    ) {
        root.resolve("pom.xml").writeText(parentPom(groupId, artifactId, version))

        val libraryRoot = root.resolve("library")
        val appRoot = root.resolve("app")
        check(libraryRoot.mkdirs() && appRoot.mkdirs()) {
            "Could not create Maven modules"
        }

        libraryRoot.resolve("pom.xml").writeText(
            modulePom(
                groupId = groupId,
                parentArtifactId = artifactId,
                version = version,
                artifactId = "library"
            )
        )
        libraryRoot.writeJavaSource(
            "$packageName.library",
            "Greeting.java",
            """
                package $packageName.library;

                public final class Greeting {
                    private Greeting() {}

                    public static String message() {
                        return "Hello from $artifactId!";
                    }
                }
            """
        )

        val mainClass = "$packageName.app.Main"
        appRoot.resolve("pom.xml").writeText(
            modulePom(
                groupId = groupId,
                parentArtifactId = artifactId,
                version = version,
                artifactId = "app",
                dependencyArtifactId = "library",
                execMainClass = mainClass
            )
        )
        appRoot.writeJavaSource(
            "$packageName.app",
            "Main.java",
            """
                package $packageName.app;

                import $packageName.library.Greeting;

                public final class Main {
                    private Main() {}

                    public static void main(String[] args) {
                        System.out.println(Greeting.message());
                    }
                }
            """
        )
    }
}

private object MavenProjectCommandProvider : ProjectCommandProvider {
    override val id = "org.cosmicide.plugins.maven.commands"
    override val displayName = "Maven commands"
    override val description = "Build, run, test, and inspect Maven projects"

    override fun commands(project: Project): List<ProjectCommand> {
        if (!MavenProjectTypeProvider.supports(project.root)) return emptyList()

        val runTargets = mavenRunTargets(project.root)
        return buildList {
            add(
                command(
                    name = "sync",
                    command = "mvn dependency:resolve dependency:go-offline",
                    label = "Sync project",
                    description = "Sync Maven project and resolve dependencies",
                    kind = ProjectCommandKind.SYNC
                )
            )
            add(
                command(
                    name = "resolve",
                    command = "mvn dependency:go-offline",
                    label = "Resolve dependencies",
                    description = "Resolve project dependencies and plugins",
                    kind = ProjectCommandKind.SYNC
                )
            )
            add(
                command(
                    name = "build",
                    command = "mvn package",
                    label = "Build",
                    description = "Compile, test, and package the project",
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
                                command = target.command,
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
                    command = "mvn test",
                    label = "Test",
                    description = "Run the Maven test phase"
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

class MavenProjectTaskProvider : ProjectTaskProvider {
    override val id = "org.cosmicide.plugins.maven.tasks"
    override val displayName = "Maven goals"
    override val description =
        "Choose a lifecycle phase or plugin goal discovered from the Maven project"

    // Cache for task lists, keyed by project root path
    private val taskCache = mutableMapOf<String, List<ProjectTask>>()

    // Track projects that have been synced and need cache refresh
    private val syncedProjects = mutableSetOf<String>()

    override fun supports(project: Project): Boolean =
        MavenProjectTypeProvider.supports(project.root)

    override suspend fun tasks(project: Project): List<ProjectTask> {
        val cacheKey = project.root.absolutePath

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
        val tasks = mavenTasks(project.root)
        taskCache[cacheKey] = tasks
        return tasks
    }

    /**
     * Clear the task cache for a specific project.
     * This should be called after sync operations or when project files change.
     */
    fun clearCache(projectRoot: File) {
        val cacheKey = projectRoot.absolutePath
        taskCache.remove(cacheKey)
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
        private var instance: MavenProjectTaskProvider? = null

        fun getInstance(): MavenProjectTaskProvider {
            return instance ?: synchronized(this) {
                instance ?: MavenProjectTaskProvider().also { instance = it }
            }
        }
    }
}

internal fun mavenTasks(projectRoot: File): List<ProjectTask> {
    val detectedPlugins = mavenPluginArtifactIds(projectRoot)
    val configuredGoals = mavenConfiguredGoals(projectRoot)
    val packaging = mavenRootPackaging(projectRoot)

    return buildList {
        addGoalTasks(
            group = "Lifecycle",
            goals = listOf(
                "clean" to "Clean",
                "validate" to "Validate",
                "compile" to "Compile",
                "test" to "Test",
                "package" to "Package",
                "verify" to "Verify",
                "install" to "Install locally"
            )
        )
        addGoalTasks(
            group = "Dependencies",
            goals = listOf(
                "dependency:resolve" to "Resolve",
                "dependency:tree" to "Dependency tree",
                "dependency:analyze" to "Analyze",
                "dependency:resolve-sources" to "Download sources",
                "dependency:copy-dependencies" to "Copy dependencies"
            )
        )
        addGoalTasks(
            group = "Project information",
            goals = listOf(
                "help:effective-pom" to "Effective POM",
                "help:active-profiles" to "Active profiles",
                "help:effective-settings" to "Effective settings",
                "help:all-profiles" to "All profiles"
            )
        )
        addGoalTasks(
            group = "Documentation",
            goals = listOf(
                "javadoc:javadoc" to "Generate Javadoc",
                "site:site" to "Generate project site"
            )
        )
        addGoalTasks(
            group = "Plugin goals",
            goals = detectedPluginGoals(packaging, detectedPlugins)
        )
        addGoalTasks(
            group = "Configured executions",
            goals = configuredGoals.map { goal -> goal to goal }
        )
        add(
            ProjectTask(
                id = "org.cosmicide.plugins.maven.tasks.custom",
                label = "Custom goals…",
                command = CUSTOM_MAVEN_GOALS_COMMAND,
                description = "Enter any Maven goals and options in the terminal",
                group = "Other"
            )
        )
    }.distinctBy(ProjectTask::id)
}

private fun MutableList<ProjectTask>.addGoalTasks(
    group: String,
    goals: List<Pair<String, String>>
) {
    goals.forEach { (goal, label) ->
        add(
            ProjectTask(
                id = "org.cosmicide.plugins.maven.tasks.${group.commandId()}.${goal.commandId()}",
                label = label,
                command = "mvn $goal",
                description = "Run mvn $goal",
                group = group
            )
        )
    }
}

private fun detectedPluginGoals(
    packaging: String,
    plugins: Set<String>
): List<Pair<String, String>> = buildList {
    if (packaging == "war" || "maven-war-plugin" in plugins) {
        add("war:war" to "Build WAR")
        add("war:exploded" to "Exploded WAR")
        add("war:inplace" to "In-place WAR")
    }
    if ("exec-maven-plugin" in plugins) {
        add("exec:java" to "Exec Java")
        add("exec:exec" to "Exec program")
    }
    if ("spring-boot-maven-plugin" in plugins) {
        add("spring-boot:run" to "Spring Boot run")
        add("spring-boot:repackage" to "Spring Boot repackage")
    }
    if ("quarkus-maven-plugin" in plugins) {
        add("quarkus:dev" to "Quarkus dev")
        add("quarkus:build" to "Quarkus build")
    }
    if ("javafx-maven-plugin" in plugins) {
        add("javafx:run" to "JavaFX run")
    }
    if ("maven-shade-plugin" in plugins) {
        add("shade:shade" to "Shade JAR")
    }
    if ("maven-assembly-plugin" in plugins) {
        add("assembly:single" to "Build assembly")
    }
    if ("maven-checkstyle-plugin" in plugins) {
        add("checkstyle:check" to "Checkstyle")
    }
    if ("spotless-maven-plugin" in plugins) {
        add("spotless:check" to "Spotless check")
        add("spotless:apply" to "Spotless apply")
    }
    if ("maven-javadoc-plugin" in plugins) {
        add("javadoc:javadoc" to "Generate Javadoc")
    }
}

internal data class MavenRunTarget(
    val modulePath: String,
    val mainClass: String
) {
    val label: String
        get() = if (modulePath == ".") mainClass else "$modulePath — $mainClass"

    val commandId: String
        get() = "$modulePath:$mainClass".commandId()

    val command: String
        get() {
            val execGoal = "org.codehaus.mojo:exec-maven-plugin:$EXEC_PLUGIN_VERSION:java"
            val mainOption = "-Dexec.mainClass=${mainClass.shellQuote()}"
            return if (modulePath == ".") {
                "mvn compile $execGoal $mainOption"
            } else {
                val module = modulePath.shellQuote()
                "mvn -pl $module -am install -DskipTests && " +
                        "mvn -pl $module $execGoal $mainOption"
            }
        }
}

internal fun mavenRunTargets(projectRoot: File): List<MavenRunTarget> {
    return mavenPomFiles(projectRoot)
        .flatMap { pom ->
            val moduleRoot = checkNotNull(pom.parentFile)
            val modulePath = moduleRoot.relativeTo(projectRoot)
                .invariantSeparatorsPath
                .ifBlank { "." }
            val sourceRoot = moduleRoot.resolve("src/main/java")
            if (!sourceRoot.isDirectory) {
                emptySequence()
            } else {
                sourceRoot.walkTopDown()
                    .filter { source -> source.isFile && source.extension == "java" }
                    .mapNotNull { source ->
                        val text = runCatching { source.readText() }.getOrDefault("")
                        if (!JAVA_MAIN_METHOD_REGEX.containsMatchIn(text)) return@mapNotNull null
                        val packageName = JAVA_PACKAGE_DECLARATION_REGEX
                            .find(text)
                            ?.groupValues
                            ?.get(1)
                        val className = source.nameWithoutExtension
                        MavenRunTarget(
                            modulePath = modulePath,
                            mainClass = listOfNotNull(packageName, className).joinToString(".")
                        )
                    }
            }
        }
        .distinctBy { "${it.modulePath}:${it.mainClass}" }
        .toList()
}

internal fun mavenPluginArtifactIds(projectRoot: File): Set<String> {
    return mavenPomFiles(projectRoot)
        .flatMap { pom ->
            val text = runCatching { pom.readText() }.getOrDefault("")
            MAVEN_PLUGIN_BLOCK_REGEX.findAll(text).mapNotNull { plugin ->
                MAVEN_ARTIFACT_ID_REGEX.find(plugin.groupValues[1])
                    ?.groupValues
                    ?.get(1)
                    ?.lowercase()
            }
        }
        .toSet()
}

internal fun mavenConfiguredGoals(projectRoot: File): List<String> {
    return mavenPomFiles(projectRoot)
        .flatMap { pom ->
            val text = runCatching { pom.readText() }.getOrDefault("")
            MAVEN_PLUGIN_BLOCK_REGEX.findAll(text).flatMap { plugin ->
                val pluginBody = plugin.groupValues[1]
                val artifactId = MAVEN_ARTIFACT_ID_REGEX
                    .find(pluginBody)
                    ?.groupValues
                    ?.get(1)
                    ?: return@flatMap emptySequence()
                val prefix = mavenPluginPrefix(artifactId)
                MAVEN_GOAL_REGEX.findAll(pluginBody).map { goal ->
                    "$prefix:${goal.groupValues[1]}"
                }
            }
        }
        .distinct()
        .toList()
}

internal fun mavenRootPackaging(projectRoot: File): String {
    val text = runCatching { projectRoot.resolve("pom.xml").readText() }.getOrDefault("")
    return MAVEN_PACKAGING_REGEX.find(text)?.groupValues?.get(1)?.trim().orEmpty().ifBlank { "jar" }
}

private fun mavenPomFiles(projectRoot: File): Sequence<File> {
    return projectRoot.walkTopDown()
        .onEnter { directory ->
            directory == projectRoot ||
                    directory.name !in MAVEN_SCAN_EXCLUDED_DIRECTORIES
        }
        .filter { it.isFile && it.name == "pom.xml" }
        .sortedWith(
            compareBy<File>(
                { it.relativeTo(projectRoot).invariantSeparatorsPath.count { char -> char == '/' } },
                { it.relativeTo(projectRoot).invariantSeparatorsPath }
            )
        )
}

private fun File.writeJavaSource(
    packageName: String,
    fileName: String,
    source: String
) {
    val directory = resolve("src/main/java/${packageName.replace('.', '/')}")
    check(directory.mkdirs()) { "Could not create Java source directory" }
    directory.resolve(fileName).writeText(source.trimIndent() + "\n")
}

internal data class MavenArchetype(
    val artifactId: String,
    val version: String,
    val label: String,
    val properties: Map<String, String> = emptyMap()
)

internal fun mavenArchetypeArguments(
    archetype: MavenArchetype,
    groupId: String,
    artifactId: String,
    version: String,
    packageName: String
): List<String> = buildList {
    add(MAVEN_ARCHETYPE_GENERATE_GOAL)
    add("-B")
    add("-DarchetypeCatalog=internal")
    add("-DarchetypeGroupId=$MAVEN_ARCHETYPE_GROUP")
    add("-DarchetypeArtifactId=${archetype.artifactId}")
    add("-DarchetypeVersion=${archetype.version}")
    add("-DgroupId=$groupId")
    add("-DartifactId=$artifactId")
    add("-Dversion=$version")
    add("-Dpackage=$packageName")
    archetype.properties.forEach { (name, value) ->
        add("-D$name=$value")
    }
}

internal fun parentPom(
    groupId: String,
    artifactId: String,
    version: String
): String = """
    <?xml version="1.0" encoding="UTF-8"?>
    <project xmlns="http://maven.apache.org/POM/4.0.0"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
      <modelVersion>4.0.0</modelVersion>

      <groupId>$groupId</groupId>
      <artifactId>$artifactId</artifactId>
      <version>$version</version>
      <packaging>pom</packaging>

    ${mavenProperties().prependIndent("  ")}
      <modules>
        <module>library</module>
        <module>app</module>
      </modules>
    </project>
""".trimIndent().trimStart() + "\n"

internal fun modulePom(
    groupId: String,
    parentArtifactId: String,
    version: String,
    artifactId: String,
    dependencyArtifactId: String? = null,
    execMainClass: String? = null
): String {
    val dependency = dependencyArtifactId?.let {
        """
            <dependencies>
              <dependency>
                <groupId>$groupId</groupId>
                <artifactId>$it</artifactId>
                <version>${'$'}{project.version}</version>
              </dependency>
            </dependencies>
        """.trimIndent()
    }.orEmpty()

    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <project xmlns="http://maven.apache.org/POM/4.0.0"
                 xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                 xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
          <modelVersion>4.0.0</modelVersion>

          <parent>
            <groupId>$groupId</groupId>
            <artifactId>$parentArtifactId</artifactId>
            <version>$version</version>
          </parent>

          <artifactId>$artifactId</artifactId>

        ${dependency.prependIndent("  ")}
        ${mavenBuild(execMainClass).prependIndent("  ")}
        </project>
    """.trimIndent().trimStart() + "\n"
}

private fun mavenProperties(): String = """
    <properties>
      <maven.compiler.release>17</maven.compiler.release>
      <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>
""".trimIndent()

private fun mavenBuild(execMainClass: String?): String {
    val execPlugin = execMainClass?.let {
        """
            <plugin>
              <groupId>org.codehaus.mojo</groupId>
              <artifactId>exec-maven-plugin</artifactId>
              <version>$EXEC_PLUGIN_VERSION</version>
              <configuration>
                <mainClass>$it</mainClass>
              </configuration>
            </plugin>
        """.trimIndent()
    }

    return """
        <build>
          <plugins>
            <plugin>
              <groupId>org.apache.maven.plugins</groupId>
              <artifactId>maven-compiler-plugin</artifactId>
              <version>$COMPILER_PLUGIN_VERSION</version>
            </plugin>
            <plugin>
              <groupId>org.apache.maven.plugins</groupId>
              <artifactId>maven-surefire-plugin</artifactId>
              <version>$SUREFIRE_PLUGIN_VERSION</version>
            </plugin>
        ${execPlugin?.prependIndent("    ").orEmpty()}
          </plugins>
        </build>
    """.trimIndent()
}

private fun String.shellQuote(): String = "'${replace("'", "'\"'\"'")}'"

private fun String.commandId(): String {
    val readable = replace(Regex("[^A-Za-z0-9_.-]"), "_")
    return "$readable.${hashCode().toUInt().toString(16)}"
}

private fun mavenPluginPrefix(artifactId: String): String {
    return when {
        artifactId.startsWith("maven-") && artifactId.endsWith("-plugin") ->
            artifactId.removePrefix("maven-").removeSuffix("-plugin")
        artifactId.endsWith("-maven-plugin") ->
            artifactId.removeSuffix("-maven-plugin")
        artifactId.endsWith("-plugin") ->
            artifactId.removeSuffix("-plugin")
        else -> artifactId
    }
}

private const val MAVEN_INSTALL_COMMAND = "pacman -S --needed maven"
private const val MAVEN_ARCHETYPE_GENERATE_GOAL =
    "org.apache.maven.plugins:maven-archetype-plugin:3.4.1:generate"
private const val MAVEN_ARCHETYPE_GROUP = "org.apache.maven.archetypes"
private const val COMPILER_PLUGIN_VERSION = "3.14.1"
private const val SUREFIRE_PLUGIN_VERSION = "3.5.5"
private const val EXEC_PLUGIN_VERSION = "3.6.3"
private val MAVEN_ARCHETYPES = mapOf(
    "application" to MavenArchetype(
        artifactId = "maven-archetype-quickstart",
        version = "1.5",
        label = "Java quickstart",
        properties = mapOf(
            "javaCompilerVersion" to "17",
            "junitVersion" to "5.11.0"
        )
    ),
    "library" to MavenArchetype(
        artifactId = "maven-archetype-simple",
        version = "1.5",
        label = "simple JAR project"
    ),
    "webapp" to MavenArchetype(
        artifactId = "maven-archetype-webapp",
        version = "1.5",
        label = "web application"
    )
)
private val MAVEN_PROJECT_KINDS = setOf(
    "application",
    "library",
    "webapp",
    "multi-module"
)
private val MAVEN_SCAN_EXCLUDED_DIRECTORIES = setOf(
    ".git",
    ".gradle",
    ".idea",
    "build",
    "target"
)
private val ARTIFACT_ID_REGEX = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
private val JAVA_PACKAGE_REGEX =
    Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")
private val VERSION_REGEX = Regex("[A-Za-z0-9][A-Za-z0-9._+-]*")
private val JAVA_MAIN_METHOD_REGEX =
    Regex("""\bpublic\s+static\s+void\s+main\s*\(\s*String(?:\s*\[\s*]|\s+\w+\s*\[\s*])""")
private val JAVA_PACKAGE_DECLARATION_REGEX =
    Regex("""(?m)^\s*package\s+([A-Za-z_][A-Za-z0-9_.]*)\s*;""")
private val MAVEN_PACKAGING_REGEX =
    Regex("""<packaging>\s*([^<]+)\s*</packaging>""", RegexOption.IGNORE_CASE)
private val MAVEN_PLUGIN_BLOCK_REGEX = Regex(
    """<plugin\b[^>]*>(.*?)</plugin>""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
)
private val MAVEN_ARTIFACT_ID_REGEX =
    Regex("""<artifactId>\s*([A-Za-z0-9_.-]+)\s*</artifactId>""", RegexOption.IGNORE_CASE)
private val MAVEN_GOAL_REGEX =
    Regex("""<goal>\s*([A-Za-z0-9_.-]+)\s*</goal>""", RegexOption.IGNORE_CASE)
private const val CUSTOM_MAVEN_GOALS_COMMAND =
    "printf 'Maven goals and options: '; IFS= read -r goals; " +
            "if [ -z \"\$goals\" ]; then echo 'No goals entered.'; " +
            "else set -f; mvn \$goals; fi"
