package io.github.kingg22.godot.codegen.extensionapi.impl.knative.impl

import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import io.github.kingg22.godot.codegen.extensionapi.Context
import io.github.kingg22.godot.codegen.extensionapi.TypeResolver
import io.github.kingg22.godot.codegen.impl.buildLazyBlock
import io.github.kingg22.godot.codegen.impl.renameGodotClass
import io.github.kingg22.godot.codegen.impl.screamingToPascalCase
import io.github.kingg22.godot.codegen.models.extensionapi.EnumConstant
import io.github.kingg22.godot.codegen.models.extensionapi.domain.ResolvedEnum
import io.github.kingg22.godot.codegen.types.*

/**
 * Native implementation generator for the `Variant` class.
 *
 * Generates a single final class — no subclasses, no inheritance hierarchy.
 * `Variant` is a plain opaque container that owns a Godot Variant allocation and exposes:
 *
 * - Conversion constructors (Boolean, Long, Int, Double, GodotString, all builtins, GodotObject)
 * - All `variant_*` operations from GDExtension as instance methods
 * - Typed extractors: `asXOrNull()` returning nullable, `asX()` throwing on type mismatch
 * - Top-level lazy fptr properties for `get_variant_from_type_constructor` and
 *   `get_variant_to_type_constructor` for every non-NIL/MAX type
 * - Top-level `asVariant()` extension functions for all concrete types
 *
 * ## Memory model
 *
 * ```kotlin
 * class Variant {
 *     private val storage: CPointer<ByteVar> =
 *          nativeHeap.alloc(size = <variantSize>, align = <pointerAlign>)
 *               .reinterpret<ByteVar>().ptr
 * }
 * ```
 *
 * The storage is zero-initialised by `nativeHeap.alloc`, so `Variant()` with no arguments
 * is already a valid NIL variant without any explicit `variant_new_nil` call.
 * Every other constructor writes into the same storage via the appropriate GDExtension path.
 */
class VariantImplGen(private val typeResolver: TypeResolver) {
    private lateinit var implPackageRegistry: ImplementationPackageRegistry

    fun initialize(implRegistry: ImplementationPackageRegistry) {
        implPackageRegistry = implRegistry
    }

    // ── Class skeleton ────────────────────────────────────────────────────────

    /**
     * Adds storage, rawPtr, closed guard, close(), and copy constructor to [classBuilder].
     * Also registers [K_AUTOCLOSEABLE] and GodotNative as superinterfaces.
     */
    context(ctx: Context)
    fun configureVariantClass(classBuilder: TypeSpec.Builder) {
        val variantSize = resolveVariantSize()
        val variantAlign = resolveVariantAlign()
        val variantBinding = implPackageRegistry.classNameForOrDefault("VariantBinding")

        // private val storage: CPointer<ByteVar>
        val storageProp = PropertySpec
            .builder("storage", C_POINTER.parameterizedBy(BYTE_VAR), KModifier.PRIVATE)
            .initializer(
                CodeBlock
                    .builder()
                    .addStatement("%T.alloc(size = %L, align = %L)", cinteropNativeHeap, variantSize, variantAlign)
                    .withIndent {
                        addStatement(".%M<%T>().%M", cinteropReinterpret, BYTE_VAR, cinteropPtr)
                    }
                    .build(),
            )
            .build()
        classBuilder.addProperty(storageProp)

        // private var closed: Boolean = false
        val closedProp = PropertySpec
            .builder("closed", BOOLEAN, KModifier.PRIVATE)
            .mutable(true)
            .initializer("false")
            .build()
        classBuilder.addProperty(closedProp)

        // GodotNative + AutoCloseable
        classBuilder
            .addSuperinterface(ctx.classNameForOrDefault("GodotNative"))
            .addSuperinterface(K_AUTOCLOSEABLE)

        // override val rawPtr: COpaquePointer get() = storage
        classBuilder.addProperty(
            PropertySpec
                .builder("rawPtr", COPAQUE_POINTER, KModifier.OVERRIDE)
                .getter(FunSpec.getterBuilder().addStatement("return %N", storageProp).build())
                .build(),
        )

        // override fun close()
        classBuilder.addFunction(
            FunSpec
                .builder("close")
                .addModifiers(KModifier.OVERRIDE)
                .addCode(
                    CodeBlock
                        .builder()
                        .beginControlFlow("if (!%N)", closedProp)
                        .addStatement("%T.destroyRaw(rawPtr)", variantBinding)
                        .addStatement("%T.free(%N.rawValue)", cinteropNativeHeap, "storage")
                        .addStatement("%N = true", closedProp)
                        .endControlFlow()
                        .build(),
                )
                .build(),
        )

        // constructor(from: COpaquePointer) — copy from existing Godot pointer
        classBuilder.addFunction(
            FunSpec
                .constructorBuilder()
                .addParameter(
                    ParameterSpec
                        .builder("from", COPAQUE_POINTER)
                        .addKdoc("Copies a Godot-owned variant pointer into this instance.")
                        .build(),
                )
                .callThisConstructor()
                .addStatement("%T.newCopyRaw(rawPtr, from)", variantBinding)
                .build(),
        )
    }

    // ── Conversion constructors ───────────────────────────────────────────────

