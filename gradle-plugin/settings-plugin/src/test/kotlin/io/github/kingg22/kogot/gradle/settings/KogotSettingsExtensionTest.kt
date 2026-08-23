package io.github.kingg22.kogot.gradle.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [KogotSettingsExtension.exports] is `internal`; this test lives in the module's own `test` source
 * set, which the Kotlin Gradle Plugin compiles as a friend module of `main` by default, so the
 * internal accessor is visible here without needing to widen its visibility for testing.
 */
class KogotSettingsExtensionTest {
    @Test
    fun `export with no configuration block registers a spec with defaults`() {
        val extension = TestKogotSettingsExtension()

        extension.export(":app")

        val spec = extension.exports.single()
        assertEquals(":app", spec.modulePath)
        assertTrue(spec.targets.isEmpty())
    }

    @Test
    fun `export applies the configuration block to the created spec`() {
        val extension = TestKogotSettingsExtension()

        extension.export(":app") {
            it.targets = listOf(NativeTargetPreset.LINUX_X64)
            it.libraryBaseName = "my_game"
        }

        val spec = extension.exports.single()
        assertEquals(listOf(NativeTargetPreset.LINUX_X64), spec.targets)
        assertEquals("my_game", spec.libraryBaseName)
    }

    @Test
    fun `multiple export calls accumulate independent specs`() {
        val extension = TestKogotSettingsExtension()

        extension.export(":app") { it.libraryBaseName = "app_game" }
        extension.export(":tools:editor") { it.libraryBaseName = "editor_tool" }

        assertEquals(2, extension.exports.size)
        assertEquals(listOf(":app", ":tools:editor"), extension.exports.map { it.modulePath })
        assertEquals(listOf("app_game", "editor_tool"), extension.exports.map { it.libraryBaseName })
    }
}

/** [KogotSettingsExtension] is `abstract` only for Gradle's own decoration; safe to instantiate directly in tests. */
private class TestKogotSettingsExtension : KogotSettingsExtension()
