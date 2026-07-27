package io.github.kingg22.kogot.processor.generators.kotlin

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
import io.github.kingg22.kogot.processor.model.PropertyInfo
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
            .addStatement("%T.getVirtual,", NodeVirtualDispatcherClassName)
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
        for (prop in exportProperties) {
            val variantType = typeResolver.resolve(prop.type.qualifiedName)
            val isMutable = prop.isMutable
            val propGetterName = "_godot_get_${prop.name}"
            val propSetterName = if (isMutable) "_godot_set_${prop.name}" else null
            val setterName = if (isMutable) SETTER_PREFIX + prop.name else ""
            val getterName = GETTER_PREFIX + prop.name

            // Generate getter trampoline
            val getterProperty = generateGetterMethodTrampoline(
                trampolineName = propGetterName,
                propName = prop.name,
                classType = classType,
            )
            typeSpecBuilder.addProperty(getterProperty)

            // Generate setter trampoline (only if mutable)
            if (isMutable) {
                val setterProperty = generateSetterMethodTrampoline(
                    trampolineName = propSetterName!!,
                    propName = prop.name,
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
                prop.name,
                VARIANT_TYPE_CLASS_NAME,
                variantType,
                getterName,
                setterName,
            )
        }

        typeSpecBuilder.addFunction(funSpec.build())
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
