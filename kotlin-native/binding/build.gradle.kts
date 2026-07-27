import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    alias(libs.plugins.kotlin.multiplatform.conventions)
    alias(libs.plugins.kotlin.styles.conventions)
}

kotlin {
    explicitApi()

    compilerOptions {
        optIn.addAll(
            "kotlinx.cinterop.ExperimentalForeignApi",
            "kotlin.experimental.ExperimentalNativeApi",
        )
    }

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    dependencies {
        implementation(libs.jetbrains.annotations)
        api(projects.kotlinNative.ffi)
        implementation(projects.kotlinNative.api)
    }

    applyDefaultHierarchyTemplate()

    linuxX64()
    macosArm64()
    mingwX64()
}
