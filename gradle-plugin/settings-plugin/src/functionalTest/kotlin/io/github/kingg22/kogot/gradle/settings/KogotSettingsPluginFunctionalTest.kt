package io.github.kingg22.kogot.gradle.settings

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Properties

/**
 * TestKit functional test for [KogotSettingsPlugin]. `settings.gradle.kts` applies the plugin by
 * bare id, no version. The generated companion project's build script additionally applies
 * `io.github.kingg22.kogot` (from `:plugin`), which `gradlePlugin.testSourceSets`' automatic
 * classpath injection (`GradleRunner.withPluginClasspath()`) doesn't cover on its own - see
 * [combinedPluginClasspath] and settings-plugin/build.gradle.kts's `combinedFunctionalTestPluginClasspath`
 * task for how both plugins' classpaths get combined for this test.
 *
 * `org.gradle.configureondemand=true` is set on purpose: without it, a plain `gradle :app:tasks`
 * would also configure the generated `:app:export` companion project even though nothing requested
 * depends on it. The first two tests below rely on that to stay fast (only `:app` gets configured);
 * the third explicitly targets `:app:export` instead, to prove the generated script is not just
 * textually plausible but actually loadable and appliable by Gradle - see
 * [io.github.kingg22.kogot.gradle.KogotPluginTest]'s class doc for why that target still declares no
 * real Kotlin/Native target (avoiding a Konan toolchain download).
 */
class KogotSettingsPluginFunctionalTest {
    @TempDir
    lateinit var rootDir: File

    /**
     * `:plugin` + `:settings-plugin`'s combined runtime classpath, written by the
     * `combinedFunctionalTestPluginClasspath` task (settings-plugin/build.gradle.kts) into the
     * directory named by the `combinedPluginClasspathDir` system property.
     */
    private val combinedPluginClasspath: List<File> by lazy {
        val metadataFile =
            File(System.getProperty("combinedPluginClasspathDir"), "plugin-under-test-metadata.properties")
        val properties = Properties().apply { metadataFile.inputStream().use(::load) }
        properties.getProperty("implementation-classpath").split(File.pathSeparator).map(::File)
    }

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
            .withPluginClasspath(combinedPluginClasspath)
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

    @Test
    fun `the generated companion project actually configures and registers kogot's own tasks`() {
        writeSettings(
            """
            export(":app") {
                targets = listOf(NativeTargetPreset.LINUX_X64)
            }
            """.trimIndent(),
        )

        // Unlike the other two tests, this forces :app:export itself to configure - proving the
        // generated build script actually applies org.jetbrains.kotlin.multiplatform and
        // io.github.kingg22.kogot successfully, not just that its text looks right.
        val result = runner(":app:export:tasks", "--all").build()

        assertTrue(result.output.contains("runGodot"), result.output)
        assertTrue(result.output.contains("generateKogotGdextension"), result.output)
    }
}
