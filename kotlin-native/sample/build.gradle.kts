import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType

plugins {
    alias(libs.plugins.kotlin.multiplatform.conventions)
    alias(libs.plugins.kotlin.styles.conventions)
}

val isRelease = hasProperty("releaseMode") || hasProperty("release")
val isCi = System.getenv("CI") != null

val listOfNativeBuildType = if (isRelease) {
    listOf(NativeBuildType.DEBUG, NativeBuildType.RELEASE)
} else if (isCi) {
    listOf()
} else {
    listOf(NativeBuildType.DEBUG)
}

kotlin {
    compilerOptions {
        optIn.addAll(
            "kotlinx.cinterop.ExperimentalForeignApi",
            "kotlin.experimental.ExperimentalNativeApi",
        )
    }

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    dependencies {
        implementation(projects.kotlinNative.api)
    }

    applyDefaultHierarchyTemplate()

    linuxX64 { applyBinariesExport() }
    macosArm64 { applyBinariesExport() }
    // mingwX64 { applyBinariesExport() }
}

fun KotlinNativeTarget.applyBinariesExport(baseName: String = "godot-kotlin-sample") {
    binaries {
        sharedLib(buildTypes = listOfNativeBuildType) {
            this.baseName = baseName
        }
    }
}
