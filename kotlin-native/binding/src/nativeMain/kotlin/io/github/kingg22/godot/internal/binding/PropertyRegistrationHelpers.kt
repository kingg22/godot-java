package io.github.kingg22.godot.internal.binding

import io.github.kingg22.godot.api.EnumMask
import io.github.kingg22.godot.api.PropertyHint
import io.github.kingg22.godot.api.PropertyUsageFlags
import io.github.kingg22.godot.api.builtin.Variant
import io.github.kingg22.godot.api.builtin.internal.toGDE
import io.github.kingg22.godot.api.builtin.toStringName
import io.github.kingg22.godot.internal.ffi.GDExtensionPropertyInfo
import kotlinx.cinterop.cValue
import kotlinx.cinterop.memScoped

/**
 * Registers a property on an extension class using getter and setter method names.
 *
 * This is the SwiftGodot pattern where:
 * 1. First register getter method via registerMethod
 * 2. Then register setter method via registerMethod
 * 3. Finally call this function to link them as a property
 *
 * @param className The Godot class name
 * @param propertyName The name of the property
 * @param variantType The GDExtensionVariantType for the property
 * @param hint PropertyHint ordinal
 * @param hintString Additional hint string
 * @param usage PropertyUsageFlags bitfield
 * @param getterMethodName The name of the registered getter method
 * @param setterMethodName The name of the registered setter method (can be null for readonly)
 */
@InternalBinding
public fun registerProperty(
    className: String,
    propertyName: String,
    variantType: Variant.Type,
    getterMethodName: String,
    setterMethodName: String? = null,
    hint: EnumMask<PropertyHint> = EnumMask.of(PropertyHint.NONE),
    hintString: String = "",
    usage: EnumMask<PropertyUsageFlags> = EnumMask.of(PropertyUsageFlags.DEFAULT),
) {
    className.toStringName().use { classNameStr ->
        propertyName.toStringName().use { propertyNameStr ->
            hintString.toStringName().use { hintStringStr ->
                val propertyInfo = cValue<GDExtensionPropertyInfo> {
                    this.type = variantType.toGDE()
                    this.name = propertyNameStr.rawPtr
                    this.class_name = classNameStr.rawPtr
                    this.hint = hint.value.toUInt()
                    this.hint_string = hintStringStr.rawPtr
                    this.usage = usage.value.toUInt()
                }

                memScoped {
                    ClassDBBinding.instance.registerExtensionClassPropertyRaw(
                        BindingProcAddressHolder.library,
                        classNameStr.rawPtr,
                        propertyInfo.ptr,
                        setterMethodName?.toStringName()?.rawPtr,
                        getterMethodName.toStringName().rawPtr,
                    )
                }
            }
        }
    }
}
