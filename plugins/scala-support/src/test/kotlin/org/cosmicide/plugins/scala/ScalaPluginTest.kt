package org.cosmicide.plugins.scala

import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScalaPluginTest {
    @Test
    fun detectsBuildToolsInPrecedenceOrder() {
        val root = createTempDirectory("scala-plugin-test").toFile()
        try {
            assertEquals(ScalaBuildTool.SCALA_CLI, scalaBuildTool(root))
            root.resolve("pom.xml").writeText("<project/>")
            assertEquals(ScalaBuildTool.MAVEN, scalaBuildTool(root))
            root.resolve("gradlew").writeText("#!/bin/sh")
            assertEquals(ScalaBuildTool.GRADLE, scalaBuildTool(root))
            root.resolve("build.sbt").writeText("")
            assertEquals(ScalaBuildTool.SBT, scalaBuildTool(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun createsDeterministicScalaThreeBuildDefinition() {
        val build = scalaBuildSbt("hello-scala", "com.example")

        assertTrue(build.contains("""organization := "com.example""""))
        assertTrue(build.contains("""scalaVersion := "3.3.8""""))
        assertTrue(build.contains("""name := "hello-scala""""))
    }

    @Test
    fun buildToolCommandsUseProjectLocalWrappersWhereAvailable() {
        assertEquals("./gradlew build", ScalaBuildTool.GRADLE.command("build"))
        assertEquals("sbt test", ScalaBuildTool.SBT.command("test"))
        assertEquals("scala-cli run .", ScalaBuildTool.SCALA_CLI.command("run"))
    }
}