    /**
     * Builds all typed conversion constructors and adds them to [classBuilder].
     *
     * | Constructor arg      | GDExtension path                              |
     * |----------------------|-----------------------------------------------|
     * | `Boolean`            | `allocGdBool` → `fromTypeFptr_BOOL(rawPtr, …)` |
     * | `Long`               | `LongVar`    → `fromTypeFptr_INT(rawPtr, …)`  |
     * | `Int`                | delegates to `Long` constructor               |
     * | `Double`             | `DoubleVar`  → `fromTypeFptr_FLOAT(rawPtr, …)` |
     * | builtin / GodotObject| `fromTypeFptr_TYPE(rawPtr, value.rawPtr)`     |
     *
     * NIL is covered by the zero-arg primary constructor (zero-initialised storage = NIL).
     */
    context(ctx: Context)
    fun buildConversionConstructors(classBuilder: TypeSpec.Builder, variantTypes: ResolvedEnum) {
        // Boolean
        classBuilder.addFunction(
            FunSpec
                .constructorBuilder()
                .addParameter("value", BOOLEAN)
                .callThisConstructor()
                .addCode(
                    CodeBlock
                        .builder()
                        .beginControlFlow("%M", memScoped)
                        .addStatement(
                            "val boolVar = %M(value)",
                            implPackageRegistry.memberNameForOrDefault("allocGdBool"),
                        )
                        .addStatement("fromTypeFptr_BOOL.%M(rawPtr, boolVar)", cinteropInvoke)
                        .endControlFlow()
                        .build(),
                )
                .build(),
        )

        // Long
        classBuilder.addFunction(
            FunSpec
                .constructorBuilder()
                .addParameter("value", LONG)
                .callThisConstructor()
                .addCode(
                    CodeBlock
                        .builder()
                        .beginControlFlow("%M", memScoped)
                        .addStatement("val intVar = %M<%T>()", cinteropAlloc, LONG_VAR)
                        .addStatement("intVar.%M = value", cinteropValue)
                        .addStatement("fromTypeFptr_INT.%M(rawPtr, intVar.%M)", cinteropInvoke, cinteropPtr)
                        .endControlFlow()
                        .build(),
                )
                .build(),
        )

        // Int — synthetic convenience, delegates to Long constructor
        classBuilder.addFunction(
            FunSpec
                .constructorBuilder()
                .addParameter("value", INT)
                .callThisConstructor("value.toLong()")
                .addKdoc("Convenience constructor; delegates to the [Long] constructor.")
                .build(),
        )

        // Double
        classBuilder.addFunction(
            FunSpec
                .constructorBuilder()
                .addParameter("value", DOUBLE)
                .callThisConstructor()
                .addCode(
                    CodeBlock
                        .builder()
                        .beginControlFlow("%M", memScoped)
                        .addStatement("val floatVar = %M<%T>()", cinteropAlloc, DOUBLE_VAR)
                        .addStatement("floatVar.%M = value", cinteropValue)
                        .addStatement("fromTypeFptr_FLOAT.%M(rawPtr, floatVar.%M)", cinteropInvoke, cinteropPtr)
                        .endControlFlow()
                        .build(),
                )
                .build(),
        )

        // Float — synthetic convenience, delegates to Double constructor
        classBuilder.addFunction(
            FunSpec
                .constructorBuilder()
                .addParameter("value", FLOAT)
                .callThisConstructor("value.toDouble()")
                .addKdoc("Convenience constructor; delegates to the [Double] constructor.")
                .build(),
        )

        // Special constructor of Object, requires ptr to ptr
        classBuilder.addFunction(
            FunSpec
                .constructorBuilder()
                .addParameter("value", ctx.classNameForOrDefault("Object"))
                .callThisConstructor()
                .addCode(
                    CodeBlock
                        .builder()
                        .beginControlFlow("%M", memScoped)
                        .addStatement(
                            "val objectVar = %M<%T>()",
                            cinteropAlloc,
                            implPackageRegistry.classNameForOrDefault("GDExtensionObjectPtrVar"),
                        )
                        .addStatement("objectVar.%M = value.rawPtr", cinteropValue)
                        .addStatement("fromTypeFptr_%L.%M(rawPtr, objectVar.%M)", "OBJECT", cinteropInvoke, cinteropPtr)
                        .endControlFlow()
                        .build(),
                )
                .build(),
        )

        // All builtin/object types — value.rawPtr path
        variantTypes.raw.values.forEach { enumValue ->
            val (valueType, subclassName) = resolveTypeNameAndName(enumValue) ?: return@forEach

            classBuilder.addFunction(
                FunSpec
                    .constructorBuilder()
                    .addParameter("value", valueType)
                    .callThisConstructor()
                    .addStatement("fromTypeFptr_%L.%M(rawPtr, value.rawPtr)", subclassName, cinteropInvoke)
                    .build(),
            )
        }
    }

    // ── Variant operations (instance methods) ─────────────────────────────────

