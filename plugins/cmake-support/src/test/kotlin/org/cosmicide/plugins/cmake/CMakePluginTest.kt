package org.cosmicide.plugins.cmake

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Test

class CMakePluginTest {
    @Test
    fun discoversExecutableTargetsInProjectOrder() {
        withProject { root ->
            root.resolve("CMakeLists.txt").writeText(
                """
                    add_library(core STATIC core.cpp)
                    add_executable(app main.cpp)
                    add_executable(alias ALIAS app)
                    add_executable(imported IMPORTED)
                """.trimIndent()
            )
            root.resolve("tools").mkdirs()
            root.resolve("tools/CMakeLists.txt").writeText(
                """
                    add_executable("generator" generator.cpp)
                    add_executable(app duplicate.cpp)
                """.trimIndent()
            )

            assertEquals(
                listOf("app", "generator"),
                cmakeExecutableTargets(root).map(CMakeExecutableTarget::name)
            )
        }
    }

    @Test
    fun ignoresGeneratedBuildDirectories() {
        withProject { root ->
            root.resolve("CMakeLists.txt").writeText("add_executable(app main.cpp)\n")
            root.resolve("build").mkdirs()
            root.resolve("build/CMakeLists.txt").writeText("add_executable(probe probe.c)\n")
            root.resolve("cmake-build-debug").mkdirs()
            root.resolve("cmake-build-debug/CMakeLists.txt")
                .writeText("add_executable(debug_probe probe.c)\n")

            assertEquals(
                listOf("app"),
                cmakeExecutableTargets(root).map(CMakeExecutableTarget::name)
            )
        }
    }

    private fun withProject(block: (File) -> Unit) {
        val root = createTempDirectory("cmake-plugin-test").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
