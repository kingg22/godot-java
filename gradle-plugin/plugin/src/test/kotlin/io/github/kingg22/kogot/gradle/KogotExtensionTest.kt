package io.github.kingg22.kogot.gradle

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Unit tests for [KogotExtension]'s defaults and its `gradle.properties` fallback wiring (see
 * [KogotConventions] for every key). Uses [ProjectBuilder] instead of a full build: `gradle.properties`
 * placed in the project directory before the project is built is picked up by
 * `providers.gradleProperty(...)` exactly like a real build would.
 */
class KogotExtensionTest {
    @TempDir
    lateinit var projectDir: File

    private fun extensionWithProperties(vararg properties: Pair<String, String>): KogotExtension {
        if (properties.isNotEmpty()) {
            File(projectDir, "gradle.properties").writeText(
                properties.joinToString("\n") { (key, value) -> "$key=$value" },
            )
        }
        val project = ProjectBuilder.builder().withProjectDir(projectDir).withName("my-game").build()
        return project.extensions.create(EXTENSION_NAME, KogotExtension::class.java, project)
    }

    @Test
    fun `defaults match KogotConventions when no gradle properties are set`() {
        val extension = extensionWithProperties()

        assertEquals(KogotConventions.DEFAULT_KOGOT_VERSION, extension.kogotVersion.get())
        assertEquals(KogotConventions.DEFAULT_KOGOT_GROUP, extension.kogotGroup.get())
        assertTrue(extension.autoApplyKsp.get())
        assertTrue(extension.autoAddDependencies.get())
        assertEquals(KogotConventions.DEFAULT_GODOT_VERSION, extension.godotVersion.get())
        assertEquals(KogotConventions.DEFAULT_ENTRY_SYMBOL, extension.entrySymbol.get())
        assertEquals(KogotConventions.DEFAULT_ENTRY_POINT_PACKAGE, extension.entryPointPackage.get())
        assertTrue(extension.generateEntryPoint.get())
        assertTrue(extension.generateGdextensionFile.get())
        assertEquals(KogotConventions.DEFAULT_RUNTIME_PACKAGE, extension.runtimePackage.get())
        assertEquals(KogotConventions.DEFAULT_BINARY_OUTPUT_DIR, extension.binaryOutputDir.get())
        assertEquals(KogotConventions.DEFAULT_MIN_INITIALIZATION_LEVEL, extension.minInitializationLevel.get())
        assertFalse(extension.reloadable.get())
        assertFalse(extension.androidAarPlugin.get())
        assertTrue(extension.icons.get().isEmpty())
        assertTrue(extension.gdextensionDependencies.get().isEmpty())
        assertTrue(extension.godotCliArgs.get().isEmpty())
        assertFalse(extension.compatibilityMaximum.isPresent)
        assertFalse(extension.godotProjectDir.isPresent)
        assertEquals(
            KogotConventions.DEFAULT_GENERATED_BINDINGS_PACKAGE,
            extension.generatedBindingsPackage.get(),
        )
        assertEquals(
            KogotConventions.DEFAULT_GENERATED_BINDINGS_CLASS_NAME,
            extension.generatedBindingsClassName.get(),
        )
    }

    @Test
    fun `libraryBaseName falls back to the Gradle project name`() {
        val extension = extensionWithProperties()
        assertEquals("my-game", extension.libraryBaseName.get())
    }

    @Test
    fun `every gradle properties key overrides its matching extension property`() {
        val godotProjectDir = File(projectDir, "godot-project").apply { mkdirs() }
        val extension = extensionWithProperties(
            KogotConventions.PROP_KOGOT_VERSION to "9.9.9",
            KogotConventions.PROP_KOGOT_GROUP to "com.example.custom",
            KogotConventions.PROP_AUTO_APPLY_KSP to "false",
            KogotConventions.PROP_AUTO_ADD_DEPENDENCIES to "false",
            KogotConventions.PROP_GODOT_VERSION to "4.3.0",
            KogotConventions.PROP_GODOT_PROJECT_DIR to godotProjectDir.absolutePath,
            KogotConventions.PROP_ENTRY_SYMBOL to "custom_entry",
            KogotConventions.PROP_ENTRY_POINT_PACKAGE to "com.example.entry",
            KogotConventions.PROP_GENERATE_ENTRY_POINT to "false",
            KogotConventions.PROP_GENERATE_GDEXTENSION_FILE to "false",
            KogotConventions.PROP_RUNTIME_PACKAGE to "com.example.runtime",
            KogotConventions.PROP_BINARY_OUTPUT_DIR to "out",
            KogotConventions.PROP_MIN_INITIALIZATION_LEVEL to "GDEXTENSION_INITIALIZATION_CORE",
            KogotConventions.PROP_LIBRARY_BASE_NAME to "custom_base_name",
            KogotConventions.PROP_COMPATIBILITY_MAXIMUM to "4.5.0",
            KogotConventions.PROP_RELOADABLE to "true",
            KogotConventions.PROP_ANDROID_AAR_PLUGIN to "true",
            KogotConventions.PROP_GENERATED_BINDINGS_PACKAGE to "com.example.generated",
            KogotConventions.PROP_GENERATED_BINDINGS_CLASS_NAME to "CustomGeneratedBindings",
        )

        assertEquals("9.9.9", extension.kogotVersion.get())
        assertEquals("com.example.custom", extension.kogotGroup.get())
        assertFalse(extension.autoApplyKsp.get())
        assertFalse(extension.autoAddDependencies.get())
        assertEquals("4.3.0", extension.godotVersion.get())
        assertEquals(godotProjectDir, extension.godotProjectDir.get().asFile)
        assertEquals("custom_entry", extension.entrySymbol.get())
        assertEquals("com.example.entry", extension.entryPointPackage.get())
        assertFalse(extension.generateEntryPoint.get())
        assertFalse(extension.generateGdextensionFile.get())
        assertEquals("com.example.runtime", extension.runtimePackage.get())
        assertEquals("out", extension.binaryOutputDir.get())
        assertEquals("GDEXTENSION_INITIALIZATION_CORE", extension.minInitializationLevel.get())
        assertEquals("custom_base_name", extension.libraryBaseName.get())
        assertEquals("4.5.0", extension.compatibilityMaximum.get())
        assertTrue(extension.reloadable.get())
        assertTrue(extension.androidAarPlugin.get())
        assertEquals("com.example.generated", extension.generatedBindingsPackage.get())
        assertEquals("CustomGeneratedBindings", extension.generatedBindingsClassName.get())
    }

    @Test
    fun `explicit godotExecutable gradle property wins over PATH auto-detection`() {
        val fakeExecutable = File(projectDir, "godot4").apply {
            writeText("#!/bin/sh\n")
            setExecutable(true)
        }
        val extension = extensionWithProperties(
            KogotConventions.PROP_GODOT_EXECUTABLE to fakeExecutable.absolutePath,
        )

        assertEquals(fakeExecutable.absolutePath, extension.godotExecutable.get().asFile.absolutePath)
    }
}
