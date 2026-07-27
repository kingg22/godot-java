package io.github.kingg22.godot

import io.github.kingg22.godot.api.GodotNative
import io.github.kingg22.godot.api.builtin.toStringName
import io.github.kingg22.godot.api.core.GodotObject
import io.github.kingg22.godot.internal.binding.ClassDBBinding
import io.github.kingg22.godot.internal.binding.ObjectBinding
import io.github.kingg22.godot.internal.ffi.GDExtensionObjectPtr
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

@Suppress("DEPRECATION")
@PublishedApi
internal inline fun <Convert : GodotNative> castToInternal(
    rawPtr: GDExtensionObjectPtr,
    godotClassName: String,
    factory: (nativePtr: GDExtensionObjectPtr) -> Convert,
): Convert? {
    contract { callsInPlace(factory, InvocationKind.AT_MOST_ONCE) }

    val classTagPtr = godotClassName.toStringName().use { str ->
        ClassDBBinding
            .getClassTagRaw(str.rawPtr)
            ?: return null
    }

    val castedPtr = ObjectBinding.castToRaw(
        rawPtr,
        classTagPtr,
    ) ?: return null

    // FIXME must getInstance of the already kotlin instance instead of creating a new one!!
    // But only create if the instance is not already a kotlin instance
    return factory(castedPtr)
}

// -----------------------------------------------------------------------------
// GDExtensionObjectPtr overloads
// -----------------------------------------------------------------------------

public inline fun <reified Convert : GodotNative> GDExtensionObjectPtr.castTo(
    factory: (nativePtr: GDExtensionObjectPtr) -> Convert,
): Convert {
    contract { callsInPlace(factory, InvocationKind.AT_MOST_ONCE) }

    return castTo(Convert::class.simpleName!!, factory)
}

public inline fun <Convert : GodotNative> GDExtensionObjectPtr.castTo(
    godotClassName: String,
    factory: (nativePtr: GDExtensionObjectPtr) -> Convert,
): Convert {
    contract { callsInPlace(factory, InvocationKind.AT_MOST_ONCE) }

    return castToInternal(this, godotClassName, factory)
        ?: throw ClassCastException("Failed to cast pointer to $godotClassName")
}

public inline fun <reified Convert : GodotNative> GDExtensionObjectPtr.castToOrNull(
    factory: (nativePtr: GDExtensionObjectPtr) -> Convert,
): Convert? {
    contract { callsInPlace(factory, InvocationKind.AT_MOST_ONCE) }

    return castToInternal(this, Convert::class.simpleName!!, factory)
}

public inline fun <Convert : GodotNative> GDExtensionObjectPtr.castToOrNull(
    godotClassName: String,
    factory: (nativePtr: GDExtensionObjectPtr) -> Convert,
): Convert? {
    contract { callsInPlace(factory, InvocationKind.AT_MOST_ONCE) }

    return castToInternal(this, godotClassName, factory)
}

// -----------------------------------------------------------------------------
// GodotObject overloads (Actual)
// -----------------------------------------------------------------------------

public inline fun <Actual : GodotObject, reified Convert : GodotNative> Actual.castTo(
    factory: (nativePtr: GDExtensionObjectPtr) -> Convert,
): Convert {
    contract { callsInPlace(factory, InvocationKind.AT_MOST_ONCE) }

    return rawPtr.castTo(factory)
}

public inline fun <Actual : GodotObject, reified Convert : GodotNative> Actual.castTo(
    godotClassName: String,
    factory: (nativePtr: GDExtensionObjectPtr) -> Convert,
): Convert {
    contract { callsInPlace(factory, InvocationKind.AT_MOST_ONCE) }

    return rawPtr.castTo(godotClassName, factory)
}

public inline fun <Actual : GodotObject, reified Convert : GodotNative> Actual.castToOrNull(
    factory: (nativePtr: GDExtensionObjectPtr) -> Convert,
): Convert? {
    contract { callsInPlace(factory, InvocationKind.AT_MOST_ONCE) }

    return rawPtr.castToOrNull(factory)
}

public inline fun <Actual : GodotObject, reified Convert : GodotNative> Actual.castToOrNull(
    godotClassName: String,
    factory: (nativePtr: GDExtensionObjectPtr) -> Convert,
): Convert? {
    contract { callsInPlace(factory, InvocationKind.AT_MOST_ONCE) }

    return rawPtr.castToOrNull(godotClassName, factory)
}
