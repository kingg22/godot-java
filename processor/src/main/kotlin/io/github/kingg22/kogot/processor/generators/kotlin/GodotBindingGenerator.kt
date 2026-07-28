package io.github.kingg22.kogot.processor.generators.kotlin

import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.withIndent
import io.github.kingg22.kogot.processor.diagnostics.DiagnosticCode
import io.github.kingg22.kogot.processor.diagnostics.DiagnosticLocation
import io.github.kingg22.kogot.processor.diagnostics.DiagnosticMessage
import io.github.kingg22.kogot.processor.model.AnnotationInfo
import io.github.kingg22.kogot.processor.model.ClassInfo
import io.github.kingg22.kogot.processor.model.FunctionInfo
import io.github.kingg22.kogot.processor.model.PropertyInfo
import io.github.kingg22.kogot.processor.model.getExportedMethods
import io.github.kingg22.kogot.processor.model.getParentClassShortName
import io.github.kingg22.kogot.processor.model.getRegisterSignalAnnotation
import io.github.kingg22.kogot.processor.model.hasExport
import io.github.kingg22.kogot.processor.model.hasGodotAnnotation
import io.github.kingg22.kogot.processor.model.inheritsFromNode2D
import io.github.kingg22.kogot.processor.model.inheritsFromSprite2D
import io.github.kingg22.kogot.processor.resolver.DefaultVariantTypeResolver
import io.github.kingg22.kogot.processor.resolver.VariantTypeResolver

private const val GETTER_PREFIX = "_get_"
private const val SETTER_PREFIX = "_set_"

/** `_physics_process` -> `physicsProcess`, matching codegen's `VirtualCallImplGen.trampolineName`. */
private fun godotVirtualTrampolineName(godotName: String): String =
    godotName.removePrefix("_").split("_").filter { it.isNotEmpty() }.mapIndexed { index, part ->
        if (index == 0) part else part.replaceFirstChar(Char::uppercaseChar)
    }.joinToString("")

/**
 * A generated Kotlin file, ready to be written by the caller.
 *
 * @param sourceClassNames qualified names of the `ClassInfo`s this file was derived from, used by the
 *   caller to scope KSP incremental-compilation dependencies to the actual source files involved.
 */
data class GeneratedFile(
    val relativePath: String,
    val content: String,
    val sourceClassNames: List<String> = emptyList(),
)

/** Result of a generation pass: the files produced plus any diagnostics raised along the way. */
data class GenerationResult(val files: List<GeneratedFile>, val diagnostics: List<DiagnosticMessage>)

/**
 * Generates Kotlin binding code (`<Class>_Binding` objects) for `@Godot`-annotated classes.
 */
class GodotBindingGenerator(private val typeResolver: VariantTypeResolver = DefaultVariantTypeResolver()) {

    fun generate(classes: List<ClassInfo>): GenerationResult {
        val files = mutableListOf<GeneratedFile>()
        val diagnostics = mutableListOf<DiagnosticMessage>()

        val godotClasses = classes.filter { it.hasGodotAnnotation() }

        if (godotClasses.isEmpty()) {
            return GenerationResult(emptyList(), emptyList())
        }

        for (classInfo in godotClasses) {
            try {
                files.add(generateBindingFile(classInfo))
            } catch (e: Exception) {
                diagnostics.add(
                    DiagnosticMessage.error(
                        code = DiagnosticCode.GENERATION_FAILED,
                        message = "Failed to generate binding for ${classInfo.qualifiedName}: ${e.message}",
                        location = DiagnosticLocation(
                            classInfo.filePath,
                            classInfo.lineNumber,
                            0,
                        ),
                    ),
                )
            }
        }

        files.add(generateCallbacksFile(godotClasses))

        return GenerationResult(files, diagnostics)
    }

