import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
    alias(libs.plugins.kotlin.multiplatform.conventions)
    alias(libs.plugins.kotlin.styles.conventions)
    alias(libs.plugins.dokka.conventions)
}

kotlin {
    explicitApi()

    @OptIn(ExperimentalAbiValidation::class)
    abiValidation {
        keepLocallyUnsupportedTargets.set(true)
    }

    compilerOptions {
        optIn.addAll(
            "kotlinx.cinterop.ExperimentalForeignApi",
            "kotlin.experimental.ExperimentalNativeApi",
            "io.github.kingg22.godot.api.ExperimentalGodotApi",
            "io.github.kingg22.godot.api.ExperimentalGodotKotlin",
            "io.github.kingg22.godot.api.ExperimentalForInheritanceGodotApi",
            "io.github.kingg22.godot.api.InternalForInheritanceGodotApi",
        )
    }

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    dependencies {
        api(libs.jetbrains.annotations)
    }

    applyDefaultHierarchyTemplate()

    linuxX64()
    macosArm64()
    mingwX64()
}
