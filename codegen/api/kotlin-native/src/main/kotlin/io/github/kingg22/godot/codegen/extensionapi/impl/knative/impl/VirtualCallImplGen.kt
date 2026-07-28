package io.github.kingg22.godot.codegen.extensionapi.impl.knative.impl

import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.buildCodeBlock
import io.github.kingg22.godot.codegen.extensionapi.Context
import io.github.kingg22.godot.codegen.extensionapi.TypeResolver
import io.github.kingg22.godot.codegen.impl.safeIdentifier
import io.github.kingg22.godot.codegen.models.extensionapi.EngineClass
import io.github.kingg22.godot.codegen.models.extensionapi.MethodArg
import io.github.kingg22.godot.codegen.models.extensionapi.MethodReturn
import io.github.kingg22.godot.codegen.types.C_OPAQUE_POINTER_VAR
import io.github.kingg22.godot.codegen.types.K_REQUIRE_NOT_NULL
import io.github.kingg22.godot.codegen.types.LONG_VAR
import io.github.kingg22.godot.codegen.types.cinteropGet
import io.github.kingg22.godot.codegen.types.cinteropPointed
import io.github.kingg22.godot.codegen.types.cinteropReinterpret
import io.github.kingg22.godot.codegen.types.cinteropStaticCFunction
import io.github.kingg22.godot.codegen.types.cinteropValue

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
        val kotlinType = typeResolver.resolve(rv)
        return primitiveKotlinToCVar(kotlinType) != null ||
            kotlinType == BOOLEAN ||
            returnType.startsWith("enum::") ||
            returnType.startsWith("bitfield::") ||
            ctx.isEngineClass(returnType)
    }

    /** Property name for the trampoline, e.g. `_physics_process` -> `physicsProcess`. */
    fun trampolineName(method: EngineClass.ClassMethod): String = safeIdentifier(method.name.removePrefix("_"))

    context(ctx: Context)
    fun buildTrampoline(method: EngineClass.ClassMethod, engineClassName: ClassName): PropertySpec {
        val callVirtualType = implPackageRegistry.classNameForOrDefault("GDExtensionClassCallVirtual")
        val getInstanceMember = implPackageRegistry.memberNameForOrDefault("getInstance", isExtension = true)
        val kotlinMethodName = safeIdentifier(method.name)

        val rv = method.returnValue
        val returnType = rv?.type
        val hasReturn = returnType != null && returnType != "void"
        val resolvedReturn = if (hasReturn) typeResolver.resolve(rv) else null

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
                if (arg.isNullable) {
                    addStatement("val %N = %N?.let·{·%T(it)·}", varName, ptrVar, kotlinType)
                } else {
                    addStatement(
                        "val %N = %T(%M(%N) { %S })",
                        varName,
                        kotlinType,
                        K_REQUIRE_NOT_NULL,
                        ptrVar,
                        "Argument $index (${arg.type}) was null",
                    )
                }
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

            ctx.isEngineClass(returnType) -> addStatement(
                "ret?.%M<%T>()?.%M?.%M = result.rawPtr",
                cinteropReinterpret,
                C_OPAQUE_POINTER_VAR,
                cinteropPointed,
                cinteropValue,
            )

            else -> error("Unsupported virtual call return type: $returnType (resolved: $kotlinType)")
        }
    }
}