    private fun generateBindingFile(classInfo: ClassInfo): GeneratedFile {
        val bindingClassName = "${classInfo.shortName}_Binding"
        val packageName = classInfo.packageName

        val parentClassName = classInfo.getParentClassShortName()
            ?: error("No parent class")

        val godotBaseClass = when {
            classInfo.inheritsFromSprite2D() -> "Sprite2D"
            classInfo.inheritsFromNode2D() -> "Node2D"
            else -> parentClassName
        }

        val classType = ClassName.bestGuess(classInfo.qualifiedName)

        val fileSpec = FileSpec
            .builder(packageName, bindingClassName)
            .applyCommonConfig()
            .optInForeignNative()

        val typeSpec = TypeSpec
            .objectBuilder(bindingClassName)
            .addAnnotation(InternalBindingClassName)
            .generateRegisterFun(classInfo, classType, godotBaseClass)
            .build()

        fileSpec.addType(typeSpec)

        val content = StringBuilder()
        fileSpec.build().writeTo(content)

        return GeneratedFile(
            relativePath = "${packageName.replace('.', '/')}/$bindingClassName.kt",
            content = content.toString(),
            sourceClassNames = listOf(classInfo.qualifiedName),
        )
    }

    private fun TypeSpec.Builder.generateRegisterFun(
        classInfo: ClassInfo,
        classType: ClassName,
        baseClass: String,
    ): TypeSpec.Builder = apply {
        val typeSpecBuilder = this
        val funSpec = FunSpec
            .builder("register")
            .addStatement("%M<%T>(", REGISTER_CLASS, classType)
            .addStatement("⇥%S,", classInfo.shortName)
            .addStatement("%S,", baseClass)
            .addStatement("%M { _, notifyPostInitialize ->", STATIC_C_FUNCTION)
            .addStatement(
                "⇥%M(%S, %S, notifyPostInitialize == %T.%M, ::%T)⇤",
                CREATE_INSTANCE_FUN,
                baseClass,
                classInfo.shortName,
                GDExtensionBoolClassName,
                GDExtensionBoolTrueMember,
                classType,
            )
            .addStatement("},")
            .addGetVirtualArgument(classInfo)
            .addStatement("⇤)")

        // Add signal registrations
        val registerSignalProperties = classInfo.properties.filter { it.getRegisterSignalAnnotation() != null }

        for (prop in registerSignalProperties) {
            val annotation = prop.getRegisterSignalAnnotation()
            if (annotation != null) {
                val annotationCode = buildRegisterSignalAnnotationCode(prop, annotation)
                funSpec
                    .addStatement("%M(⇥", REGISTER_CUSTOM_SIGNAL)
                    .addStatement("%S,", classInfo.shortName)
                    .addCode("%L", annotationCode)
                    .addStatement("⇤)")
            }
        }

        /*
         Add property registrations
         */
        val exportProperties = classInfo.properties.filter { it.hasExport() }
        for ((name, type, isMutable) in exportProperties) {
            val variantType = typeResolver.resolve(type.qualifiedName)
            val propGetterName = "_godot_get_$name"
            val propSetterName = if (isMutable) "_godot_set_$name" else null
            val setterName = if (isMutable) SETTER_PREFIX + name else ""
            val getterName = GETTER_PREFIX + name

            // Generate getter trampoline
            val getterProperty = generateGetterMethodTrampoline(
                trampolineName = propGetterName,
                propName = name,
                classType = classType,
            )
            typeSpecBuilder.addProperty(getterProperty)

            // Generate setter trampoline (only if mutable)
            if (isMutable) {
                val setterProperty = generateSetterMethodTrampoline(
                    trampolineName = propSetterName!!,
                    propName = name,
                    classType = classType,
                    variantType = variantType,
                )
                typeSpecBuilder.addProperty(setterProperty)

                funSpec.addStatement(
                    "%M(%S, %S, %T.%L, %N)",
                    REGISTER_METHOD_SETTER,
                    classInfo.shortName,
                    setterName,
                    VARIANT_TYPE_CLASS_NAME,
                    variantType,
                    propSetterName,
                )
            }

            funSpec.addStatement(
                "%M(%S, %S, %T.%L, %N)",
                REGISTER_METHOD_GETTER,
                classInfo.shortName,
                getterName,
                VARIANT_TYPE_CLASS_NAME,
                variantType,
                propGetterName,
            )

            funSpec.addStatement(
                "%M(%S, %S, %T.%L, %S, %S)",
                REGISTER_PROPERTY,
                classInfo.shortName,
                name,
                VARIANT_TYPE_CLASS_NAME,
                variantType,
                getterName,
                setterName,
            )
        }

        /*
         Add method registrations
         */
        val exportedMethods = classInfo.getExportedMethods()
        for (function in exportedMethods) {
            val trampolineName = "_godot_call_${function.name}"
            val trampolineProperty = generateMethodInvocationTrampoline(trampolineName, function, classType)
            typeSpecBuilder.addProperty(trampolineProperty)
            funSpec.addCode(buildRegisterMethodCode(classInfo, function, trampolineName))
        }

        typeSpecBuilder.addFunction(funSpec.build())
    }

