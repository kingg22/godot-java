package io.github.kingg22.godot.internal.binding

import io.github.kingg22.godot.api.EnumMask
import io.github.kingg22.godot.api.MethodFlags
import io.github.kingg22.godot.api.PropertyHint
import io.github.kingg22.godot.api.PropertyUsageFlags
import io.github.kingg22.godot.api.builtin.Variant
import io.github.kingg22.godot.api.builtin.internal.toGDE
import io.github.kingg22.godot.api.builtin.toStringName
import io.github.kingg22.godot.internal.ffi.FALSE
import io.github.kingg22.godot.internal.ffi.GDExtensionBool
import io.github.kingg22.godot.internal.ffi.GDExtensionClassMethodArgumentMetadata
import io.github.kingg22.godot.internal.ffi.GDExtensionClassMethodCall
import io.github.kingg22.godot.internal.ffi.GDExtensionClassMethodInfo
import io.github.kingg22.godot.internal.ffi.GDExtensionPropertyInfo
import io.github.kingg22.godot.internal.ffi.TRUE
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cValue
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr

/**
 * Represents a method argument for ClassDB registration.
 *
 * @param type The Variant type of the argument
 * @param name The argument name
 * @param hint Property hint for the argument
 * @param hintString Additional hint string
 * @param usage Property usage flags
 */
@InternalBinding
public data class MethodArgument(
    val type: Variant.Type,
    val name: String,
    val hint: EnumMask<PropertyHint> = EnumMask.of(PropertyHint.NONE),
    val hintString: String = "",
    val usage: EnumMask<PropertyUsageFlags> = EnumMask.of(PropertyUsageFlags.DEFAULT),
)

/**
 * Registers a method on an extension class in ClassDB.
 *
 * This is the SwiftGodot pattern where:
 * 1. First register getter method via registerMethod
 * 2. Then register setter method via registerMethod (if mutable)
 * 3. Finally call registerProperty to link them as a property
 *
 * @param className The Godot class name
 * @param methodName The name of the method to register
 * @param flags Bitfield Method flags (e.g., GDEXTENSION_METHOD_FLAG_NORMAL)
 * @param hasReturnValue Whether the method returns a value
 * @param returnType The Variant type of the return value
 * @param arguments List of method arguments
 * @param callFunction The function pointer to call when the method is invoked
 */
@InternalBinding
public fun registerMethod(
    className: String,
    methodName: String,
    flags: EnumMask<MethodFlags> = EnumMask.of(MethodFlags.DEFAULT),
    hasReturnValue: Boolean = false,
    returnType: Variant.Type = Variant.Type.NIL,
    arguments: List<MethodArgument> = emptyList(),
    callFunction: GDExtensionClassMethodCall,
) {
    // Pre-compute pointers that will be used in cValue
    val classNameStr = className.toStringName()
    val methodNameStr = methodName.toStringName()
    val flagsUInt = flags.value.toUInt()
    val returnTypeGde = returnType.toGDE()
    val hasReturnGde = if (hasReturnValue) GDExtensionBool.TRUE else GDExtensionBool.FALSE

    classNameStr.use { classNameStr ->
        methodNameStr.use { methodNameStr ->
            memScoped {
                // Pre-allocate return value info if needed
                val returnValueInfo = if (hasReturnValue) {
                    alloc<GDExtensionPropertyInfo> {
                        type = returnTypeGde
                        name = methodNameStr.rawPtr
                        class_name = classNameStr.rawPtr
                        hint = PropertyHint.NONE.value.toUInt()
                        hint_string = "".toStringName().rawPtr
                        usage = PropertyUsageFlags.DEFAULT.value.toUInt()
                    }.ptr
                } else {
                    null
                }

                // Pre-allocate argument infos if needed
                val argumentsInfo = if (arguments.isNotEmpty()) {
                    allocArray<GDExtensionPropertyInfo>(arguments.size) { index ->
                        val arg = arguments[index]

                        this.type = arg.type.toGDE()
                        this.name = arg.name.toStringName().rawPtr
                        this.class_name = classNameStr.rawPtr
                        this.hint = arg.hint.value.toUInt()
                        this.hint_string = arg.hintString.toStringName().rawPtr
                        this.usage = arg.usage.value.toUInt()
                    }
                } else {
                    null
                }

                // Pre-allocate arguments metadata
                val argumentsMetadata = if (arguments.isNotEmpty()) {
                    allocArray<GDExtensionClassMethodArgumentMetadata.Var>(arguments.size) { _ ->
                        this.value = GDExtensionClassMethodArgumentMetadata.GDEXTENSION_METHOD_ARGUMENT_METADATA_NONE
                    }
                } else {
                    null
                }

                // Build method info with pre-allocated pointers
                val methodInfo = cValue<GDExtensionClassMethodInfo> {
                    name = methodNameStr.rawPtr
                    method_userdata = null
                    call_func = callFunction
                    ptrcall_func = null
                    method_flags = flagsUInt
                    has_return_value = hasReturnGde
                    return_value_info = returnValueInfo
                    return_value_metadata = GDEXTENSION_METHOD_ARGUMENT_METADATA_NONE
                    argument_count = arguments.size.toUInt()
                    arguments_info = argumentsInfo
                    arguments_metadata = argumentsMetadata
                    default_argument_count = 0u
                    default_arguments = null
                }

                ClassDBBinding.instance.registerExtensionClassMethodRaw(
                    BindingProcAddressHolder.library,
                    classNameStr.rawPtr,
                    methodInfo.ptr,
                )
            }
        }
    }
}

/**
 * Registers a property getter method.
 *
 * @param className The Godot class name
 * @param methodName The name of the property
 * @param returnType The Variant type of the return value
 * @param callFunction The function pointer to call when the getter is invoked
 */
@InternalBinding
public fun registerMethodGetter(
    className: String,
    methodName: String,
    returnType: Variant.Type,
    callFunction: GDExtensionClassMethodCall,
) {
    registerMethod(
        className = className,
        methodName = methodName,
        hasReturnValue = true,
        returnType = returnType,
        arguments = emptyList(),
        callFunction = callFunction,
    )
}

/**
 * Registers a property setter method.
 *
 * @param className The Godot class name
 * @param methodName The name of the method
 * @param valueType The Variant type of the value being set
 * @param callFunction The function pointer to call when the setter is invoked
 */
@InternalBinding
public fun registerMethodSetter(
    className: String,
    methodName: String,
    valueType: Variant.Type,
    callFunction: GDExtensionClassMethodCall,
) {
    registerMethod(
        className = className,
        methodName = methodName,
        hasReturnValue = false,
        returnType = Variant.Type.NIL,
        arguments = listOf(MethodArgument(valueType, "value")),
        callFunction = callFunction,
    )
}
