package io.github.kingg22.godot.codegen.extensionapi.impl.knative.generators

import com.squareup.kotlinpoet.BYTE
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.LONG
import com.squareup.kotlinpoet.SHORT
import com.squareup.kotlinpoet.U_BYTE
import com.squareup.kotlinpoet.U_INT
import com.squareup.kotlinpoet.U_LONG
import com.squareup.kotlinpoet.U_SHORT
import io.github.kingg22.godot.codegen.extensionapi.Context
import io.github.kingg22.godot.codegen.extensionapi.impl.noop.EmptyContext
import io.github.kingg22.godot.codegen.models.extensionapi.GlobalConstant
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.function.Executable

class NativeGlobalConstantGeneratorTest {
    private val generator = NativeGlobalConstantGenerator()
    private val testContext: Context = EmptyContext()

    @Test
    fun `when each known global constant is generated, then has the expected narrowed type and bound reference`() {
        assertAll(
            KNOWN_CONSTANTS.map { (constant, expectedType, expectedBoundRef) ->
                val property = context(testContext) {
                    generator.generateProperties(listOf(constant)).single()
                }

                Executable {
                    assertEquals(constant.name, property.name)
                    assertEquals(expectedType, property.type, "Unexpected type for ${constant.name}")
                    assertTrue(
                        property.initializer.toString().endsWith(expectedBoundRef),
                        "Expected initializer of ${constant.name} to end with '$expectedBoundRef', " +
                            "was '${property.initializer}'",
                    )
                }
            },
        )
    }

    @Test
    fun `when generating INT64_MIN, then does not emit the raw literal that overflows Long`() {
        // Regression test: '-9223372036854775808' overflows Long's positive range by one before
        // the unary minus applies, so `val x: Long = -9223372036854775808` fails to compile.
        val property = context(testContext) {
            generator.generateProperties(listOf(GlobalConstant("INT64_MIN", Long.MIN_VALUE))).single()
        }

        assertEquals(LONG, property.type)
        assertFalse(property.initializer.toString().contains("9223372036854775808"))
        assertTrue(property.initializer.toString().endsWith("Long.MIN_VALUE"))
    }

    @Test
    fun `when a known name's declared value doesn't match Kotlin's bound, then throws`() {
        val bogus = GlobalConstant("INT8_MAX", value = 100L)

        val exception = assertThrows<IllegalStateException> {
            context(testContext) {
                val _ = generator.generateProperties(listOf(bogus))
            }
        }

        assertTrue(exception.message?.contains("INT8_MAX") == true)
    }

    @Test
    fun `when a constant name doesn't match the known naming, then falls back to Long with its raw value`() {
        val unknown = GlobalConstant("SOME_FUTURE_CONSTANT", value = 42L)

        val property = context(testContext) {
            generator.generateProperties(listOf(unknown)).single()
        }

        assertEquals(LONG, property.type)
        assertEquals("42", property.initializer.toString())
    }

    @Test
    fun `when an unmatched name's value is Long MIN_VALUE, then the fallback also avoids the raw literal`() {
        val unknown = GlobalConstant("SOME_FUTURE_MIN", value = Long.MIN_VALUE)

        val property = context(testContext) {
            generator.generateProperties(listOf(unknown)).single()
        }

        assertEquals(LONG, property.type)
        assertTrue(property.initializer.toString().endsWith("Long.MIN_VALUE"))
    }

    @Test
    fun `when no constants are given, then returns an empty list`() {
        val properties = context(testContext) { generator.generateProperties(emptyList()) }

        assertTrue(properties.isEmpty())
    }
}

private val KNOWN_CONSTANTS = listOf(
    Triple(GlobalConstant("UINT8_MAX", 255L), U_BYTE, "UByte.MAX_VALUE"),
    Triple(GlobalConstant("UINT16_MAX", 65535L), U_SHORT, "UShort.MAX_VALUE"),
    Triple(GlobalConstant("UINT32_MAX", 4294967295L), U_INT, "UInt.MAX_VALUE"),
    Triple(GlobalConstant("INT8_MIN", -128L), BYTE, "Byte.MIN_VALUE"),
    Triple(GlobalConstant("INT8_MAX", 127L), BYTE, "Byte.MAX_VALUE"),
    Triple(GlobalConstant("INT16_MIN", -32768L), SHORT, "Short.MIN_VALUE"),
    Triple(GlobalConstant("INT16_MAX", 32767L), SHORT, "Short.MAX_VALUE"),
    Triple(GlobalConstant("INT32_MIN", -2147483648L), INT, "Int.MIN_VALUE"),
    Triple(GlobalConstant("INT32_MAX", 2147483647L), INT, "Int.MAX_VALUE"),
    Triple(GlobalConstant("INT64_MIN", Long.MIN_VALUE), LONG, "Long.MIN_VALUE"),
    Triple(GlobalConstant("INT64_MAX", Long.MAX_VALUE), LONG, "Long.MAX_VALUE"),
    // U_LONG has no real-world entry (UINT64_MAX can't fit Godot's int64 JSON field), but the
    // naming/typing path is still reachable and worth covering.
    Triple(GlobalConstant("UINT64_MAX", -1L), U_LONG, "ULong.MAX_VALUE"),
)