    /**
     * Appends the `getVirtual` argument to a `registerClass<T>(...)` call: a per-class `staticCFunction`
     * dispatching only the Godot virtual methods this specific class overrides (from
     * [ClassInfo.overriddenVirtualMethods]), resolved to their `<EngineClass>VirtualCalls` trampolines.
     */
    private fun FunSpec.Builder.addGetVirtualArgument(classInfo: ClassInfo): FunSpec.Builder = apply {
        val overrides = classInfo.overriddenVirtualMethods
        if (overrides.isEmpty()) {
            addStatement("%M { _, _, _ -> null },", STATIC_C_FUNCTION)
            return@apply
        }

        beginControlFlow("%M { _, funcNamePtr, _ ->", STATIC_C_FUNCTION)
        beginControlFlow("%M(funcNamePtr) { funcName ->", RESOLVE_VIRTUAL_CALL)
        beginControlFlow("when (funcName)·{")
        overrides.forEach { override ->
            addStatement(
                "%S as %T -> %T.%N",
                override.godotName,
                ANY,
                ClassName(override.enginePackageName, "${override.engineClassShortName}VirtualCalls"),
                godotVirtualTrampolineName(override.godotName),
            )
        }
        addStatement("else -> null")
        endControlFlow()
        endControlFlow()
        addCode("⇤},\n")
    }

    private fun generateGetterMethodTrampoline(
        trampolineName: String,
        propName: String,
        classType: ClassName,
    ): PropertySpec {
        val initializer = CodeBlock
            .builder()
            .beginControlFlow("%M { _, instancePtr, _, _, returnValue, rError ->", STATIC_C_FUNCTION)
            .addStatement("val obj = instancePtr.%M<%T>()", GET_INSTANCE_PTR, classType)
            .beginControlFlow("if (returnValue != null)")
            .addStatement(
                "%T.newCopyRaw(returnValue, obj.%L.%M().rawPtr)",
                VARIANT_BINDING,
                propName,
                TO_VARIANT,
            )
            .endControlFlow()
            .addStatement("rError.%M()", CallErrorWritePtr)
            .endControlFlow()
            .build()

        return PropertySpec
            .builder(trampolineName, GDExtensionClassMethodCall, KModifier.PRIVATE)
            .initializer(initializer)
            .build()
    }

    private fun generateSetterMethodTrampoline(
        trampolineName: String,
        propName: String,
        classType: ClassName,
        variantType: String,
    ): PropertySpec {
        val initializer = CodeBlock
            .builder()
            .beginControlFlow("%M { _, instancePtr, args, _, _, rError ->", STATIC_C_FUNCTION)
            .addStatement("val obj = instancePtr.%M<%T>()", GET_INSTANCE_PTR, classType)
            .addStatement("val value = args?.%M?.%M", POINTED, POINTED_VALUE)
            .beginControlFlow("if (value == null)")
            .addStatement(
                "rError.%M(%T.%N, 0)",
                CallErrorWritePtr,
                GDExtensionCallErrorType,
                "GDEXTENSION_CALL_ERROR_INSTANCE_IS_NULL",
            )
            .addStatement("return@%M", STATIC_C_FUNCTION)
            .endControlFlow()
            .addStatement("val variantValue = %T(value)", VARIANT_CLASS_NAME)
            .addStatement(
                "obj.%L = variantValue.%M<%T>()",
                propName,
                VARIANT_GET_VALUE_OR_NULL,
                typeResolver.toKotlinPoetType(variantType),
            )
            .withIndent {
                beginControlFlow("?: run")
                    .addStatement(
                        "rError.%M(%T.%N, 0)",
                        CallErrorWritePtr,
                        GDExtensionCallErrorType,
                        "GDEXTENSION_CALL_ERROR_INVALID_ARGUMENT",
                    )
                    .addStatement("return@%M", STATIC_C_FUNCTION)
                endControlFlow()
            }
            .addStatement("rError.%M()", CallErrorWritePtr)
            .endControlFlow()
            .build()

        return PropertySpec
            .builder(trampolineName, GDExtensionClassMethodCall, KModifier.PRIVATE)
            .initializer(initializer)
            .build()
    }

