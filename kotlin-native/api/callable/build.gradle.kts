import io.github.kingg22.buildlogic.godot.conventions.CodegenBackend
import io.github.kingg22.buildlogic.godot.conventions.CodegenKind
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    alias(libs.plugins.kotlin.multiplatform.conventions)
    alias(libs.plugins.kotlin.styles.conventions)
    alias(libs.plugins.godot.codegen.simple)
    alias(libs.plugins.dokka.conventions)
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
        implementation(projects.kotlinNative.runtime)
        implementation(projects.kotlinNative.api.chore)
    }

    applyDefaultHierarchyTemplate()

    linuxX64()
    macosArm64()
    mingwX64()
}

godotCodegen {
    backend = CodegenBackend.KOTLIN_NATIVE
    kind = CodegenKind.CALLABLE
    this.packageName = "io.github.kingg22.godot.api.internal.callable"
}