    /**
     * Adds all `variant_*` GDExtension operations as instance methods on [classBuilder].
     *
     * Includes: getType, isNil, evaluate, set/get family, hasMethod/hasKey/hasMember,
     * iter family, call/callStatic, booleanize, stringify, hashVariant, duplicate,
     * canConvert, canConvertStrict.
     */
    context(context: Context)
    fun buildVariantOperations(classBuilder: TypeSpec.Builder, variantClassName: ClassName) {
        val variantBinding = implPackageRegistry.classNameForOrDefault("VariantBinding")
        val checkCallError = implPackageRegistry.memberNameForOrDefault("checkCallError")
        val stringNameClass = context.classNameForOrDefault("StringName")
        val godotStringClass = context.classNameForOrDefault("String", "GodotString")
        val variantTypeClass = variantClassName.nestedClass("Type")
        val variantOperatorClass = variantClassName.nestedClass("Operator")

        // ── getType() ─────────────────────────────────────────────────────────
        val getTypeFun = FunSpec
            .builder("getType")
            .returns(variantTypeClass)
            .addCode(
                CodeBlock
                    .builder()
                    .addStatement("val raw = %T.getTypeRaw(rawPtr)", variantBinding)
                    .addStatement(
                        "return %T.entries.firstOrNull { it.value.toUInt() == raw.value } ?: %T.NIL",
                        variantTypeClass,
                        variantTypeClass,
                    )
                    .build(),
            )
            .build()
        classBuilder.addFunction(getTypeFun)

        // ── isNil() ───────────────────────────────────────────────────────────
        classBuilder.addFunction(
            FunSpec
                .builder("isNil")
                .returns(BOOLEAN)
                .addStatement("return %N() == %T.NIL", getTypeFun, variantTypeClass)
                .build(),
        )

        // ── evaluate() ────────────────────────────────────────────────────────
        classBuilder.addFunction(
            FunSpec
                .builder("evaluate")
                .returns(variantClassName.copy(nullable = true))
                .addParameter("rhs", variantClassName)
                .addParameter("op", variantOperatorClass)
                .addKdoc("Evaluate an operator on two Variants\n\n")
                .addKdoc("@param rhs The right-hand side of the operator. The second Variant\n")
                .addKdoc("@param op The operator to evaluate. See [Variant.Operator].\n")
                .addKdoc("@return The result of the operator, or null if the operation is invalid.")
                .addCode(
                    CodeBlock
                        .builder()
                        .addStatement("val result = %T()", variantClassName)
                        .addStatement("val status = %T", variantBinding)
                        .indent()
                        .addStatement(
                            ".evaluate(op.%M(), rawPtr, rhs.rawPtr, result.rawPtr)",
                            implPackageRegistry.memberNameForOrDefault("toGDE"),
                        )
                        .unindent()
                        .beginControlFlow("return if (status.isOk)")
                        .addStatement("result")
                        .nextControlFlow("else")
                        .addStatement("result.close()")
                        .addStatement("null")
                        .endControlFlow()
                        .build(),
                )
                .build(),
        )

        // ── set(key, value) ───────────────────────────────────────────────────
        classBuilder.addFunction(
            FunSpec
                .builder("set")
                .returns(BOOLEAN)
                .addModifiers(KModifier.OPERATOR)
                .addParameter("key", variantClassName)
                .addParameter("value", variantClassName)
                .addKdoc("Sets [key] to [value]. Returns false if the operation is invalid.")
                .addCode(
                    CodeBlock
                        .builder()
                        .addStatement(
                            "return %T",
                            variantBinding,
                        )
                        .withIndent {
                            addStatement(".set(rawPtr, key.rawPtr, value.rawPtr)")
                            addStatement(".isOk")
                        }.build(),
                )
                .build(),
        )

        // ── get(key) ──────────────────────────────────────────────────────────
        classBuilder.addFunction(
            FunSpec
                .builder("get")
                .returns(variantClassName.copy(nullable = true))
                .addModifiers(KModifier.OPERATOR)
                .addParameter("key", variantClassName)
                .addKdoc("Gets the value at [key]. Returns null if the operation is invalid.")
                .addCode(
                    CodeBlock
                        .builder()
                        .addStatement("val result = %T()", variantClassName)
                        .addStatement(
                            "val status = %T.get(rawPtr, key.rawPtr, result.rawPtr)",
                            variantBinding,
                        )
                        .beginControlFlow("return if (status.isOk)")
                        .addStatement("result")
                        .nextControlFlow("else")
                        .addStatement("result.close()")
                        .addStatement("null")
                        .endControlFlow()
                        .build(),
                )
                .build(),
        )

        // ── setNamed(name, value) ─────────────────────────────────────────────
        classBuilder.addFunction(
            FunSpec
                .builder("setNamed")
                .returns(BOOLEAN)
                .addParameter("name", stringNameClass)
                .addParameter("value", variantClassName)
                .addKdoc("Sets named member [name] to [value]. Returns false if invalid.")
                .addCode(
                    CodeBlock
                        .builder()
                        .addStatement("return %T", variantBinding)
                        .withIndent {
                            addStatement(".setNamed(rawPtr, name.rawPtr, value.rawPtr)")
                            addStatement(".isOk")
                        }.build(),
                )
                .build(),
        )

        // ── getNamed(name) ────────────────────────────────────────────────────
        classBuilder.addFunction(
            FunSpec
                .builder("getNamed")
                .returns(variantClassName.copy(nullable = true))
                .addParameter("name", stringNameClass)
                .addKdoc("Gets named member [name]. Returns null if invalid.")
                .addCode(
                    CodeBlock
                        .builder()
                        .addStatement("val result = %T()", variantClassName)
                        .addStatement(
                            "val status = %T.getNamed(rawPtr, name.rawPtr, result.rawPtr)",
                            variantBinding,
                        )
                        .beginControlFlow("return if (status.isOk)")
                        .addStatement("result")
                        .nextControlFlow("else")
                        .addStatement("result.close()")
                        .addStatement("null")
                        .endControlFlow()
                        .build(),
                )
                .build(),
        )

        // ── setKeyed(key, value) ──────────────────────────────────────────────
        classBuilder.addFunction(
            FunSpec
                .builder("setKeyed")
                .returns(BOOLEAN)
                .addParameter("key", variantClassName)
                .addParameter("value", variantClassName)
                .addKdoc("Sets keyed property [key] to [value]. Returns false if invalid.")
                .addCode(
                    CodeBlock
                        .builder()
                        .addStatement("return %T", variantBinding)
                        .withIndent {
                            addStatement(".setKeyed(rawPtr, key.rawPtr, value.rawPtr)")
                            addStatement(".isOk")
                        }.build(),
                )
                .build(),
        )

        // ── getKeyed(key) ─────────────────────────────────────────────────────
        classBuilder.addFunction(
            FunSpec
                .builder("getKeyed")
                .returns(variantClassName.copy(nullable = true))
                .addParameter("key", variantClassName)
                .addKdoc("Gets keyed property [key]. Returns null if invalid.")
                .addCode(
                    CodeBlock
                        .builder()
                        .addStatement("val result = %T()", variantClassName)
                        .addStatement(
                            "val status = %T.getKeyed(rawPtr, key.rawPtr, result.rawPtr)",
                            variantBinding,
                        )
                        .beginControlFlow("return if (status.isOk)")
                        .addStatement("result")
                        .nextControlFlow("else")
                        .addStatement("result.close()")
                        .addStatement("null")
                        .endControlFlow()
                        .build(),
                )
                .build(),
        )

        // ── setIndexed(index, value) ──────────────────────────────────────────
        classBuilder.addFunction(
            FunSpec
                .builder("setIndexed")
                .returns(BOOLEAN)
                .addParameter("index", LONG)
                .addParameter("value", variantClassName)
                .addKdoc("Sets element at [index]. Returns false if invalid or out of bounds.")
                .addCode(
                    CodeBlock
                        .builder()
                        .addStatement("return %T", variantBinding)
                        .withIndent {
                            addStatement(".setIndexed(rawPtr, index, value.rawPtr)")
                            addStatement(".isOk")
                        }
                        .build(),
                )
                .build(),
        )

        // ── getIndexed(index) ─────────────────────────────────────────────────
        classBuilder.addFunction(
            FunSpec
                .builder("getIndexed")
                .returns(variantClassName.copy(nullable = true))
                .addParameter("index", LONG)
                .addKdoc("Gets element at [index]. Returns null if invalid or out of bounds.")
                .addCode(
                    CodeBlock
                        .builder()
                        .addStatement("val result = %T()", variantClassName)
                        .addStatement(
                            "val status = %T.getIndexed(rawPtr, index, result.rawPtr)",
                            variantBinding,
                        )
                        .beginControlFlow("return if (status.isOk)")
                        .addStatement("result")
                        .nextControlFlow("else")
                        .addStatement("result.close()")
                        .addStatement("null")
                        .endControlFlow()
                        .build(),
                )
                .build(),
        )

        // ── hasMethod(method) ─────────────────────────────────────────────────
        classBuilder.addFunction(
            FunSpec
                .builder("hasMethod")
                .returns(BOOLEAN)
                .addParameter("method", stringNameClass)
                .addStatement("return %T.hasMethod(rawPtr, method.rawPtr)", variantBinding)
                .build(),
        )

        // ── hasKey(key) ───────────────────────────────────────────────────────
        classBuilder.addFunction(
            FunSpec
                .builder("hasKey")
                .returns(BOOLEAN)
                .addParameter("key", variantClassName)
                .addKdoc("Returns true if this variant has the given key and the check itself is valid.")
                .addCode("return %T.hasKey(rawPtr, key.rawPtr).isOk", variantBinding)
                .build(),
        )

        // ── hasMember(type, member) — static-style ────────────────────────────
        // Exposed as a companion fun; added later in buildCompanionMembers.
        // Here we just note it for reference — companion is built in the generator.

        // ── iterInit(iter) ────────────────────────────────────────────────────
        classBuilder.addFunction(
            FunSpec
                .builder("iterInit")
                .returns(variantClassName.copy(nullable = true))
                .addKdoc(
                    "Initialises an iterator. Returns the iterator Variant on success, null if not iterable.",
                )
                .addCode(
                    CodeBlock
                        .builder()
                        .addStatement("val iter = %T()", variantClassName)
                        .addStatement(
                            "val result = %T.iterInit(rawPtr, iter.rawPtr)",
                            variantBinding,
                        )
                        .beginControlFlow("return if (result.isOk)")
                        .addStatement("iter")
                        .nextControlFlow("else")
                        .addStatement("iter.close()")
                        .addStatement("null")
                        .endControlFlow()
                        .build(),
                )
                .build(),
        )

        // ── iterNext(iter) ────────────────────────────────────────────────────
        classBuilder.addFunction(
            FunSpec
                .builder("iterNext")
                .returns(BOOLEAN)
                .addParameter("iter", variantClassName)
                .addKdoc("Advances the iterator. Returns true if there is a next element.")
                .addCode("return %T.iterNext(rawPtr, iter.rawPtr).isOk", variantBinding)
                .build(),
        )

        // ── iterGet(iter) ─────────────────────────────────────────────────────
        classBuilder.addFunction(
            FunSpec
                .builder("iterGet")
                .returns(variantClassName.copy(nullable = true))
                .addParameter("iter", variantClassName)
                .addKdoc("Gets the current iterator value. Returns null if invalid.")
                .addCode(
                    CodeBlock
                        .builder()
                        .addStatement("val result = %T()", variantClassName)
                        .addStatement(
                            "val status = %T.iterGet(rawPtr, iter.rawPtr, result.rawPtr)",
                            variantBinding,
                        )
                        .beginControlFlow("return if (status.valid == true)")
                        .addStatement("result")
                        .nextControlFlow("else")
                        .addStatement("result.close()")
                        .addStatement("null")
                        .endControlFlow()
                        .build(),
                )
                .build(),
        )

        // ── call(method, vararg args) ──────────────────────────────────────────
        classBuilder.addFunction(
            FunSpec
                .builder("call")
                .returns(variantClassName)
                .addParameter("method", stringNameClass)
                .addParameter("args", variantClassName, KModifier.VARARG)
                .addCode(
                    CodeBlock
                        .builder()
                        .addStatement("val result = %T()", variantClassName)
                        .addStatement("val errorInfo = %T.call(", variantBinding)
                        .indent()
                        .addStatement("rawPtr,")
                        .addStatement("method.rawPtr,")
                        .addStatement("*args.map { it.rawPtr }.toTypedArray(),")
                        .addStatement("rReturn = result.rawPtr,")
                        .unindent()
                        .addStatement(")")
                        .addStatement($$"""%M("Variant.call: $method", errorInfo)""", checkCallError)
                        .addStatement("return result")
                        .build(),
                )
                .build(),
        )

        // ── callStatic(type, method, vararg args) ──────────────────────────────
        classBuilder.addFunction(
            FunSpec
                .builder("callStatic")
                .returns(variantClassName)
                .addParameter("type", variantTypeClass)
                .addParameter("method", stringNameClass)
                .addParameter("args", variantClassName, KModifier.VARARG)
                .addCode(
                    CodeBlock
                        .builder()
                        .addStatement("val result = %T()", variantClassName)
                        .addStatement("val errorInfo = %T.callStatic(", variantBinding)
                        .indent()
                        .addStatement(
                            "type.%M(),",
                            implPackageRegistry.memberNameForOrDefault("toGDE"),
                        )
                        .addStatement("method.rawPtr,")
                        .addStatement("*args.map { it.rawPtr }.toTypedArray(),")
                        .addStatement("rReturn = result.rawPtr,")
                        .unindent()
                        .addStatement(")")
                        .addStatement($$"""%M("Variant.callStatic: $type.$method", errorInfo)""", checkCallError)
                        .addStatement("return result")
                        .build(),
                )
                .build(),
        )

        // ── booleanize() ──────────────────────────────────────────────────────
        classBuilder.addFunction(
            FunSpec
                .builder("booleanize")
                .returns(BOOLEAN)
                .addStatement("return %T.booleanize(rawPtr)", variantBinding)
                .build(),
        )

        // ── stringify() ───────────────────────────────────────────────────────
        classBuilder.addFunction(
            FunSpec
                .builder("stringify")
                .returns(godotStringClass)
                .addCode(
                    CodeBlock
                        .builder()
                        .addStatement("val result = %T()", godotStringClass)
                        .addStatement("%T.stringifyRaw(rawPtr, result.rawPtr)", variantBinding)
                        .addStatement("return result")
                        .build(),
                )
                .build(),
        )

        // ── toString() ───────────────────────────────────────────────────────
        classBuilder.addFunction(
            FunSpec
                .builder("toString")
                .addModifiers(KModifier.OVERRIDE)
                .returns(STRING)
                .addCode("return this.stringify().toKString()")
                .build(),
        )

        // ── hashVariant() ─────────────────────────────────────────────────────
        classBuilder.addFunction(
            FunSpec
                .builder("hash")
                .returns(LONG)
                .addKdoc("Gets the hash of a Variant\n")
                .addStatement("return %T.hashRaw(rawPtr)", variantBinding)
                .build(),
        )

        classBuilder.addFunction(
            FunSpec
                .builder("hashCode")
                .addModifiers(KModifier.OVERRIDE)
                .returns(INT)
                .addCode("return this.hash().toInt()")
                .build(),
        )

        classBuilder.addFunction(
            FunSpec
                .builder("equals")
                .addModifiers(KModifier.OVERRIDE)
                .addParameter("other", ANY.copy(nullable = true))
                .returns(BOOLEAN)
                .addStatement("// Fast paths")
                .addStatement("if (other === this) return true")
                .addStatement("if (other == null) return false")
                .addStatement(
                    "val rh = %M(other) ?: return false",
                    implPackageRegistry.memberNameForOrDefault("anyToVariantOrNull"),
                )
                .addStatement("val result = this.evaluate(rh, %T.EQUAL) ?: return false", variantOperatorClass)
                .addStatement("return result.toBoolOrNull() ?: false")
                .build(),
        )

        // ── recursiveHash() ─────────────────────────────────────────────────────
        classBuilder.addFunction(
            FunSpec
                .builder("recursiveHash")
                .returns(LONG)
                .addKdoc("Gets the recursive hash of a Variant")
                .addParameter(
                    ParameterSpec
                        .builder("depth", LONG)
                        .defaultValue("0")
                        .addKdoc("The number of recursive loops so far.")
                        .build(),
                )
                .addStatement("return %T.recursiveHashRaw(rawPtr, depth)", variantBinding)
                .build(),
        )

        // ── compareHash() ─────────────────────────────────────────────────────
        classBuilder.addFunction(
            FunSpec
                .builder("compareHash")
                .returns(BOOLEAN)
                .addKdoc("Compares two Variants by their hash")
                .addParameter("other", variantClassName)
                .addStatement("return %T.hashCompare(rawPtr, other.rawPtr)", variantBinding)
                .build(),
        )

        // ── duplicate(deep) ───────────────────────────────────────────────────
        classBuilder.addFunction(
            FunSpec
                .builder("duplicate")
                .returns(variantClassName)
                .addParameter(
                    ParameterSpec
                        .builder("deep", BOOLEAN)
                        .defaultValue("false")
                        .addKdoc("If `true`, nested containers are also duplicated.")
                        .build(),
                )
                .addCode(
                    CodeBlock
                        .builder()
                        .addStatement("val result = %T()", variantClassName)
                        .addStatement("%T.duplicate(rawPtr, result.rawPtr, deep)", variantBinding)
                        .addStatement("return result")
                        .build(),
                )
                .build(),
        )

        // ── getObjectInstanceId() ─────────────────────────────────────────────────────
        classBuilder.addFunction(
            FunSpec
                .builder("getObjectInstanceId")
                .returns(U_LONG)
                .addKdoc("Gets the object instance ID from a variant of type [Variant.Type.OBJECT]")
                .addKdoc("\n\n")
                .addKdoc("If the variant isn't of type [Variant.Type.OBJECT], then zero will be returned")
                .addKdoc("\n\n")
                .addKdoc("The instance ID will be returned even if the object is no longer valid -\n")
                .addKdoc("use [GodotObject.getInstanceId] to check if the object is still valid.")
                .addStatement("return %T.getObjectInstanceIdRaw(rawPtr)", variantBinding)
                .build(),
        )
    }

