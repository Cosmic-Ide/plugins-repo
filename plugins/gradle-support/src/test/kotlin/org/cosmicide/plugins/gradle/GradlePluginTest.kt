package org.cosmicide.plugins.gradle

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue
import java.io.File

class GradlePluginTest {
    @Test
    fun testGradleProjectTypeProviderSupportsWithGradlew() {
        val tempDir = File(System.getProperty("java.io.tmpdir"))
        val testDir = File(tempDir, "gradle-test-${System.currentTimeMillis()}")
        testDir.mkdirs()
        
        try {
            File(testDir, "gradlew").createNewFile()
            assertTrue(GradleProjectTypeProvider.supports(testDir))
        } finally {
            testDir.deleteRecursively()
        }
    }

    @Test
    fun testGradleProjectTypeProviderSupportsWithBuildGradle() {
        val tempDir = File(System.getProperty("java.io.tmpdir"))
        val testDir = File(tempDir, "gradle-test-${System.currentTimeMillis()}")
        testDir.mkdirs()
        
        try {
            File(testDir, "build.gradle").createNewFile()
            assertTrue(GradleProjectTypeProvider.supports(testDir))
        } finally {
            testDir.deleteRecursively()
        }
    }

    @Test
    fun testGradleProjectTypeProviderSupportsWithBuildGradleKts() {
        val tempDir = File(System.getProperty("java.io.tmpdir"))
        val testDir = File(tempDir, "gradle-test-${System.currentTimeMillis()}")
        testDir.mkdirs()
        
        try {
            File(testDir, "build.gradle.kts").createNewFile()
            assertTrue(GradleProjectTypeProvider.supports(testDir))
        } finally {
            testDir.deleteRecursively()
        }
    }

    @Test
    fun testGradleProjectTypeProviderDoesNotSupportEmptyDir() {
        val tempDir = File(System.getProperty("java.io.tmpdir"))
        val testDir = File(tempDir, "gradle-test-${System.currentTimeMillis()}")
        testDir.mkdirs()
        
        try {
            assertTrue(!GradleProjectTypeProvider.supports(testDir))
        } finally {
            testDir.deleteRecursively()
        }
    }
}
