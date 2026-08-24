package io.github.kingg22.godot.codegen.extensionapi.impl.knative.impl

import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.buildCodeBlock
import com.squareup.kotlinpoet.withIndent
import io.github.kingg22.godot.codegen.extensionapi.Context
import io.github.kingg22.godot.codegen.extensionapi.TypeResolver
import io.github.kingg22.godot.codegen.impl.buildLazyBlock
import io.github.kingg22.godot.codegen.impl.safeIdentifier
import io.github.kingg22.godot.codegen.models.extensionapi.EngineClass
import io.github.kingg22.godot.codegen.models.extensionapi.MethodArg
import io.github.kingg22.godot.codegen.models.extensionapi.MethodReturn
import io.github.kingg22.godot.codegen.types.C_OPAQUE_POINTER_VAR
import io.github.kingg22.godot.codegen.types.K_REQUIRE_NOT_NULL
import io.github.kingg22.godot.codegen.types.LONG_VAR
import io.github.kingg22.godot.codegen.types.cinteropGet
import io.github.kingg22.godot.codegen.types.cinteropInvoke
import io.github.kingg22.godot.codegen.types.cinteropPointed
import io.github.kingg22.godot.codegen.types.cinteropReinterpret
import io.github.kingg22.godot.codegen.types.cinteropStaticCFunction
import io.github.kingg22.godot.codegen.types.cinteropValue
import io.github.kingg22.godot.codegen.types.memScoped

/**
 * Builds `GDExtensionClassCallVirtual` trampolines: the reverse of [EngineMethodImplGen]'s ptrcall
 * bodies. See `docs/technical-design/virtual-dispatch.md` for the type-support matrix.
 */
class VirtualCallImplGen(private val typeResolver: TypeResolver) {
    private lateinit var implPackageRegistry: ImplementationPackageRegistry

    fun initialize(implRegistry: ImplementationPackageRegistry) {
        implPackageRegistry = implRegistry
    }

    fun internalBindingClassName(): ClassName = implPackageRegistry.classNameForOrDefault("InternalBinding")

    context(ctx: Context)
    fun isSupported(method: EngineClass.ClassMethod): Boolean =
        method.isVirtual && method.arguments.all { isArgSupported(it) } && isReturnSupported(method.returnValue)

    context(ctx: Context)
    private fun isArgSupported(arg: MethodArg): Boolean {
        if (arg.type.trim().removePrefix("const ").trim() == "void*") return false
        if (ctx.isNativeStructure(arg.type)) return false
        val kotlinType = typeResolver.resolve(arg)
        return primitiveKotlinToCVar(kotlinType) != null ||
            kotlinType == BOOLEAN ||
            arg.type.startsWith("enum::") ||
            arg.type.startsWith("bitfield::") ||
            ctx.isEngineClass(arg.type) ||
            ctx.isSingleton(arg.type) ||
            ctx.isBuiltin(arg.type)
    }

    context(ctx: Context)
    private fun isReturnSupported(rv: MethodReturn?): Boolean {
        val returnType = rv?.type ?: return true
        if (returnType == "void") return true
        if (ctx.isNativeStructure(returnType)) return false
        if (returnType == "Variant") return true
        // extension_api.json only ever uses the plain "void*" string as a return type (unlike argument
        // types, which also see "const void*") — confirmed against v4_7_1's 3 void*-returning virtuals
        // (ScriptExtension._instance_create/_placeholder_instance_create,
        // ScriptLanguageExtension._debug_get_stack_level_instance), so no "const " stripping needed here.
        if (returnType == "void*") return true
        val kotlinType = typeResolver.resolve(rv)
        return primitiveKotlinToCVar(kotlinType) != null ||
            kotlinType == BOOLEAN ||
            returnType.startsWith("enum::") ||
            returnType.startsWith("bitfield::") ||
            ctx.isEngineClass(returnType) ||
            (ctx.isBuiltin(returnType) && copyConstructorIndex(returnType) != null) ||
            // typedarray::X (typeResolver.resolve() already maps this to GodotArray<X>, confirmed in
            // KotlinNativeTypeResolver.resolvePlain) is backed by a plain Godot "Array" at the ABI level —
            // see heapBackedBuiltinName's doc for why its own copy constructor is enough, no per-element-type
            // constructor to look up.
            (returnType.startsWith("typedarray::") && copyConstructorIndex(heapBackedBuiltinName(returnType)) != null)
    }

    /**
     * Index of the builtin's own copy constructor (the one taking a single argument of its own type),
     * resolved from the same [ctx].model.builtins data every generated builtin wrapper's `constructor(from:
     * T)` already uses — not a hand-picked index. `null` if the type has none (so the return type is left
     * unsupported rather than guessed at).
     */
    context(ctx: Context)
    private fun copyConstructorIndex(builtinTypeName: String): Int? {
        val builtin = ctx.model.builtins.firstOrNull { it.name == builtinTypeName } ?: return null
        return builtin.constructors
            .firstOrNull { it.arguments.size == 1 && it.arguments[0].type == builtinTypeName }
            ?.index
            ?.takeIf { it >= 0 }
    }

