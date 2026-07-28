package io.github.kingg22.godot.codegen.extensionapi.impl.knative.impl

import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import io.github.kingg22.godot.codegen.extensionapi.Context
import io.github.kingg22.godot.codegen.extensionapi.TypeResolver
import io.github.kingg22.godot.codegen.impl.ofStatement
import io.github.kingg22.godot.codegen.impl.safeIdentifier
import io.github.kingg22.godot.codegen.models.extensionapi.MethodArg
import io.github.kingg22.godot.codegen.models.extensionapi.domain.ResolvedBuiltinLayout
import io.github.kingg22.godot.codegen.types.*

fun buildLayoutConstants(layout: ResolvedBuiltinLayout): List<PropertySpec> =
    layout.memberOffsets.map { (member, offset) ->
        PropertySpec
            .builder("OFFSET_${member.uppercase()}", INT, KModifier.CONST)
            .initializer("%L", offset)
            .addKdoc(
                "Byte offset of member `%L` for build configuration `%L`.",
                member,
                layout.buildConfiguration.jsonName,
            )
            .build()
    }

/**
 * Maps a member_offsets [meta] to the StructMemory function name and the Kotlin type
 * that corresponds to the physical storage.
 *
 * This mapping is INDEPENDENT of build configuration — meta="float" always means
 * C float (32-bit), never real_t. The build-config-dependent widening (Float→Double
 * in float_64) is handled separately via [storageToPropertyConv].
 */
fun metaToStorageInfo(meta: String, type: String): Pair<String, TypeName>? {
    check(type == "set" || type == "get")
    return when (meta.lowercase()) {
        "float" -> "${type}Float" to FLOAT
        "double" -> "${type}Double" to DOUBLE
        "int32" -> "${type}Int" to INT
        "int64" -> "${type}Long" to LONG
        "uint32" -> "${type}UInt" to U_INT
        "uint64" -> "${type}ULong" to U_LONG
        "int8" -> "${type}Byte" to BYTE
        "uint8" -> "${type}UByte" to U_BYTE
        "int16" -> "${type}Short" to SHORT
        "uint16" -> "${type}UShort" to U_SHORT
        else -> null // compound (Vector2, Vector3, …) — caller falls back to todoGetter
    }
}

/** Widening conversion from physical storage type to API property type. */
fun storageToPropertyConv(storage: TypeName, property: TypeName): String? = when (storage) {
    FLOAT if property == DOUBLE -> ".toDouble()"
    DOUBLE if property == FLOAT -> ".toFloat()"
    INT if property == LONG -> ".toLong()"
    LONG if property == INT -> ".toInt()"
    U_INT if property == U_LONG -> ".toULong()"
    U_LONG if property == U_INT -> ".toUInt()"
    U_INT if property == INT -> ".toInt()"
    U_LONG if property == LONG -> ".toLong()"
    LONG if property == U_INT -> ".toUInt()"
    LONG if property == U_LONG -> ".toULong()"
    else -> null
}

/** Narrowing conversion from API property type to physical storage type (setter path). */
fun propertyToStorageConv(property: TypeName, storage: TypeName) = storageToPropertyConv(property, storage)

/** Maps a Kotlin primitive TypeName to its CVar equivalent for stack allocation, or null for builtin classes. */
fun primitiveKotlinToCVar(type: TypeName): TypeName? = when (type) {
    BOOLEAN -> U_BYTE_VAR
    FLOAT -> FLOAT_VAR
    DOUBLE -> DOUBLE_VAR
    INT -> INT_VAR
    LONG -> LONG_VAR
    BYTE -> BYTE_VAR
    SHORT -> SHORT_VAR
    U_BYTE -> U_BYTE_VAR
    U_SHORT -> U_SHORT_VAR
    U_INT -> U_INT_VAR
    U_LONG -> U_LONG_VAR
    else -> null // builtin class
}

// ── Shared duplication helpers for method/function generators ───────────────

