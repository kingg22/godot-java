package io.github.kingg22.kogot.processor.resolver

import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FLOAT
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.STRING
import io.github.kingg22.kogot.processor.generators.kotlin.VARIANT_CLASS_NAME
import io.github.kingg22.kogot.processor.generators.kotlin.VECTOR2_CLASS_NAME
import io.github.kingg22.kogot.processor.generators.kotlin.VECTOR3_CLASS_NAME
import io.github.kingg22.kogot.processor.model.GodotPrimitives

/**
 * Maps Kotlin/Godot qualified type names to Godot's `Variant.Type` enum entry names,
 * and maps those entry names back to the KotlinPoet type used to read/write the value.
 */
interface VariantTypeResolver {
    /** Resolves a Kotlin qualified type name to a `Variant.Type` enum entry name, e.g. "INT", "VECTOR2". */
    fun resolve(qualifiedName: String): String

    /** Resolves a `Variant.Type` enum entry name to the KotlinPoet type used for `Variant.getValueOrNull<T>()`. */
    fun toKotlinPoetType(variantType: String): ClassName
}

class DefaultVariantTypeResolver : VariantTypeResolver {
    override fun resolve(qualifiedName: String): String = when (qualifiedName) {
        GodotPrimitives.INT, GodotPrimitives.LONG -> "INT"
        GodotPrimitives.FLOAT, GodotPrimitives.DOUBLE -> "FLOAT"
        GodotPrimitives.BOOLEAN -> "BOOL"
        GodotPrimitives.STRING -> "STRING"
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

    override fun toKotlinPoetType(variantType: String): ClassName = when (variantType) {
        "INT" -> INT
        "FLOAT" -> FLOAT
        "BOOL" -> BOOLEAN
        "STRING" -> STRING
        "VECTOR2" -> VECTOR2_CLASS_NAME
        "VECTOR3" -> VECTOR3_CLASS_NAME
        else -> VARIANT_CLASS_NAME
    }
}