    // ── Typed extractors ──────────────────────────────────────────────────────

    /**
     * Adds `asXOrNull()` and `asX()` extractor methods for every concrete Variant type.
     *
     * `asXOrNull()` — returns null when the stored type doesn't match.
     * `asX()`       — returns the value or throws [IllegalStateException].
     *
     * The actual extraction uses `toTypeFptr_<TYPE>` (from `get_variant_to_type_constructor`).
     *
     * Primitive types (BOOL, INT, FLOAT) use their respective cinterop alloc pattern.
     * All other types allocate a fresh Kotlin wrapper and invoke the to-type fptr.
     */
    context(ctx: Context)
    fun buildExtractors(classBuilder: TypeSpec.Builder, variantClassName: ClassName, variantTypes: ResolvedEnum) {
        val variantTypeClass = variantClassName.nestedClass("Type")

        variantTypes.raw.values.forEach { enumValue ->
            val subclassName = enumValue.name.removePrefix("TYPE_")
                .takeUnless { it == "MAX" || it == "NIL" } ?: return@forEach

            val (orNullFun, forcedFun) = when (subclassName) {
                "BOOL" -> buildPrimitiveExtractor(
                    subclassName,
                    variantTypeClass,
                    BOOLEAN,
                    CodeBlock
                        .builder()
                        .beginControlFlow("%M", memScoped)
                        .addStatement("val out = %M()", implPackageRegistry.memberNameForOrDefault("allocGdBool"))
                        .addStatement("toTypeFptr_BOOL.%M(out, rawPtr)", cinteropInvoke)
                        .addStatement(
                            "return out.%M()",
                            implPackageRegistry.memberNameForOrDefault("readGdBool"),
                        )
                        .endControlFlow()
                        .build(),
                )

                "INT" -> buildPrimitiveExtractor(
                    subclassName,
                    variantTypeClass,
                    LONG,
                    CodeBlock
                        .builder()
                        .beginControlFlow("%M", memScoped)
                        .addStatement("val out = %M<%T>()", cinteropAlloc, LONG_VAR)
                        .addStatement("toTypeFptr_INT.%M(out.%M, rawPtr)", cinteropInvoke, cinteropPtr)
                        .addStatement("return out.%M", cinteropValue)
                        .endControlFlow()
                        .build(),
                )

                "FLOAT" -> buildPrimitiveExtractor(
                    subclassName,
                    variantTypeClass,
                    DOUBLE,
                    CodeBlock
                        .builder()
                        .beginControlFlow("%M", memScoped)
                        .addStatement("val out = %M<%T>()", cinteropAlloc, DOUBLE_VAR)
                        .addStatement("toTypeFptr_FLOAT.%M(out.%M, rawPtr)", cinteropInvoke, cinteropPtr)
                        .addStatement("return out.%M", cinteropValue)
                        .endControlFlow()
                        .build(),
                )

                "OBJECT" -> {
                    val returnType = ctx.classNameForOrDefault("Object")
                    buildPrimitiveExtractor(
                        subclassName,
                        variantTypeClass,
                        returnType,
                        CodeBlock
                            .builder()
                            .beginControlFlow("%M", memScoped)
                            .addStatement("val out = %M<%T>()", cinteropAlloc, C_OPAQUE_POINTER_VAR)
                            .addStatement("toTypeFptr_OBJECT.%M(out.%M, rawPtr)", cinteropInvoke, cinteropPtr)
                            .addStatement(
                                "return %T(%M(out.%M)·{·%S·})",
                                returnType,
                                K_CHECK_NOT_NULL,
                                cinteropValue,
                                "Return pointer value of Object was null",
                            )
                            .endControlFlow()
                            .build(),
                    )
                }

                else -> {
                    val godotTypeName = subclassName.screamingToPascalCase().renameGodotClass()
                    val valueType = typeResolver.resolve(godotTypeName)
                    buildWrapperExtractor(subclassName, variantTypeClass, valueType)
                }
            }

            classBuilder.addFunction(orNullFun)
            classBuilder.addFunction(forcedFun)

            if (subclassName.equals("STRING", ignoreCase = true)) {
                classBuilder.addFunction(
                    orNullFun
                        .toBuilder(name = "toKStringOrNull")
                        .clearBody()
                        .returns(STRING.copy(nullable = true))
                        .addCode("return this.toGodotStringOrNull()?.toKString()")
                        .build(),
                )
                classBuilder.addFunction(
                    forcedFun
                        .toBuilder("toKString")
                        .clearBody()
                        .returns(STRING)
                        .addCode("return this.toGodotString().toKString()")
                        .build(),
                )
            }
        }
    }

