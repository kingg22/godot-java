package io.github.kingg22.kogot.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KogotConventionsTest {
    @Test
    fun `kspConfigurationName capitalizes only the first character of the target name`() {
        assertEquals("kspLinuxX64", KogotConventions.kspConfigurationName("linuxX64"))
        assertEquals("kspX64", KogotConventions.kspConfigurationName("x64"))
    }

    @Test
    fun `entryPointTaskName follows the generateKogotEntryPoint naming template`() {
        assertEquals(
            "generateKogotEntryPointLinuxX64",
            KogotConventions.entryPointTaskName("linuxX64"),
        )
    }

    @Test
    fun `copyBinaryTaskName capitalizes both the target and build type`() {
        assertEquals(
            "copyKogotBinaryLinuxX64Debug",
            KogotConventions.copyBinaryTaskName("linuxX64", "debug"),
        )
        assertEquals(
            "copyKogotBinaryMacosArm64Release",
            KogotConventions.copyBinaryTaskName("macosArm64", "release"),
        )
    }

    @Test
    fun `entryPointOutputDir is nested under generated slash kogot slash entrypoint per target`() {
        assertEquals("generated/kogot/entrypoint/linuxX64", KogotConventions.entryPointOutputDir("linuxX64"))
    }

    @Test
    fun `binaryRelativePath joins output dir, target and build type in order`() {
        assertEquals("bin/linuxX64/debug", KogotConventions.binaryRelativePath("bin", "linuxX64", "debug"))
    }

    @Test
    fun `gdextensionResPath prefixes a res colon slash slash path`() {
        assertEquals(
            "res://bin/linuxX64/debug/libgame.so",
            KogotConventions.gdextensionResPath("bin", "linuxX64", "debug", "libgame.so"),
        )
    }

    @Test
    fun `gdextensionFileName appends the gdextension extension to the library base name`() {
        assertEquals("mygame.gdextension", KogotConventions.gdextensionFileName("mygame"))
    }

    @Test
    fun `godot executable candidates are checked godot4 before godot`() {
        assertEquals(listOf("godot4", "godot"), KogotConventions.GODOT_EXECUTABLE_CANDIDATES)
    }
}
