package io.github.kingg22.kogot.gradle.settings

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Tests [generateExportBuildScript]'s output. Assertions check for individual tokens rather than
 * reconstructing full lines, since KotlinPoet is free to re-wrap long statements (e.g. a control-flow
 * header with a long `listOf(...)` argument) at any space once a line nears its column limit.
 */
class ExportBuildScriptGeneratorTest {
    @Test
    fun `throws when the spec declares no targets`() {
        val spec = KogotExportSpec(":app")

        val exception = assertThrows(IllegalArgumentException::class.java) { generateExportBuildScript(spec) }
        assertTrue(exception.message.orEmpty().contains(":app"), exception.message)
    }

    @Test
    fun `minimal spec applies both plugins and only the required kogot and kotlin settings`() {
        val spec = KogotExportSpec(":app").apply { targets = listOf(NativeTargetPreset.LINUX_X64) }

        val content = generateExportBuildScript(spec).toString()

        assertTrue(content.contains("DO NOT EDIT"), content)
        assertTrue(content.contains(":app"), content)
        assertTrue(content.contains("id(\"org.jetbrains.kotlin.multiplatform\")"), content)
        assertTrue(content.contains("id(\"io.github.kingg22.kogot\")"), content)
        assertTrue(content.contains("autoApplyKsp.set(false)"), content)
        assertTrue(content.contains("autoAddDependencies.set(false)"), content)
        assertTrue(content.contains("generateEntryPoint.set(true)"), content)
        assertTrue(content.contains("libraryBaseName.set(\"app\")"), content)
        assertTrue(content.contains("implementation(project(\":app\"))"), content)
        assertTrue(content.contains("applyDefaultHierarchyTemplate()"), content)
        assertTrue(content.contains("linuxX64"), content)
        assertTrue(content.contains("binaries"), content)
        assertTrue(content.contains("sharedLib(buildTypes = listOf("), content)
        assertTrue(content.contains("NativeBuildType.DEBUG"), content)
        assertTrue(content.contains("baseName = \"app\""), content)

        assertFalse(content.contains("entrySymbol.set("), content)
        assertFalse(content.contains("godotVersion.set("), content)
        assertFalse(content.contains("godotProjectDir.set("), content)
        assertFalse(content.contains("generatedBindingsPackage.set("), content)
        assertFalse(content.contains("generatedBindingsClassName.set("), content)
        assertFalse(content.contains("generateGdextensionFile.set("), content)
    }

    @Test
    fun `libraryBaseName is derived from the last segment of a nested module path`() {
        val spec = KogotExportSpec(":games:app").apply { targets = listOf(NativeTargetPreset.LINUX_X64) }

        val content = generateExportBuildScript(spec).toString()

        assertTrue(content.contains("libraryBaseName.set(\"app\")"), content)
        assertTrue(content.contains("implementation(project(\":games:app\"))"), content)
    }

    @Test
    fun `explicit libraryBaseName overrides the derived one`() {
        val spec = KogotExportSpec(":app").apply {
            targets = listOf(NativeTargetPreset.LINUX_X64)
            libraryBaseName = "my_game"
        }

        val content = generateExportBuildScript(spec).toString()

        assertTrue(content.contains("libraryBaseName.set(\"my_game\")"), content)
        assertTrue(content.contains("baseName = \"my_game\""), content)
    }

    @Test
    fun `every optional field renders its own set call when configured`() {
        val projectDirFile = File("/godot/my-project")
        val spec = KogotExportSpec(":app").apply {
            targets = listOf(NativeTargetPreset.LINUX_X64)
            entrySymbol = "custom_entry"
            godotVersion = "4.7.1"
            godotProjectDir = projectDirFile
            generatedBindingsPackage = "com.example.generated"
            generatedBindingsClassName = "CustomBindings"
            generateGdextensionFile = false
        }

        val content = generateExportBuildScript(spec).toString()

        assertTrue(content.contains("entrySymbol.set(\"custom_entry\")"), content)
        assertTrue(content.contains("godotVersion.set(\"4.7.1\")"), content)
        assertTrue(content.contains("godotProjectDir.set(file("), content)
        assertTrue(content.contains(projectDirFile.absolutePath), content)
        assertTrue(content.contains("generatedBindingsPackage.set(\"com.example.generated\")"), content)
        assertTrue(content.contains("generatedBindingsClassName.set(\"CustomBindings\")"), content)
        assertTrue(content.contains("generateGdextensionFile.set(false)"), content)
    }

    @Test
    fun `multiple targets each get their own binaries block`() {
        val spec = KogotExportSpec(":app").apply {
            targets = listOf(NativeTargetPreset.LINUX_X64, NativeTargetPreset.MACOS_ARM64)
            buildTypes = listOf(KogotBuildType.DEBUG, KogotBuildType.RELEASE)
        }

        val content = generateExportBuildScript(spec).toString()

        assertTrue(content.contains("linuxX64"), content)
        assertTrue(content.contains("macosArm64"), content)
        assertTrue(content.contains("NativeBuildType.DEBUG"), content)
        assertTrue(content.contains("NativeBuildType.RELEASE"), content)
    }

    @Test
    fun `does not apply this monorepo's own build-logic convention plugins`() {
        val spec = KogotExportSpec(":app").apply { targets = listOf(NativeTargetPreset.LINUX_X64) }

        val content = generateExportBuildScript(spec).toString()

        assertFalse(content.contains("buildlogic."), content)
    }
}