/**
 * Allocates a CVar stack variable for a method/function argument.
 * Works for engine methods (typeResolver-based), builtin methods (string-based),
 * and utility functions (primitive-only).
 *
 * When typeResolver is null, uses string-based dispatch:
 * - Godot primitives (float/double/int/bool) → alloc
 * - enum/bitfield → alloc LongVar
 * - engine class / singleton → alloc COpaquePointerVar
 * - builtin classes → no alloc (rawPtr passed directly)
 */
context(ctx: Context)
fun buildArgAlloc(
    arg: MethodArg,
    implPackageRegistry: ImplementationPackageRegistry,
    typeResolver: TypeResolver,
): CodeBlock {
    val name = safeIdentifier(arg.name)
    val varName = "${name}Var"

    val kotlinType = typeResolver.resolve(arg)
    val cVarType = primitiveKotlinToCVar(kotlinType)
    return buildCodeBlock {
        when {
            kotlinType == BOOLEAN -> addStatement(
                "val %N = %M(%N)",
                varName,
                implPackageRegistry.memberNameForOrDefault("allocGdBool"),
                name,
            )

            cVarType != null -> {
                addStatement("val %N = %M<%T>()", varName, cinteropAlloc, cVarType)
                addStatement("%N.%M = %N", varName, cinteropValue, name)
            }

            arg.type.startsWith("enum::") || arg.type.startsWith("bitfield::") -> {
                addStatement("val %N = %M<%T>()", varName, cinteropAlloc, LONG_VAR)
                addStatement("%N.%M = %N.value", varName, cinteropValue, name)
            }

            ctx.isEngineClass(arg.type) || ctx.isSingleton(arg.type) -> {
                addStatement("val %N = %M<%T>()", varName, cinteropAlloc, C_OPAQUE_POINTER_VAR)
                addStatement(
                    "%N.%M = %N${if (arg.isNullable) "?" else ""}.rawPtr",
                    varName,
                    cinteropValue,
                    name,
                )
            }
        }
    }
}

/**
 * Produces the pointer expression for an argument passed to a FFI invoke call.
 * Must be called AFTER [buildArgAlloc] — consumes the same type decisions.
 */
context(ctx: Context)
fun argPointerExpression(
    arg: MethodArg,
    implPackageRegistry: ImplementationPackageRegistry,
    typeResolver: TypeResolver,
): CodeBlock {
    val _ = implPackageRegistry
    val name = safeIdentifier(arg.name)
    val varName = "${name}Var"

    val kotlinType = typeResolver.resolve(arg)
    val cVarType = primitiveKotlinToCVar(kotlinType)

    return when {
        kotlinType == BOOLEAN -> CodeBlock.ofStatement("%L,", varName)

        kotlinType == COPAQUE_POINTER ||
            kotlinType == ctx.classNameForOrDefault("GDExtensionInitializationFunction") ||
            (kotlinType is ParameterizedTypeName && kotlinType.rawType == C_POINTER)
        -> CodeBlock.ofStatement("%N,", name)

        cVarType != null -> CodeBlock.ofStatement("%N.%M,", varName, cinteropPtr)

        ctx.isNativeStructure(arg.type) -> CodeBlock.ofStatement("%N.%M,", name, cinteropPtr)

        arg.type.startsWith("enum::") || arg.type.startsWith("bitfield::") ->
            CodeBlock.ofStatement("%N.%M,", varName, cinteropPtr)

        ctx.isEngineClass(arg.type) || ctx.isSingleton(arg.type) ->
            CodeBlock.ofStatement("%N.%M,", varName, cinteropPtr)

        ctx.isBuiltin(arg.type) ||
            arg.type.startsWith("array") ||
            arg.type.startsWith("dictionary") ||
            arg.type.startsWith("typeddictionary") ||
            arg.type.startsWith("typedarray")
        -> CodeBlock.ofStatement("%N${if (arg.isNullable) "?" else ""}.rawPtr,", name)

        else -> error("Invalid arg type, unknown strategy to invoke: '${arg.type}' (resolved: $kotlinType)")
    }
}

