package io.github.kingg22.godot.codegen.extensionapi.impl.knative.generators

import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.TypeSpec
import io.github.kingg22.godot.codegen.extensionapi.Context
import io.github.kingg22.godot.codegen.extensionapi.impl.knative.impl.UtilityFunctionImplGen
import io.github.kingg22.godot.codegen.impl.createFile
import io.github.kingg22.godot.codegen.models.extensionapi.GlobalConstant
import io.github.kingg22.godot.codegen.models.extensionapi.UtilityFunction
import io.github.kingg22.godot.codegen.utils.withExceptionContext

/**
 * Generates the `GD` object that exposes Godot API utility functions and [GlobalConstant]s.
 *
 * When [implGen] is provided (i.e. we're in the native *implementation* backend rather than
 * a stub backend), each generated function receives:
 *
 * 1. A private `lazy(PUBLICATION)` property that resolves the `GDExtensionPtrUtilityFunction`
 *    pointer via `VariantBinding.getPtrUtilityFunctionRaw`.
 * 2. A real function body that invokes the pointer, replacing the default `TODO()` stub.
 *
 * When [implGen] is `null` (stub backends, test harnesses), only the public API shape is
 * generated — all bodies are `TODO()`.
 *
 * `global_constants` need no such split: they're compile-time literals, so [globalConstantGen]
 * produces the same `const val` properties regardless of backend.
 */
class NativeUtilityFunctionGenerator(
    private val methodGen: NativeMethodGenerator,
    private val overloadGen: TypeOverloadGenerator,
    private val implGen: UtilityFunctionImplGen,
    private val globalConstantGen: NativeGlobalConstantGenerator = NativeGlobalConstantGenerator(),
) {
    context(context: Context)
    fun generateFile(functions: List<UtilityFunction>, constants: List<GlobalConstant> = emptyList()): FileSpec {
        val spec = generateSpec(functions, constants)
        return createFile(spec, spec.name!!, context.packageForUtilObject()) {
            // Add all lazy function-pointer properties
            functions.forEach { fn ->
                withExceptionContext({ "Error generating fn-ptr property for '${fn.name}'" }) {
                    addProperty(implGen.buildFunctionPointerProperty(fn))
                }
            }
        }
    }

    context(context: Context)
    fun generateSpec(functions: List<UtilityFunction>, constants: List<GlobalConstant> = emptyList()): TypeSpec {
        withExceptionContext({ "Generating utility functions, count: ${functions.size}" }) {
            val functionsSpec = functions.flatMap { fn ->
                withExceptionContext({ "Error generating utility function '${fn.name}'" }) {
                    // Ask implGen for a real body; null → NativeMethodGenerator falls back to TODO().
                    val implBody = implGen.buildFunctionBody(fn)

                    val original = methodGen.buildMethod(
                        method = fn,
                        className = "GD",
                        codeBody = implBody,
                    ) {
                        addKdoc("\n\n**Category**: `%S`", fn.category)
                    }.let { methodSpec ->
                        // FIXME Godot JSON needs to provides all nullable information
                        // Patch return type to nullable for engine class returns
                        if (fn.name == "instance_from_id" && fn.returnType == "Object") {
                            val nonNullReturnType = methodSpec.returnType
                            methodSpec
                                .toBuilder()
                                .returns(nonNullReturnType.copy(nullable = true))
                                .build()
                        } else {
                            methodSpec
                        }
                    }

                    overloadGen.buildOverloadsForMethod(
                        fn,
                        original,
                        TypeOverloadGenerator.GodotStringMapping,
                    ).ifEmpty {
                        listOf(original)
                    }
                }
            }

            val typeBuilder = TypeSpec
                .objectBuilder("GD")
                .addKdoc("Utility functions for Godot API.")

            typeBuilder.addFunctions(functionsSpec)
            typeBuilder.addProperties(globalConstantGen.generateProperties(constants))
            return typeBuilder.build()
        }
    }
}
