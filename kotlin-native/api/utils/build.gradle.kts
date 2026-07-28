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
        )
    }

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    dependencies {
        implementation(projects.kotlinNative.api.generated)
        testImplementation(projects.kotlinNative.api.testing)
    }

    applyDefaultHierarchyTemplate()

    linuxX64()
    // mingwX64()
}