    /**
     * The builtin whose copy constructor/`GDEXTENSION_VARIANT_TYPE_*` actually backs [returnType]'s
     * native storage, for [copyConstructorIndex]/[variantTypeConst]/[copyCtorFptrPropertyName] lookups.
     *
     * `typedarray::X` (e.g. `typedarray::Dictionary`, `typedarray::StringName`) is, at the GDExtension
     * ABI level, still exactly a Godot `Array` — there is no separate "typed Array" builtin or
     * constructor in `ctx.model.builtins` to look up per element type. Godot's own `Array` copy
     * constructor (`Array::Array(const Array&)` -> `_ref`) is a ref-counted share of the source's
     * `ArrayPrivate*`, which carries the source's typed state along with it — so the exact same
     * "Array" copy-constructor fptr this file already generates for plain `Array` returns also
     * correctly preserves typing for `typedarray::*` returns, with no extra `set_typed`-equivalent
     * call needed here. Mirrors the forward ptrcall generator (`Shared.kt`'s `buildReturnAlloc`),
     * which likewise treats `typedarray::*` identically to a plain builtin return once resolved.
     */
    private fun heapBackedBuiltinName(returnType: String): String =
        if (returnType.startsWith("typedarray::")) "Array" else returnType

    /** Property name for the trampoline, e.g. `_physics_process` -> `physicsProcess`. */
    fun trampolineName(method: EngineClass.ClassMethod): String = safeIdentifier(method.name.removePrefix("_"))

    context(ctx: Context)
    fun buildTrampoline(method: EngineClass.ClassMethod, engineClassName: ClassName): PropertySpec {
        val callVirtualType = implPackageRegistry.classNameForOrDefault("GDExtensionClassCallVirtual")
        val getInstanceMember = implPackageRegistry.memberNameForOrDefault("getInstance", isExtension = true)

        val rv = method.returnValue
        val returnType = rv?.type
        val hasReturn = returnType != null && returnType != "void"
        val resolvedReturn = if (hasReturn) typeResolver.resolve(rv) else null

        // When a method's return type is String, codegen (TypeOverloadGenerator.GodotStringMapping) renames
        // the GodotString-typed original to `<name>AsGdStr` and repurposes the plain name for an all-Kotlin
        // convenience wrapper (Kotlin String return, Kotlin String params). appendArgRead below always
        // builds GodotString-typed args, so calling the plain name here — as this used to — mismatches its
        // (Kotlin String) parameters whenever the method also takes a String argument. Call the AsGdStr
        // sibling instead: GodotString-typed throughout, matching appendArgRead, and its return is already
        // GodotString (no bridging needed in buildReturnWrite).
        val kotlinMethodName = safeIdentifier(method.name).let { if (returnType == "String") "${it}AsGdStr" else it }

        val argsParamName = if (method.arguments.isEmpty()) "_" else "args"
        val retParamName = if (hasReturn) "ret" else "_"

        val body = buildCodeBlock {
            beginControlFlow(
                "%M { instancePtr, %L, %L ->",
                cinteropStaticCFunction,
                argsParamName,
                retParamName,
            )
            addStatement("val instance = instancePtr.%M<%T>()", getInstanceMember, engineClassName)

            val argNames = method.arguments.mapIndexed { index, arg -> appendArgRead(arg, index) }
            val callExpr = "instance.%N(${argNames.joinToString(", ")})"

            if (hasReturn && resolvedReturn != null) {
                addStatement("val result = $callExpr", kotlinMethodName)
                add(buildReturnWrite(returnType, resolvedReturn))
            } else {
                addStatement(callExpr, kotlinMethodName)
            }

            endControlFlow()
        }

        return PropertySpec.builder(trampolineName(method), callVirtualType).initializer(body).build()
    }

    // ── Argument reading ─────────────────────────────────────────────────────

