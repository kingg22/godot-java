package io.github.kingg22.kogot.processor.model

/**
 * Represents a parameter of a function.
 */
data class ParameterInfo(val name: String, val type: TypeInfo, val hasDefaultValue: Boolean = false)
