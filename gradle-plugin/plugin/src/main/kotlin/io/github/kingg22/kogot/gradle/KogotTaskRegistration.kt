package io.github.kingg22.kogot.gradle

import io.github.kingg22.kogot.gradle.tasks.GenerateEntryPointTask
import io.github.kingg22.kogot.gradle.tasks.GenerateGdextensionTask
import io.github.kingg22.kogot.gradle.tasks.RunGodotTask
import io.github.kingg22.kogot.gradle.tasks.TestGodotTask
import org.gradle.api.Project
import org.gradle.api.tasks.Copy
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import org.jetbrains.kotlin.gradle.plugin.mpp.SharedLibrary
import org.jetbrains.kotlin.konan.target.Family

private fun godotOsName(family: Family): String? = when (family) {
    Family.OSX -> "macos"
    Family.LINUX -> "linux"
    Family.MINGW -> "windows"
    else -> null
}

private fun godotArchName(archName: String): String = when {
    archName.contains("ARM64") -> "arm64"
    archName.contains("X64") -> "x86_64"
    archName.contains("ARM32") -> "arm32"
    archName.contains("X86") -> "x86_32"
    else -> archName.lowercase()
}

internal fun sharedLibraries(target: KotlinNativeTarget) = target.binaries.filterIsInstance<SharedLibrary>()

@OptIn(ExperimentalKotlinGradlePluginApi::class)
internal fun registerEntryPointTask(project: Project, extension: KogotExtension, target: KotlinNativeTarget) {
    // No KSP task lookup or output scanning: the entry point references the KSP-generated
    // GeneratedBindings class by its fixed, stable name — that class is already part of this
    // target's compilation (the KSP Gradle plugin wires its own output in), so the Kotlin
    // compiler resolves it directly. See GenerateEntryPointTask's class doc.
    val genTask = project.tasks.register(
        KogotConventions.entryPointTaskName(target.name),
        GenerateEntryPointTask::class.java,
    ) { task ->
        task.entrySymbol.set(extension.entrySymbol)
        task.packageName.set(extension.entryPointPackage)
        task.minInitializationLevel.set(extension.minInitializationLevel)
        task.runtimePackage.set(extension.runtimePackage)
        task.generatedBindingsPackage.set(extension.generatedBindingsPackage)
        task.generatedBindingsClassName.set(extension.generatedBindingsClassName)
        task.outputDir.set(project.layout.buildDirectory.dir(KogotConventions.entryPointOutputDir(target.name)))
    }

    target.compilations.findByName(KogotConventions.MAIN_COMPILATION)
        ?.defaultSourceSet
        ?.generatedKotlin
        ?.srcDir(genTask.map { it.outputDir })
}

internal fun registerCopyTask(project: Project, extension: KogotExtension, target: KotlinNativeTarget) {
    sharedLibraries(target).forEach { binary ->
        val buildTypeName = binary.buildType.name.lowercase()
        project.tasks.register(
            KogotConventions.copyBinaryTaskName(target.name, buildTypeName),
            Copy::class.java,
        ) { task ->
            task.group = KogotConventions.TASK_GROUP
            task.description = "Copies the ${target.name} $buildTypeName GDExtension binary into the Godot project"
            task.from(binary.linkTaskProvider.map { it.outputFile })
            task.into(
                extension.godotProjectDir.dir(
                    extension.binaryOutputDir.map {
                        KogotConventions.binaryRelativePath(it, target.name, buildTypeName)
                    },
                ),
            )
            task.dependsOn(binary.linkTaskProvider)
        }
    }
}

internal fun registerGdextensionTask(project: Project, extension: KogotExtension, targets: List<KotlinNativeTarget>) {
    project.tasks.register(KogotConventions.GDEXTENSION_TASK_NAME, GenerateGdextensionTask::class.java) { task ->
        task.entrySymbol.set(extension.entrySymbol)
        task.compatibilityMinimum.set(extension.godotVersion)
        task.compatibilityMaximum.set(extension.compatibilityMaximum)
        task.reloadable.set(extension.reloadable)
        task.androidAarPlugin.set(extension.androidAarPlugin)
        task.icons.set(extension.icons)
        task.dependencies.set(extension.gdextensionDependencies)
        task.outputFile.set(
            extension.godotProjectDir.file(extension.libraryBaseName.map { KogotConventions.gdextensionFileName(it) }),
        )

        // Computed eagerly (not via a lazy Provider): this runs inside afterEvaluate, so every
        // value is already final, and wrapping Kotlin/Native domain objects in a stored closure
        // breaks the configuration cache (they hold non-serializable internal state).
        val binaryOutputDir = extension.binaryOutputDir.get()
        val libraries = buildMap {
            targets.forEach { target ->
                val os = godotOsName(target.konanTarget.family) ?: return@forEach
                val arch = godotArchName(target.konanTarget.architecture.name)
                sharedLibraries(target).forEach { binary ->
                    val debug = binary.buildType == NativeBuildType.DEBUG
                    val key = "$os.${if (debug) "debug" else "release"}.$arch"
                    val buildTypeName = binary.buildType.name.lowercase()
                    val fileName = binary.linkTaskProvider.get().outputFile.get().name
                    put(
                        key,
                        KogotConventions.gdextensionResPath(binaryOutputDir, target.name, buildTypeName, fileName),
                    )
                }
            }
        }
        task.libraries.set(libraries)
        targets.forEach { target -> sharedLibraries(target).forEach { task.dependsOn(it.linkTaskProvider) } }
    }
}

internal fun registerGodotCliTasks(project: Project, extension: KogotExtension) {
    project.tasks.register(KogotConventions.RUN_GODOT_TASK_NAME, RunGodotTask::class.java) { task ->
        task.godotExecutable.set(extension.godotExecutable)
        task.godotProjectDir.set(extension.godotProjectDir)
        task.headless.set(false)
        task.extraArgs.set(extension.godotCliArgs)
    }

    project.tasks.register(KogotConventions.TEST_GODOT_TASK_NAME, TestGodotTask::class.java) { task ->
        task.godotExecutable.set(extension.godotExecutable)
        task.godotProjectDir.set(extension.godotProjectDir)
        task.headless.set(true)
        task.extraArgs.set(extension.godotCliArgs)
    }
}
