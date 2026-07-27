import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    alias(libs.plugins.kotlin.multiplatform.conventions)
    alias(libs.plugins.kotlin.styles.conventions)
    alias(libs.plugins.dokka.conventions)
}

kotlin {
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    dependencies {
        api(projects.kotlinNative.api.annotations)
        api(projects.kotlinNative.api.generated)
    }

    applyDefaultHierarchyTemplate()

    linuxX64()
    macosArm64()
    mingwX64()
}

dependencies {
    dokka(projects.kotlinNative.api.annotations)
    dokka(projects.kotlinNative.api.generated)
}
