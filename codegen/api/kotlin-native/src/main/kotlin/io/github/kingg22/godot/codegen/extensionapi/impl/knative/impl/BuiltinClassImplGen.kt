package io.github.kingg22.godot.codegen.extensionapi.impl.knative.impl

import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import io.github.kingg22.godot.codegen.extensionapi.Context
import io.github.kingg22.godot.codegen.extensionapi.TypeResolver
import io.github.kingg22.godot.codegen.impl.buildLazyBlock
import io.github.kingg22.godot.codegen.impl.ofStatement
import io.github.kingg22.godot.codegen.impl.safeIdentifier
import io.github.kingg22.godot.codegen.models.extensionapi.BuiltinClass
import io.github.kingg22.godot.codegen.models.extensionapi.MethodArg
import io.github.kingg22.godot.codegen.models.extensionapi.domain.ResolvedBuiltinClass
import io.github.kingg22.godot.codegen.models.extensionapi.domain.ResolvedBuiltinConstructor
import io.github.kingg22.godot.codegen.types.*

// ── Named constants ────────────────────────────────────────────────────────────
private const val VARIANT_TYPE_STRING = "GDEXTENSION_VARIANT_TYPE_STRING"
private const val VARIANT_TYPE_STRING_NAME = "GDEXTENSION_VARIANT_TYPE_STRING_NAME"
private const val VARIANT_TYPE_NODE_PATH = "GDEXTENSION_VARIANT_TYPE_NODE_PATH"

/**
 * All Godot builtin class types that need native memory backing (storage + rawPtr).
 *
 * Covers every non-primitive builtin. Types without a destructor (Vector2, Color, etc.)
 * still need storage + rawPtr so they can be passed to other GDExtension ptr-constructors.
 *
 * `Variant` itself is NOT here — handled separately by `VariantImplGen`.
 */
private val STORAGE_BACKED_BUILTINS = setOf(
    "String", "StringName", "NodePath",
    "Vector2", "Vector2i", "Rect2", "Rect2i",
    "Vector3", "Vector3i", "Transform2D",
    "Vector4", "Vector4i", "Plane", "Quaternion",
    "Aabb", "AABB", "Basis", "Transform3D", "Projection",
    "Color",
    "Rid", "RID", "Callable", "Signal", "Dictionary", "Array",
    "PackedByteArray", "PackedInt32Array", "PackedInt64Array",
    "PackedFloat32Array", "PackedFloat64Array", "PackedStringArray",
    "PackedVector2Array", "PackedVector3Array", "PackedColorArray",
    "PackedVector4Array",
)

