package io.github.kingg22.kogot.gradle.settings

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.initialization.Settings
import java.io.File

const val SETTINGS_EXTENSION_NAME = "kogot"
private const val KOGOT_EXPORT_TASK_NAME = "kogotExport"
private const val ASSEMBLE_TASK_NAME = "assemble"
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
 */
abstract class KogotSettingsPlugin : Plugin<Settings> {
    override fun apply(settings: Settings) {
        val extension = settings.extensions.create(SETTINGS_EXTENSION_NAME, KogotSettingsExtension::class.java)

        settings.gradle.settingsEvaluated {
            extension.exports.forEach { spec -> includeExportProject(settings, spec) }
        }

        // Registered on the *main* module's beforeProject, not the export project's: with
        // configuration-on-demand, the export project is only ever configured when something
        // depends on it. Requiring "run the export project's config first" would make the task
        // registration itself the thing that never triggers.
        settings.gradle.beforeProject { project ->
            val spec = extension.exports.find { it.modulePath == project.path }
            if (spec != null) registerExportAggregatorTask(project, spec)
        }
    }

    private fun includeExportProject(settings: Settings, spec: KogotExportSpec) {
        val exportPath = "${spec.modulePath}:${spec.exportProjectName}"
        settings.include(exportPath)

        val projectDir = spec.exportProjectDir(settings.rootDir)
        // Gradle requires the directory to physically exist to include it, even though the build
        // script inside it is fully generated (never checked in, never hand-edited).
        projectDir.mkdirs()
        settings.project(exportPath).projectDir = projectDir

        File(projectDir, "build.gradle.kts").writeText(generateExportBuildScript(spec))
    }

    /** Mirrors KogotConventions.copyBinaryTaskName in the :plugin module (not a shared dependency, see module doc). */
    private fun copyBinaryTaskName(targetName: String, buildType: String) =
        "copyKogotBinary${targetName.replaceFirstChar(Char::uppercase)}${buildType.replaceFirstChar(Char::uppercase)}"

    /** Registers `kogotExport` on the main module (the one users actually run tasks from) as a thin proxy. */
    private fun registerExportAggregatorTask(mainProject: Project, spec: KogotExportSpec) {
        val exportPath = "${spec.modulePath}:${spec.exportProjectName}"
        mainProject.tasks.register(KOGOT_EXPORT_TASK_NAME) { task ->
            task.group = "kogot"
            task.description = "Builds and exports the GDExtension binaries via the $exportPath companion project"
            // Task path strings, not TaskProviders: resolved lazily at task-graph time, so they
            // don't require the export project to already be configured right now (it usually
            // isn't yet — configuration-on-demand only configures it once something depends on it,
            // which is exactly this).
            task.dependsOn("$exportPath:$ASSEMBLE_TASK_NAME")
            // Neither the copy-into-godotProjectDir tasks nor generateKogotGdextension are wired
            // into assemble (they're not build outputs, they're deployment/manifest steps) — both
            // need to be listed explicitly, and the .gdextension's [libraries] paths assume the
            // copy already happened.
            spec.targets.forEach { targetName ->
                spec.buildTypes.forEach { buildType ->
                    task.dependsOn("$exportPath:${copyBinaryTaskName(targetName, buildType)}")
                }
            }
            if (spec.generateGdextensionFile != false) {
                task.dependsOn("$exportPath:$GDEXTENSION_TASK_NAME")
            }
        }
    }
}