/**
 * Produces the **Variant pointer** expression for an argument passed to the FFI invoke call.
 * Doesn't require previous allocations of arguments
 * @throws IllegalStateException if the argument is an native structure
 */
context(ctx: Context)
fun argVariantPointerExpression(arg: MethodArg): CodeBlock {
    val name = safeIdentifier(arg.name)
    val variantClass = ctx.classNameForOrDefault("Variant")

    return when {
        arg.type.startsWith("enum::") || arg.type.startsWith("bitfield::") ->
            CodeBlock.ofStatement("%T(%N.value).rawPtr,", variantClass, name)

        ctx.isNativeStructure(arg.meta ?: arg.type) -> error("Unsupported native structure as Variant arg: $arg")

        else -> CodeBlock.ofStatement("%T(%N).rawPtr,", variantClass, name)
    }
}

/**
 * Allocates the return buffer for a method/function invocation.
 * Works for engine methods (typeResolver-based), builtin methods (string-based),
 * and utility functions (primitive-only).
 *
 * When typeResolver is null, uses string-based dispatch:
 * - Godot primitives → alloc CVar
 * - enum/bitfield → alloc LongVar
 * - engine class → alloc COpaquePointerVar
 * - builtin classes → construct directly
 */
context(ctx: Context)
fun buildReturnAlloc(
    returnType: String,
    implPackageRegistry: ImplementationPackageRegistry,
    kotlinType: TypeName,
): CodeBlock {
    val cVarType = primitiveKotlinToCVar(kotlinType)

    return buildCodeBlock {
        when {
            kotlinType == BOOLEAN -> addStatement(
                "val retPtr = %M()",
                implPackageRegistry.memberNameForOrDefault("allocGdBool"),
            )

            cVarType != null -> addStatement("val retPtr = %M<%T>()", cinteropAlloc, cVarType)

            ctx.isNativeStructure(returnType) ->
                addStatement("val retPtr = %M<%T>()", cinteropAlloc, kotlinType)

            returnType.startsWith("enum::") || returnType.startsWith("bitfield::") ->
                addStatement("val retPtr = %M<%T>()", cinteropAlloc, LONG_VAR)

            // Builtin value types (Vector2i, Vector2, Color, Transform2D, ...) are structs: ptrcall
            // writes the raw struct bytes directly into the destination, so the destination must be
            // the type's own correctly-sized/aligned native storage, not a pointer-sized slot. The
            // wrapper's no-arg constructor already allocates that storage (see e.g. generated
            // Vector2i(rawPtr = null) -> nativeHeap.alloc(size, align)); mirrors buildOperatorBody.
            ctx.isBuiltin(returnType) -> addStatement("val retPtr = %T()", kotlinType)

            returnType.startsWith("array") ||
                returnType.startsWith("dictionary") ||
                returnType.startsWith("typeddictionary") ||
                returnType.startsWith("typedarray") ||
                kotlinType == COPAQUE_POINTER ||
                ctx.isEngineClass(returnType) ->
                addStatement("val retPtr = %M<%T>()", cinteropAlloc, C_OPAQUE_POINTER_VAR)

            kotlinType is ParameterizedTypeName && kotlinType.rawType == C_POINTER -> addStatement(
                "val retPtr = %M<%T>()",
                cinteropAlloc,
                C_POINTER_VAR.parameterizedBy(kotlinType.typeArguments),
            )

            else -> error("Invalid return type, unknown strategy: '$returnType' (resolved: $kotlinType)")
        }
    }
}

/**
 * Reads the return value from the allocated return buffer after a FFI invoke call.
 * Must be called AFTER [buildReturnAlloc] — consumes the same type decisions.
 */
