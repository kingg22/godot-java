package io.github.kingg22.kogot.gradle

import org.gradle.api.internal.project.ProjectInternal
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests exercising [KogotPlugin.apply] against a real (but native-target-free) Kotlin
 * Multiplatform project built with [ProjectBuilder]. No `linuxX64()`/`macosArm64()` target is ever
 * declared here on purpose: declaring one triggers Kotlin/Native toolchain resolution (a multi-hundred
 * MB download the first time), which has no place in a fast unit test. That target-dependent branch
 * of the plugin (KSP-configuration wiring per [org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget])
 * is covered instead by [KogotPluginWireDependenciesTest] with mocked targets, and end-to-end by the
 * `functionalTest` suite.
 *
 * `afterEvaluate` never fires on its own for a [ProjectBuilder] project, so tests call
 * [ProjectInternal.evaluate] explicitly to trigger it - the standard technique for unit-testing
 * Gradle plugins that defer work to `afterEvaluate`.
 */
class KogotPluginTest {
    private fun evaluatedProject(configure: KogotExtension.() -> Unit = {}): org.gradle.api.Project {
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply("org.jetbrains.kotlin.multiplatform")
        project.pluginManager.apply(KogotPlugin::class.java)
        project.extensions.getByType(KogotExtension::class.java).configure()
        (project as ProjectInternal).evaluate()
        return project
    }

    @Test
    fun `registers the kogot extension`() {
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply(KogotPlugin::class.java)

        assertNotNull(project.extensions.findByName(EXTENSION_NAME))
        assertTrue(project.extensions.findByName(EXTENSION_NAME) is KogotExtension)
    }

    @Test
    fun `does nothing beyond registering the extension when the KMP plugin is never applied`() {
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply(KogotPlugin::class.java)
        (project as ProjectInternal).evaluate()

        assertNull(project.tasks.findByName(KogotConventions.RUN_GODOT_TASK_NAME))
        assertNull(project.tasks.findByName(KogotConventions.GDEXTENSION_TASK_NAME))
    }

    @Test
    fun `registers the godot CLI and copy-aggregator tasks once the KMP plugin is applied`() {
        val project = evaluatedProject()

        assertNotNull(project.tasks.findByName(KogotConventions.RUN_GODOT_TASK_NAME))
        assertNotNull(project.tasks.findByName(KogotConventions.TEST_GODOT_TASK_NAME))
        assertNotNull(project.tasks.findByName(KogotConventions.COPY_ALL_TASK_NAME))
        // generateGdextensionFile defaults to true.
        assertNotNull(project.tasks.findByName(KogotConventions.GDEXTENSION_TASK_NAME))
    }

    @Test
    fun `no entry-point task is registered when there are no native targets`() {
        val project = evaluatedProject()
        assertTrue(project.tasks.filter { it.name.startsWith("generateKogotEntryPoint") }.isEmpty())
    }

    @Test
    fun `generateGdextensionFile set to false skips registering the gdextension task`() {
        val project = evaluatedProject { generateGdextensionFile.set(false) }
        assertNull(project.tasks.findByName(KogotConventions.GDEXTENSION_TASK_NAME))
    }

    @Test
    fun `autoApplyKsp applies the KSP plugin automatically`() {
        val project = evaluatedProject()
        assertTrue(project.plugins.hasPlugin(KogotConventions.KSP_PLUGIN_ID))
    }

    @Test
    fun `autoApplyKsp set to false leaves the KSP plugin unapplied`() {
        val project = evaluatedProject { autoApplyKsp.set(false) }
        assertFalse(project.plugins.hasPlugin(KogotConventions.KSP_PLUGIN_ID))
    }
}
