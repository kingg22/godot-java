package io.github.kingg22.godot.codegen.extensionapi.impl.knative.generators

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.UNIT
import io.github.kingg22.godot.codegen.extensionapi.Context
import io.github.kingg22.godot.codegen.extensionapi.TypeResolver
import io.github.kingg22.godot.codegen.extensionapi.resolver.addKdocIfPresent
import io.github.kingg22.godot.codegen.extensionapi.resolver.experimentalApiAnnotation
import io.github.kingg22.godot.codegen.impl.safeIdentifier
import io.github.kingg22.godot.codegen.models.extensionapi.BuiltinClass
import io.github.kingg22.godot.codegen.models.extensionapi.EngineClass
import io.github.kingg22.godot.codegen.models.extensionapi.MethodArg
import io.github.kingg22.godot.codegen.models.extensionapi.MethodDescriptor
import io.github.kingg22.godot.codegen.models.extensionapi.UtilityFunction
import io.github.kingg22.godot.codegen.utils.logger
import io.github.kingg22.godot.codegen.utils.trace
import io.github.kingg22.godot.codegen.utils.withExceptionContext

/**
 * Shared method/parameter generation logic used by both builtin class
 * generators and engine class generators.
 *
 * Knows nothing about class structure, operators, or statics —
 * those are concerns of the specific generators.
 *
 * @param body provides CodeBlock bodies (stub TODO() or real cinterop calls)
 */