    /**
     * Builds `asBoolOrNull/asBool`, `asLongOrNull/asLong`, `asDoubleOrNull/asDouble` —
     * primitive extractors that use stack-allocated cinterop variables.
     */
    private fun buildPrimitiveExtractor(
        subclassName: String,
        variantTypeClass: ClassName,
        returnType: TypeName,
        extractBody: CodeBlock,
    ): Pair<FunSpec, FunSpec> {
        val pascalName = subclassName.screamingToPascalCase()
            .replaceFirstChar { it.uppercase() }
        val orNullName = "to${pascalName}OrNull"
        val forcedName = "to$pascalName"

        val orNullFun = FunSpec
            .builder(orNullName)
            .returns(returnType.copy(nullable = true))
            .addKdoc("Returns the [%T] value or null if this Variant is not %L.", returnType, subclassName)
            .addCode(
                CodeBlock
                    .builder()
                    .addStatement("if (getType() != %T.%L) return null", variantTypeClass, subclassName)
                    .add(extractBody)
                    .build(),
            )
            .build()

        val forcedFun = FunSpec
            .builder(forcedName)
            .returns(returnType)
            .addKdoc(
                "Returns the [%T] value or throws [IllegalStateException] if this Variant is not %L.",
                returnType,
                subclassName,
            )
            .beginControlFlow(
                "return %M(%N())",
                K_CHECK_NOT_NULL,
                orNullName,
            )
            .addStatement("%P", $$"Variant type mismatch: expected $$subclassName but was ${getType()}")
            .endControlFlow()
            .build()

        return orNullFun to forcedFun
    }

