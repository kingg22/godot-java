package io.github.kingg22.kogot.processor.model

/**
 * Represents a property (field) of a class.
 */
data class PropertyInfo(
    val name: String,
    val type: TypeInfo,
    val isMutable: Boolean = true,
    val hasDefaultValue: Boolean = false,
    val annotations: List<AnnotationInfo> = emptyList(),
    val modifiers: Set<String> = emptySet(),
)

/**
 * Returns true if this property has @Export annotation.
 */
fun PropertyInfo.hasExport(): Boolean =
    annotations.any { it.shortName == "Export" || it.matches("io.github.kingg22.godot.api.annotations.Export") }

/**
 * Returns the @Export annotation or null if not present.
 */
fun PropertyInfo.getExportAnnotation(): AnnotationInfo? =
    annotations.find { it.shortName == "Export" || it.matches("io.github.kingg22.godot.api.annotations.Export") }

/**
 * Returns the @RegisterSignal annotation or null if not present.
 */
fun PropertyInfo.getRegisterSignalAnnotation(): AnnotationInfo? = annotations.find {
    it.shortName == "RegisterSignal" || it.matches("io.github.kingg22.godot.api.annotations.RegisterSignal")
}
