package io.github.kingg22.kogot.processor.model

/**
 * Represents a function/method of a class.
 */
data class FunctionInfo(
    val name: String,
    val returnType: TypeInfo?,
    val parameters: List<ParameterInfo> = emptyList(),
    val annotations: List<AnnotationInfo> = emptyList(),
    val modifiers: Set<String> = emptySet(),
    val isConstructor: Boolean = false,
)

/**
 * Returns true if this function has @Rpc annotation.
 */
fun FunctionInfo.hasRpc(): Boolean =
    annotations.any { it.shortName == "Rpc" || it.matches("io.github.kingg22.godot.api.annotations.Rpc") }

/**
 * Returns the @Rpc annotation or null if not present.
 */
fun FunctionInfo.getRpcAnnotation(): AnnotationInfo? =
    annotations.find { it.shortName == "Rpc" || it.matches("io.github.kingg22.godot.api.annotations.Rpc") }

/**
 * Checks if this function is a valid RPC target.
 */
fun FunctionInfo.isValidRpcTarget(): Boolean {
    if (!hasRpc()) return false
    // RPC functions cannot be constructors
    if (isConstructor) return false
    // RPC functions cannot have out parameters (we don't track out specifically here, but void return is expected)
    return returnType != null
}
