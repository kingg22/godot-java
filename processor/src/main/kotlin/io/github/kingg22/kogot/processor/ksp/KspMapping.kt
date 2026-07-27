package io.github.kingg22.kogot.processor.ksp

import com.google.devtools.ksp.isConstructor
import com.google.devtools.ksp.symbol.FileLocation
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeReference
import com.google.devtools.ksp.symbol.Nullability
import io.github.kingg22.kogot.processor.model.AnnotationInfo
import io.github.kingg22.kogot.processor.model.ClassInfo
import io.github.kingg22.kogot.processor.model.FunctionInfo
import io.github.kingg22.kogot.processor.model.ParameterInfo
import io.github.kingg22.kogot.processor.model.PropertyInfo
import io.github.kingg22.kogot.processor.model.TypeInfo

private const val UNKNOWN = "<unknown>"

/**
 * Extension to convert KSClassDeclaration to ClassInfo.
 */
fun KSClassDeclaration.toClassInfo(): ClassInfo {
    val packageName = packageName.asString()
    val qName = this.qualifiedName
    val qualifiedName = qName?.asString() ?: "$packageName.${simpleName.asString()}"

    val annotations = this.annotations.map { it.toAnnotationInfo() }.toList()
    val modifiers = this.modifiers.map { it.name }.toSet()

    val supertypes = this.superTypes.mapNotNull { it.toTypeInfo() }.toList()

    val properties = this.getAllProperties().map { it.toPropertyInfo() }.toList()
    val functions = this.getAllFunctions().map { it.toFunctionInfo() }.toList()

    val filePath = this.containingFile?.filePath ?: UNKNOWN
    val line = this.getLineNumber()

    return ClassInfo(
        qualifiedName = qualifiedName,
        shortName = simpleName.asString(),
        packageName = packageName,
        supertypes = supertypes,
        properties = properties,
        functions = functions,
        annotations = annotations,
        modifiers = modifiers,
        filePath = filePath,
        lineNumber = line,
    )
}

/**
 * Gets line number from a KSNode.
 */
private fun KSNode.getLineNumber(): Int {
    val loc = this.location as? FileLocation ?: return 0
    return loc.lineNumber
}

/**
 * Extension to convert KSAnnotation to AnnotationInfo.
 */
fun KSAnnotation.toAnnotationInfo(): AnnotationInfo {
    val annotationType = annotationType.resolve()
    val decl = annotationType.declaration
    val qualifiedName = (decl as? KSClassDeclaration)?.qualifiedName?.asString() ?: UNKNOWN
    val shortName = (decl as? KSClassDeclaration)?.simpleName?.asString() ?: qualifiedName

    val arguments = this.arguments.associate { arg ->
        val name = arg.name?.asString() ?: UNKNOWN

        val value = when (val v = arg.value) {
            is KSType -> {
                val typeDecl = v.declaration
                if (typeDecl is KSClassDeclaration) {
                    typeDecl.qualifiedName?.asString() ?: UNKNOWN
                } else {
                    typeDecl.toString()
                }
            }

            // Enum-typed annotation arguments (e.g. Variant.Type.INT) resolve to the enum entry's
            // own KSClassDeclaration, not a KSType wrapping it.
            is KSClassDeclaration -> v.qualifiedName?.asString() ?: v.simpleName.asString()

            is KSAnnotation -> v.toAnnotationInfo()

            is List<*> -> v.mapNotNull { element ->
                when (element) {
                    is KSAnnotation -> element.toAnnotationInfo()

                    is KSType -> {
                        val typeDecl = element.declaration
                        if (typeDecl is KSClassDeclaration) {
                            typeDecl.qualifiedName?.asString()
                        } else {
                            element.toString()
                        }
                    }

                    is KSClassDeclaration -> element.qualifiedName?.asString() ?: element.simpleName.asString()

                    else -> element?.toString()
                }
            }

            else -> v
        }
        name to value
    }

    @Suppress("UNCHECKED_CAST")
    return AnnotationInfo(
        qualifiedName = qualifiedName,
        shortName = shortName,
        arguments = arguments.filterValues { it != null } as Map<String, Any>,
    )
}

/**
 * Extension to convert KSTypeReference to TypeInfo.
 */
fun KSTypeReference.toTypeInfo(): TypeInfo? {
    val type = this.resolve().takeUnless { it.isError }
    return type?.toTypeInfo()
}

/**
 * Extension to convert KSType to TypeInfo.
 */
fun KSType.toTypeInfo(): TypeInfo {
    val declaration = this.declaration
    val declQName = declaration.qualifiedName
    val qualifiedName = declQName?.asString() ?: UNKNOWN

    return TypeInfo(
        qualifiedName = qualifiedName,
        shortName = declaration.simpleName.asString(),
        isNullable = this.nullability == Nullability.NULLABLE,
        isPrimitive = false,
    )
}

/**
 * Extension to convert KSPropertyDeclaration to PropertyInfo.
 */
fun KSPropertyDeclaration.toPropertyInfo(): PropertyInfo {
    val typeInfo = type.toTypeInfo() ?: TypeInfo(UNKNOWN, UNKNOWN)
    val annotations = this.annotations.map { it.toAnnotationInfo() }.toList()
    val modifiers = this.modifiers.map { it.name }.toSet()

    return PropertyInfo(
        name = simpleName.asString(),
        type = typeInfo,
        isMutable = this.isMutable,
        hasDefaultValue = false,
        annotations = annotations,
        modifiers = modifiers,
    )
}

/**
 * Extension to convert KSFunctionDeclaration to FunctionInfo.
 */
fun KSFunctionDeclaration.toFunctionInfo(): FunctionInfo {
    val returnTypeInfo = returnType?.toTypeInfo()
    val annotations = this.annotations.map { it.toAnnotationInfo() }.toList()
    val modifiers = this.modifiers.map { it.name }.toSet()

    val parameters = this.parameters.map { param ->
        ParameterInfo(
            name = param.name?.asString() ?: UNKNOWN,
            type = param.type.toTypeInfo() ?: TypeInfo(UNKNOWN, UNKNOWN),
            hasDefaultValue = param.hasDefault,
        )
    }

    val simpleNameStr = simpleName.asString()

    return FunctionInfo(
        name = simpleNameStr,
        returnType = returnTypeInfo,
        parameters = parameters,
        annotations = annotations,
        modifiers = modifiers,
        isConstructor = isConstructor(),
    )
}
