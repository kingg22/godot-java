package io.github.kingg22.godot.codegen.extensionapi.impl.knative.impl

import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.buildCodeBlock
import com.squareup.kotlinpoet.joinToCode
import com.squareup.kotlinpoet.withIndent
import io.github.kingg22.godot.codegen.extensionapi.Context
import io.github.kingg22.godot.codegen.extensionapi.TypeResolver
import io.github.kingg22.godot.codegen.impl.buildLazyBlock
import io.github.kingg22.godot.codegen.impl.ofStatement
import io.github.kingg22.godot.codegen.impl.safeIdentifier
import io.github.kingg22.godot.codegen.models.extensionapi.EngineClass
import io.github.kingg22.godot.codegen.models.extensionapi.domain.ResolvedEngineClass
import io.github.kingg22.godot.codegen.types.K_TODO
import io.github.kingg22.godot.codegen.types.cinteropValue
import io.github.kingg22.godot.codegen.types.memScoped
import io.github.kingg22.godot.codegen.utils.logger
import io.github.kingg22.godot.codegen.utils.warning

/**
 * Generates lazy-loaded method bind properties and ptrcall bodies for engine class methods/properties.
 *
 * ## Calling convention
 *
 * Engine methods use `GDExtensionMethodBindPtr` retrieved via
 * `ClassDBBinding.getMethodBindRaw(className, methodName, hash)`.
 * Invocation: `ObjectBinding.methodBindPtrcallRaw(bind, p_object, p_args, r_ret)`.
 *
 * ## Type resolution
 *
 * Always delegate to [typeResolver] — it carries full meta-aware logic. Never dispatch on
 * raw type strings for CVar selection; use [primitiveKotlinToCVar] on the resolved [TypeName].
 *
 * Examples:
 * - `type=int, meta=uint64` → resolver returns `ULong` → `ULongVar` (no `.toLong()`)
 * - `type=int, meta=int32`  → resolver returns `Int`   → `IntVar`
 * - `type=float` (no meta)  → resolver returns `Double`→ `DoubleVar`
 * - `type=float, meta=float`→ resolver returns `Float` → `FloatVar`
 *
 * ## Vararg methods
 *
 * Fixed args are collected together with the trailing `vararg args: Variant` into a single
 * list, mapped to `.rawPtr`, and passed via `methodBindCall` (varcall path), because
 * ptrcall is only safe for statically-known arg counts.
 *
 * ## Enum return values
 *
 * When the resolved return type is an enum, `retPtr` is a `LongVar` and the read emits
 * `godotEnumFrom<TheEnum>(retPtr.value)`.
 *
 * ## Abstract / non-instantiable return types
 *
 * Cannot do `AbstractClass(ptr)` — emits `TODO()`. Follow-up: Godot runtime-type cast helper.
 *
 * ## Nullable vs non-null engine class return
 *
 * `COpaquePointerVar.value` is nullable. For non-null declared returns we emit
 * `requireNotNull(retPtr.value) { "…" }.let { T(it) }`.
 */
class EngineMethodImplGen(private val typeResolver: TypeResolver) {
    private val logger = logger()
    private lateinit var implPackageRegistry: ImplementationPackageRegistry

    fun initialize(implRegistry: ImplementationPackageRegistry) {
        implPackageRegistry = implRegistry
    }

    // ── Top-level lazy fptr properties ────────────────────────────────────────

    /**
     * One top-level `private val` lazy property per engine method that has a method bind.
     *
     * Virtual methods without a hash have no bind on the Godot side and are skipped.
     */
    context(ctx: Context)
    fun buildTopLevelFptrProperties(cls: ResolvedEngineClass): List<PropertySpec> {
        val classDBBinding = implPackageRegistry.classNameForOrDefault("ClassDBBinding")
        val stringNameClass = ctx.classNameForOrDefault("StringName")
        return cls.raw.methods.mapNotNull { buildMethodBindProperty(it, cls.name, classDBBinding, stringNameClass) }
    }

