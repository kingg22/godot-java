import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType

plugins {
    alias(libs.plugins.kotlin.multiplatform.conventions)
    alias(libs.plugins.kotlin.styles.conventions)
    id("io.github.kingg22.kogot")
}

kogot {
    // KSP + kogot deps are already wired by hand in :mi-juego-prueba:kotlin_native_game:source;
    // this module only exercises the export-side tasks (issue #25).
    autoApplyKsp.set(false)
    autoAddDependencies.set(false)
    // Main.kt already hand-writes the @CName entry point; don't generate a duplicate symbol.
    generateEntryPoint.set(false)

    godotVersion.set(providers.gradleProperty("godotVersion").orElse("4.7.1"))
    godotProjectDir.set(rootProject.layout.projectDirectory.dir("mi-juego-prueba"))
    entrySymbol.set("godot_kotlin_init")
    libraryBaseName.set("godot_kotlin_sample")
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
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    dependencies {
        implementation(projects.miJuegoPrueba.kotlinNativeGame.source)
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

            if (buildType == NativeBuildType.RELEASE) {
                binaryOption("smallBinary", "true")
            }
        }
    }
}