context(ctx: Context)
fun buildReturnRead(
    returnType: String,
    implPackageRegistry: ImplementationPackageRegistry,
    kotlinType: TypeName,
    setterMode: Boolean = false,
): CodeBlock {
    val cVarType = primitiveKotlinToCVar(kotlinType)
    val preAppendReturn = if (setterMode) "" else "return "

    return when {
        kotlinType == BOOLEAN -> CodeBlock.ofStatement(
            "${preAppendReturn}retPtr.%M()",
            implPackageRegistry.memberNameForOrDefault("readGdBool"),
        )

        cVarType != null -> CodeBlock.ofStatement("${preAppendReturn}retPtr.%M", cinteropValue)

        kotlinType == COPAQUE_POINTER ||
            (kotlinType is ParameterizedTypeName && kotlinType.rawType == C_POINTER) -> {
            CodeBlock
                .builder()
                .addStatement("$preAppendReturn%M(retPtr.%M) {", K_REQUIRE_NOT_NULL, cinteropValue)
                .indent()
                .addStatement(
                    "%S",
                    "${returnType.removePrefix("const ").removeSuffix("*").trim()} pointer value was null",
                )
                .endControlFlow()
                .build()
        }

        returnType.startsWith("enum::") -> {
            val godotEnum = ctx.classNameForOrDefault("GodotEnum", "GodotEnum")
            CodeBlock.ofStatement("$preAppendReturn%T.fromValue<%T>(retPtr.%M)", godotEnum, kotlinType, cinteropValue)
        }

        returnType.startsWith("bitfield::") ->
            CodeBlock.ofStatement("$preAppendReturn%T(retPtr.%M)", kotlinType, cinteropValue)

        // retPtr is already the constructed builtin instance (see buildReturnAlloc) — ptrcall wrote
        // straight into its own native storage, so there's no pointer to dereference or null-check.
        ctx.isBuiltin(returnType) -> CodeBlock.ofStatement("${preAppendReturn}retPtr")

        ctx.isEngineClass(returnType) ||
            returnType.startsWith("array") ||
            returnType.startsWith("dictionary") ||
            returnType.startsWith("typeddictionary") ||
            returnType.startsWith("typedarray") -> {
            CodeBlock
                .builder()
                .addStatement("$preAppendReturn%T(", kotlinType)
                .indent()
                .addStatement("%M(retPtr.%M) {", K_REQUIRE_NOT_NULL, cinteropValue)
                .withIndent { addStatement("%S", "$returnType pointer value was null") }
                .withIndent { endControlFlow() }
                .addStatement(")")
                .build()
        }

        else -> CodeBlock.ofStatement("${preAppendReturn}retPtr")
    }
}

/**
 * generates Variant converter calls (`toInt(), toBool()`, etc.)
 * instead of CVar value access. Use this when the return buffer is a Variant
 * object returned from methodBindCall.
 */
context(ctx: Context)
fun buildReturnReadOfVariant(returnType: String, kotlinType: TypeName, setterMode: Boolean = false): CodeBlock {
    val preAppendReturn = if (setterMode) "" else "return "

    // Variant return path - use Variant converter methods
    return when {
        kotlinType == BOOLEAN -> CodeBlock.ofStatement("${preAppendReturn}retPtr.toBool()")

        kotlinType == INT -> CodeBlock.ofStatement("${preAppendReturn}retPtr.toInt().toInt()")

        kotlinType == LONG -> CodeBlock.ofStatement("${preAppendReturn}retPtr.toInt()")

        kotlinType == DOUBLE -> CodeBlock.ofStatement("${preAppendReturn}retPtr.toFloat()")

        kotlinType == FLOAT -> CodeBlock.ofStatement("${preAppendReturn}retPtr.toFloat().toFloat()")

        kotlinType == STRING -> CodeBlock.ofStatement("${preAppendReturn}retPtr.toString().toKString()")

        returnType.startsWith("enum::") -> {
            val godotEnum = ctx.classNameForOrDefault("GodotEnum")
            CodeBlock.ofStatement("$preAppendReturn%T.fromValue<%T>(retPtr.toInt())", godotEnum, kotlinType)
        }

        returnType.startsWith("bitfield::") ->
            CodeBlock.ofStatement("$preAppendReturn%T(retPtr.toLont())", kotlinType)

        kotlinType == ctx.classNameForOrDefault("Variant") -> CodeBlock.ofStatement("${preAppendReturn}retPtr")

        ctx.isBuiltin(returnType) -> {
            val converterName = "to${returnType.removePrefix("builtin::").replaceFirstChar(Char::uppercase)}"
            CodeBlock.ofStatement("$preAppendReturn%T(retPtr.$converterName())", kotlinType)
        }

        else -> CodeBlock.ofStatement("${preAppendReturn}retPtr.getValue<%T>()", kotlinType)
    }
}