    /**
     * Generates a `GDExtensionClassMethodCall` trampoline for an `@ExportMethod`-annotated function:
     * reads each argument out of the `args` array as a `Variant`, calls the real user method, and (if it
     * has a return value) writes the result back. Mirrors [generateGetterMethodTrampoline] /
     * [generateSetterMethodTrampoline] but generalized to N arguments.
     */
    private fun generateMethodInvocationTrampoline(
        trampolineName: String,
        function: FunctionInfo,
        classType: ClassName,
    ): PropertySpec {
        val builder = CodeBlock
            .builder()
            .beginControlFlow("%M { _, instancePtr, args, _, returnValue, rError ->", STATIC_C_FUNCTION)
            .addStatement("val obj = instancePtr.%M<%T>()", GET_INSTANCE_PTR, classType)

        if (function.parameters.isNotEmpty()) {
            builder
                .beginControlFlow("if (args == null)")
                .addStatement(
                    "rError.%M(%T.%N, 0)",
                    CallErrorWritePtr,
                    GDExtensionCallErrorType,
                    "GDEXTENSION_CALL_ERROR_INVALID_ARGUMENT",
                )
                .addStatement("return@%M", STATIC_C_FUNCTION)
                .endControlFlow()
        }

        val argNames = function.parameters.mapIndexed { index, parameter ->
            val variantType = typeResolver.resolve(parameter.type.qualifiedName)
            val kotlinType = typeResolver.toKotlinPoetType(variantType)
            val argName = "arg$index"

            builder
                .addStatement("val rawArg$index = args.%M(${index}L)", CINTEROP_GET)
                .beginControlFlow("if (rawArg$index == null)")
                .addStatement(
                    "rError.%M(%T.%N, %L)",
                    CallErrorWritePtr,
                    GDExtensionCallErrorType,
                    "GDEXTENSION_CALL_ERROR_INVALID_ARGUMENT",
                    index,
                )
                .addStatement("return@%M", STATIC_C_FUNCTION)
                .endControlFlow()
                .addStatement(
                    "val %L = %T(rawArg$index).%M<%T>()",
                    argName,
                    VARIANT_CLASS_NAME,
                    VARIANT_GET_VALUE_OR_NULL,
                    kotlinType,
                )
                .withIndent {
                    beginControlFlow("?: run")
                        .addStatement(
                            "rError.%M(%T.%N, %L)",
                            CallErrorWritePtr,
                            GDExtensionCallErrorType,
                            "GDEXTENSION_CALL_ERROR_INVALID_ARGUMENT",
                            index,
                        )
                        .addStatement("return@%M", STATIC_C_FUNCTION)
                    endControlFlow()
                }

            argName
        }

        val callFormat = "obj.%N(${argNames.joinToString(", ")})"
        if (function.returnType != null) {
            builder
                .addStatement("val result = $callFormat", function.name)
                .beginControlFlow("if (returnValue != null)")
                .addStatement("%T.newCopyRaw(returnValue, result.%M().rawPtr)", VARIANT_BINDING, TO_VARIANT)
                .endControlFlow()
        } else {
            builder.addStatement(callFormat, function.name)
        }

        val initializer = builder
            .addStatement("rError.%M()", CallErrorWritePtr)
            .endControlFlow()
            .build()

        return PropertySpec
            .builder(trampolineName, GDExtensionClassMethodCall, KModifier.PRIVATE)
            .initializer(initializer)
            .build()
    }