/** Explicit map of Godot class name → GDEXTENSION_VARIANT_TYPE_* constant. */
private fun variantTypeConst(godotName: String?): String? = when (godotName) {
    "nil", "null", null -> "GDEXTENSION_VARIANT_TYPE_NIL"
    "bool" -> "GDEXTENSION_VARIANT_TYPE_BOOL"
    "int" -> "GDEXTENSION_VARIANT_TYPE_INT"
    "float" -> "GDEXTENSION_VARIANT_TYPE_FLOAT"
    "String" -> VARIANT_TYPE_STRING
    "StringName" -> VARIANT_TYPE_STRING_NAME
    "NodePath" -> VARIANT_TYPE_NODE_PATH
    "Vector2" -> "GDEXTENSION_VARIANT_TYPE_VECTOR2"
    "Vector2i" -> "GDEXTENSION_VARIANT_TYPE_VECTOR2I"
    "Rect2" -> "GDEXTENSION_VARIANT_TYPE_RECT2"
    "Rect2i" -> "GDEXTENSION_VARIANT_TYPE_RECT2I"
    "Vector3" -> "GDEXTENSION_VARIANT_TYPE_VECTOR3"
    "Vector3i" -> "GDEXTENSION_VARIANT_TYPE_VECTOR3I"
    "Transform2D" -> "GDEXTENSION_VARIANT_TYPE_TRANSFORM2D"
    "Vector4" -> "GDEXTENSION_VARIANT_TYPE_VECTOR4"
    "Vector4i" -> "GDEXTENSION_VARIANT_TYPE_VECTOR4I"
    "Plane" -> "GDEXTENSION_VARIANT_TYPE_PLANE"
    "Quaternion" -> "GDEXTENSION_VARIANT_TYPE_QUATERNION"
    "Aabb", "AABB" -> "GDEXTENSION_VARIANT_TYPE_AABB"
    "Basis" -> "GDEXTENSION_VARIANT_TYPE_BASIS"
    "Transform3D" -> "GDEXTENSION_VARIANT_TYPE_TRANSFORM3D"
    "Projection" -> "GDEXTENSION_VARIANT_TYPE_PROJECTION"
    "Color" -> "GDEXTENSION_VARIANT_TYPE_COLOR"
    "Rid", "RID" -> "GDEXTENSION_VARIANT_TYPE_RID"
    "Callable" -> "GDEXTENSION_VARIANT_TYPE_CALLABLE"
    "Signal" -> "GDEXTENSION_VARIANT_TYPE_SIGNAL"
    "Dictionary" -> "GDEXTENSION_VARIANT_TYPE_DICTIONARY"
    "Array" -> "GDEXTENSION_VARIANT_TYPE_ARRAY"
    "PackedByteArray" -> "GDEXTENSION_VARIANT_TYPE_PACKED_BYTE_ARRAY"
    "PackedInt32Array" -> "GDEXTENSION_VARIANT_TYPE_PACKED_INT32_ARRAY"
    "PackedInt64Array" -> "GDEXTENSION_VARIANT_TYPE_PACKED_INT64_ARRAY"
    "PackedFloat32Array" -> "GDEXTENSION_VARIANT_TYPE_PACKED_FLOAT32_ARRAY"
    "PackedFloat64Array" -> "GDEXTENSION_VARIANT_TYPE_PACKED_FLOAT64_ARRAY"
    "PackedStringArray" -> "GDEXTENSION_VARIANT_TYPE_PACKED_STRING_ARRAY"
    "PackedVector2Array" -> "GDEXTENSION_VARIANT_TYPE_PACKED_VECTOR2_ARRAY"
    "PackedVector3Array" -> "GDEXTENSION_VARIANT_TYPE_PACKED_VECTOR3_ARRAY"
    "PackedColorArray" -> "GDEXTENSION_VARIANT_TYPE_PACKED_COLOR_ARRAY"
    "PackedVector4Array" -> "GDEXTENSION_VARIANT_TYPE_PACKED_VECTOR4_ARRAY"
    "Object" -> "GDEXTENSION_VARIANT_TYPE_OBJECT"
    else -> null
}

private val K_FUNCTION = ClassName("kotlin", "Function")

/**
 * Generates implementation bodies for Godot builtin classes.
 *
 * Injecting [typeResolver] is required so that constructor argument types are resolved with
 * their `meta` hint (e.g. `meta:"int32"` → `Int`, `meta:"float"` → `Float`) before deciding
 * which `*Var` stack allocation to emit inside a `memScoped` block.
 */
class BuiltinClassImplGen(private val typeResolver: TypeResolver, private val methodImplGen: BuiltinMethodImplGen) {
    private lateinit var implPackageRegistry: ImplementationPackageRegistry

    fun initialize(implementationPackageRegistry: ImplementationPackageRegistry) {
        implPackageRegistry = implementationPackageRegistry
        methodImplGen.initialize(implementationPackageRegistry)
    }

    // ── Storage infrastructure ────────────────────────────────────────────────

    context(ctx: Context)
    fun configureStorageBackedBuiltin(
        builtinClass: ResolvedBuiltinClass,
        classBuilder: TypeSpec.Builder,
    ): TypeSpec.Builder {
        if (builtinClass.name !in STORAGE_BACKED_BUILTINS) return classBuilder

        val layout = builtinClass.layout
            ?: error("Missing layout for storage-backed builtin '${builtinClass.name}'")

        classBuilder.primaryConstructor(
            FunSpec
                .constructorBuilder()
                .addParameter("rawPtr", COPAQUE_POINTER.copy(nullable = true))
                .build(),
        )

        val storageProperty = PropertySpec
            .builder("storage", C_POINTER.parameterizedBy(BYTE_VAR), KModifier.PRIVATE)
            .initializer(
                CodeBlock
                    .builder()
                    .addStatement("rawPtr?.%M()", cinteropReinterpret)
                    .withIndent {
                        addStatement(
                            "?: %T.alloc(size = %L, align = %L)",
                            cinteropNativeHeap,
                            layout.size,
                            layout.align,
                        )
                        withIndent {
                            addStatement(
                                ".%M<%T>().%M",
                                cinteropReinterpret,
                                BYTE_VAR,
                                cinteropPtr,
                            )
                        }
                    }
                    .build(),
            )
            .build()

        classBuilder.addProperty(storageProperty)

        if (builtinClass.hasDestructor) {
            classBuilder.addProperty(
                PropertySpec
                    .builder("closed", BOOLEAN, KModifier.PRIVATE)
                    .mutable(true)
                    // If this object was constructed by opaque pointer, don't allow closing this object
                    .initializer("rawPtr != null")
                    .build(),
            )
        }

        classBuilder
            .addSuperinterface(ctx.classNameForOrDefault("GodotNative"))
            .addProperty(
                PropertySpec
                    .builder("rawPtr", COPAQUE_POINTER, KModifier.OVERRIDE)
                    .getter(FunSpec.getterBuilder().addStatement("return %N", storageProperty).build())
                    .build(),
            )

        return classBuilder
    }

