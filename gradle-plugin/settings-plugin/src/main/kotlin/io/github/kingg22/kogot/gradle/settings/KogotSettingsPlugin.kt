package io.github.kingg22.kogot.gradle.settings

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.initialization.Settings

const val SETTINGS_EXTENSION_NAME = "kogot"
private const val KOGOT_EXPORT_TASK_NAME = "kogotExport"
private const val ASSEMBLE_TASK_NAME = "assemble"
private const val COPY_ALL_TASK_NAME = "copyKogotBinaries"
private const val GDEXTENSION_TASK_NAME = "generateKogotGdextension"

/**
 * Settings-level counterpart to `io.github.kingg22.kogot` (issue #25). Applied in
 * `settings.gradle.kts`:
 * ```
 * plugins { id("io.github.kingg22.kogot.settings") }
 * kogot { export(":app") { targets = listOf("linuxX64", "macosArm64") } }
 * ```
 * For every [KogotExportSpec] registered through `export(...)`, this includes a companion project
 * at `$modulePath:$exportProjectName` and generates a `build.gradle.kts` for it (see
 * [generateExportBuildScript] for why it's generated text rather than typed KGP DSL configuration).
 * This keeps the game module itself a single, plain KMP module — the split-module dance, and the
 * fact it ever existed, is fully owned by the plugin.
 *
 * [KogotExportSpec.targets] is required (not auto-detected from the main module's own declared
 * targets): doing so would need the companion project's build script generated only after the main
 * module finishes configuring, which needs `Project.evaluationDependsOn` forced across a
 * configuration-on-demand boundary — confirmed unreliable in practice (it can report success while
 * Kotlin Gradle Plugin's staged internal lifecycle hasn't actually finished registering tasks like
 * `assemble` on the forced-evaluated project). Both [includeExportProject] and
 * [registerExportAggregatorTask] run eagerly, entirely within `settingsEvaluated`/`beforeProject`, to
 * stay clear of that whole class of problem.
 */
abstract class KogotSettingsPlugin : Plugin<Settings> {
    override fun apply(settings: Settings) {
        val extension = settings.extensions.create(SETTINGS_EXTENSION_NAME, KogotSettingsExtension::class.java)

        settings.gradle.settingsEvaluated {
            extension.exports.forEach { spec -> includeExportProject(settings, spec) }
        }

        settings.gradle.beforeProject { project ->
            val spec = extension.exports.find { it.modulePath == project.path } ?: return@beforeProject
            registerExportAggregatorTask(project, spec)
        }
    }

    /** Includes the companion project and writes its generated build.gradle.kts, both eagerly. */
    private fun includeExportProject(settings: Settings, spec: KogotExportSpec) {
        val exportPath = "${spec.modulePath}:${spec.exportProjectName}"
        settings.include(exportPath)

        val projectDir = spec.exportProjectDir(settings.rootDir)
        // Gradle requires the directory to physically exist to include it, even though the build
        // script inside it is fully generated (never checked in, never hand-edited).
        projectDir.mkdirs()
        settings.project(exportPath).projectDir = projectDir

        generateExportBuildScript(spec).writeTo(projectDir)
    }

    /** Registers `kogotExport` on the main module (the one users actually run tasks from) as a thin proxy. */
    private fun registerExportAggregatorTask(mainProject: Project, spec: KogotExportSpec) {
        val exportPath = "${spec.modulePath}:${spec.exportProjectName}"
        mainProject.tasks.register(KOGOT_EXPORT_TASK_NAME) { task ->
            task.group = "kogot"
            task.description = "Builds and exports the GDExtension binaries via the $exportPath companion project"
            // Task path strings, not TaskProviders: resolved lazily at task-graph time, so they
            // don't require the export project to already be configured right now (it usually
            // isn't — configuration-on-demand only configures it once something depends on it,
            // which is exactly this).
            task.dependsOn("$exportPath:$ASSEMBLE_TASK_NAME")
            // Neither the copy-into-godotProjectDir tasks nor generateKogotGdextension are wired
            // into assemble (they're not build outputs, they're deployment/manifest steps) — both
            // need to be listed explicitly, and the .gdextension's [libraries] paths assume the
            // copy already happened.
            task.dependsOn("$exportPath:$COPY_ALL_TASK_NAME")
            if (spec.generateGdextensionFile != false) {
                task.dependsOn("$exportPath:$GDEXTENSION_TASK_NAME")
            }
        }
    }
}
