package io.github.kingg22.kogot.gradle

/**
 * Single source of truth for every default value, `gradle.properties` key, and naming convention
 * the kogot plugin uses. Nothing outside this file should hardcode a `kogot.*` property name, a
 * task-name pattern, or a generated-path template — add it here instead.
 */
object KogotConventions {
    // --- gradle.properties keys -------------------------------------------------------------
    const val PROP_KOGOT_VERSION = "kogot.version"
    const val PROP_KOGOT_GROUP = "kogot.group"
    const val PROP_AUTO_APPLY_KSP = "kogot.autoApplyKsp"
    const val PROP_AUTO_ADD_DEPENDENCIES = "kogot.autoAddDependencies"
    const val PROP_GODOT_VERSION = "kogot.godotVersion"
    const val PROP_GODOT_EXECUTABLE = "kogot.godotExecutable"
    const val PROP_GODOT_PROJECT_DIR = "kogot.godotProjectDir"
    const val PROP_ENTRY_SYMBOL = "kogot.entrySymbol"
    const val PROP_ENTRY_POINT_PACKAGE = "kogot.entryPointPackage"
    const val PROP_GENERATE_ENTRY_POINT = "kogot.generateEntryPoint"
    const val PROP_GENERATE_GDEXTENSION_FILE = "kogot.generateGdextensionFile"
    const val PROP_RUNTIME_PACKAGE = "kogot.runtimePackage"
    const val PROP_BINARY_OUTPUT_DIR = "kogot.binaryOutputDir"
    const val PROP_MIN_INITIALIZATION_LEVEL = "kogot.minInitializationLevel"
    const val PROP_LIBRARY_BASE_NAME = "kogot.libraryBaseName"
    const val PROP_COMPATIBILITY_MAXIMUM = "kogot.compatibilityMaximum"
    const val PROP_RELOADABLE = "kogot.reloadable"
    const val PROP_ANDROID_AAR_PLUGIN = "kogot.androidAarPlugin"
    const val PROP_GENERATED_BINDINGS_PACKAGE = "kogot.generatedBindingsPackage"
    const val PROP_GENERATED_BINDINGS_CLASS_NAME = "kogot.generatedBindingsClassName"

    // --- default values ----------------------------------------------------------------------
    const val DEFAULT_KOGOT_VERSION = "0.1.0"
    const val DEFAULT_KOGOT_GROUP = "io.github.kingg22.kogot"
    const val DEFAULT_GODOT_VERSION = "4.7.1"
    const val DEFAULT_ENTRY_SYMBOL = "godot_kotlin_init"
    const val DEFAULT_ENTRY_POINT_PACKAGE = "generated"

    /** Base package of kogot's own Kotlin/Native runtime, used to qualify imports in generated code. */
    const val DEFAULT_RUNTIME_PACKAGE = "io.github.kingg22.godot.internal"

    /** Directory (relative to the Godot project) that compiled binaries are copied into / referenced from. */
    const val DEFAULT_BINARY_OUTPUT_DIR = "bin"
    const val DEFAULT_MIN_INITIALIZATION_LEVEL = "GDEXTENSION_INITIALIZATION_SCENE"
    val GODOT_EXECUTABLE_CANDIDATES = listOf("godot4", "godot")

    /** Package/class name the kogot KSP processor always emits its `BindingInitializationCallbacks` aggregator under. */
    const val DEFAULT_GENERATED_BINDINGS_PACKAGE = "generated"
    const val DEFAULT_GENERATED_BINDINGS_CLASS_NAME = "GeneratedBindings"

    // --- kogot's own published artifact names, resolved under kogotGroup:kogotVersion --------
    const val ARTIFACT_ANNOTATIONS = "kogot-annotations"
    const val ARTIFACT_PROCESSOR = "kogot-processor"

    // --- third-party / Gradle identifiers ------------------------------------------------------
    const val KSP_PLUGIN_ID = "com.google.devtools.ksp"
    const val TASK_GROUP = "kogot"
    const val COMMON_MAIN_SOURCE_SET = "commonMain"
    const val MAIN_COMPILATION = "main"
    const val KSP_COMMON_MAIN_METADATA_CONFIGURATION = "kspCommonMainMetadata"

    // --- task-name templates -------------------------------------------------------------------
    fun kspConfigurationName(targetName: String) = "ksp${targetName.replaceFirstChar(Char::uppercase)}"

    fun entryPointTaskName(targetName: String) =
        "generateKogotEntryPoint${targetName.replaceFirstChar(Char::uppercase)}"

    fun copyBinaryTaskName(targetName: String, buildType: String) =
        "copyKogotBinary${targetName.replaceFirstChar(Char::uppercase)}${buildType.replaceFirstChar(Char::uppercase)}"

    const val GDEXTENSION_TASK_NAME = "generateKogotGdextension"
    const val RUN_GODOT_TASK_NAME = "runGodot"
    const val TEST_GODOT_TASK_NAME = "testGodot"
    const val COPY_ALL_TASK_NAME = "copyKogotBinaries"

    // --- generated-path templates ----------------------------------------------------------------
    fun entryPointOutputDir(targetName: String) = "generated/kogot/entrypoint/$targetName"

    fun binaryRelativePath(binaryOutputDir: String, targetName: String, buildType: String) =
        "$binaryOutputDir/$targetName/$buildType"

    fun gdextensionResPath(binaryOutputDir: String, targetName: String, buildType: String, fileName: String) =
        "res://$binaryOutputDir/$targetName/$buildType/$fileName"

    fun gdextensionFileName(libraryBaseName: String) = "$libraryBaseName.gdextension"
}