/**
 * Holds the return argument expression for FFI invoke calls, with explicit flag
 * to determine how to emit in the invoke call.
 *
 * ## Usage in invoke call
 *
 * BuiltinMethodImplGen uses a special pattern where for CVar types and engine/builtin
 * types, the invoke call needs `retPtr.%M` (the pointer itself) instead of a CodeBlock
 * expression. This is because the FFI function expects a pointer-sized `r_return` argument:
 *
 * ```
 * invoke(p_base, p_args, retPtr.%M, argCount)  // when needsPtrInInvoke = true
 * invoke(p_base, p_args, rReturnCodeBlock, argCount)  // when needsPtrInInvoke = false
 * ```
 *
 * ## needsPtrInInvoke flag
 *
 * When `true`: emit `retPtr.%M` directly in the invoke call (cinteropPtr extension).
 * This is needed for:
 * - CVar types (IntVar, LongVar, FloatVar, etc.) - need pointer to stack-allocated var
 * - Engine classes, builtins, collections - need pointer to allocated COpaquePointerVar
 *
 * When `false`: use `asCodeBlock` directly in the invoke call.
 * This is for:
 * - void/null - passes `null`
 * - bool - passes `retPtr` (the GdBool instance, not a pointer)
 * - enums, bitfields - passes `retPtr` (the LongVar, not a pointer)
 *
 * ## asCodeBlock field
 *
 * The CodeBlock expression that represents the return argument in the generated code.
 * For use in non-ptr positions (e.g., when the return needs reinterpret()).
 */
data class ReturnArgInfo(
    val asCodeBlock: CodeBlock,
    /**
     * When true: emit `retPtr.%M` directly in the invoke call.
     * When false: use `asCodeBlock` directly.
     */
    val needsPtrInInvoke: Boolean,
)

/**
 * Builds the r_return expression to pass to an FFI invoke call.
 *
 * ## Parameters
 * - [returnType]: The Godot return type string (e.g., "void", "int", "Sprite2D")
 * - [kotlinType]: The resolved Kotlin TypeName
 * - [forBuiltinInvoke]: When true, includes .reinterpret() for engine/builtin types (for
 *   BuiltinMethodImplGen's GDExtensionPtrBuiltInMethod.invoke). When false, uses simpler
 *   retPtr.%M form (for EngineMethodImplGen's methodBindPtrcallRaw).
 *
 * ## BuiltinMethodImplGen invoke signature
 * `GDExtensionPtrBuiltInMethod.invoke(p_base, p_args, r_return, argument_count)`
 * - r_return: COpaquePointer - the raw pointer to return buffer
 * - For engine/builtin types: pass retPtr.%M (pointer), then construct type via reinterpret()
 *   after the call completes
 *
 * ## EngineMethodImplGen methodBindPtrcallRaw signature
 * `ObjectBinding.methodBindPtrcallRaw(bind, p_object, p_args, r_ret)`
 * - r_ret: COpaquePointer? - just needs the pointer, no reinterpret needed
 * - For engine/builtin types: pass retPtr.%M (the pointer to COpaquePointerVar.value)
 *
 * ## Return type mapping
 * - `void`/`null` → needsPtrInInvoke=false, asCodeBlock="null"
 * - `bool` → needsPtrInInvoke=false, asCodeBlock="retPtr" (GdBool instance, not pointer)
 * - CVar types (Int, Long, Float, Double, etc.) → needsPtrInInvoke=true, asCodeBlock="retPtr.%M" (cinteropPtr)
 * - COPAQUE_POINTER, enums, bitfields, C_POINTER → needsPtrInInvoke=false, asCodeBlock="retPtr.%M" (cinteropPtr)
 * - Builtins → needsPtrInInvoke=false, asCodeBlock="retPtr.rawPtr" (retPtr is the constructed instance
 *   from [buildReturnAlloc]; its own rawPtr is already the correctly-sized destination, no reinterpret)
 * - Engine classes, arrays, dicts, typed dicts, typed arrays:
 *   - forBuiltinInvoke=true → needsPtrInInvoke=true, asCodeBlock="retPtr.%M.%M()" (cinteropPtr.reinterpret())
 *   - forBuiltinInvoke=false → needsPtrInInvoke=true, asCodeBlock="retPtr.%M" (cinteropPtr)
 * - Fallback → needsPtrInInvoke=false, asCodeBlock="retPtr"
 */
