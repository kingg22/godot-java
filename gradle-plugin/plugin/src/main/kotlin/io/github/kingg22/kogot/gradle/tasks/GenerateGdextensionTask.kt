package io.github.kingg22.kogot.gradle.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Generates a `.gdextension` manifest following the official format:
 * https://docs.godotengine.org/en/stable/engine_details/engine_api/gdextension/gdextension_file.html
 *
 * - `[configuration]`: entry_symbol (required), compatibility_minimum/maximum, reloadable, android_aar_plugin.
 * - `[libraries]`: `platform.target.arch = "path"`. Order is preserved (not sorted) because Godot
 *   matches entries in declaration order — more specific feature-tag combinations must come first.
 * - `[icons]`: `ClassName = "path/to/icon.svg"`.
 * - `[dependencies]`: `platform.target = { "source_path" : "subdirectory" }` nested dictionaries.
 */
abstract class GenerateGdextensionTask : DefaultTask() {
    init {
        description = "Generates the .gdextension manifest for this GDExtension"
        group = "kogot"
    }

    @get:Input
    abstract val entrySymbol: Property<String>

    @get:Input
    @get:Optional
    abstract val compatibilityMinimum: Property<String>

    @get:Input
    @get:Optional
    abstract val compatibilityMaximum: Property<String>

    @get:Input
    @get:Optional
    abstract val reloadable: Property<Boolean>

    @get:Input
    @get:Optional
    abstract val androidAarPlugin: Property<Boolean>

    /** `platform.target.arch` -> path. Insertion order is significant, see class doc. */
    @get:Input
    abstract val libraries: MapProperty<String, String>

    /** `ClassName` -> path to a 16x16 SVG icon. */
    @get:Input
    @get:Optional
    abstract val icons: MapProperty<String, String>

    /** `platform.target` -> {source path -> destination subdirectory}. */
    @get:Input
    @get:Optional
    abstract val dependencies: MapProperty<String, Map<String, String>>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val content = buildString {
            appendLine("[configuration]")
            appendLine("entry_symbol = \"${entrySymbol.get()}\"")
            compatibilityMinimum.orNull?.let { appendLine("compatibility_minimum = \"$it\"") }
            compatibilityMaximum.orNull?.let { appendLine("compatibility_maximum = \"$it\"") }
            if (reloadable.getOrElse(false)) appendLine("reloadable = true")
            if (androidAarPlugin.getOrElse(false)) appendLine("android_aar_plugin = true")

            appendLine()
            appendLine("[libraries]")
            // Insertion order preserved on purpose: Godot matches entries top-to-bottom.
            libraries.get().forEach { (key, path) -> appendLine("$key = \"$path\"") }

            val iconEntries = icons.getOrElse(emptyMap())
            if (iconEntries.isNotEmpty()) {
                appendLine()
                appendLine("[icons]")
                iconEntries.forEach { (className, path) -> appendLine("$className = \"$path\"") }
            }

            val dependencyEntries = dependencies.getOrElse(emptyMap())
            if (dependencyEntries.isNotEmpty()) {
                appendLine()
                appendLine("[dependencies]")
                dependencyEntries.forEach { (featureTag, mapping) ->
                    appendLine("$featureTag = {")
                    val rows = mapping.entries.toList()
                    rows.forEachIndexed { index, (source, destination) ->
                        val comma = if (index == rows.lastIndex) "" else ","
                        appendLine("\"$source\" : \"$destination\"$comma")
                    }
                    appendLine("}")
                }
            }
        }

        val file = outputFile.get().asFile
        file.parentFile.mkdirs()
        file.writeText(content)
        logger.lifecycle("kogot: wrote .gdextension to ${file.absolutePath}")
    }
}
