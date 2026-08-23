package io.github.kingg22.kogot.gradle.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class KogotExportSpecTest {
    @Test
    fun `defaults are export, no targets, debug-only, and every optional field unset`() {
        val spec = KogotExportSpec(":app")

        assertEquals(":app", spec.modulePath)
        assertEquals("export", spec.exportProjectName)
        assertTrue(spec.targets.isEmpty())
        assertEquals(listOf(KogotBuildType.DEBUG), spec.buildTypes)
        assertNull(spec.entrySymbol)
        assertNull(spec.godotVersion)
        assertNull(spec.godotProjectDir)
        assertNull(spec.libraryBaseName)
        assertNull(spec.generatedBindingsPackage)
        assertNull(spec.generatedBindingsClassName)
        assertNull(spec.generateGdextensionFile)
    }

    @Test
    fun `exportProjectDir defaults to rootDir slash modulePath slash build slash kogot-export`() {
        val spec = KogotExportSpec(":app")
        val rootDir = File("/settings-root")

        assertEquals(File("/settings-root/app/build/kogot-export"), spec.exportProjectDir(rootDir))
    }

    @Test
    fun `exportProjectDir strips only the leading colon and replaces remaining colons with slashes`() {
        val spec = KogotExportSpec(":games:app")
        val rootDir = File("/settings-root")

        assertEquals(File("/settings-root/games/app/build/kogot-export"), spec.exportProjectDir(rootDir))
    }

    @Test
    fun `fields are mutable for the export DSL block`() {
        val spec = KogotExportSpec(":app").apply {
            exportProjectName = "custom-export"
            targets = listOf(NativeTargetPreset.LINUX_X64, NativeTargetPreset.MACOS_ARM64)
            buildTypes = listOf(KogotBuildType.DEBUG, KogotBuildType.RELEASE)
            entrySymbol = "custom_entry"
            godotVersion = "4.7.1"
            libraryBaseName = "my_game"
            generatedBindingsPackage = "com.example.generated"
            generatedBindingsClassName = "CustomBindings"
            generateGdextensionFile = false
        }

        assertEquals("custom-export", spec.exportProjectName)
        assertEquals(listOf(NativeTargetPreset.LINUX_X64, NativeTargetPreset.MACOS_ARM64), spec.targets)
        assertEquals(listOf(KogotBuildType.DEBUG, KogotBuildType.RELEASE), spec.buildTypes)
        assertEquals("custom_entry", spec.entrySymbol)
        assertEquals("4.7.1", spec.godotVersion)
        assertEquals("my_game", spec.libraryBaseName)
        assertEquals("com.example.generated", spec.generatedBindingsPackage)
        assertEquals("CustomBindings", spec.generatedBindingsClassName)
        assertEquals(false, spec.generateGdextensionFile)
    }
}
