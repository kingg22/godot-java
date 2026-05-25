package io.github.kingg22.kogot.processor.generation.kotlin

import com.google.devtools.ksp.symbol.KSAnnotation
import com.squareup.kotlinpoet.*
import io.github.kingg22.kogot.analysis.models.ClassInfo
import io.github.kingg22.kogot.analysis.models.PropertyInfo
import io.github.kingg22.kogot.analysis.models.getParentClassShortName
import io.github.kingg22.kogot.analysis.models.hasExport
import io.github.kingg22.kogot.analysis.models.hasGodotAnnotation
import io.github.kingg22.kogot.analysis.models.inheritsFromNode2D
import io.github.kingg22.kogot.analysis.models.inheritsFromSprite2D
import io.github.kingg22.kogot.processor.diagnostics.DiagnosticCode
import io.github.kingg22.kogot.processor.diagnostics.DiagnosticLocation
import io.github.kingg22.kogot.processor.diagnostics.DiagnosticMessage
import io.github.kingg22.kogot.processor.generation.GeneratedFile
import io.github.kingg22.kogot.processor.generation.GeneratedOutput
import io.github.kingg22.kogot.processor.generation.Generator
import io.github.kingg22.kogot.processor.generation.GeneratorContext

private const val GETTER_PREFIX = "_get_"
private const val SETTER_PREFIX = "_set_"

class GodotBindingGenerator : Generator {
    override val name: String = "GodotBindingGenerator"