    private fun buildMethodBindProperty(
        method: EngineClass.ClassMethod,
        className: String,
        classDBBinding: ClassName,
        stringNameClass: ClassName,
    ): PropertySpec? {
        if (method.isVirtual) return null
        val bindType = implPackageRegistry.classNameForOrDefault("GDExtensionMethodBindPtr")
        val body = buildLazyBlock {
            beginControlFlow("%T(%S).use { cn ->", stringNameClass, className)
                .beginControlFlow("%T(%S).use { mn ->", stringNameClass, method.name)
                .addStatement(
                    "%T.getMethodBindRaw(cn.rawPtr, mn.rawPtr, %LL)",
                    classDBBinding,
                    method.hash,
                )
                .withIndent {
                    addStatement(
                        "?: error(\"Missing method bind '%L', hash: %L\")",
                        "$className.${method.name}",
                        method.hash,
                    )
                }
                .endControlFlow()
            endControlFlow()
        }
        return PropertySpec
            .builder(methodBindName(className, method), bindType, KModifier.PRIVATE)
            .delegate(body)
            .build()
    }

    // ── Method bodies ─────────────────────────────────────────────────────────

    context(_: Context)
    fun buildMethodBody(method: EngineClass.ClassMethod, className: String): CodeBlock {
        if (method.isVirtual) {
            val rv = method.returnValue
            val returnType = rv?.type ?: rv?.meta
            val hasReturn = returnType != null && returnType != "void"
            return if (hasReturn) {
                CodeBlock.of(
                    "%M(%P)",
                    K_TODO,
                    $$"Virtual method '$${method.name}' of '$$className' requires override in ${this::class.simpleName}",
                )
            } else {
                CodeBlock.of("")
            }
        }
        if (method.isVararg) {
            return buildVarargBody(method, className)
        }
        return buildPtrcallBody(method, className)
    }

    // ── Property bodies ───────────────────────────────────────────────────────

    context(_: Context)
    fun buildPropertyGetterBody(getter: EngineClass.ClassMethod, cls: ResolvedEngineClass): CodeBlock {
        if (getter.isVirtual) logger.warning { "Found virtual getter: $getter in $cls" }
        return buildPtrcallBody(getter, cls.name)
    }

    context(_: Context)
    fun buildPropertySetterBody(setter: EngineClass.ClassMethod, cls: ResolvedEngineClass): CodeBlock {
        if (setter.isVirtual) logger.warning { "Found virtual setter: $setter in $cls" }
        return buildPtrcallBody(setter, cls.name, true)
    }

    // ── ptrcall body ───────────────────────────────────────────────

