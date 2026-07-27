import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    alias(libs.plugins.kotlin.multiplatform.conventions)
    alias(libs.plugins.kotlin.styles.conventions)
    alias(libs.plugins.ksp)
}

kotlin {
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    dependencies {
        api(projects.kotlinNative.api)
        implementation(projects.kotlinNative.binding)
    }

    applyDefaultHierarchyTemplate()

    linuxX64()
    // mingwX64()
}

dependencies {
    add("kspCommonMainMetadata", projects.processor)
    add("kspLinuxX64", projects.processor)
    // put KSP on nativeMain source set when have more than one target
}