    /**
     * Builds `asXOrNull/asX` for builtin wrapper types (GodotString, Vector2, Color, etc.).
     * The extraction allocates a new Kotlin wrapper and calls the to-type fptr into it.
     */
    private fun buildWrapperExtractor(
        subclassName: String,
        variantTypeClass: ClassName,
        valueType: TypeName,
    ): Pair<FunSpec, FunSpec> {
        val pascalName = subclassName.screamingToPascalCase().takeUnless { it == "String" } ?: "GodotString"
        val orNullName = "to${pascalName}OrNull"
        val forcedName = "to$pascalName"

        val orNullFun = FunSpec
            .builder(orNullName)
            .returns(valueType.copy(nullable = true))
            .addKdoc(
                "Returns the [%T] value or null if this Variant is not %L.",
                valueType,
                subclassName,
            )
            .addCode(
                CodeBlock
                    .builder()
                    .addStatement("if (getType() != %T.%L) return null", variantTypeClass, subclassName)
                    .addStatement("val result = %T()", valueType)
                    .addStatement("toTypeFptr_%L.%M(result.rawPtr, rawPtr)", subclassName, cinteropInvoke)
                    .addStatement("return result")
                    .build(),
            )
            .build()

        val forcedFun = FunSpec
            .builder(forcedName)
            .returns(valueType)
            .addKdoc(
                "Returns the [%T] value or throws [IllegalStateException] if this Variant is not %L.",
                valueType,
                subclassName,
            )
            .beginControlFlow(
                "return %M(%N())",
                K_CHECK_NOT_NULL,
                orNullName,
            )
            .addStatement("%P", $$"Variant type mismatch: expected $$subclassName but was ${getType()}")
            .endControlFlow()
            .build()

        return orNullFun to forcedFun
    }

