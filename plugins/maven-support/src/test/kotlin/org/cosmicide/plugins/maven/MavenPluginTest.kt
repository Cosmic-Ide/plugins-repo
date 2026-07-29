package org.cosmicide.plugins.maven

import java.io.File
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xml.sax.InputSource

class MavenPluginTest {
    @Test
    fun discoversRunnableMainClassesAcrossModules() {
        withProject { root ->
            root.resolve("pom.xml").writeText("<project><packaging>pom</packaging></project>")
            writeMain(root.resolve("app"), "com.example.app", "Main")
            writeMain(root.resolve("tools"), "com.example.tools", "Tool")
            root.resolve("target/generated").mkdirs()
            writeMain(root.resolve("target/generated"), "ignored", "Generated")

            val targets = mavenRunTargets(root)

            assertEquals(
                listOf(
                    "app" to "com.example.app.Main",
                    "tools" to "com.example.tools.Tool"
                ),
                targets.map { it.modulePath to it.mainClass }
            )
            assertTrue(targets.first().command.contains("-pl 'app' -am install"))
            assertTrue(targets.first().command.contains("-Dexec.mainClass='com.example.app.Main'"))
        }
    }

    @Test
    fun detectsPackagingAndConfiguredPlugins() {
        withProject { root ->
            root.resolve("pom.xml").writeText(
                """
                    <project>
                      <packaging>war</packaging>
                      <build>
                        <plugins>
                          <plugin>
                            <artifactId>spring-boot-maven-plugin</artifactId>
                            <executions>
                              <execution>
                                <goals>
                                  <goal>repackage</goal>
                                </goals>
                              </execution>
                            </executions>
                          </plugin>
                          <plugin>
                            <artifactId>maven-war-plugin</artifactId>
                            <executions>
                              <execution>
                                <goals>
                                  <goal>war</goal>
                                </goals>
                              </execution>
                            </executions>
                          </plugin>
                        </plugins>
                      </build>
                    </project>
                """.trimIndent()
            )

            assertEquals("war", mavenRootPackaging(root))
            assertEquals(
                setOf("spring-boot-maven-plugin", "maven-war-plugin"),
                mavenPluginArtifactIds(root)
            )
            assertEquals(
                listOf("spring-boot:repackage", "war:war"),
                mavenConfiguredGoals(root)
            )
        }
    }

    @Test
    fun exposesSearchableTasksFromLifecycleAndConfiguredPlugins() {
        withProject { root ->
            root.resolve("pom.xml").writeText(
                """
                    <project>
                      <packaging>war</packaging>
                      <build>
                        <plugins>
                          <plugin>
                            <artifactId>spring-boot-maven-plugin</artifactId>
                            <executions>
                              <execution>
                                <goals><goal>repackage</goal></goals>
                              </execution>
                            </executions>
                          </plugin>
                        </plugins>
                      </build>
                    </project>
                """.trimIndent()
            )

            val tasks = mavenTasks(root)

            assertTrue(tasks.any { it.group == "Lifecycle" && it.command == "mvn clean" })
            assertTrue(tasks.any { it.group == "Plugin goals" && it.command == "mvn spring-boot:run" })
            assertTrue(
                tasks.any {
                    it.group == "Configured executions" &&
                        it.command == "mvn spring-boot:repackage"
                }
            )
            assertEquals(tasks.size, tasks.map { it.id }.distinct().size)
        }
    }

    @Test
    fun quotesModulePathsUsedInCommands() {
        val target = MavenRunTarget(
            modulePath = "apps/user's app",
            mainClass = "com.example.Main"
        )

        assertTrue(target.command.contains("""-pl 'apps/user'"'"'s app'"""))
        assertFalse(target.command.contains("apps/user's app -am"))
    }

    @Test
    fun buildsReproducibleBatchArchetypeArguments() {
        val arguments = mavenArchetypeArguments(
            archetype = MavenArchetype(
                artifactId = "maven-archetype-quickstart",
                version = "1.5",
                label = "Java quickstart",
                properties = mapOf("javaCompilerVersion" to "17")
            ),
            groupId = "com.example",
            artifactId = "hello-world",
            version = "1.0-SNAPSHOT",
            packageName = "com.example.hello"
        )

        assertEquals(
            listOf(
                "org.apache.maven.plugins:maven-archetype-plugin:3.4.1:generate",
                "-B",
                "-DarchetypeCatalog=internal",
                "-DarchetypeGroupId=org.apache.maven.archetypes",
                "-DarchetypeArtifactId=maven-archetype-quickstart",
                "-DarchetypeVersion=1.5",
                "-DgroupId=com.example",
                "-DartifactId=hello-world",
                "-Dversion=1.0-SNAPSHOT",
                "-Dpackage=com.example.hello",
                "-DjavaCompilerVersion=17"
            ),
            arguments
        )
    }

    @Test
    fun generatedMultiModulePomsAreWellFormedXml() {
        val poms = listOf(
            parentPom("com.example", "parent", "1.0"),
            modulePom(
                groupId = "com.example",
                parentArtifactId = "parent",
                version = "1.0",
                artifactId = "app",
                dependencyArtifactId = "library",
                execMainClass = "com.example.Main"
            )
        )

        poms.forEach { pom ->
            val document = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(InputSource(StringReader(pom)))
            assertEquals("project", document.documentElement.localName ?: document.documentElement.nodeName)
        }
    }

    private fun writeMain(module: File, packageName: String, className: String) {
        module.mkdirs()
        module.resolve("pom.xml").writeText("<project/>")
        val sourceDirectory =
            module.resolve("src/main/java/${packageName.replace('.', '/')}")
        sourceDirectory.mkdirs()
        sourceDirectory.resolve("$className.java").writeText(
            """
                package $packageName;

                public class $className {
                    public static void main(String[] args) {}
                }
            """.trimIndent()
        )
    }

    private fun withProject(block: (File) -> Unit) {
        val root = createTempDirectory("maven-plugin-test").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