    fun configureStorageBackedSecondaryCtor(
        builtinClass: ResolvedBuiltinClass,
        ctorBuilder: FunSpec.Builder,
    ): FunSpec.Builder {
        if (builtinClass.name !in STORAGE_BACKED_BUILTINS) return ctorBuilder
        ctorBuilder.callThisConstructor("null")
        return ctorBuilder
    }

    // ── close() ───────────────────────────────────────────────────────────────

    /**
     * Generates the `close()` override for builtin classes that have a GDExtension destructor.
     *
     * Note: `NativeBuiltinClassGenerator` only calls this when `hasDestructor == true`, so
     * math types (Vector2, Color, …) in `STORAGE_BACKED_BUILTINS` without a destructor
     * never reach here.
     */
    fun buildCloseFunction(builtinClass: ResolvedBuiltinClass): FunSpec {
        if (!builtinClass.hasDestructor || builtinClass.name !in STORAGE_BACKED_BUILTINS) {
            error("Builtin class doesn't have a close() function: $builtinClass")
        }

        return FunSpec
            .builder("close")
            .addModifiers(KModifier.OVERRIDE)
            .addCode(
                CodeBlock
                    .builder()
                    .beginControlFlow("if (!closed)")
                    .addStatement("destructorFptr.%M(rawPtr)", cinteropInvoke)
                    .addStatement("%T.free(%N.rawValue)", cinteropNativeHeap, "storage")
                    .addStatement("closed = true")
                    .endControlFlow()
                    .build(),
            )
            .build()
    }

    // ── Constructor bodies ────────────────────────────────────────────────────

    context(context: Context)
    fun constructorBodyFor(builtinClass: ResolvedBuiltinClass, ctor: ResolvedBuiltinConstructor): CodeBlock {
        if (builtinClass.name !in STORAGE_BACKED_BUILTINS) error("Class is not storage-backed: $builtinClass")
        return constructorInvocation(builtinClass, ctor)
    }

    context(context: Context)
    fun stringConstructorBodyFor(builtinClass: ResolvedBuiltinClass): CodeBlock {
        val stringBinding = implPackageRegistry.classNameForOrDefault("StringBinding")

        return when (builtinClass.name) {
            "String" ->
                CodeBlock
                    .builder()
                    .beginControlFlow("%M", memScoped)
                    .addStatement(
                        "%T.newWithUtf16CharsRaw(rawPtr, value.%M.%M)",
                        stringBinding,
                        cinteropStrUtf16,
                        cinteropPtr,
                    )
                    .endControlFlow()
                    .build()

            "StringName" -> CodeBlock.builder()
                .addStatement("%T.nameNewWithUtf8Chars(rawPtr, value)", stringBinding).build()

            "NodePath" -> CodeBlock.builder()
                .beginControlFlow(
                    "%T(value).use { godotString ->",
                    context.classNameForOrDefault("String", "GodotString"),
                )
                .add(callBuiltinConstructorSimple(2, "godotString.rawPtr"))
                .endControlFlow()
                .build()

            else -> error("Synthetic String constructor not supported for: $builtinClass")
        }
    }

