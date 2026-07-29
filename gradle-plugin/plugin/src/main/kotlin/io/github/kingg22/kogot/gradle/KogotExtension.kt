package io.github.kingg22.kogot.gradle

import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import java.io.File
import javax.inject.Inject

const val EXTENSION_NAME = "kogot"

/** Looks for `godot4`/`godot` on PATH, mirroring `godot-version/track.sh`'s resolution strategy. */
private fun findGodotOnPath(): File? {
    val pathDirs = System.getenv("PATH")?.split(File.pathSeparator).orEmpty()
    for (dir in pathDirs) {
        for (name in KogotConventions.GODOT_EXECUTABLE_CANDIDATES) {
            val candidate = File(dir, name)
            if (candidate.isFile && candidate.canExecute()) return candidate
        }
    }
    return null
}

/**
 * Public DSL surface for the kogot Gradle plugin. Every value has a `gradle.properties` fallback
 * (see [KogotConventions] for the exact key and default) so CI/local overrides don't require
 * editing the build script.
 */
abstract class KogotExtension
@Inject
constructor(project: Project) {
    private val objects = project.objects
    private val providers = project.providers
    private fun gradleProp(name: String) = providers.gradleProperty(name)

    /** kogot artifacts version (annotations, processor, runtime). Used to auto-wire dependencies. */
    val kogotVersion: Property<String> = objects.property(String::class.java).convention(
        gradleProp(KogotConventions.PROP_KOGOT_VERSION).orElse(KogotConventions.DEFAULT_KOGOT_VERSION),
    )

    /** Maven group used to resolve kogot's own artifacts (annotations/processor/runtime). */
    val kogotGroup: Property<String> = objects.property(String::class.java).convention(
        gradleProp(KogotConventions.PROP_KOGOT_GROUP).orElse(KogotConventions.DEFAULT_KOGOT_GROUP),
    )

    /** Whether the plugin should apply the KSP plugin and wire the kogot processor automatically. */
    val autoApplyKsp: Property<Boolean> = objects.property(Boolean::class.java).convention(
        gradleProp(KogotConventions.PROP_AUTO_APPLY_KSP).map(String::toBoolean).orElse(true),
    )

    /** Whether the plugin should auto-add the kogot annotations/runtime dependencies to the KMP source sets. */
    val autoAddDependencies: Property<Boolean> = objects.property(Boolean::class.java).convention(
        gradleProp(KogotConventions.PROP_AUTO_ADD_DEPENDENCIES).map(String::toBoolean).orElse(true),
    )

    /** Godot version this project targets, used for `.gdextension` `compatibility_minimum` and CLI resolution hints. */
    val godotVersion: Property<String> = objects.property(String::class.java).convention(
        gradleProp(KogotConventions.PROP_GODOT_VERSION).orElse(KogotConventions.DEFAULT_GODOT_VERSION),
    )

    /** Absolute path to the Godot editor/CLI executable. Auto-detected from PATH when unset. */
    val godotExecutable: RegularFileProperty = objects.fileProperty().apply {
        val explicit = gradleProp(KogotConventions.PROP_GODOT_EXECUTABLE).orNull
        val resolved = explicit ?: findGodotOnPath()?.absolutePath
        resolved?.let { set(project.file(it)) }
    }

    /** Directory of the Godot project that consumes the compiled GDExtension (for copy/run/test tasks). */
    val godotProjectDir: DirectoryProperty = objects.directoryProperty().apply {
        gradleProp(KogotConventions.PROP_GODOT_PROJECT_DIR).orNull?.let { set(project.file(it)) }
    }

    /** `entry_symbol` written to the `.gdextension` file and the generated `@CName` entry point. */
    val entrySymbol: Property<String> = objects.property(String::class.java).convention(
        gradleProp(KogotConventions.PROP_ENTRY_SYMBOL).orElse(KogotConventions.DEFAULT_ENTRY_SYMBOL),
    )

    /** Package the generated entry-point Kotlin file is placed in. */
    val entryPointPackage: Property<String> = objects.property(String::class.java).convention(
        gradleProp(KogotConventions.PROP_ENTRY_POINT_PACKAGE).orElse(KogotConventions.DEFAULT_ENTRY_POINT_PACKAGE),
    )

    /** Whether to generate the `@CName` entry point that aggregates every `*_Binding.register()` call. */
    val generateEntryPoint: Property<Boolean> = objects.property(Boolean::class.java).convention(
        gradleProp(KogotConventions.PROP_GENERATE_ENTRY_POINT).map(String::toBoolean).orElse(true),
    )

    /** Whether to generate the `.gdextension` manifest file. */
    val generateGdextensionFile: Property<Boolean> = objects.property(Boolean::class.java).convention(
        gradleProp(KogotConventions.PROP_GENERATE_GDEXTENSION_FILE).map(String::toBoolean).orElse(true),
    )

    /** Base name used for the compiled shared library, matched against `.gdextension` `[libraries]` entries. */
    val libraryBaseName: Property<String> = objects.property(String::class.java).convention(
        gradleProp(KogotConventions.PROP_LIBRARY_BASE_NAME).orElse(project.provider { project.name }),
    )

    /** Extra raw CLI arguments forwarded to every `godot` invocation (run + test tasks). */
    val godotCliArgs: ListProperty<String> = objects.listProperty(String::class.java).convention(emptyList())

    /** Base package of kogot's runtime (`internal.binding`/`internal.ffi`), used to qualify imports in generated code. */
    val runtimePackage: Property<String> = objects.property(String::class.java).convention(
        gradleProp(KogotConventions.PROP_RUNTIME_PACKAGE).orElse(KogotConventions.DEFAULT_RUNTIME_PACKAGE),
    )

    /** Directory (relative to [godotProjectDir]) that compiled binaries are copied into and `.gdextension` `res://` paths point at. */
    val binaryOutputDir: Property<String> = objects.property(String::class.java).convention(
        gradleProp(KogotConventions.PROP_BINARY_OUTPUT_DIR).orElse(KogotConventions.DEFAULT_BINARY_OUTPUT_DIR),
    )

    /** `GDExtensionInitializationLevel` constant that class registrations are attached to in the generated entry point. */
    val minInitializationLevel: Property<String> = objects.property(String::class.java).convention(
        gradleProp(KogotConventions.PROP_MIN_INITIALIZATION_LEVEL)
            .orElse(KogotConventions.DEFAULT_MIN_INITIALIZATION_LEVEL),
    )

    /** `.gdextension` `compatibility_maximum`: prevents newer Godot versions from loading the extension. Unset by default. */
    val compatibilityMaximum: Property<String> = objects.property(String::class.java).convention(
        gradleProp(KogotConventions.PROP_COMPATIBILITY_MAXIMUM),
    )

    /** `.gdextension` `reloadable`: reloads the extension upon recompilation. */
    val reloadable: Property<Boolean> = objects.property(Boolean::class.java).convention(
        gradleProp(KogotConventions.PROP_RELOADABLE).map(String::toBoolean).orElse(false),
    )

    /** `.gdextension` `android_aar_plugin`: set when this GDExtension is part of a v2 Android plugin. */
    val androidAarPlugin: Property<Boolean> = objects.property(Boolean::class.java).convention(
        gradleProp(KogotConventions.PROP_ANDROID_AAR_PLUGIN).map(String::toBoolean).orElse(false),
    )

    /** `.gdextension` `[icons]` section: `ClassName` -> path to a 16x16 SVG icon. */
    val icons: MapProperty<String, String> =
        objects.mapProperty(String::class.java, String::class.java).convention(emptyMap())

    /**
     * `.gdextension` `[dependencies]` section: `platform.target` feature tag -> {source path -> destination
     * subdirectory}. Named `gdextensionDependencies` (not `dependencies`) to avoid colliding with the
     * `Project.dependencies { }` DSL block when this extension is configured inside a build script.
     */
    @Suppress("UNCHECKED_CAST")
    val gdextensionDependencies: MapProperty<String, Map<String, String>> =
        (objects.mapProperty(String::class.java, Map::class.java) as MapProperty<String, Map<String, String>>)
            .convention(emptyMap())

    /** Package of the KSP-generated `BindingInitializationCallbacks` aggregator (`generated.GeneratedBindings` by default). */
    val generatedBindingsPackage: Property<String> = objects.property(String::class.java).convention(
        gradleProp(KogotConventions.PROP_GENERATED_BINDINGS_PACKAGE)
            .orElse(KogotConventions.DEFAULT_GENERATED_BINDINGS_PACKAGE),
    )

    /** Class name of the KSP-generated `BindingInitializationCallbacks` aggregator. */
    val generatedBindingsClassName: Property<String> = objects.property(String::class.java).convention(
        gradleProp(KogotConventions.PROP_GENERATED_BINDINGS_CLASS_NAME)
            .orElse(KogotConventions.DEFAULT_GENERATED_BINDINGS_CLASS_NAME),
    )
}
