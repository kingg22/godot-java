package io.github.kingg22.kogot.gradle.settings

/** Konan target presets this generator knows how to declare — mirrors the DSL functions KGP exposes. */
enum class NativeTargetPreset(val dslName: String) {
    LINUX_X64("linuxX64"),
    LINUX_ARM64("linuxArm64"),
    MACOS_X64("macosX64"),
    MACOS_ARM64("macosArm64"),
    MINGW_X64("mingwX64"),
    IOS_ARM64("iosArm64"),
    IOS_X64("iosX64"),
    IOS_SIMULATOR_ARM64("iosSimulatorArm64"),
}

/** `NativeBuildType` values a `binaries.sharedLib()` can be exported as. */
enum class KogotBuildType(val dslName: String) {
    DEBUG("DEBUG"),
    RELEASE("RELEASE"),
}