    // ── Companion object ──────────────────────────────────────────────────────

    /**
     * Builds a companion object containing static-style helpers:
     * - `hasMember(type, member): Boolean`
     * - `canConvert(from, to): Boolean`
     * - `canConvertStrict(from, to): Boolean`
     */
    context(ctx: Context)
    fun buildCompanion(classBuilder: TypeSpec.Builder, variantClassName: ClassName) {
        val variantBinding = implPackageRegistry.classNameForOrDefault("VariantBinding")
        val stringNameClass = ctx.classNameForOrDefault("StringName")
        val variantTypeClass = variantClassName.nestedClass("Type")

        val companion = TypeSpec.companionObjectBuilder()

        companion.addFunction(
            FunSpec
                .builder("hasMember")
                .returns(BOOLEAN)
                .addParameter("type", variantTypeClass)
                .addParameter("member", stringNameClass)
                .addStatement(
                    "return %T♢.hasMember(type.%M(), member.rawPtr)",
                    variantBinding,
                    implPackageRegistry.memberNameForOrDefault("toGDE"),
                )
                .build(),
        )

        companion.addFunction(
            FunSpec
                .builder("canConvert")
                .returns(BOOLEAN)
                .addParameter("from", variantTypeClass)
                .addParameter("to", variantTypeClass)
                .addStatement(
                    "return %T♢.canConvert(from.%M(), to.%M())",
                    variantBinding,
                    implPackageRegistry.memberNameForOrDefault("toGDE"),
                    implPackageRegistry.memberNameForOrDefault("toGDE"),
                )
                .build(),
        )

        companion.addFunction(
            FunSpec
                .builder("canConvertStrict")
                .returns(BOOLEAN)
                .addParameter("from", variantTypeClass)
                .addParameter("to", variantTypeClass)
                .addStatement(
                    "return %T♢.canConvertStrict(from.%M(), to.%M())",
                    variantBinding,
                    implPackageRegistry.memberNameForOrDefault("toGDE"),
                    implPackageRegistry.memberNameForOrDefault("toGDE"),
                )
                .build(),
        )

        classBuilder.addType(companion.build())
    }

