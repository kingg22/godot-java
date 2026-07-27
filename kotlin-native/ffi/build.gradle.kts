import io.github.kingg22.buildlogic.godot.chore.GodotCodegenChoreExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

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

    applyDefaultHierarchyTemplate()

    linuxX64 { configureGodotInterop() }
    macosArm64 { configureGodotInterop() }
    mingwX64 { configureGodotInterop() }
}

fun KotlinNativeTarget.configureGodotInterop() {
    compilations.named("main").configure {
        cinterops.register("godot") {
            this.packageName = "io.github.kingg22.godot.internal.ffi"
            defFile(layout.projectDirectory.file("nativeInterop/cinterop/godot.def"))
            includeDirs.allHeaders(rootProject.extensions.getByType<GodotCodegenChoreExtension>().godotVersionDir)
        }
    }
}