class NativeMethodGenerator(
    private val typeResolver: TypeResolver,
    private val defaultValueGenerator: DefaultValueGenerator,
) {
    private val logger = logger()

    /**
     * Builds a [FunSpec] from raw method data.
     *
     * @param method         The method descriptor (builtin, engine class, or utility function).
     * @param className      Godot class name (used for experimental annotation lookup).
     * @param modifiers      Additional [KModifier]s (e.g., OVERRIDE, OPERATOR).
     * @param codeBody       Optional body [CodeBlock] override. When non-null it is used
     *                       instead of the default `TODO()` stub. Callers supply this when an
     *                       implementation generator has
     *                       produced a real cinterop body.
     * @param forceNullableEngineArgs When `true`, every engine-class/singleton-typed parameter is
     *                       declared nullable regardless of [MethodArg.isNullable]. Used only for
     *                       virtual method stubs: [MethodArg.isNullable] reflects a JSON default
     *                       value, a forward-call-only concept — `extension_api.json` never sets one
     *                       on a virtual method's arguments, yet the engine can and does hand back a
     *                       genuine null pointer on dispatch (see `VirtualCallImplGen.appendArgRead`).
     *                       Forward (non-virtual) call sites must keep passing `false` so their
     *                       nullability stays exactly [MethodArg.isNullable]-derived, unchanged.
     * @param forceNullableEngineReturn When `true` and the method's return type resolves to an
     *                       engine class or singleton, the return type is declared nullable
     *                       (`.copy(nullable = true)`). Used only for virtual method stubs: a
     *                       `COpaquePointer` (the type behind `GodotObject.rawPtr`) cannot itself
     *                       represent a null address — the only way a Kotlin override can hand back
     *                       "no value" for one of these virtuals is by returning a `null` reference —
     *                       yet the generated return type was always declared non-nullable, making
     *                       that architecturally impossible (see `VirtualCallImplGen.buildReturnWrite`).
     *                       Forward (non-virtual) call sites must keep passing `false` so their return
     *                       type stays exactly as resolved, unchanged.
     * @param block          Extra customisation applied to the [FunSpec.Builder] after the
     *                       body is set (KDoc additions, annotations, etc.).
     */
    context(context: Context)
    fun buildMethod(
        method: MethodDescriptor,
        className: String,
        codeBody: CodeBlock,
        vararg modifiers: KModifier,
        forceNullableEngineArgs: Boolean = false,
        forceNullableEngineReturn: Boolean = false,
        block: FunSpec.Builder.() -> Unit = {},
    ): FunSpec {
        withExceptionContext({ "Generating method $className.'${method.name}'" }) {
            val (rawReturnTypeSpec, originalType, originalMeta) = when (method) {
                is BuiltinClass.BuiltinMethod -> Triple(
                    method.returnType?.let { typeResolver.resolve(it) } ?: UNIT,
                    method.returnType,
                    null,
                )

                is EngineClass.ClassMethod -> Triple(
                    method.returnValue?.let { typeResolver.resolve(it) } ?: UNIT,
                    method.returnValue?.type,
                    method.returnValue?.meta,
                )

                is UtilityFunction -> Triple(
                    method.returnType?.let { typeResolver.resolve(it) } ?: UNIT,
                    method.returnType,
                    null,
                )
            }

            val returnTypeSpec = if (forceNullableEngineReturn &&
                originalType != null &&
                (context.isEngineClass(originalType) || context.isSingleton(originalType))
            ) {
                rawReturnTypeSpec.copy(nullable = true)
            } else {
                rawReturnTypeSpec
            }

            val name = method.name
            val isVararg = method.isVararg
            val arguments = method.arguments

            val kotlinName = safeIdentifier(name).fixAccidentalOverride(name, returnTypeSpec)

            val builder = FunSpec
                .builder(kotlinName)
                .addModifiers(*modifiers)
                .returns(returnTypeSpec)
                // Use the provided body override, otherwise fall back to the TODO() stub.
                .addCode(codeBody)
                .addKdocIfPresent(method)
                .apply {
                    if (name != kotlinName) {
                        if (method.description != null) addKdoc("\n\n")
                        addKdoc("Original name: `%S`", name)
                    }
                    if (originalType != null) {
                        addKdoc("\n\n@return Original type: `%L`", originalType)
                        if (originalMeta != null) addKdoc(", meta type: `%L`", originalMeta)
                    }
                }
                .experimentalApiAnnotation(className, name)

            // Fixed args always come first
            arguments.forEach { arg ->
                require(!isVararg || safeIdentifier(arg.name) != "args") {
                    "Vararg method '$name' has a fixed arg named 'args' — rename it to avoid clash"
                }
                val forceNullable = forceNullableEngineArgs &&
                    (context.isEngineClass(arg.type) || context.isSingleton(arg.type))
                builder.addParameter(buildParameter(arg, forceNullable))
            }

            // Trailing vararg only after all fixed args
            if (isVararg) {
                builder.addParameter(
                    ParameterSpec
                        .builder("args", context.classNameFor("Variant"), KModifier.VARARG)
                        .build(),
                )
            }

            return builder.apply(block).build()
        }
    }

    context(context: Context)
    private fun String.fixAccidentalOverride(godotName: String, returnType: TypeName): String = when (godotName) {
        "to_string" if this == "toString" && returnType == context.classNameFor("String", "GodotString") -> {
            logger.trace { "INFO: renaming toString() → toGodotString() to avoid `Any` clash" }

            "toGodotString"
        }

        else -> this
    }

    /**
     * Builds a [ParameterSpec] for a single [MethodArg].
     *
     * Default values from Godot JSON are emitted as `TODO()` —
     * the impl layer replaces them with actual expressions.
     *
     * @param forceNullable Forces a nullable type regardless of [MethodArg.isNullable]. See
     *                       [buildMethod]'s `forceNullableEngineArgs` doc — only ever `true` for
     *                       virtual method stubs.
     */
    context(context: Context)
    fun buildParameter(arg: MethodArg, forceNullable: Boolean = false): ParameterSpec {
        withExceptionContext({
            "Generating parameter '${arg.name}': ${arg.type} (${arg.meta})} = ${arg.defaultValue ?: "--"}"
        }) {
            val rawType = typeResolver.resolve(arg)
            val type = if (arg.isNullable || forceNullable) rawType.copy(nullable = true) else rawType
            val kotlinName = safeIdentifier(arg.name)
            val paramBuilder = ParameterSpec.builder(kotlinName, type)

            // Documentación
            if (arg.name != kotlinName) paramBuilder.addKdoc("Original name: `%S`\n", arg.name)
            paramBuilder.addKdoc("Original type: `%L`", arg.type)
            if (arg.meta != null) paramBuilder.addKdoc(", meta type: `%L`", arg.meta!!)

            // Default value
            arg.defaultValue?.let { value ->
                paramBuilder.addKdoc("\nDefault value (unparsed): `%L`", value)
                val defaultCode = defaultValueGenerator.generate(arg, type)
                check(defaultCode?.isNotEmpty() == true) {
                    "Failed to generate default value for ${arg.name}: ${arg.type} = $value"
                }
                paramBuilder.defaultValue(defaultCode)
            }

            return paramBuilder.build()
        }
    }
}
