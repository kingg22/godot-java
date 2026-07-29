package io.github.kingg22.kogot.gradle.tasks

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.options.Option
import org.gradle.process.CommandLineArgumentProvider

/** Common base for tasks that shell out to the `godot` CLI. Every input is a typed, overridable property. */
abstract class GodotExecTask : Exec() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    @get:Option(option = "godotExecutable", description = "Path to the godot executable")
    abstract val godotExecutable: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    @get:Option(option = "godotProjectDir", description = "Path to the Godot project directory (--path)")
    abstract val godotProjectDir: DirectoryProperty

    @get:Input
    @get:Optional
    @get:Option(option = "headless", description = "Run godot with --headless")
    abstract val headless: Property<Boolean>

    @get:Input
    @get:Optional
    @get:Option(option = "extraArgs", description = "Extra raw CLI arguments appended to the godot invocation")
    abstract val extraArgs: ListProperty<String>

    init {
        headless.convention(true)
        standardOutput = System.out
        errorOutput = System.err
        argumentProviders += CommandLineArgumentProvider {
            buildList {
                add("--path")
                add(godotProjectDir.get().asFile.absolutePath)
                if (headless.getOrElse(true)) add("--headless")
                addAll(extraArgs.getOrElse(emptyList()))
            }
        }
    }

    override fun exec() {
        val exe = godotExecutable.get().asFile
        require(exe.exists()) { "kogot: godot executable not found at ${exe.absolutePath}. Set kogot.godotExecutable." }
        executable = exe.absolutePath
        super.exec()
    }
}

/** Launches the configured Godot project (editor or headless, per [GodotExecTask.headless]). */
abstract class RunGodotTask : GodotExecTask() {
    init {
        description = "Runs the Godot project via the CLI"
        group = "kogot"
    }
}

/**
 * Runs the Godot project headless with a test-runner flag, for exercising Kotlin/Native code
 * registered through the GDExtension (e.g. a GUT/GdUnit runner scene, or `--script` invocation).
 */
abstract class TestGodotTask : GodotExecTask() {
    @get:Input
    @get:Optional
    @get:Option(option = "testScenePath", description = "res:// path to the scene/script that drives the test run")
    abstract val testScenePath: Property<String>

    init {
        description = "Runs Kotlin/Native tests through Godot headless"
        group = "kogot"
        headless.convention(true)
        argumentProviders += CommandLineArgumentProvider {
            testScenePath.orNull?.let { listOf("--script", it) } ?: emptyList()
        }
    }
}