    fun callableCustomConstructorFor() = FunSpec
        .constructorBuilder()
        .callThisConstructor("null")
        .addKdoc(
            """
            Creates a Godot Callable from a Kotlin lambda with arguments [variant type compatible][MustBeVariant].

            The return type of the lambda must be a [Variant type compatible][MustBeVariant].

            Usage:
            ```
            val callable = Callable { println("Hello!") }
            val result: Variant = callable.call() // sync
            callable.callDeferred() // async
            ```

            **Safety**:
            - The lambda must not be more than ≈ 22 arguments, otherwise throws [IllegalStateException].
            - Currently, not all Kotlin types are supported as parameters.
            - Currently methods of an instance are supported, but the Callable still register as [custom][Callable.isCustom]

            @param lambda The Kotlin lambda to wrap
            @return A [Callable] that wraps the Kotlin lambda
            """.trimIndent(),
        )
        .addParameter("lambda", K_FUNCTION.parameterizedBy(STAR))
        .addStatement("@%T(%T::class)", K_OPT_IN, implPackageRegistry.classNameForOrDefault("InternalBinding"))
        .addStatement("%T.create(lambda, this)", implPackageRegistry.classNameForOrDefault("CallableFactory"))
        .build()

    // ── Top-level fptr lazy properties ────────────────────────────────────────

    /**
     * Emits top-level `private val` lazy properties for all function pointers that
     * the class file needs: constructors, destructor (if any), and per-member getter/setter
     * for members that have NO direct storage offset (utility/computed members).
     *
     * These are file-scoped so they are shared across all instances without a class-level lock.
     *
     * @param builtinClass the class being generated
     * @param utilMembers members from [BuiltinClass.members] whose name is NOT present in
     * [io.github.kingg22.godot.codegen.models.extensionapi.domain.ResolvedBuiltinLayout.memberOffsets] —
     * i.e., utility/computed members
     */
    context(ctx: Context)
    fun buildTopLevelFptrProperties(
        builtinClass: ResolvedBuiltinClass,
        utilMembers: List<BuiltinClass.BuiltinClassMember>,
    ): List<PropertySpec> {
        if (builtinClass.name !in STORAGE_BACKED_BUILTINS) return emptyList()

        val variantType = variantTypeConst(builtinClass.name) ?: error("Unknown variant type: ${builtinClass.name}")
        val variantBinding = implPackageRegistry.classNameForOrDefault("VariantBinding")
        val stringNameClass = ctx.classNameForOrDefault("StringName")

        return buildList {
            // ── Constructors ──────────────────────────────────────────────────
            builtinClass.constructors.filter { it.raw != null && !it.usesKotlinStringBridge }.forEach { ctor ->
                add(
                    PropertySpec
                        .builder(
                            "constructorFptr_${ctor.index}",
                            implPackageRegistry.classNameForOrDefault("GDExtensionPtrConstructor"),
                            KModifier.PRIVATE,
                        )
                        .delegate(
                            buildLazyBlock {
                                addStatement(
                                    "%T.getPtrConstructorRaw(%N, %L)",
                                    variantBinding,
                                    variantType,
                                    ctor.index,
                                )
                                withIndent {
                                    addStatement(
                                        "?: error(%S)",
                                        "Missing builtin constructor for ${builtinClass.name}[${ctor.index}]",
                                    )
                                }
                            },
                        )
                        .build(),
                )
            }

            // ── Destructor ────────────────────────────────────────────────────
            if (builtinClass.hasDestructor) {
                add(
                    PropertySpec
                        .builder(
                            "destructorFptr",
                            implPackageRegistry.classNameForOrDefault("GDExtensionPtrDestructor"),
                            KModifier.PRIVATE,
                        )
                        .delegate(
                            buildLazyBlock {
                                addStatement("%T.getPtrDestructorRaw(%N)", variantBinding, variantType)
                                withIndent {
                                    addStatement("?: error(%S)", "Missing builtin destructor for ${builtinClass.name}")
                                }
                            },
                        )
                        .build(),
                )
            }

            // ── Utility member getters/setters ────────────────────────────────
            utilMembers.forEach { member ->
                val safeName = safeIdentifier(member.name)
                add(
                    PropertySpec
                        .builder(
                            "memberGetterFptr_$safeName",
                            implPackageRegistry.classNameForOrDefault("GDExtensionPtrGetter"),
                            KModifier.PRIVATE,
                        )
                        .delegate(
                            buildLazyBlock {
                                beginControlFlow("%T(%S).use { sn ->", stringNameClass, member.name)
                                    .addStatement(
                                        "%T.getPtrGetterRaw(%N, sn.rawPtr)",
                                        variantBinding,
                                        variantType,
                                    )
                                    .withIndent {
                                        addStatement(
                                            "?: error(%S)",
                                            "Missing getter for ${builtinClass.name}.$safeName",
                                        )
                                    }
                                endControlFlow()
                            },
                        )
                        .build(),
                )
                add(
                    PropertySpec
                        .builder(
                            "memberSetterFptr_$safeName",
                            implPackageRegistry.classNameForOrDefault("GDExtensionPtrSetter"),
                            KModifier.PRIVATE,
                        )
                        .delegate(
                            buildLazyBlock {
                                beginControlFlow("%T(%S).use { sn ->", stringNameClass, member.name)
                                    .addStatement(
                                        "%T.getPtrSetterRaw(%N, sn.rawPtr)",
                                        variantBinding,
                                        variantType,
                                    )
                                    .withIndent {
                                        addStatement(
                                            "?: error(%S)",
                                            "Missing setter for ${builtinClass.name}.$safeName",
                                        )
                                    }
                                endControlFlow()
                            },
                        )
                        .build(),
                )
            }

            // ── Method fptrs ──────────────────────────────────────────────────
            builtinClass.raw.methods.forEach { method ->
                add(methodImplGen.buildMethodFptrProperty(method, variantType, builtinClass.name))
            }

            // ── Operator evaluator fptrs ──────────────────────────────────────────────
            builtinClass.raw.operators
                .forEach { op ->
                    if (op.name == "!=") return@forEach // != derived from equals; Kotlin never calls this fptr
                    if (op.name == "==" && op.rightType == "Variant") return@forEach // == requires the other fptr
                    val variantOp = methodImplGen.godotOpToVariantOp(op.name) ?: return@forEach
                    val rightVariantType = variantTypeConst(op.rightType) ?: "GDEXTENSION_VARIANT_TYPE_NIL"
                    add(
                        PropertySpec
                            .builder(
                                methodImplGen.operatorFptrName(op),
                                implPackageRegistry.classNameForOrDefault("GDExtensionPtrOperatorEvaluator"),
                                KModifier.PRIVATE,
                            )
                            .delegate(
                                buildLazyBlock {
                                    addStatement("%T", variantBinding)
                                    withIndent {
                                        addStatement(".getPtrOperatorEvaluatorRaw(")
                                        withIndent {
                                            addStatement("%N,", variantOp)
                                            addStatement("%N,", variantType)
                                            addStatement("%N,", rightVariantType)
                                        }
                                        addStatement(")")
                                    }
                                    withIndent {
                                        addStatement(
                                            "?: error(%S)",
                                            "Missing operator evaluator (${builtinClass.name})${op.name}(${op.rightType.orEmpty()})",
                                        )
                                    }
                                },
                            )
                            .build(),
                    )
                }
        }
    }