context(ctx: Context)
fun returnArgExpression(returnType: String?, kotlinType: TypeName, forBuiltinInvoke: Boolean = false): ReturnArgInfo {
    val cVarType = primitiveKotlinToCVar(kotlinType)

    // Builtin value types: retPtr is the constructed wrapper instance itself (buildReturnAlloc),
    // so its own rawPtr is already the correctly-sized/aligned destination for the raw struct bytes.
    // Excludes scalar builtins (int/float/bool/...) that already have a dedicated CVar mapping —
    // Godot's own builtin_classes list includes those alongside Vector2i/Color/etc.
    val isBuiltinReturn = returnType != null && cVarType == null && ctx.isBuiltin(returnType)

    // Engine classes and collections: need raw pointer for the invoke call.
    // The type is constructed via reinterpret() after the call (BuiltinMethodImplGen path).
    val isEngineOrCollection = returnType != null && (
        ctx.findEngineClass(returnType) != null ||
            returnType.startsWith("array") ||
            returnType.startsWith("dictionary") ||
            returnType.startsWith("typeddictionary") ||
            returnType.startsWith("typedarray")
        )

    // CVar types (IntVar, LongVar, etc.): need pointer to stack-allocated variable.
    val isCVar = cVarType != null

    return when {
        returnType == null || returnType == "void" ->
            ReturnArgInfo(CodeBlock.of("null"), needsPtrInInvoke = false)

        kotlinType == BOOLEAN ->
            // bool: passes GdBool instance directly (not pointer). Value read via readGdBool().
            ReturnArgInfo(CodeBlock.of("retPtr"), needsPtrInInvoke = false)

        isBuiltinReturn -> ReturnArgInfo(CodeBlock.of("retPtr.rawPtr"), needsPtrInInvoke = false)

        isEngineOrCollection ->
            // Engine/collection types: invoke needs retPtr.%M (the raw pointer).
            // asCodeBlock differs based on context:
            // - forBuiltinInvoke=true: include .reinterpret() for post-call type construction
            // - forBuiltinInvoke=false: just retPtr.%M for methodBindPtrcallRaw
            ReturnArgInfo(
                if (forBuiltinInvoke) {
                    CodeBlock.of("retPtr.%M.%M()", cinteropPtr, cinteropReinterpret)
                } else {
                    CodeBlock.of("retPtr.%M", cinteropPtr)
                },
                needsPtrInInvoke = true,
            )

        isCVar || kotlinType == COPAQUE_POINTER ||
            returnType.startsWith("enum::") ||
            returnType.startsWith("bitfield::") ||
            (kotlinType is ParameterizedTypeName && kotlinType.rawType == C_POINTER)
        -> ReturnArgInfo(CodeBlock.of("retPtr.%M", cinteropPtr), needsPtrInInvoke = false)

        else ->
            ReturnArgInfo(CodeBlock.of("retPtr"), needsPtrInInvoke = false)
    }
}
