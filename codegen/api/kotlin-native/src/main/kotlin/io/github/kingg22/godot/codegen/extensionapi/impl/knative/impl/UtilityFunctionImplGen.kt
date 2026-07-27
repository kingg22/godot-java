package io.github.kingg22.godot.codegen.extensionapi.impl.knative.impl

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.joinToCode
import com.squareup.kotlinpoet.withIndent
import io.github.kingg22.godot.codegen.extensionapi.Context
import io.github.kingg22.godot.codegen.extensionapi.TypeResolver
import io.github.kingg22.godot.codegen.impl.buildLazyBlock
import io.github.kingg22.godot.codegen.impl.renameGodotClass
import io.github.kingg22.godot.codegen.impl.safeIdentifier
import io.github.kingg22.godot.codegen.models.extensionapi.UtilityFunction
import io.github.kingg22.godot.codegen.types.cinteropInvoke
import io.github.kingg22.godot.codegen.types.memScoped

/**
 * Generates lazy-loaded function pointer properties and actual invocation bodies
 * for Godot API utility functions (the `GD` object).
 *
 * Each utility function gets:
 * 1. A private `lazy(PUBLICATION)` property that loads the `GDExtensionPtrUtilityFunction`
 *    via `VariantBinding.getPtrUtilityFunctionRaw`, using a temporary `StringName`.
 * 2. A function body that invokes the loaded pointer, packing arguments appropriately.
 *
 * ## Argument mapping
 * - Godot primitive `float` / `double` → `alloc<DoubleVar>()`, assign, pass `.ptr.reinterpret()`
 * - Godot `int`                        → `alloc<LongVar>()`, assign, pass `.ptr.reinterpret()`
 * - Godot `bool`                       → `allocGdBool(arg)`, pass directly
 * - Builtin class (has `rawPtr`)       → pass `arg.rawPtr` directly
 *
 * ## Return type mapping
 * - `void` (null return type) → pass `null` as `r_return`
 * - `float` / `double`       → `alloc<DoubleVar>()`, read `.value` after invoke
 * - `int`                    → `alloc<LongVar>()`, read `.value` after invoke
 * - `bool`                   → `allocGdBool()`, read via `readGdBool()` after invoke
 */
class UtilityFunctionImplGen(private val typeResolver: TypeResolver) {
    private lateinit var implPackageRegistry: ImplementationPackageRegistry

    fun initialize(implRegistry: ImplementationPackageRegistry) {
        implPackageRegistry = implRegistry
    }

    // ── Property: lazy GDExtensionPtrUtilityFunction ──────────────────────────

    context(context: Context)
    fun buildFunctionPointerProperty(fn: UtilityFunction): PropertySpec {
        val ptrUtilityFunctionType = implPackageRegistry.classNameForOrDefault("GDExtensionPtrUtilityFunction")
        val variantBindingClass = implPackageRegistry.classNameForOrDefault("VariantBinding")
        val stringNameClass = context.classNameForOrDefault("StringName")

        return PropertySpec
            .builder(functionPointerName(fn), ptrUtilityFunctionType, KModifier.PRIVATE)
            .delegate(
                buildLazyBlock {
                    beginControlFlow("%T(%S).use { name ->", stringNameClass, fn.name)
                    addStatement("%T", variantBindingClass)
                    withIndent {
                        addStatement(".getPtrUtilityFunctionRaw(name.rawPtr, %LL)", fn.hash)
                        addStatement("?: error(%S)", "Missing utility function '${fn.name}'")
                    }
                    endControlFlow()
                },
            )
            .build()
    }

    // ── Body Implementation ───────────────────────────────────────────────────

    context(context: Context)
    fun buildFunctionBody(fn: UtilityFunction): CodeBlock {
        val propName = functionPointerName(fn)
        if (fn.isVararg) return buildVarargBody(fn, propName)
        return buildFixedArgsBody(fn, propName)
    }

    context(context: Context)
    private fun buildVarargBody(fn: UtilityFunction, propName: String): CodeBlock = CodeBlock.builder().apply {
        if (fn.returnType != null) add("return ")

        beginControlFlow("%M", memScoped)

        // Map fixed arguments and varargs into a single list of Variants
        // Note: Vararg utilities in Godot usually take an array of Variant pointers
        addStatement("val args = listOf(")
        indent()
        fn.arguments.forEach { arg ->
            addStatement("%N,", safeIdentifier(arg.name))
        }
        addStatement("*args")
        unindent()
        addStatement(").map { it.rawPtr }")

        addStatement(
            "val argsPtr = %M(*args.toTypedArray())",
            implPackageRegistry.memberNameForOrDefault("allocConstTypePtrArray"),
        )

        if (fn.returnType != null) {
            addStatement("val ret = %T()", context.classNameForOrDefault(fn.returnType!!.renameGodotClass()))
            addStatement("%N.%M(ret.rawPtr, argsPtr, args.size)", propName, cinteropInvoke)
            addStatement("return ret")
        } else {
            addStatement("%N.%M(null, argsPtr, args.size)", propName, cinteropInvoke)
        }

        endControlFlow()
    }.build()

    context(ctx: Context)
    private fun buildFixedArgsBody(fn: UtilityFunction, propName: String): CodeBlock = CodeBlock.builder().apply {
        val returnType = fn.returnType
        val resolvedReturn = if (returnType != null) typeResolver.resolve(returnType, null) else null

        if (returnType != null) add("return ")

        beginControlFlow("%M", memScoped)

        // 1. Alloc return buffer
        if (returnType != null && resolvedReturn != null) {
            add(buildReturnAlloc(returnType, implPackageRegistry, resolvedReturn))
        }

        // 2. Alloc arguments
        fn.arguments.forEach { arg -> add(buildArgAlloc(arg, implPackageRegistry, typeResolver)) }

        // 3. Invoke logic
        val argExpressions = fn.arguments.joinToCode("") { arg ->
            argPointerExpression(
                arg,
                implPackageRegistry,
                typeResolver,
            )
        }
        val retExpression = if (returnType != null && resolvedReturn != null) {
            returnArgExpression(returnType, resolvedReturn).asCodeBlock
        } else {
            CodeBlock.of("null")
        }

        if (fn.arguments.isEmpty()) {
            addStatement("%N.%M(%L, null, 0)", propName, cinteropInvoke, retExpression)
        } else {
            addStatement("%N.%M(", propName, cinteropInvoke)
            indent()
            addStatement("%L,", retExpression)
            addStatement("%M(", implPackageRegistry.memberNameForOrDefault("allocConstTypePtrArray"))
            indent()
            add(argExpressions)
            unindent()
            addStatement("),")
            addStatement("%L,", fn.arguments.size)
            unindent()
            addStatement(")")
        }

        // 4. Read return
        if (returnType != null && resolvedReturn != null) {
            add(buildReturnRead(returnType, implPackageRegistry, resolvedReturn))
        }

        endControlFlow()
    }.build()

    private fun functionPointerName(fn: UtilityFunction) = safeIdentifier(fn.name) + "Fn"
}
