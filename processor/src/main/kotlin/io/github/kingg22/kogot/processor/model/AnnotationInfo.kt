package io.github.kingg22.kogot.processor.model

/**
 * Represents an annotation applied to a declaration.
 * This is a KSP-agnostic model - no KSAnnotation types.
 */
data class AnnotationInfo(
    val qualifiedName: String,
    val shortName: String,
    val arguments: Map<String, Any> = emptyMap(),
)

/**
 * Checks if this annotation matches the given qualified name.
 */
fun AnnotationInfo.matches(qualifiedName: String): Boolean =
    qualifiedName == this.qualifiedName || shortName == qualifiedName

/**
 * Returns true if this annotation has the given argument.
 */
fun AnnotationInfo.hasArgument(name: String): Boolean = arguments.containsKey(name)

/**
 * Returns the argument value or null if not present.
 */
@Suppress("UNCHECKED_CAST")
fun <T> AnnotationInfo.getArgument(name: String): T? = arguments[name] as? T
