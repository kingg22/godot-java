package io.github.kingg22.kogot.gradle.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NativeTargetPresetTest {
    @Test
    fun `dslName matches the Kotlin Gradle Plugin target function name for every preset`() {
        assertEquals("linuxX64", NativeTargetPreset.LINUX_X64.dslName)
        assertEquals("linuxArm64", NativeTargetPreset.LINUX_ARM64.dslName)
        assertEquals("macosX64", NativeTargetPreset.MACOS_X64.dslName)
        assertEquals("macosArm64", NativeTargetPreset.MACOS_ARM64.dslName)
        assertEquals("mingwX64", NativeTargetPreset.MINGW_X64.dslName)
        assertEquals("iosArm64", NativeTargetPreset.IOS_ARM64.dslName)
        assertEquals("iosX64", NativeTargetPreset.IOS_X64.dslName)
        assertEquals("iosSimulatorArm64", NativeTargetPreset.IOS_SIMULATOR_ARM64.dslName)
    }

    @Test
    fun `every enum constant has a unique dslName`() {
        val dslNames = NativeTargetPreset.entries.map { it.dslName }
        assertEquals(dslNames.size, dslNames.toSet().size)
    }
}

class KogotBuildTypeTest {
    @Test
    fun `dslName matches NativeBuildType constant names`() {
        assertEquals("DEBUG", KogotBuildType.DEBUG.dslName)
        assertEquals("RELEASE", KogotBuildType.RELEASE.dslName)
    }
}
