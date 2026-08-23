package io.github.kingg22.kogot.gradle.tasks

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Unit tests for [GenerateGdextensionTask]. Unlike [GenerateEntryPointTaskTest] this task builds its
 * output with a plain `buildString` (no KotlinPoet formatting to account for), so exact-content
 * assertions are safe and are the strongest guard against accidentally reordering or reformatting
 * the `.gdextension` sections (see the task's class doc: Godot matches `[libraries]` entries in
 * declaration order, so insertion order must never be silently "fixed" into sorted order).
 */
class GenerateGdextensionTaskTest {
    @TempDir
    lateinit var projectDir: File

    private lateinit var task: GenerateGdextensionTask
    private lateinit var outputFile: File

    @BeforeEach
    fun setUp() {
        val project = ProjectBuilder.builder().withProjectDir(projectDir).build()
        task = project.tasks.register("generateGdextensionTest", GenerateGdextensionTask::class.java).get()
        outputFile = File(projectDir, "godot-project/game.gdextension")
        task.outputFile.set(outputFile)
    }

    @Test
    fun `minimal configuration omits every optional section`() {
        task.entrySymbol.set("my_entry")
        task.libraries.set(linkedMapOf("linux.debug.x86_64" to "res://bin/linuxX64/debug/libgame.so"))

        task.generate()

        val expected = "[configuration]\n" +
            "entry_symbol = \"my_entry\"\n" +
            "\n" +
            "[libraries]\n" +
            "linux.debug.x86_64 = \"res://bin/linuxX64/debug/libgame.so\"\n"

        assertEquals(expected, outputFile.readText())
    }

    @Test
    fun `writes compatibility, reloadable and android_aar_plugin only when set`() {
        task.entrySymbol.set("my_entry")
        task.compatibilityMinimum.set("4.7.1")
        task.reloadable.set(true)
        task.androidAarPlugin.set(true)
        task.libraries.set(linkedMapOf("linux.debug.x86_64" to "res://bin/linuxX64/debug/libgame.so"))
        // compatibilityMaximum left unset on purpose.

        task.generate()

        val expected = "[configuration]\n" +
            "entry_symbol = \"my_entry\"\n" +
            "compatibility_minimum = \"4.7.1\"\n" +
            "reloadable = true\n" +
            "android_aar_plugin = true\n" +
            "\n" +
            "[libraries]\n" +
            "linux.debug.x86_64 = \"res://bin/linuxX64/debug/libgame.so\"\n"

        assertEquals(expected, outputFile.readText())
    }

    @Test
    fun `preserves libraries insertion order rather than sorting`() {
        task.entrySymbol.set("my_entry")
        task.libraries.set(
            linkedMapOf(
                "windows.debug.x86_64" to "res://bin/mingwX64/debug/libgame.dll",
                "linux.debug.x86_64" to "res://bin/linuxX64/debug/libgame.so",
            ),
        )

        task.generate()

        val librariesSection = outputFile.readText().substringAfter("[libraries]\n")
        val order = librariesSection.lines().filter { it.isNotBlank() }
        assertEquals(
            listOf(
                "windows.debug.x86_64 = \"res://bin/mingwX64/debug/libgame.dll\"",
                "linux.debug.x86_64 = \"res://bin/linuxX64/debug/libgame.so\"",
            ),
            order,
        )
    }

    @Test
    fun `writes icons section only when icons are configured`() {
        task.entrySymbol.set("my_entry")
        task.libraries.set(linkedMapOf("linux.debug.x86_64" to "res://bin/linuxX64/debug/libgame.so"))
        task.icons.set(linkedMapOf("OtherNode" to "res://icons/other_node.svg", "MyNode" to "res://icons/my_node.svg"))

        task.generate()

        val expected = "[configuration]\n" +
            "entry_symbol = \"my_entry\"\n" +
            "\n" +
            "[libraries]\n" +
            "linux.debug.x86_64 = \"res://bin/linuxX64/debug/libgame.so\"\n" +
            "\n" +
            "[icons]\n" +
            "OtherNode = \"res://icons/other_node.svg\"\n" +
            "MyNode = \"res://icons/my_node.svg\"\n"

        assertEquals(expected, outputFile.readText())
    }

    @Test
    fun `formats dependencies with a trailing comma on every row but the last`() {
        task.entrySymbol.set("my_entry")
        task.libraries.set(linkedMapOf("linux.debug.x86_64" to "res://bin/linuxX64/debug/libgame.so"))
        task.dependencies.set(
            linkedMapOf(
                "linux.x86_64" to linkedMapOf("libs/liba.so" to "bin", "libs/libb.so" to "bin"),
            ),
        )

        task.generate()

        val expected = "[configuration]\n" +
            "entry_symbol = \"my_entry\"\n" +
            "\n" +
            "[libraries]\n" +
            "linux.debug.x86_64 = \"res://bin/linuxX64/debug/libgame.so\"\n" +
            "\n" +
            "[dependencies]\n" +
            "linux.x86_64 = {\n" +
            "\"libs/liba.so\" : \"bin\",\n" +
            "\"libs/libb.so\" : \"bin\"\n" +
            "}\n"

        assertEquals(expected, outputFile.readText())
    }

    @Test
    fun `creates parent directories for the output file`() {
        task.entrySymbol.set("my_entry")
        task.libraries.set(emptyMap())

        task.generate()

        assertEquals(true, outputFile.exists())
        assertEquals(true, outputFile.parentFile.isDirectory)
    }
}