    // ── Member getters/setters via fptr (utility members) ─────────────────────

    /**
     * Generates a getter for a utility member (no storage offset) using the cached
     * `_memberGetterFptr_$memberName` top-level lazy val.
     *
     * Signature of GDExtensionPtrGetter: `(p_base: COpaquePointer, r_value: COpaquePointer) → Unit`
     * - p_base = rawPtr (self)
     * - r_value = output buffer
     *
     * For primitive types, allocates a stack CVar to receive the value.
     * For builtin class types, constructs a new instance and uses its rawPtr as output.
     */
    fun buildMemberGetterViaFptr(memberName: String, memberType: TypeName): FunSpec {
        val safeName = safeIdentifier(memberName)
        val fptrVal = "memberGetterFptr_$safeName"
        val getter = FunSpec.getterBuilder()

        val cVarType = primitiveKotlinToCVar(memberType)
        if (cVarType != null) {
            // Primitive: alloc stack var, invoke, read value
            getter.beginControlFlow("%M", memScoped)
                .addStatement("val buf = %M<%T>()", cinteropAlloc, cVarType)
                .addStatement("%L.%M(rawPtr, buf.%M)", fptrVal, cinteropInvoke, cinteropPtr)
                .addStatement(
                    "return buf.%M%L",
                    cinteropValue,
                    if (memberType == BOOLEAN) {
                        implPackageRegistry.memberNameForOrDefault("toBoolean")
                    } else {
                        ""
                    },
                )
            getter.endControlFlow()
        } else {
            // Builtin class: construct an empty instance, invoke into its rawPtr
            getter.addStatement("val result = %T()", memberType)
            getter.addStatement("%L.%M(rawPtr, result.rawPtr)", fptrVal, cinteropInvoke)
            getter.addStatement("return result")
        }

        return getter.build()
    }

