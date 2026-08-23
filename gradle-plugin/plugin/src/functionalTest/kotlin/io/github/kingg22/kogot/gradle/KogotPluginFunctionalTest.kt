package io.github.kingg22.kogot.gradle

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * TestKit functional tests: black-box, running `io.github.kingg22.kogot` through a real, separate
 * Gradle build (via [GradleRunner]) rather than through [ProjectBuilder][org.gradle.testfixtures.ProjectBuilder].
 * `settings.gradle.kts`/`build.gradle.kts` in the sample project apply plugins by bare id, with no
 * version and no plugin repository - `gradlePlugin.testSourceSets` (see plugin/build.gradle.kts)
 * makes `GradleRunner.withPluginClasspath()` inject this module's own runtime classpath (Kotlin
 * Gradle Plugin, KSP Gradle plugin, KotlinPoet, and this plugin's own classes) into the sample build,
 * so plugin resolution needs no network access.
 *
 * No native target (`linuxX64()`, etc.) is declared anywhere here, on purpose: see [KogotPluginTest]'s
 * class doc for why.
 */
class KogotPluginFunctionalTest {
    @TempDir
    lateinit var projectDir: File

    @BeforeEach
    fun setUp() {
        File(projectDir, "settings.gradle.kts").writeText(
            """
            rootProject.name = "sample"
            """.trimIndent(),
        )
    }

    private fun runner(vararg arguments: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(*arguments, "--stacktrace")

    private fun writeBuildScript(kogotBlock: String = "") {
        File(projectDir, "build.gradle.kts").writeText(
            """
            plugins {
                id("org.jetbrains.kotlin.multiplatform")
                id("io.github.kingg22.kogot")
            }

            kogot {
                $kogotBlock
            }

            tasks.register("printAppliedPlugins") {
                doLast {
                    println("KSP_APPLIED=" + project.plugins.hasPlugin("com.google.devtools.ksp"))
                }
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `applying the plugin registers the godot CLI, copy-aggregator and gdextension tasks`() {
        writeBuildScript()

        val result = runner("tasks", "--all").build()

        assertTrue(result.output.contains(KogotConventions.RUN_GODOT_TASK_NAME), result.output)
        assertTrue(result.output.contains(KogotConventions.TEST_GODOT_TASK_NAME), result.output)
        assertTrue(result.output.contains(KogotConventions.COPY_ALL_TASK_NAME), result.output)
        assertTrue(result.output.contains(KogotConventions.GDEXTENSION_TASK_NAME), result.output)
    }

    @Test
    fun `generateGdextensionFile set to false skips registering the gdextension task`() {
        writeBuildScript(kogotBlock = "generateGdextensionFile.set(false)")

        val result = runner("tasks", "--all").build()

        assertFalse(result.output.contains(KogotConventions.GDEXTENSION_TASK_NAME), result.output)
    }

    @Test
    fun `autoApplyKsp true auto-applies the KSP plugin`() {
        writeBuildScript()

        val result = runner("printAppliedPlugins").build()

        assertTrue(result.output.contains("KSP_APPLIED=true"), result.output)
    }

    @Test
    fun `autoApplyKsp false leaves the KSP plugin unapplied`() {
        writeBuildScript(kogotBlock = "autoApplyKsp.set(false)")

        val result = runner("printAppliedPlugins").build()

        assertTrue(result.output.contains("KSP_APPLIED=false"), result.output)
    }
}
