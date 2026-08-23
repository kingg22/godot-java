package io.github.kingg22.kogot.gradle.settings

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * TestKit functional test for [KogotSettingsPlugin]. `settings.gradle.kts` applies the plugin by
 * bare id, no version - `gradlePlugin.testSourceSets` (see settings-plugin/build.gradle.kts) makes
 * `GradleRunner.withPluginClasspath()` inject this module's runtime classpath into the sample build,
 * so it also has `NativeTargetPreset`/`KogotBuildType` available for the settings script to import.
 *
 * `org.gradle.configureondemand=true` is set on purpose: without it, a plain `gradle :app:tasks`
 * still configures *every* included project by default, including the generated `:app:export`
 * companion project, which declares a real `linuxX64()` native target and would trigger a
 * Kotlin/Native toolchain download - see [io.github.kingg22.kogot.gradle.KogotPluginTest]'s class doc
 * for why that has no place in a fast test. Configuration-on-demand keeps `:app:export` unconfigured
 * as long as no requested task actually needs its task graph, letting this test verify the eager,
 * settings-evaluation-time side effects (project inclusion, generated build script, task
 * registration) without paying for a full companion-project build.
 */
class KogotSettingsPluginFunctionalTest {
    @TempDir
    lateinit var rootDir: File

    private fun writeSettings(kogotBlock: String) {
        File(rootDir, "gradle.properties").writeText("org.gradle.configureondemand=true\n")
        File(rootDir, "app").mkdirs()
        File(rootDir, "settings.gradle.kts").writeText(
            """
            import io.github.kingg22.kogot.gradle.settings.NativeTargetPreset

            plugins {
                id("io.github.kingg22.kogot.settings")
            }

            rootProject.name = "sample-root"

            include(":app")

            kogot {
                $kogotBlock
            }
            """.trimIndent(),
        )
    }

    private fun runner(vararg arguments: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(rootDir)
            .withPluginClasspath()
            .withArguments(*arguments, "--stacktrace")

    @Test
    fun `export includes a companion project and generates its build script on disk`() {
        writeSettings(
            """
            export(":app") {
                targets = listOf(NativeTargetPreset.LINUX_X64)
            }
            """.trimIndent(),
        )

        runner(":app:tasks", "--all").build()

        val generatedScript = File(rootDir, "app/build/kogot-export/build.gradle.kts")
        assertTrue(generatedScript.isFile, "expected $generatedScript to have been generated")

        val content = generatedScript.readText()
        assertTrue(content.contains("id(\"io.github.kingg22.kogot\")"), content)
        assertTrue(content.contains("linuxX64"), content)
    }

    @Test
    fun `kogotExport is registered on the exporting module as a proxy task`() {
        writeSettings(
            """
            export(":app") {
                targets = listOf(NativeTargetPreset.LINUX_X64)
            }
            """.trimIndent(),
        )

        val result = runner(":app:tasks", "--all").build()

        assertTrue(result.output.contains("kogotExport"), result.output)
    }
}
