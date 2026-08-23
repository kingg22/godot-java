package io.github.kingg22.kogot.gradle.tasks

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Unit tests for [GodotExecTask]/[RunGodotTask]/[TestGodotTask]: the CLI argument assembly (read
 * back from the registered [org.gradle.process.CommandLineArgumentProvider]s, without running any
 * task graph) and the executable-existence guard in [GodotExecTask.exec]. Never calls `super.exec()`
 * with a real path — that would spawn an actual OS process, which isn't something a unit test
 * should depend on (no `godot` binary is available in CI/unit-test environments).
 */
class GodotExecTaskTest {
    @TempDir
    lateinit var projectDir: File

    private lateinit var godotProjectDir: File

    @BeforeEach
    fun setUp() {
        godotProjectDir = File(projectDir, "godot-project").apply { mkdirs() }
    }

    private fun <T : GodotExecTask> createTask(type: Class<T>): T {
        val project = ProjectBuilder.builder().withProjectDir(projectDir).build()
        return project.tasks.register("execTest", type).get()
    }

    private fun GodotExecTask.resolvedArgs(): List<String> = argumentProviders.flatMap { it.asArguments() }

    @Test
    fun `RunGodotTask defaults headless to true when not set explicitly`() {
        val task = createTask(RunGodotTask::class.java)
        assertTrue(task.headless.get())
    }

    @Test
    fun `arguments always start with --path followed by the godot project directory`() {
        val task = createTask(RunGodotTask::class.java)
        task.godotProjectDir.set(godotProjectDir)
        task.headless.set(false)

        val args = task.resolvedArgs()
        assertEquals(listOf("--path", godotProjectDir.absolutePath), args)
    }

    @Test
    fun `--headless is appended only when headless is true`() {
        val task = createTask(RunGodotTask::class.java)
        task.godotProjectDir.set(godotProjectDir)
        task.headless.set(true)

        assertEquals(listOf("--path", godotProjectDir.absolutePath, "--headless"), task.resolvedArgs())
    }

    @Test
    fun `extraArgs are appended after --path and --headless`() {
        val task = createTask(RunGodotTask::class.java)
        task.godotProjectDir.set(godotProjectDir)
        task.headless.set(true)
        task.extraArgs.set(listOf("--verbose", "--quit"))

        assertEquals(
            listOf("--path", godotProjectDir.absolutePath, "--headless", "--verbose", "--quit"),
            task.resolvedArgs(),
        )
    }

    @Test
    fun `TestGodotTask defaults headless to true`() {
        val task = createTask(TestGodotTask::class.java)
        assertTrue(task.headless.get())
    }

    @Test
    fun `TestGodotTask appends --script only when testScenePath is set`() {
        val withoutScript = createTask(TestGodotTask::class.java)
        withoutScript.godotProjectDir.set(godotProjectDir)
        assertFalse(withoutScript.resolvedArgs().contains("--script"))

        val withScript = createTask(TestGodotTask::class.java)
        withScript.godotProjectDir.set(godotProjectDir)
        withScript.testScenePath.set("res://test/run_tests.gd")

        assertEquals(
            listOf("--path", godotProjectDir.absolutePath, "--headless", "--script", "res://test/run_tests.gd"),
            withScript.resolvedArgs(),
        )
    }

    @Test
    fun `exec fails fast when the configured godot executable does not exist`() {
        val task = createTask(RunGodotTask::class.java)
        task.godotProjectDir.set(godotProjectDir)
        task.godotExecutable.set(File(projectDir, "does-not-exist"))

        // exec() itself is protected (inherited from Gradle's Exec/AbstractExecTask); go through the
        // public Task.actions the same way Gradle's own task executor would invoke the @TaskAction method.
        val exception = assertThrows(IllegalArgumentException::class.java) {
            task.actions.forEach { it.execute(task) }
        }
        assertTrue(exception.message.orEmpty().contains("godot executable not found"), exception.message)
    }
}
