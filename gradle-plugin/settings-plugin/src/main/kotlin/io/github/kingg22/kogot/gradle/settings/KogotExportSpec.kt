package io.github.kingg22.kogot.gradle.settings

import java.io.File

/**
 * Describes one game module that should get an auto-managed companion project for exporting
 * GDExtension binaries (issue #25). The companion project is included under
 * `$modulePath:$exportProjectName`, and [KogotSettingsPlugin] writes a small generated
 * `build.gradle.kts` into it — it depends on `modulePath`, contains only the generated `@CName`
 * entry point, and declares `binaries.sharedLib()` for each target in [targets], using the exact
 * same `io.github.kingg22.kogot` project plugin and `binaries.sharedLib()` Kotlin/Native DSL a
 * hand-written module would.
 *
 * The build script is *generated text*, not configured via typed Kotlin/Native Gradle Plugin APIs,
 * on purpose: this module backs a Settings plugin, and Settings-plugin classpaths are visible
 * build-wide — bundling kotlin-gradle-plugin here (needed for typed KGP DSL access) was confirmed to
 * break every other build script's ability to request a specific Kotlin plugin version elsewhere in
 * this build (`plugins { id("org.jetbrains.kotlin.jvm") version X }` fails with "already on the
 * classpath with an unknown version", even when versions match exactly). Generating a normal build
 * script sidesteps this entirely: the companion project resolves KGP through the same
 * `pluginManagement` every other subproject uses.
 */
class KogotExportSpec(val modulePath: String) {
    /** Path segment appended to [modulePath] for the companion project, e.g. `:app:export`. */
    var exportProjectName: String = "export"

    /**
     * Konan target presets to declare on the companion project.
     * Required — not auto-detected from the main module's own declared targets, see
     * [KogotSettingsPlugin]'s doc for why. Doesn't need to match the main module's target list
     * exactly (e.g. the main module can also build `mingwX64` for local development while only
     * Linux/macOS are exported here).
     */
    var targets: List<NativeTargetPreset> = emptyList()

    /** Build types to export. */
    var buildTypes: List<KogotBuildType> = listOf(KogotBuildType.DEBUG)

    /** Directory (relative to the settings root) the companion project's build output is generated into. */
    var exportProjectDir: (rootDir: File) -> File = { rootDir ->
        File(rootDir, "${modulePath.trimStart(':').replace(':', '/')}/build/kogot-export")
    }

    /** GDExtension `entry_symbol`, forwarded to the generated `@CName` entry point and `.gdextension`. */
    var entrySymbol: String? = null

    /** Godot version this project targets, forwarded to the companion project's `kogot { }` extension. */
    var godotVersion: String? = null

    /** Absolute path to the Godot project directory that consumes the compiled GDExtension. */
    var godotProjectDir: File? = null

    /** Base name used for the compiled shared library / `.gdextension` file name. */
    var libraryBaseName: String? = null

    /** Package of the KSP-generated `BindingInitializationCallbacks` aggregator, if not `generated`. */
    var generatedBindingsPackage: String? = null

    /** Class name of the KSP-generated `BindingInitializationCallbacks` aggregator, if not `GeneratedBindings`. */
    var generatedBindingsClassName: String? = null

    /**
     * Whether the companion project should also generate a `.gdextension` manifest
     * (`kogot.generateGdextensionFile`). Leave `null` to use the plugin's own default (`true`); set
     * to `false` when the Godot project already hand-maintains its own manifest.
     */
    var generateGdextensionFile: Boolean? = null
}