    /** Appends the `val argN = ...` read statement(s) for [arg] and returns the local variable name. */
    context(ctx: Context)
    private fun CodeBlock.Builder.appendArgRead(arg: MethodArg, index: Int): String {
        val varName = "arg$index"
        val kotlinType = typeResolver.resolve(arg)
        val cVarType = primitiveKotlinToCVar(kotlinType)
        val rawArg = CodeBlock.of("%M(args?.%M(%L))", K_REQUIRE_NOT_NULL, cinteropGet, index)

        when {
            kotlinType == BOOLEAN -> {
                val toBooleanMember = implPackageRegistry.memberNameForOrDefault("toBoolean", isExtension = true)
                addStatement(
                    "val %N = %L.%M<%T>().%M.%M.%M()",
                    varName,
                    rawArg,
                    cinteropReinterpret,
                    primitiveKotlinToCVar(BOOLEAN),
                    cinteropPointed,
                    cinteropValue,
                    toBooleanMember,
                )
            }

            cVarType != null -> addStatement(
                "val %N = %L.%M<%T>().%M.%M",
                varName,
                rawArg,
                cinteropReinterpret,
                cVarType,
                cinteropPointed,
                cinteropValue,
            )

            arg.type.startsWith("enum::") -> addStatement(
                "val %N = %T.fromValue<%T>(%L.%M<%T>().%M.%M)",
                varName,
                ctx.classNameForOrDefault("GodotEnum"),
                kotlinType,
                rawArg,
                cinteropReinterpret,
                LONG_VAR,
                cinteropPointed,
                cinteropValue,
            )

            arg.type.startsWith("bitfield::") -> addStatement(
                "val %N = %T(%L.%M<%T>().%M.%M)",
                varName,
                kotlinType,
                rawArg,
                cinteropReinterpret,
                LONG_VAR,
                cinteropPointed,
                cinteropValue,
            )

            ctx.isEngineClass(arg.type) || ctx.isSingleton(arg.type) -> {
                val ptrVar = "${varName}Ptr"
                addStatement(
                    "val %N = %L.%M<%T>().%M.%M",
                    ptrVar,
                    rawArg,
                    cinteropReinterpret,
                    C_OPAQUE_POINTER_VAR,
                    cinteropPointed,
                    cinteropValue,
                )
                // Unlike a forward (Kotlin-calls-Godot) call, arg.isNullable's JSON-default-value heuristic
                // says nothing about whether the *engine* can hand this pointer back as null on a virtual
                // dispatch — it never does for this direction (extension_api.json has no default_value on
                // virtual-method arguments at all). Godot genuinely does pass null here for some calls (e.g.
                // ScriptLanguageExtension._complete_code's `owner` when the script isn't attached to a live
                // scene object), so always take the null-safe path rather than requireNotNull-ing and
                // crashing the whole process with an uncatchable Kotlin/Native exception across the
                // staticCFunction boundary.
                addStatement("val %N = %N?.let·{·%T(it)·}", varName, ptrVar, kotlinType)
            }

            ctx.isBuiltin(arg.type) -> addStatement("val %N = %T(%L)", varName, kotlinType, rawArg)

            else -> error("Unsupported virtual call argument type: ${arg.type} (resolved: $kotlinType)")
        }

        return varName
    }

    // ── Return writing ───────────────────────────────────────────────────────

    context(ctx: Context)
    private fun buildReturnWrite(returnType: String, kotlinType: TypeName): CodeBlock = buildCodeBlock {
        val cVarType = primitiveKotlinToCVar(kotlinType)
        when {
            kotlinType == BOOLEAN -> {
                val toGdBoolMember = implPackageRegistry.memberNameForOrDefault("toGdBool", isExtension = true)
                addStatement(
                    "ret?.%M<%T>()?.%M?.%M = result.%M()",
                    cinteropReinterpret,
                    primitiveKotlinToCVar(BOOLEAN),
                    cinteropPointed,
                    cinteropValue,
                    toGdBoolMember,
                )
            }

            cVarType != null -> addStatement(
                "ret?.%M<%T>()?.%M?.%M = result",
                cinteropReinterpret,
                cVarType,
                cinteropPointed,
                cinteropValue,
            )

            returnType.startsWith("enum::") || returnType.startsWith("bitfield::") -> addStatement(
                "ret?.%M<%T>()?.%M?.%M = result.value",
                cinteropReinterpret,
                LONG_VAR,
                cinteropPointed,
                cinteropValue,
            )

            // NativeEngineClassGenerator's virtual-stub call site passes forceNullableEngineReturn =
            // method.isVirtual, so `result` is Type? here for every engine-class-returning virtual —
            // COpaquePointer (behind rawPtr) can't itself represent a null address, so a null `result`
            // has to become a null-safe read (`result?.rawPtr` evaluates to null), not a direct property
            // access, or a genuinely-null override return would NPE inside the trampoline instead of
            // correctly reporting "no value" back to Godot. Mirrors appendArgRead's `?.let { }` null
            // safety on the read side of this same trampoline.
            ctx.isEngineClass(returnType) -> addStatement(
                "ret?.%M<%T>()?.%M?.%M = result?.rawPtr",
                cinteropReinterpret,
                C_OPAQUE_POINTER_VAR,
                cinteropPointed,
                cinteropValue,
            )

            // typeResolver.resolve() maps "void*" directly to COpaquePointer (KotlinNativeTypeResolver
            // .resolvePointer), so `result` is already the raw pointer value here — same write as the
            // engine-class branch above, just without unwrapping a `.rawPtr` property first.
            returnType == "void*" -> addStatement(
                "ret?.%M<%T>()?.%M?.%M = result",
                cinteropReinterpret,
                C_OPAQUE_POINTER_VAR,
                cinteropPointed,
                cinteropValue,
            )

            returnType == "Variant" -> addStatement(
                "%T.newCopyRaw(ret, result.rawPtr)",
                implPackageRegistry.classNameForOrDefault("VariantBinding"),
            )

            ctx.isBuiltin(returnType) || returnType.startsWith("typedarray::") -> {
                // buildTrampoline calls the `<name>AsGdStr` sibling whenever returnType == "String", so
                // `result` is already the GodotString-typed wrapper here — no bridging needed for any builtin.
                // typedarray::X results are backed by a plain Array at the ABI level (see
                // heapBackedBuiltinName) — its copy constructor already preserves whatever typed state
                // `result` carries, so it shares the same fptr as plain Array returns.
                val allocConstTypePtrArrayMember = implPackageRegistry.memberNameForOrDefault("allocConstTypePtrArray")
                beginControlFlow("%M", memScoped)
                addStatement(
                    "%N.%M(ret, %M(result.rawPtr))",
                    copyCtorFptrPropertyName(heapBackedBuiltinName(returnType)),
                    cinteropInvoke,
                    allocConstTypePtrArrayMember,
                )
                endControlFlow()
            }

            else -> error("Unsupported virtual call return type: $returnType (resolved: $kotlinType)")
        }
    }

