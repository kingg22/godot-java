package io.github.kingg22.kogot.gradle.tasks

import io.github.kingg22.kogot.gradle.KogotConventions
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Unit tests for [GenerateEntryPointTask]'s [GenerateEntryPointTask.generate] action, calling it
 * directly (no task graph / no Gradle build execution) against a task instance created through
 * [ProjectBuilder]. Assertions check for individual tokens rather than reconstructing full lines,
 * since KotlinPoet is free to re-wrap long statements and to use plain identifiers (no import) for
 * any type that happens to share the file's own package.
 */
class GenerateEntryPointTaskTest {
    @TempDir
    lateinit var projectDir: File

    private lateinit var task: GenerateEntryPointTask
    private lateinit var outputDir: File

    @BeforeEach
    fun setUp() {
        val project = ProjectBuilder.builder().withProjectDir(projectDir).build()
        task = project.tasks.register("generateEntryPointTest", GenerateEntryPointTask::class.java).get()
        outputDir = File(projectDir, "build/generated").apply { mkdirs() }
        task.outputDir.set(outputDir)
    }

    private fun generatedFile(packageName: String): File =
        File(outputDir, packageName.replace('.', '/') + "/KogotEntryPoint.kt")

    @Test
    fun `generates the CName entry point wired to the default runtime and bindings packages`() {
        // A package distinct from the default bindings/entry-point package ("generated") so the
        // generated bindings class reference is guaranteed to need (and show) an import.
        task.entrySymbol.set("my_game_entry")
        task.packageName.set("com.example.entry")

        task.generate()

        val content = generatedFile("com.example.entry").readText()
        assertTrue(content.contains("package com.example.entry"), content)
        assertTrue(content.contains("@CName(\"my_game_entry\")"), content)
        assertTrue(content.contains("fun kogotEntryPoint("), content)
        assertTrue(
            content.contains(
                "import ${KogotConventions.DEFAULT_GENERATED_BINDINGS_PACKAGE}." +
                    KogotConventions.DEFAULT_GENERATED_BINDINGS_CLASS_NAME,
            ),
            content,
        )
        // "internal" is a Kotlin soft keyword, so KotlinPoet backtick-escapes that path segment
        // (`godot.\`internal\`.binding...`) - check the distinctive tail instead of the full FQN.
        assertTrue(content.contains("binding.BindingInitializationCallbacks"), content)
        assertTrue(content.contains("minimum_initialization_level ="), content)
        assertTrue(content.contains(KogotConventions.DEFAULT_MIN_INITIALIZATION_LEVEL), content)
    }

    @Test
    fun `honors overridden runtime, bindings and initialization level properties`() {
        task.entrySymbol.set("custom_entry")
        task.packageName.set("com.example.entry")
        task.minInitializationLevel.set("GDEXTENSION_INITIALIZATION_CORE")
        task.runtimePackage.set("com.example.runtime")
        task.generatedBindingsPackage.set("com.example.generated")
        task.generatedBindingsClassName.set("CustomBindings")

        task.generate()

        val content = generatedFile("com.example.entry").readText()
        assertTrue(content.contains("@CName(\"custom_entry\")"), content)
        assertTrue(content.contains("import com.example.generated.CustomBindings"), content)
        assertTrue(content.contains("import com.example.runtime.binding.BindingInitializationCallbacks"), content)
        assertTrue(content.contains("import com.example.runtime.ffi.GDExtensionInitializationLevel"), content)
        assertTrue(content.contains("minimum_initialization_level ="), content)
        assertTrue(content.contains("GDEXTENSION_INITIALIZATION_CORE"), content)
    }

    @Test
    fun `file is marked generated and not meant to be edited`() {
        task.entrySymbol.set("entry")
        task.packageName.set("generated")

        task.generate()

        val content = generatedFile("generated").readText()
        assertTrue(content.contains("DO NOT EDIT"), content)
        assertFalse(content.isBlank())
    }
}