    /** Builds the `registerMethod(...)` call for an `@ExportMethod`-annotated function. */
    private fun buildRegisterMethodCode(
        classInfo: ClassInfo,
        function: FunctionInfo,
        trampolineName: String,
    ): CodeBlock {
        val builder = CodeBlock.builder()
        builder.addStatement("%M(⇥", REGISTER_METHOD)
        builder.addStatement("%S,", classInfo.shortName)
        builder.addStatement("%S,", function.name)

        val hasReturnValue = function.returnType != null
        val returnVariantType = function.returnType?.let { typeResolver.resolve(it.qualifiedName) } ?: "NIL"
        builder.addStatement("hasReturnValue = %L,", hasReturnValue)
        builder.addStatement("returnType = %T.%L,", VARIANT_TYPE_CLASS_NAME, returnVariantType)

        if (function.parameters.isEmpty()) {
            builder.addStatement("arguments = emptyList(),")
        } else {
            builder.addStatement("arguments = listOf(⇥")
            for (parameter in function.parameters) {
                val variantType = typeResolver.resolve(parameter.type.qualifiedName)
                builder.addStatement(
                    "%T(%T.%L, %S),",
                    METHOD_ARGUMENT_CLASS_NAME,
                    VARIANT_TYPE_CLASS_NAME,
                    variantType,
                    parameter.name,
                )
            }
            builder.addStatement("⇤),")
        }

        builder.addStatement("callFunction = %N,", trampolineName)
        builder.addStatement("⇤)")
        return builder.build()
    }

    private fun buildRegisterSignalAnnotationCode(prop: PropertyInfo, annotation: AnnotationInfo): CodeBlock {
        val builder = CodeBlock.builder()
        builder.addStatement("%T(⇥", REGISTER_SIGNAL_CLASS_NAME)

        val signalName = (annotation.arguments["name"] as? String)?.takeIf { it.isNotEmpty() } ?: prop.name

        @Suppress("UNCHECKED_CAST")
        val paramAnnotations = annotation.arguments["params"] as? List<AnnotationInfo> ?: emptyList()

        if (paramAnnotations.isNotEmpty()) {
            paramAnnotations.forEach { paramAnn ->
                val paramName = (paramAnn.arguments["name"] as? String).orEmpty()
                val typeQualifiedName = paramAnn.arguments["type"] as? String
                    ?: error(
                        "Missing type for signal parameter in ${prop.name}, signal param: $paramName",
                    )
                val entryName = typeQualifiedName.substringAfterLast('.')
                builder.addStatement(
                    "%T(%T.%L, %S),", // Param(Variant.Type.NIL, "name")
                    REGISTER_SIGNAL_PARAM_CLASS_NAME,
                    VARIANT_TYPE_CLASS_NAME,
                    entryName,
                    paramName,
                )
            }
        }
        builder.addStatement("name = %S,", signalName)
        builder.addStatement("⇤),")

        return builder.build()
    }

    private fun generateCallbacksFile(classes: List<ClassInfo>): GeneratedFile {
        val packageName = classes.first().packageName + ".generated"
        val className = "GeneratedBindings"

        val type = TypeSpec
            .classBuilder(className)
            .superclass(BindingInitializationCallbacksClassName)
            .addAnnotation(InternalBindingClassName)
            .addFunction(
                FunSpec
                    .builder("onInitScene")
                    .addModifiers(KModifier.OVERRIDE)
                    .apply {
                        classes.forEach {
                            addStatement(
                                "%T.register()",
                                ClassName(it.packageName, "${it.shortName}_Binding"),
                            )
                        }
                    }
                    .build(),
            )
            .build()

        val file = FileSpec
            .builder(packageName, className)
            .applyCommonConfig()
            .addType(type)
            .build()

        val content = StringBuilder()
        file.writeTo(content)

        return GeneratedFile(
            relativePath = "${packageName.replace('.', '/')}/$className.kt",
            content = content.toString(),
            sourceClassNames = classes.map { it.qualifiedName },
        )
    }
}