    /**
     * Generates a setter for a utility member using `_memberSetterFptr_$memberName`.
     *
     * Signature of GDExtensionPtrSetter: `(p_base: COpaquePointer, p_value: COpaquePointer) → Unit`
     */
    fun buildMemberSetterViaFptr(memberName: String, memberType: TypeName): FunSpec {
        val safeName = safeIdentifier(memberName)
        val fptrVal = "memberSetterFptr_$safeName"
        val setter = FunSpec.setterBuilder().addParameter("value", memberType)

        val cVarType = primitiveKotlinToCVar(memberType)
        if (cVarType != null) {
            setter.beginControlFlow("%M", memScoped)
                .addStatement("val buf = %M<%T>()", cinteropAlloc, cVarType)
                .addStatement(
                    "buf.%M = value%L",
                    cinteropValue,
                    if (memberType == BOOLEAN) {
                        implPackageRegistry.memberNameForOrDefault("toGdBool")
                    } else {
                        ""
                    },
                )
                .addStatement("%L.%M(rawPtr, buf.%M)", fptrVal, cinteropInvoke, cinteropPtr)
            setter.endControlFlow()
        } else {
            setter.addStatement("%L.%M(rawPtr, value.rawPtr)", fptrVal, cinteropInvoke)
        }

        return setter.build()
    }

    context(context: Context)
    fun buildMethodBody(method: BuiltinClass.BuiltinMethod, className: String): CodeBlock =
        methodImplGen.buildMethodBody(method, className)

    context(_: Context)
    fun buildHashCodeBody(resolvedClass: ResolvedBuiltinClass): CodeBlock =
        methodImplGen.buildHashCodeBody(resolvedClass)

    context(_: Context)
    fun buildToStringBody(): CodeBlock = methodImplGen.buildToStringBody()

    fun buildToKStringConverters() = methodImplGen.buildToStringConverters()

    context(_: Context)
    fun buildStaticStringConverters() = methodImplGen.buildStaticFromString()

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Generates the full constructor invocation CodeBlock.
     *
     * For String-bridge constructors and the known String/StringName/NodePath cases,
     * delegates to the existing specific implementations.
     *
     * For all other storage-backed builtins, uses the **generic** path: resolves each arg's
     * Kotlin type (respecting `meta` hints), emits the correct `*Var` stack-alloc inside
     * `memScoped`, and builds the `getPtrConstructorRaw` + `invoke` call.
     */
    context(context: Context)
    private fun constructorInvocation(
        builtinClass: ResolvedBuiltinClass,
        ctor: ResolvedBuiltinConstructor,
    ): CodeBlock {
        val stringBinding = implPackageRegistry.classNameForOrDefault("StringBinding")

        if (ctor.usesKotlinStringBridge) {
            return when (builtinClass.name) {
                "String" -> CodeBlock.builder()
                    .addStatement("%T.newWithUtf8Chars(rawPtr, value)", stringBinding).build()

                "StringName" -> CodeBlock.builder()
                    .addStatement("%T.nameNewWithUtf8Chars(rawPtr, value)", stringBinding).build()

                "NodePath" -> CodeBlock.builder()
                    .beginControlFlow(
                        "%T(value).use { godotString ->",
                        context.classNameForOrDefault("String", "GodotString"),
                    )
                    .add(callBuiltinConstructorSimple(2, "godotString.rawPtr"))
                    .endControlFlow()
                    .build()

                else -> error("String bridge not supported for: $builtinClass")
            }
        }

        return when (builtinClass.name) {
            "String" -> callBuiltinConstructorSimple(
                ctor.index,
                if (ctor.index > 0) "from.rawPtr" else "",
            )

            "StringName" -> callBuiltinConstructorSimple(
                ctor.index,
                if (ctor.index > 0) "from.rawPtr" else "",
            )

            "NodePath" -> callBuiltinConstructorSimple(
                ctor.index,
                if (ctor.index > 0) "from.rawPtr" else "",
            )

            else -> callBuiltinConstructorGeneric(ctor.index, ctor.arguments)
        }
    }