    context(ctx: Context)
    private fun buildPtrcallBody(
        method: EngineClass.ClassMethod,
        className: String,
        setterMode: Boolean = false,
    ): CodeBlock {
        val objectBinding = implPackageRegistry.classNameForOrDefault("ObjectBinding")
        val propName = methodBindName(className, method)
        val allocConstTypePtrArray = implPackageRegistry.memberNameForOrDefault("allocConstTypePtrArray")

        val rv = method.returnValue
        val returnType = rv?.type
        val hasReturn = returnType != null && returnType != "void"
        val resolvedReturn = if (hasReturn) typeResolver.resolve(rv) else null

        return buildCodeBlock {
            if (hasReturn && !setterMode) add("return ")

            // TODO check if this is still needed when doesn't have to alloc anything
            beginControlFlow("%M", memScoped)

            // 1. Return buffer
            if (hasReturn && resolvedReturn != null) {
                add(buildReturnAlloc(returnType, implPackageRegistry, resolvedReturn))
            }

            // 2. Arg allocs — last arg uses name "value" in setter mode
            method.arguments.forEach { arg ->
                add(buildArgAlloc(arg, implPackageRegistry, typeResolver))
            }

            // 3. p_object
            val pObject = if (method.isStatic) "null" else "rawPtr"

            // 4. Invocation
            val argPtrs = method.arguments.map { arg ->
                argPointerExpression(arg, implPackageRegistry, typeResolver)
            } + buildList {
                if (method.isVararg) {
                    this.add(CodeBlock.ofStatement("*args.map·{·it.rawPtr·}.toTypedArray(),"))
                }
            }

            addStatement("%T.methodBindPtrcallRaw(", objectBinding)
            withIndent {
                addStatement("%N,", propName)
                addStatement("%L,", pObject)
                if (argPtrs.isEmpty()) {
                    addStatement("null,")
                } else {
                    addStatement("%M(", allocConstTypePtrArray)
                    withIndent { add(argPtrs.joinToCode("")) }
                    addStatement("),")
                }
                if (hasReturn && resolvedReturn != null) {
                    addStatement("%L,", returnArgExpression(returnType, resolvedReturn).asCodeBlock)
                } else {
                    addStatement("null,")
                }
            }
            addStatement(")")

            // 5. Return read
            if (hasReturn && resolvedReturn != null) {
                if (setterMode) {
                    val contextInfo = "$className.${method.name}"

                    when (resolvedReturn) {
                        // Caso especial: GodotError
                        ctx.classNameForOrDefault("Error") -> {
                            addStatement("%M(", implPackageRegistry.memberNameForOrDefault("checkGodotError"))
                            withIndent {
                                addStatement("%S,", contextInfo)
                                // Aquí insertamos la lectura del retorno como segundo argumento
                                add("retPtr.%M", cinteropValue)
                            }
                            addStatement(")")
                        }

                        // Caso especial: Boolean check
                        BOOLEAN -> {
                            addStatement("check(")
                            withIndent { add(buildReturnRead(returnType, implPackageRegistry, resolvedReturn, true)) }
                            addStatement(")·{♢%S♢}", "$contextInfo doesn't return true")
                        }

                        // Fallback para otros tipos en setterMode (si aplica)
                        else -> add(buildReturnRead(returnType, implPackageRegistry, resolvedReturn, true))
                    }
                } else {
                    // No es setterMode: Solo emitimos la lectura normal del retorno
                    add(buildReturnRead(returnType, implPackageRegistry, resolvedReturn))
                }
            }

            endControlFlow()
        }
    }

    // ── vararg body ───────────────────────────────────────────────

    /**
     * Generates the body for vararg methods using methodBindCall instead of methodBindPtrcallRaw.
     *
     * Vararg methods cannot use ptrcall because the argument count is not known at compile time.
     * Instead, we use methodBindCall which accepts vararg Variant pointers directly.
     */
    context(ctx: Context)
    private fun buildVarargBody(method: EngineClass.ClassMethod, className: String): CodeBlock {
        val objectBinding = implPackageRegistry.classNameForOrDefault("ObjectBinding")
        val checkCallError = implPackageRegistry.memberNameForOrDefault("checkCallError")
        val propName = methodBindName(className, method)

        val rv = method.returnValue
        val returnType = rv?.meta ?: rv?.type
        val kotlinReturnType = if (rv != null) typeResolver.resolve(rv) else null
        val hasReturn = returnType != null && returnType != "void"

        return buildCodeBlock {
            if (hasReturn) add("return ")

            beginControlFlow("%M", memScoped)

            // Return buffer - always allocate as Variant for vararg calls
            addStatement("val retPtr = %T()", ctx.classNameForOrDefault("Variant"))

            // Collect fixed args pointers
            val fixedArgPtrs = method.arguments.map { arg -> argVariantPointerExpression(arg) }

            // methodBindCall invocation
            addStatement("val error = %T.methodBindCall(", objectBinding)
            withIndent {
                addStatement("%N,", propName)
                addStatement("rawPtr,")
                // Emit fixed arg pointers
                fixedArgPtrs.forEach { add(it) }
                // Emit vararg spread
                if (method.isVararg) {
                    addStatement("*args.map·{·it.rawPtr·}.toTypedArray(),")
                }
                addStatement("rRet = retPtr.rawPtr,")
            }
            addStatement(")")

            addStatement("%M(%S, error)", checkCallError, "${method.name} of $className")

            // Return value handling - use Variant converter methods for vararg return
            if (hasReturn && kotlinReturnType != null) {
                add(buildReturnReadOfVariant(returnType, kotlinReturnType))
            }

            endControlFlow()
        }
    }

    // ── Naming ────────────────────────────────────────────────────────────────

    fun methodBindName(className: String, method: EngineClass.ClassMethod): String = "method$className" +
        safeIdentifier(method.name).replaceFirstChar(Char::uppercase) + "_Bind"
}