    // ── Top-level fptr properties ─────────────────────────────────────────────

    /**
     * Emits one `private val fromTypeFptr_X` and one `private val toTypeFptr_X` for every
     * non-NIL/MAX Variant type.
     *
     * `fromTypeFptr_X` — `GDExtensionVariantFromTypeConstructorFunc` (type → Variant)
     * `toTypeFptr_X`   — `GDExtensionTypeFromVariantConstructorFunc` (Variant → type)
     */
    fun buildTopLevelFptrProperties(variantTypes: ResolvedEnum): List<PropertySpec> {
        val fromType = implPackageRegistry.classNameForOrDefault("GDExtensionVariantFromTypeConstructorFunc")
        val toType = implPackageRegistry.classNameForOrDefault("GDExtensionTypeFromVariantConstructorFunc")
        val getBinding = implPackageRegistry.classNameForOrDefault("GetBinding")

        return variantTypes.raw.values.flatMap { enumValue ->
            val subclassName = enumValue.name.removePrefix("TYPE_")
                .takeUnless { it == "MAX" || it == "NIL" } ?: return@flatMap emptyList()

            val gdxType = "GDEXTENSION_VARIANT_TYPE_$subclassName"

            val fromProp = PropertySpec
                .builder("fromTypeFptr_$subclassName", fromType, KModifier.PRIVATE)
                .delegate(
                    buildLazyBlock {
                        addStatement("%T.variantFromTypeConstructorRaw(%L)", getBinding, gdxType)
                        withIndent {
                            addStatement("?: error(%S)", "Missing variant-from-type constructor for '$subclassName'")
                        }
                    },
                )
                .build()

            val toProp = PropertySpec
                .builder("toTypeFptr_$subclassName", toType, KModifier.PRIVATE)
                .delegate(
                    buildLazyBlock {
                        addStatement("%T.variantToTypeConstructorRaw(%L)", getBinding, gdxType)
                        withIndent {
                            addStatement("?: error(%S)", "Missing variant-to-type constructor for '$subclassName'")
                        }
                    },
                )
                .build()

            listOf(fromProp, toProp)
        }
    }

    // ── asVariant() top-level extensions ──────────────────────────────────────

    /**
     * Emits `fun X.asVariant(): Variant` and `fun X?.asVariant(): Variant` top-level extensions
     * for every non-NIL/MAX type. Also emits the primitive overloads (Boolean, Long, Int, Double).
     */
    context(ctx: Context)
    fun buildAsVariantExtensions(variantClassName: ClassName, variantTypes: ResolvedEnum): List<FunSpec> {
        val funs = mutableListOf<FunSpec>()

        // use the corresponding Variant conversion constructor
        fun asVariantType(kotlinType: TypeName, ctorArg: String = "this") {
            // nullable
            funs += FunSpec
                .builder("toVariant")
                .receiver(kotlinType.copy(nullable = true))
                .returns(variantClassName)
                .addStatement(
                    "return if (this == null) %T() else %T($ctorArg)",
                    variantClassName,
                    variantClassName,
                )
                .build()
        }

        asVariantType(INT) // delegates to Long ctor inside Variant

        asVariantType(FLOAT) // delegates to Double ctor inside Variant

        // All builtin / object types
        variantTypes.raw.values.forEach { enumValue ->
            val (receiverType) = resolveTypeNameAndName(enumValue) { false } ?: return@forEach
            asVariantType(receiverType)
        }

        return funs
    }

    context(ctx: Context)
    private fun resolveTypeNameAndName(
        enumValue: EnumConstant,
        filter: (String) -> Boolean = { it == "BOOL" || it == "INT" || it == "FLOAT" || it == "OBJECT" },
    ): Pair<TypeName, String>? {
        val subclassName = enumValue.name.removePrefix("TYPE_")
            .takeUnless { it == "MAX" || it == "NIL" || filter(it) }
            ?: return null

        val godotTypeName = subclassName.screamingToPascalCase().renameGodotClass(getTypedClass = true)
        val receiverType = when {
            godotTypeName.equals("Array", ignoreCase = true) ||
                godotTypeName.equals("GodotArray", ignoreCase = true) ->
                ctx.classNameForOrDefault("Array", typedClass = true).parameterizedBy(STAR)

            godotTypeName.equals("Dictionary", ignoreCase = true) ->
                ctx.classNameForOrDefault("Dictionary", typedClass = true).parameterizedBy(STAR, STAR)

            else -> typeResolver.resolve(godotTypeName)
        }

        return receiverType to subclassName
    }

    // ── Variant size helpers ──────────────────────────────────────────────────

    /**
     * Looks up the Variant size from `builtinClassSizes` in the extension API JSON.
     *
     * `Variant` is present in `builtin_class_sizes` but NOT in `builtin_classes`,
     * so `context.findResolvedBuiltinClass("Variant")` always returns null.
     */
    context(ctx: Context)
    private fun resolveVariantSize(): Int {
        val buildConfigName = ctx.model.buildConfiguration.jsonName
        return ctx.extensionApi.builtinClassSizes
            .firstOrNull { it.buildConfiguration == buildConfigName }
            ?.sizes
            ?.firstOrNull { it.name == "Variant" }
            ?.size
            ?: error("Cannot find Variant size for build config '$buildConfigName'")
    }

    // Variant is opaque (no memberOffsets in the JSON) → pointerAlign
    context(ctx: Context)
    private fun resolveVariantAlign(): Int = ctx.model.buildConfiguration.pointerAlign
}