    /**
     * Generic constructor emitter that resolves each arg's Kotlin type (with meta) to pick
     * the correct `*Var` stack allocation.
     *
     * Type mapping (matches [io.github.kingg22.godot.codegen.extensionapi.impl.knative.KotlinNativeTypeResolver.resolveWithMeta]):
     * - `FLOAT`  → `FloatVar`,  `alloc<FloatVar>().also { it.value = arg }`
     * - `DOUBLE` → `DoubleVar`, `alloc<DoubleVar>().also { it.value = arg }`
     * - `INT`    → `IntVar`,    `alloc<IntVar>().also { it.value = arg }`
     * - `LONG`   → `LongVar`,   `alloc<LongVar>().also { it.value = arg }`
     * - `BYTE`   → `ByteVar`    (similarly for Short, UByte, UShort, UInt, ULong)
     * - `BOOLEAN`→ `allocGdBool(arg)` (no reinterpret needed)
     * - everything else → assumed to be a builtin class with `rawPtr`
     */
    context(context: Context)
    private fun callBuiltinConstructorGeneric(constructorIndex: Int, args: List<MethodArg>): CodeBlock =
        CodeBlock.builder().beginControlFlow("%M", memScoped).apply {
            val ptrExprs = args.map { arg ->
                val kotlinName = safeIdentifier(arg.name)
                val varName = "${kotlinName}Var"
                val kotlinType = typeResolver.resolve(arg.type, arg.meta)

                when {
                    kotlinType == BOOLEAN -> {
                        addStatement(
                            "val %N = %M(%N)",
                            varName,
                            implPackageRegistry.memberNameForOrDefault("allocGdBool"),
                            kotlinName,
                        )
                        CodeBlock.ofStatement("%N,", varName)
                    }

                    kotlinType in listOf(FLOAT, DOUBLE, INT, LONG, BYTE, SHORT, U_BYTE, U_SHORT, U_INT, U_LONG) -> {
                        val cVarType = primitiveKotlinToCVar(kotlinType) ?: error("Unsupported type: $kotlinType")
                        addStatement("val %N = %M<%T>()", varName, cinteropAlloc, cVarType)
                        addStatement("%N.%M = %N", varName, cinteropValue, kotlinName)
                        CodeBlock.ofStatement("%N.%M,", varName, cinteropPtr)
                    }

                    context.isEngineClass(arg.type) -> {
                        // Engine class (Object, etc.) needs void** (PtrToArg<Object*> semantics)
                        addStatement(
                            "val %N = %M<%T>()",
                            varName,
                            cinteropAlloc,
                            implPackageRegistry.classNameForOrDefault("GDExtensionObjectPtrVar"),
                        )
                        addStatement(
                            "%N.%M = %N%L.rawPtr",
                            varName,
                            cinteropValue,
                            kotlinName,
                            if (arg.isNullable) "?" else "",
                        )
                        CodeBlock.ofStatement("%N.%M,", varName, cinteropPtr)
                    }

                    else -> CodeBlock.ofStatement("%N.rawPtr,", kotlinName)
                }
            }

            val allocConstTypePtrArray = implPackageRegistry.memberNameForOrDefault("allocConstTypePtrArray")

            // Reference the pre-cached top-level lazy val instead of inline lookup
            // Invocation
            if (ptrExprs.isEmpty()) {
                addStatement(
                    "constructorFptr_%L.%M(rawPtr, %M())",
                    constructorIndex,
                    cinteropInvoke,
                    allocConstTypePtrArray,
                )
            } else {
                addStatement("constructorFptr_%L.%M(", constructorIndex, cinteropInvoke)
                withIndent {
                    addStatement("rawPtr,")
                    addStatement("%M(", allocConstTypePtrArray)
                    withIndent {
                        add(ptrExprs.joinToCode(""))
                    }
                    addStatement("),")
                }
                addStatement(")")
            }
        }.endControlFlow().build()

    /**
     * Simple variant for constructors whose args are all pre-built pointer expressions
     * (e.g. `rawPtr` of another builtin). No stack allocs needed.
     */
    private fun callBuiltinConstructorSimple(constructorIndex: Int, argExprs: String = ""): CodeBlock = CodeBlock
        .builder()
        .beginControlFlow("%M", memScoped)
        .addStatement("constructorFptr_%L.%M(", constructorIndex, cinteropInvoke)
        .indent()
        .addStatement("rawPtr,")
        .addStatement("%M(%L),", implPackageRegistry.memberNameForOrDefault("allocConstTypePtrArray"), argExprs)
        .unindent()
        .addStatement(")")
        .endControlFlow()
        .build()

    // ── Member getters / setters ──────────────────────────────────────────────

