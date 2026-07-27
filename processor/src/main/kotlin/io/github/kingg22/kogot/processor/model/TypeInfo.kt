package io.github.kingg22.kogot.processor.model

/**
 * Represents a resolved type.
 * Uses simple strings instead of KSType so it's usable outside a KSP `Resolver` context.
 */
data class TypeInfo(
    val qualifiedName: String,
    val shortName: String,
    val isNullable: Boolean = false,
    val isPrimitive: Boolean = false,
    val typeArguments: List<TypeInfo> = emptyList(),
)

/**
 * Common Godot primitive type names.
 */
object GodotPrimitives {
    const val INT = "kotlin.Int"
    const val LONG = "kotlin.Long"
    const val SHORT = "kotlin.Short"
    const val BYTE = "kotlin.Byte"
    const val FLOAT = "kotlin.Float"
    const val DOUBLE = "kotlin.Double"
    const val BOOLEAN = "kotlin.Boolean"
    const val STRING = "kotlin.String"

    val ALL = listOf(INT, LONG, SHORT, BYTE, FLOAT, DOUBLE, BOOLEAN, STRING)

    fun isPrimitive(qualifiedName: String): Boolean = qualifiedName in ALL
}

/**
 * Checks if this type is a Godot builtin type (Vector2, Vector3, Color, etc.).
 */
fun TypeInfo.isGodotBuiltin(): Boolean = qualifiedName.startsWith("io.github.kingg22.godot.api.builtin.")