    // ── Heap-backed builtin return support ──────────────────────────────────

    /** Deterministic name for the file-scoped copy-constructor fptr shared by every trampoline in the
     * file that returns [builtinTypeName] — one lookup per type per file, not per method. */
    fun copyCtorFptrPropertyName(builtinTypeName: String): String =
        "${safeIdentifier(builtinTypeName.replaceFirstChar(Char::lowercaseChar))}CopyCtorFptr"

    /**
     * Distinct heap-backed builtin return types among [methods] that need a shared copy-constructor
     * fptr property emitted at file scope (see [buildCopyCtorFptrProperty]).
     */
    context(ctx: Context)
    fun heapBackedReturnTypes(methods: List<EngineClass.ClassMethod>): List<String> = methods
        .mapNotNull { it.returnValue }
        .filter { rv ->
            val returnType = rv.type
            // typedarray::X always needs the (shared) "Array" copy-ctor fptr — no CVar/BOOLEAN case
            // applies to it, unlike the ctx.isBuiltin types below.
            if (returnType.startsWith("typedarray::")) return@filter true
            // `ctx.isBuiltin` also matches primitive Godot type names ("bool", "int", "float", ...) —
            // those are already handled by the CVar/BOOLEAN branches in buildReturnWrite's `when`, so
            // exclude them here too or they'd get an unused fptr property generated for nothing.
            if (returnType == "Variant" || !ctx.isBuiltin(returnType)) return@filter false
            val kotlinType = typeResolver.resolve(rv)
            primitiveKotlinToCVar(kotlinType) == null && kotlinType != BOOLEAN
        }
        .map { heapBackedBuiltinName(it.type) }
        .distinct()

    /**
     * Top-level `private val` lazy property caching the copy-constructor fptr for [builtinTypeName],
     * looked up via `VariantBinding.getPtrConstructorRaw` exactly like every generated builtin wrapper's
     * own `constructor(from: T)` already does (see `BuiltinClassImplGen.buildTopLevelFptrProperties`) —
     * just targeting an externally-given `ret` pointer instead of freshly allocated storage.
     */
    context(ctx: Context)
    fun buildCopyCtorFptrProperty(builtinTypeName: String): PropertySpec {
        val index = copyConstructorIndex(builtinTypeName)
            ?: error("No copy constructor found for builtin type '$builtinTypeName'")
        val variantType = variantTypeConst(builtinTypeName)
            ?: error("Unknown variant type: $builtinTypeName")
        val variantBinding = implPackageRegistry.classNameForOrDefault("VariantBinding")

        return PropertySpec
            .builder(
                copyCtorFptrPropertyName(builtinTypeName),
                implPackageRegistry.classNameForOrDefault("GDExtensionPtrConstructor"),
                KModifier.PRIVATE,
            )
            .delegate(
                buildLazyBlock {
                    addStatement("%T.getPtrConstructorRaw(%N, %L)", variantBinding, variantType, index)
                    withIndent {
                        addStatement("?: error(%S)", "Missing copy constructor for builtin '$builtinTypeName'")
                    }
                },
            )
            .build()
    }
}
