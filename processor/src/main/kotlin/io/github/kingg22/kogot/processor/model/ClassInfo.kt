package io.github.kingg22.kogot.processor.model

/**
 * Represents a class declaration.
 */
data class ClassInfo(
    val qualifiedName: String,
    val shortName: String,
    val packageName: String,
    val supertypes: List<TypeInfo> = emptyList(),
    val properties: List<PropertyInfo> = emptyList(),
    val functions: List<FunctionInfo> = emptyList(),
    val annotations: List<AnnotationInfo> = emptyList(),
    val modifiers: Set<String> = emptySet(),
    val filePath: String = "",
    val lineNumber: Int = 0,
    val overriddenVirtualMethods: List<VirtualMethodOverride> = emptyList(),
)

/**
 * Returns true if this class has @Tool annotation.
 */
fun ClassInfo.hasTool(): Boolean =
    annotations.any { it.shortName == "Tool" || it.matches("io.github.kingg22.godot.api.annotations.Tool") }

/**
 * Returns the @Tool annotation or null if not present.
 */
fun ClassInfo.getToolAnnotation(): AnnotationInfo? =
    annotations.find { it.shortName == "Tool" || it.matches("io.github.kingg22.godot.api.annotations.Tool") }

/**
 * Returns all exported properties.
 */
fun ClassInfo.getExportedProperties(): List<PropertyInfo> = properties.filter { it.hasExport() }

/**
 * Returns all RPC functions.
 */
fun ClassInfo.getRpcFunctions(): List<FunctionInfo> = functions.filter { it.hasRpc() }

/**
 * Returns all functions exported via @ExportMethod (callable from GDScript/the editor).
 */
fun ClassInfo.getExportedMethods(): List<FunctionInfo> = functions.filter { it.hasExportMethod() }

/**
 * Checks if this class inherits from Node (directly or transitively).
 */
fun ClassInfo.inheritsFromNode(): Boolean =
    supertypes.any { it.qualifiedName == "io.github.kingg22.godot.api.core.Node" }

/**
 * Checks if this class has @Godot annotation.
 */
fun ClassInfo.hasGodotAnnotation(): Boolean =
    annotations.any { it.shortName == "Godot" || it.matches("io.github.kingg22.godot.api.Godot") }

/**
 * Checks if this class inherits from Sprite2D (directly or transitively).
 */
fun ClassInfo.inheritsFromSprite2D(): Boolean =
    supertypes.any { it.qualifiedName == "io.github.kingg22.godot.api.core.node.Sprite2D" }

/**
 * Checks if this class inherits from Node2D (directly or transitively).
 */
fun ClassInfo.inheritsFromNode2D(): Boolean =
    supertypes.any { it.qualifiedName == "io.github.kingg22.godot.api.core.node.Node2D" }

/**
 * Returns the parent class short name (first supertype).
 */
fun ClassInfo.getParentClassShortName(): String? = supertypes.firstOrNull()?.shortName
