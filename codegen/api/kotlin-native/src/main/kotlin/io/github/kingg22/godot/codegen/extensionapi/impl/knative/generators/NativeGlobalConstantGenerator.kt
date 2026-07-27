package io.github.kingg22.godot.codegen.extensionapi.impl.knative.generators

import com.squareup.kotlinpoet.BYTE
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LONG
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.SHORT
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.U_BYTE
import com.squareup.kotlinpoet.U_INT
import com.squareup.kotlinpoet.U_LONG
import com.squareup.kotlinpoet.U_SHORT
import io.github.kingg22.godot.codegen.extensionapi.Context
import io.github.kingg22.godot.codegen.extensionapi.resolver.addKdocIfPresent
import io.github.kingg22.godot.codegen.models.extensionapi.GlobalConstant
import io.github.kingg22.godot.codegen.utils.logger
import io.github.kingg22.godot.codegen.utils.warning

/**
 * Generates `const val` properties for Godot's `global_constants` — as of Godot 4.7 these are
 * exclusively fixed-width integer limits (`UINT8_MAX`, `INT64_MIN`, ...) mirroring C's
 * `<cstdint>` limits, added for GDExtension bindings that need them without hardcoding.
 *
 * Unlike engine/builtin class constants (always emitted as [LONG]), each of these is typed as
 * the *narrowest matching Kotlin type* — `UInt`, `Short`, etc. — derived straight from the
 * `U?INTn_(MIN|MAX)` shape of the constant's own name. That name shape already encodes both the
 * bit width, the signedness, and whether it's the lower or upper bound, and it happens to line
 * up exactly with Kotlin's own `Byte`/`Short`/`Int`/`Long`/`UByte`/`UShort`/`UInt`/`ULong` bounds
 * — so instead of re-deriving a numeric literal from the JSON `value` (which, for `INT64_MIN`,
 * can't even be written as a literal: `-9223372036854775808` overflows `Long`'s positive range
 * by one before the unary minus applies, so `val x: Long = -9223372036854775808` fails to
 * compile), each property is initialized by referencing the matching `MIN_VALUE`/`MAX_VALUE`
 * constant already defined on that Kotlin type.
 *
 * These are added to the `GD` object (see [NativeUtilityFunctionGenerator]) instead of the
 * package root, so names like `INT32_MAX` never sit at file scope next to unrelated top-level
 * declarations or a future Kotlin stdlib addition.
 */
class NativeGlobalConstantGenerator {
    private val logger = logger()

    context(context: Context)
    fun generateProperties(constants: List<GlobalConstant>): List<PropertySpec> = constants.map { constant ->
        val resolved = resolveType(constant)
        PropertySpec
            .builder(constant.name, resolved.type, KModifier.CONST)
            .initializer(resolved.initializer)
            .addKdocIfPresent(constant)
            .build()
    }

    private fun resolveType(constant: GlobalConstant): Resolved {
        val match = NAME_PATTERN.matchEntire(constant.name)
        if (match == null) {
            logger.warning {
                "Global constant '${constant.name}' doesn't match the known 'U?INTn_(MIN|MAX)' " +
                    "naming, defaulting to Long with its raw value"
            }
            return Resolved(LONG, safeLongLiteral(constant.value))
        }

        val (unsignedMarker, bitWidth, bound) = match.destructured
        val isUnsigned = unsignedMarker.isNotEmpty()
        val isMin = bound == "MIN"
        val type = when (bitWidth) {
            "8" -> if (isUnsigned) U_BYTE else BYTE
            "16" -> if (isUnsigned) U_SHORT else SHORT
            "32" -> if (isUnsigned) U_INT else INT
            else -> if (isUnsigned) U_LONG else LONG
        }

        // The constant's name claims it IS this Kotlin type's bound — don't just trust that,
        // check it against the actual bound before substituting it in, so a Godot-side typo or
        // a future name this regex mis-parses can't silently ship the wrong value.
        val expected = kotlinBoundOf(type, isMin)
        check(expected == constant.value) {
            "Global constant '${constant.name}' = ${constant.value} in Godot's JSON, but Kotlin's " +
                "$type.${if (isMin) "MIN_VALUE" else "MAX_VALUE"} = $expected. Refusing to substitute " +
                "a bound that doesn't match the declared value."
        }

        val member = if (isMin) "MIN_VALUE" else "MAX_VALUE"
        return Resolved(type, CodeBlock.of("%T.%N", type, member))
    }

    /** Guards against the `Long.MIN_VALUE` literal-overflow edge case (see class kdoc). */
    private fun safeLongLiteral(value: Long): CodeBlock =
        if (value == Long.MIN_VALUE) CodeBlock.of("%T.%N", LONG, "MIN_VALUE") else CodeBlock.of("%L", value)

    /**
     * The bound Kotlin actually defines for [type], as a raw 64-bit pattern comparable to the
     * `Long` values Godot's JSON uses. Unsigned bounds widen losslessly via `toLong()` except
     * `ULong.MAX_VALUE`, whose all-ones bit pattern reinterprets as `-1L` — which is exactly the
     * comparison we want, since that is also how a `uint64_t` max would have to round-trip through
     * a signed 64-bit JSON field in the first place.
     */
    private fun kotlinBoundOf(type: TypeName, isMin: Boolean): Long = when (type) {
        BYTE -> if (isMin) Byte.MIN_VALUE.toLong() else Byte.MAX_VALUE.toLong()
        SHORT -> if (isMin) Short.MIN_VALUE.toLong() else Short.MAX_VALUE.toLong()
        INT -> if (isMin) Int.MIN_VALUE.toLong() else Int.MAX_VALUE.toLong()
        LONG -> if (isMin) Long.MIN_VALUE else Long.MAX_VALUE
        U_BYTE -> if (isMin) UByte.MIN_VALUE.toLong() else UByte.MAX_VALUE.toLong()
        U_SHORT -> if (isMin) UShort.MIN_VALUE.toLong() else UShort.MAX_VALUE.toLong()
        U_INT -> if (isMin) UInt.MIN_VALUE.toLong() else UInt.MAX_VALUE.toLong()
        U_LONG -> if (isMin) ULong.MIN_VALUE.toLong() else ULong.MAX_VALUE.toLong()
        else -> error("Unreachable: '$type' isn't one of the types resolveType() ever produces")
    }

    private data class Resolved(val type: TypeName, val initializer: CodeBlock)

    private companion object {
        val NAME_PATTERN = Regex("^(U?)INT(8|16|32|64)_(MIN|MAX)$")
    }
}