    override fun generate(context: GeneratorContext, classes: List<ClassInfo>): GeneratedOutput {
        val files = mutableListOf<GeneratedFile>()
        val diagnostics = mutableListOf<DiagnosticMessage>()

        val godotClasses = classes.filter { it.hasGodotAnnotation() }

        if (godotClasses.isEmpty()) {
            return GeneratedOutput(emptyList(), emptyList())
        }

        for (classInfo in godotClasses) {
            try {
                val propertyAnnotations = context.propertyAnnotations[classInfo.qualifiedName] ?: emptyMap()
                files.add(generateBindingFile(classInfo, propertyAnnotations))
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

        return GeneratedOutput(files, diagnostics)
    }

    private fun generateBindingFile(
        classInfo: ClassInfo,
        propertyAnnotations: Map<String, List<KSAnnotation>>,
    ): GeneratedFile {
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
            .generateRegisterFun(classInfo, classType, godotBaseClass, propertyAnnotations)
            .build()

        // fileSpec.addFunction(generateCreateInstanceFun(classInfo, classType, godotBaseClass))
        fileSpec.addType(typeSpec)

        val content = StringBuilder()
        fileSpec.build().writeTo(content)

        return GeneratedFile(
            "${packageName.replace('.', '/')}/$bindingClassName.kt",
            content.toString(),
        )
    }

    private fun TypeSpec.Builder.generateRegisterFun(
        classInfo: ClassInfo,
        classType: ClassName,
        baseClass: String,
        propertyAnnotations: Map<String, List<KSAnnotation>>,
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
        val registerSignalProperties = classInfo.properties.filter { prop ->
            propertyAnnotations[prop.name]
                ?.any { it.shortName.asString() == "RegisterSignal" }
                ?: false
        }

        for (prop in registerSignalProperties) {
            val ksAnnotation = propertyAnnotations[prop.name]
                ?.first { it.shortName.asString() == "RegisterSignal" }
            if (ksAnnotation != null) {
                val annotationCode = buildRegisterSignalAnnotationFromKSAnnotation(prop, ksAnnotation)
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
            val variantType = mapTypeToVariantType(prop.type.qualifiedName)
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
                "%T.instance.newCopyRaw(returnValue, obj.%L.%M().rawPtr)",
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
                mapKotlinTypeToVariantReturnType(variantType),
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

    private fun mapKotlinTypeToVariantReturnType(variantType: String): ClassName = when (variantType) {
        "INT" -> INT
        "FLOAT" -> FLOAT
        "BOOL" -> BOOLEAN
        "STRING" -> STRING
        "VECTOR2" -> VECTOR2_CLASS_NAME
        "VECTOR3" -> VECTOR3_CLASS_NAME
        else -> VARIANT_CLASS_NAME
    }

    private fun buildRegisterSignalAnnotationFromKSAnnotation(
        prop: PropertyInfo,
        annotation: KSAnnotation,
    ): CodeBlock {
        val builder = CodeBlock.builder()
        builder.addStatement("%T(⇥", REGISTER_SIGNAL_CLASS_NAME)

        // Extract params from the KSAnnotation directly
        val paramsArg = annotation.arguments.find { it.name?.asString() == "params" }
        val nameArg = annotation.arguments.find { it.name?.asString() == "name" }

        val signalName = nameArg?.value?.toString()?.takeIf { it.isNotEmpty() } ?: prop.name

        // Handle params list
        val paramsList = paramsArg?.value as? List<*>
        val paramAnnotations = paramsList.orEmpty().filterIsInstance<KSAnnotation>()

        if (paramAnnotations.isNotEmpty()) {
            paramAnnotations.forEach { paramAnn ->
                // Extract type and name from the param annotation
                val typeArg = paramAnn.arguments.find { it.name?.asString() == "type" }
                val nameArgVal = paramAnn.arguments.find { it.name?.asString() == "name" }
                val paramName = nameArgVal?.value?.toString().orEmpty()
                val typeEntry = typeArg?.value?.toString()
                    ?: error(
                        "Missing type for signal parameter in ${prop.name}, signal param: $paramName. Type arg: $typeArg",
                    )
                builder.addStatement(
                    "%T(%T.%L, %S),", // Param(Variant.Type.NIL, "name")
                    REGISTER_SIGNAL_PARAM_CLASS_NAME,
                    VARIANT_CLASS_NAME,
                    typeEntry,
                    paramName,
                )
            }
        }
        builder.addStatement("name = %S,", signalName)
        builder.addStatement("⇤),")

        return builder.build()
    }

    private fun mapTypeToVariantType(qualifiedName: String): String = when (qualifiedName) {
        "kotlin.Int", "kotlin.Long" -> "INT"
        "kotlin.Float", "kotlin.Double" -> "FLOAT"
        "kotlin.Boolean" -> "BOOL"
        "kotlin.String" -> "STRING"
        "io.github.kingg22.godot.api.builtin.Vector2" -> "VECTOR2"
        "io.github.kingg22.godot.api.builtin.Vector2i" -> "VECTOR2I"
        "io.github.kingg22.godot.api.builtin.Vector3" -> "VECTOR3"
        "io.github.kingg22.godot.api.builtin.Vector3i" -> "VECTOR3I"
        "io.github.kingg22.godot.api.builtin.Rect2" -> "RECT2"
        "io.github.kingg22.godot.api.builtin.Rect2i" -> "RECT2I"
        "io.github.kingg22.godot.api.builtin.Transform2D" -> "TRANSFORM2D"
        "io.github.kingg22.godot.api.builtin.Vector4" -> "VECTOR4"
        "io.github.kingg22.godot.api.builtin.Vector4i" -> "VECTOR4I"
        "io.github.kingg22.godot.api.builtin.Plane" -> "PLANE"
        "io.github.kingg22.godot.api.builtin.Quaternion" -> "QUATERNION"
        "io.github.kingg22.godot.api.builtin.Aabb" -> "AABB"
        "io.github.kingg22.godot.api.builtin.Basis" -> "BASIS"
        "io.github.kingg22.godot.api.builtin.Transform3D" -> "TRANSFORM3D"
        "io.github.kingg22.godot.api.builtin.Projection" -> "PROJECTION"
        "io.github.kingg22.godot.api.builtin.Color" -> "COLOR"
        "io.github.kingg22.godot.api.builtin.StringName" -> "STRING_NAME"
        "io.github.kingg22.godot.api.builtin.NodePath" -> "NODE_PATH"
        "io.github.kingg22.godot.api.builtin.Rid" -> "RID"
        "io.github.kingg22.godot.api.builtin.Callable" -> "CALLABLE"
        "io.github.kingg22.godot.api.builtin.Signal" -> "SIGNAL"
        "io.github.kingg22.godot.api.builtin.VariantDictionary" -> "DICTIONARY"
        "io.github.kingg22.godot.api.builtin.VariantArray" -> "ARRAY"
        "io.github.kingg22.godot.api.builtin.PackedByteArray" -> "PACKED_BYTE_ARRAY"
        "io.github.kingg22.godot.api.builtin.PackedInt32Array" -> "PACKED_INT32_ARRAY"
        "io.github.kingg22.godot.api.builtin.PackedInt64Array" -> "PACKED_INT64_ARRAY"
        "io.github.kingg22.godot.api.builtin.PackedFloat32Array" -> "PACKED_FLOAT32_ARRAY"
        "io.github.kingg22.godot.api.builtin.PackedFloat64Array" -> "PACKED_FLOAT64_ARRAY"
        "io.github.kingg22.godot.api.builtin.PackedStringArray" -> "PACKED_STRING_ARRAY"
        "io.github.kingg22.godot.api.builtin.PackedVector2Array" -> "PACKED_VECTOR2_ARRAY"
        "io.github.kingg22.godot.api.builtin.PackedVector3Array" -> "PACKED_VECTOR3_ARRAY"
        "io.github.kingg22.godot.api.builtin.PackedColorArray" -> "PACKED_COLOR_ARRAY"
        "io.github.kingg22.godot.api.builtin.PackedVector4Array" -> "PACKED_VECTOR4_ARRAY"
        else -> "OBJECT"
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
            "${packageName.replace('.', '/')}/$className.kt",
            content.toString(),
        )
    }
}