    /**
     * Generates a getter for a builtin member if the [meta] resolves to a primitive
     * that `StructMemory.kt` can handle.
     *
     * @param memberName the Godot member name (e.g., "x", "r", "position")
     * @param meta the meta type from [io.github.kingg22.godot.codegen.models.extensionapi.domain.ResolvedBuiltinLayout.memberMeta]
     * (e.g., "float", "int32")
     */
    context(_: Context)
    fun buildMemberGetter(memberName: String, meta: String, memberType: TypeName): FunSpec {
        val (funName, storageType) = metaToStorageInfo(meta, "get")
            ?: return buildMemberGetterViaOffset(memberName, meta, memberType)
        val offsetConst = "OFFSET_${memberName.uppercase()}"
        val memFun = implPackageRegistry.memberNameForOrDefault(funName)
        return FunSpec
            .getterBuilder().apply {
                if (storageType == memberType) {
                    addStatement("return %M(storage, %L)", memFun, offsetConst)
                } else {
                    val conv = storageToPropertyConv(storageType, memberType)
                        ?: error("Unsupported type to convert in getter: $storageType")
                    addStatement("return %M(storage, %L)%L", memFun, offsetConst, conv)
                }
            }.build()
    }

    /**
     * Generates a setter for a builtin member if the [meta] resolves to a primitive.
     * Returns null for compound members.
     */
    context(_: Context)
    fun buildMemberSetter(memberName: String, meta: String, memberType: TypeName): FunSpec {
        val (funName, storageType) = metaToStorageInfo(meta, "set")
            ?: return buildMemberSetterViaOffset(memberName, meta, memberType)
        val offsetConst = "OFFSET_${memberName.uppercase()}"
        val memFun = implPackageRegistry.memberNameForOrDefault(funName)
        return FunSpec
            .setterBuilder()
            .addParameter("value", memberType).apply {
                if (storageType == memberType) {
                    addStatement("%M(storage, %L, value)", memFun, offsetConst)
                } else {
                    val conv = propertyToStorageConv(memberType, storageType)
                        ?: error("Unsupported type to convert in setter: $memberType")
                    addStatement("%M(storage, %L, value%L)", memFun, offsetConst, conv)
                }
            }.build()
    }

    /**
     * Getter for a compound builtin member (meta = "Vector3", "Vector2", etc.) that has
     * a known byte offset in the layout. Copies `sizeBytes` bytes from storage+offset into
     * a freshly constructed instance via `getBuiltin`.
     */
    context(_: Context)
    private fun buildMemberGetterViaOffset(memberName: String, godotType: String, memberType: TypeName): FunSpec {
        val offsetConst = "OFFSET_${memberName.uppercase()}"
        val subSize = resolveSubtypeSize(godotType)
        return FunSpec
            .getterBuilder()
            .addStatement("val result = %T()", memberType)
            .addStatement(
                "%M(storage, %L, result.rawPtr, %L)",
                implPackageRegistry.memberNameForOrDefault("getBuiltin"),
                offsetConst,
                subSize,
            )
            .addStatement("return result")
            .build()
    }

    context(_: Context)
    private fun buildMemberSetterViaOffset(memberName: String, godotType: String, memberType: TypeName): FunSpec {
        val offsetConst = "OFFSET_${memberName.uppercase()}"
        val subSize = resolveSubtypeSize(godotType)
        return FunSpec
            .setterBuilder()
            .addParameter("value", memberType)
            .addStatement(
                "%M(storage, %L, value.rawPtr, %L)",
                implPackageRegistry.memberNameForOrDefault("setBuiltin"),
                offsetConst,
                subSize,
            )
            .build()
    }

    /**
     * Resolves the byte size of a compound member type from the builtin layout.
     */
    context(ctx: Context)
    private fun resolveSubtypeSize(godotType: String): Int {
        val layout = ctx.model.builtins
            .firstOrNull { it.name == godotType }
            ?.layout
            ?: error("Cannot resolve size for compound member type '$godotType'")
        return layout.size
    }

    context(_: Context)
    fun buildOperatorBody(operator: BuiltinClass.Operator) = methodImplGen.buildOperatorBody(operator)

    context(_: Context)
    fun buildEqualsOperatorBody(resolvedBuiltinClass: ResolvedBuiltinClass) =
        methodImplGen.buildEqualsOperatorBody(resolvedBuiltinClass)

    context(_: Context)
    fun buildCompareToBody(resolvedClass: ResolvedBuiltinClass) = methodImplGen.buildCompareToBody(resolvedClass)
}
