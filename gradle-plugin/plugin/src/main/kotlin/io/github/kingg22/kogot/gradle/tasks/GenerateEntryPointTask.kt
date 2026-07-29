package io.github.kingg22.kogot.gradle.tasks

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import io.github.kingg22.kogot.gradle.KogotConventions
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option

/**
 * Generates the GDExtension `@CName` entry point for one Kotlin/Native target.
 *
 * This does NOT scan KSP output: the kogot KSP processor always emits a fixed, stable aggregator
 * class (`generated.GeneratedBindings`, a `BindingInitializationCallbacks` subclass) that already
 * registers every class, `@RegisterSignal`, and `@Export` property/method for that compilation in
 * one place — see `GodotBindingGenerator.generateCallbacksFile` in the `processor` module. This
 * task just wires that class into the entry point exactly like a hand-written one would (compare
 * `mi-juego-prueba/kotlin_native_game/exported/src/nativeMain/kotlin/Main.kt`), instead of trying
 * to reconstruct the registration surface itself.
 */
@CacheableTask
abstract class GenerateEntryPointTask : DefaultTask() {
    init {
        description = "Generates the @CName GDExtension entry point delegating to the KSP-generated GeneratedBindings"
        group = "kogot"
    }

    @get:Input
    @get:Option(option = "entrySymbol", description = "GDExtension entry_symbol, must match the .gdextension file")
    abstract val entrySymbol: Property<String>

    @get:Input
    @get:Option(option = "packageName", description = "Package for the generated entry point file")
    abstract val packageName: Property<String>

    @get:Input
    @get:Optional
    @get:Option(
        option = "minInitializationLevel",
        description = "GDExtensionInitializationLevel the plugin is loaded no earlier than",
    )
    abstract val minInitializationLevel: Property<String>

    @get:Input
    @get:Optional
    @get:Option(
        option = "runtimePackage",
        description = "Base package of kogot's internal.binding/internal.ffi runtime",
    )
    abstract val runtimePackage: Property<String>

    @get:Input
    @get:Optional
    @get:Option(
        option = "generatedBindingsPackage",
        description = "Package of the KSP-generated BindingInitializationCallbacks aggregator",
    )
    abstract val generatedBindingsPackage: Property<String>

    @get:Input
    @get:Optional
    @get:Option(
        option = "generatedBindingsClassName",
        description = "Class name of the KSP-generated BindingInitializationCallbacks aggregator",
    )
    abstract val generatedBindingsClassName: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val pkg = packageName.get()
        val symbol = entrySymbol.get()
        val level = minInitializationLevel.getOrElse(KogotConventions.DEFAULT_MIN_INITIALIZATION_LEVEL)
        val runtime = runtimePackage.getOrElse(KogotConventions.DEFAULT_RUNTIME_PACKAGE)
        val bindingsPkg = generatedBindingsPackage.getOrElse(KogotConventions.DEFAULT_GENERATED_BINDINGS_PACKAGE)
        val bindingsClass = generatedBindingsClassName.getOrElse(KogotConventions.DEFAULT_GENERATED_BINDINGS_CLASS_NAME)

        buildEntryPointFile(
            pkg,
            symbol,
            level,
            runtime,
            bindingsPkg,
            bindingsClass,
        ).writeTo(outputDir.get().asFile.toPath())

        logger.lifecycle("kogot: generated entry point '$symbol' delegating to $bindingsPkg.$bindingsClass")
    }

    private fun buildEntryPointFile(
        pkg: String,
        symbol: String,
        level: String,
        runtime: String,
        bindingsPkg: String,
        bindingsClass: String,
    ): FileSpec {
        val ffiPackage = "$runtime.ffi"
        val bindingPackage = "$runtime.binding"

        val cName = ClassName("kotlin.native", "CName")
        val bindingInitializationCallbacks = ClassName(bindingPackage, "BindingInitializationCallbacks")
        val bindingProcAddressHolder = ClassName(bindingPackage, "BindingProcAddressHolder")
        val internalBinding = ClassName(bindingPackage, "InternalBinding")
        val generatedBindings = ClassName(bindingsPkg, bindingsClass)
        val getProcAddress = ClassName(ffiPackage, "GDExtensionInterfaceGetProcAddress")
        val classLibraryPtr = ClassName(ffiPackage, "GDExtensionClassLibraryPtr")
        val initializationStruct = ClassName(ffiPackage, "GDExtensionInitialization")
        val extensionBool = ClassName(ffiPackage, "GDExtensionBool")
        val initializationLevel = ClassName(ffiPackage, "GDExtensionInitializationLevel")
        val cPointer = ClassName("kotlinx.cinterop", "CPointer")
        val stableRef = ClassName("kotlinx.cinterop", "StableRef")
        val experimentalForeignApi = ClassName("kotlinx.cinterop", "ExperimentalForeignApi")
        val experimentalNativeApi = ClassName("kotlin.experimental", "ExperimentalNativeApi")
        val pointed = MemberName("kotlinx.cinterop", "pointed")
        val staticCFunction = MemberName("kotlinx.cinterop", "staticCFunction")
        val extensionBoolTrue = MemberName(ffiPackage, "TRUE")

        val callbacksProperty = PropertySpec.builder(
            "kogotCallbacks",
            stableRef.parameterizedBy(bindingInitializationCallbacks),
        )
            .addModifiers(KModifier.PRIVATE)
            .initializer("%T.create(%T())", stableRef, generatedBindings)
            .build()

        val entryFun = FunSpec.builder("kogotEntryPoint")
            .addAnnotation(AnnotationSpec.builder(Suppress::class).addMember("%S", "unused").build())
            .addAnnotation(AnnotationSpec.builder(cName).addMember("%S", symbol).build())
            .addParameter("pGetProcAddress", getProcAddress)
            .addParameter("pLibrary", classLibraryPtr)
            .addParameter("initialization", cPointer.parameterizedBy(initializationStruct))
            .returns(extensionBool)
            .addStatement("%T.initialize(pGetProcAddress, pLibrary)", bindingProcAddressHolder)
            .addStatement("")
            .addStatement("val init = initialization.%M", pointed)
            .addStatement(
                "init.initialize = %M { userdata, lvl -> kogotCallbacks.get().initialize(userdata, lvl) }",
                staticCFunction,
            )
            .addStatement(
                "init.deinitialize = %M { userdata, lvl -> kogotCallbacks.get().deinitialize(userdata, lvl) }",
                staticCFunction,
            )
            .addStatement("init.userdata = null")
            .addStatement("init.minimum_initialization_level = %T.%L", initializationLevel, level)
            .addStatement("")
            .addStatement("return %T.%M", extensionBool, extensionBoolTrue)
            .build()

        return FileSpec.builder(pkg, "KogotEntryPoint")
            .addFileComment("Generated by the kogot Gradle plugin. DO NOT EDIT!")
            .addAnnotation(
                AnnotationSpec.builder(Suppress::class)
                    .addMember("%S", "REDUNDANT_VISIBILITY_MODIFIER")
                    .useSiteTarget(AnnotationSpec.UseSiteTarget.FILE)
                    .build(),
            )
            .addAnnotation(
                AnnotationSpec.builder(ClassName("kotlin", "OptIn"))
                    .addMember("%T::class", experimentalForeignApi)
                    .addMember("%T::class", experimentalNativeApi)
                    .addMember("%T::class", internalBinding)
                    .useSiteTarget(AnnotationSpec.UseSiteTarget.FILE)
                    .build(),
            )
            .addProperty(callbacksProperty)
            .addFunction(entryFun)
            .build()
    }
}
