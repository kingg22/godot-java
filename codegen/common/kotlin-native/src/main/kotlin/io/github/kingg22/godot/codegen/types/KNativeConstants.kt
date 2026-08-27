package io.github.kingg22.godot.codegen.types

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.MemberName

val COPAQUE_POINTER = ClassName("kotlinx.cinterop", "COpaquePointer")
val C_POINTER = ClassName("kotlinx.cinterop", "CPointer")
val C_POINTER_VAR = ClassName("kotlinx.cinterop", "CPointerVar")
val C_POINTER_VAR_OF = ClassName("kotlinx.cinterop", "CPointerVarOf")
val C_OPAQUE_POINTER_VAR = ClassName("kotlinx.cinterop", "COpaquePointerVar")
val C_STRUCT_VAR = ClassName("kotlinx.cinterop", "CStructVar")
val C_STRUCT_VAR_TYPE = ClassName("kotlinx.cinterop", "CStructVar", "Type")
val C_ARRAY_POINTER = ClassName("kotlinx.cinterop", "CArrayPointer")
val C_NATIVE_PTR = ClassName("kotlinx.cinterop", "NativePtr")
val C_FUNCTION = ClassName("kotlinx.cinterop", "CFunction")
val C_NAME_ANNOTATION = ClassName("kotlin.native", "CName")
val INTERPRET_C_POINTER = MemberName("kotlinx.cinterop", "interpretCPointer")

// CVar types (used for pointer targets)
val BYTE_VAR = ClassName("kotlinx.cinterop", "ByteVar")
val SHORT_VAR = ClassName("kotlinx.cinterop", "ShortVar")
val INT_VAR = ClassName("kotlinx.cinterop", "IntVar")
val LONG_VAR = ClassName("kotlinx.cinterop", "LongVar")
val U_BYTE_VAR = ClassName("kotlinx.cinterop", "UByteVar")
val U_SHORT_VAR = ClassName("kotlinx.cinterop", "UShortVar")
val U_INT_VAR = ClassName("kotlinx.cinterop", "UIntVar")
val U_LONG_VAR = ClassName("kotlinx.cinterop", "ULongVar")
val FLOAT_VAR = ClassName("kotlinx.cinterop", "FloatVar")
val DOUBLE_VAR = ClassName("kotlinx.cinterop", "DoubleVar")

val INT16_T = ClassName("platform.posix", "int16_t")
val INT32_T = ClassName("platform.posix", "int32_t")
val INT64_T = ClassName("platform.posix", "int64_t")
val UINT8_T = ClassName("platform.posix", "uint8_t")
val UINT16_T = ClassName("platform.posix", "uint16_t")
val UINT32_T = ClassName("platform.posix", "uint32_t")
val UINT64_T = ClassName("platform.posix", "uint64_t")
val SIZE_T = ClassName("platform.posix", "size_t")
val POSIX_FLOAT = ClassName("platform.posix", "float")
val POSIX_DOUBLE = ClassName("platform.posix", "double")
val POSIX_WCHAR_T = ClassName("platform.posix", "wchar_t")

val memScoped = MemberName("kotlinx.cinterop", "memScoped")
val cinteropCstr = MemberName("kotlinx.cinterop", "cstr", true)
val cinteropPtr = MemberName("kotlinx.cinterop", "ptr", true)
val cinteropAlloc = MemberName("kotlinx.cinterop", "alloc", true)
val cinteropAllocArray = MemberName("kotlinx.cinterop", "allocArray", true)
val cinteropReinterpret = MemberName("kotlinx.cinterop", "reinterpret", true)
val cinteropInvoke = MemberName("kotlinx.cinterop", "invoke", true)
val cinteropValue = MemberName("kotlinx.cinterop", "value", true)
val cinteropPointed = MemberName("kotlinx.cinterop", "pointed", true)
val cinteropGet = MemberName("kotlinx.cinterop", "get", true)
val cinteropStaticCFunction = MemberName("kotlinx.cinterop", "staticCFunction")
val cinteropNativeHeap = ClassName("kotlinx.cinterop", "nativeHeap")
val cinteropStrUtf16 = MemberName("kotlinx.cinterop", "utf16", true)
val cinteropStrUtf32 = MemberName("kotlinx.cinterop", "utf32", true)
val cinteropToKStrFromUtf8 = MemberName("kotlinx.cinterop", "toKStringFromUtf8", true)
val cinteropToKStrFromUtf16 = MemberName("kotlinx.cinterop", "toKStringFromUtf16", true)
val cinteropToKStrFromUtf32 = MemberName("kotlinx.cinterop", "toKStringFromUtf32", true)

val GODOT_VIRTUAL_METHOD = ClassName("io.github.kingg22.godot.api.internal", "GodotVirtualMethod")
val KOTLIN_NATIVE_CLEANER = ClassName("kotlin.native.ref", "Cleaner")

val PRIMITIVE_NUMERIC_TYPES = setOf(
    "int8_t", "int8",
    "short", "int16_t", "int16",
    "int", "int32_t", "int32",
    "long long", "int64_t", "int64", "long", "intptr_t",
    "float", "double",
)
