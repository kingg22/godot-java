package io.github.kingg22.godot.codegen.extensionapi.impl.knative

import com.squareup.kotlinpoet.FileSpec
import io.github.kingg22.godot.codegen.extensionapi.CodeImplGenerator
import io.github.kingg22.godot.codegen.extensionapi.Context
import io.github.kingg22.godot.codegen.extensionapi.TypeResolver
import io.github.kingg22.godot.codegen.extensionapi.impl.knative.generators.*
import io.github.kingg22.godot.codegen.extensionapi.impl.knative.impl.BuiltinClassImplGen
import io.github.kingg22.godot.codegen.extensionapi.impl.knative.impl.BuiltinMethodImplGen
import io.github.kingg22.godot.codegen.extensionapi.impl.knative.impl.EngineClassImplGen
import io.github.kingg22.godot.codegen.extensionapi.impl.knative.impl.EngineMethodImplGen
import io.github.kingg22.godot.codegen.extensionapi.impl.knative.impl.EnginePropertyImplGen
import io.github.kingg22.godot.codegen.extensionapi.impl.knative.impl.ImplementationPackageRegistry
import io.github.kingg22.godot.codegen.extensionapi.impl.knative.impl.KNativeImplGen
import io.github.kingg22.godot.codegen.extensionapi.impl.knative.impl.UtilityFunctionImplGen
import io.github.kingg22.godot.codegen.extensionapi.impl.knative.impl.VariantImplGen
import io.github.kingg22.godot.codegen.models.extensionapi.ExtensionApi
import io.github.kingg22.godot.codegen.models.extensioninterface.GDExtensionInterface
import io.github.kingg22.godot.codegen.utils.logger
import io.github.kingg22.godot.codegen.utils.warning

/** Generates Kotlin Native implementation bodies (cinterop / GDExtension bindings). */
class KotlinNativeImplGenerator(override val typeResolver: TypeResolver) : CodeImplGenerator {
    private val logger = logger()
    private lateinit var implPackageRegistry: ImplementationPackageRegistry
    private val builtinClassImplGen = BuiltinClassImplGen(typeResolver, BuiltinMethodImplGen(typeResolver))
    private val defaultValue = DefaultValueGenerator(typeResolver)
    private val methodGenerator = NativeMethodGenerator(typeResolver, defaultValue)
    private val genericInterceptor = GenericBuiltinInterceptor(typeResolver)
    private val enumGen = NativeEnumGenerator()
    private val typeAliasGen = TypeAliasGenerator(genericInterceptor)
    private val overloadGen = TypeOverloadGenerator(typeResolver)
    private val builtinClass = NativeBuiltinClassGenerator(
        typeResolver,
        builtinClassImplGen,
        defaultValue,
        methodGenerator,
        overloadGen,
        enumGen,
        genericInterceptor,
        typeAliasGen,
    )
    private val engineClassImplGen = EngineClassImplGen()
    private val engineMethodImplGen = EngineMethodImplGen(typeResolver)
    private val engineClass = NativeEngineClassGenerator(
        typeResolver,
        engineMethodImplGen,
        EnginePropertyImplGen(typeResolver, engineMethodImplGen),
        methodGenerator,
        overloadGen,
        enumGen,
        engineClassImplGen,
    )
    private val variantImplGen = VariantImplGen(typeResolver)
    private val variant = NativeVariantGenerator(enumGen, variantImplGen)
    private val nativeStructureBodyImpl = KNativeImplGen()
    private val nativeStructure = KNativeStructureGenerator(typeResolver, nativeStructureBodyImpl)
    private val utilFuncImplGen = UtilityFunctionImplGen(typeResolver)
    private val globalConstantGen = NativeGlobalConstantGenerator()
    private val utils = NativeUtilityFunctionGenerator(methodGenerator, overloadGen, utilFuncImplGen, globalConstantGen)

    context(ctx: Context, gdeInterface: GDExtensionInterface?)
    override fun generate(api: ExtensionApi): Sequence<FileSpec> = sequence {
        // Initialise impl generators with the resolved package registry.
        // This must happen inside generate() where Context is available.
        implPackageRegistry = ImplementationPackageRegistry(
            ctx.rootPackage,
            checkNotNull(gdeInterface) { "extensionInterface required for impl generation" },
        )
        builtinClassImplGen.initialize(implPackageRegistry)
        utilFuncImplGen.initialize(implPackageRegistry)
        variantImplGen.initialize(implPackageRegistry)
        nativeStructureBodyImpl.initialize(implPackageRegistry)
        engineClassImplGen.initialize(implPackageRegistry)
        engineMethodImplGen.initialize(implPackageRegistry)

        val builtinClassesPaths = ctx.model.builtins.asSequence().mapNotNull {
            builtinClass.generateFile(it)
        }

        yieldAll(builtinClassesPaths)

        yield(utils.generateFile(api.utilityFunctions, api.globalConstants))

        val nativeStructuresPaths = ctx.model.nativeStructures.asSequence().mapNotNull {
            nativeStructure.generateFile(it)
        }

        yieldAll(nativeStructuresPaths)

        val godotClassesPaths = ctx.model.engineClasses.asSequence().map {
            engineClass.generateFile(it)
        }

        yieldAll(godotClassesPaths)

        val (nestedEnums, globalEnums) = ctx.model.globalEnums.partition { it.ownerName != null }

        val globalEnumsPaths = globalEnums.asSequence().map {
            enumGen.generateFile(it)
        }
        yieldAll(globalEnumsPaths)

        if (nestedEnums.size > 2) {
            logger.warning {
                "Nested enums (${nestedEnums.size}) [${nestedEnums.joinToString(postfix = "]") { it.name }}"
            }
        }

        // Builtin missing: Variant
        yield(variant.generateFile(nestedEnums))
    }
}
