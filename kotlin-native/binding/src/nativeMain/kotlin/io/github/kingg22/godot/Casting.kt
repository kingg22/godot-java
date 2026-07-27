package io.github.kingg22.godot

import io.github.kingg22.godot.api.builtin.toStringName
import io.github.kingg22.godot.api.core.GodotObject
import io.github.kingg22.godot.internal.ffi.GDExtensionObjectPtr
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/**
 * `ClassDBBinding.getClassTagRaw` + `ObjectBinding.castToRaw` (GDExtension's `classdb_get_class_tag` /
 * `object_cast_to`) were deprecated in Godot 4.7: "Use the `is_class` method on `Object` to check if an
 * object can be cast instead. If true, the previous pointer can be reinterpreted as a pointer to the
 * target type." There is no pointer-level cast to perform on the Kotlin/Native side either: a
 * [GDExtensionObjectPtr] is already an opaque, type-erased pointer, so "reinterpreting" it is just
 * handing the same pointer value to [factory].
 *
 * FIXME [factory] always constructs a new Kotlin wrapper instance instead of reusing one already bound
 * to that native pointer (see https://github.com/kingg22/kogot/issues/120 discussion).
 */
@PublishedApi
internal inline fun <Convert : GodotObject> castToInternal(
    rawPtr: GDExtensionObjectPtr,
    godotClassName: String,
    factory: (nativePtr: GDExtensionObjectPtr) -> Convert,
): Convert? {
    contract { callsInPlace(factory, InvocationKind.AT_MOST_ONCE) }

    val isRequestedClass = godotClassName.toStringName().use { className ->
        GodotObject(rawPtr).isClass(className)
    }
    if (!isRequestedClass) return null

    return factory(rawPtr)
}

// -----------------------------------------------------------------------------
// GDExtensionObjectPtr overloads
// -----------------------------------------------------------------------------

public inline fun <reified Convert : GodotObject> GDExtensionObjectPtr.castTo(
    factory: (nativePtr: GDExtensionObjectPtr) -> Convert,
): Convert {
    contract { callsInPlace(factory, InvocationKind.AT_MOST_ONCE) }

    return castTo(Convert::class.simpleName!!, factory)
}

public inline fun <Convert : GodotObject> GDExtensionObjectPtr.castTo(
    godotClassName: String,
    factory: (nativePtr: GDExtensionObjectPtr) -> Convert,
): Convert {
    contract { callsInPlace(factory, InvocationKind.AT_MOST_ONCE) }

    return castToInternal(this, godotClassName, factory)
        ?: throw ClassCastException("Failed to cast pointer to $godotClassName")
}

public inline fun <reified Convert : GodotObject> GDExtensionObjectPtr.castToOrNull(
    factory: (nativePtr: GDExtensionObjectPtr) -> Convert,
): Convert? {
    contract { callsInPlace(factory, InvocationKind.AT_MOST_ONCE) }

    return castToInternal(this, Convert::class.simpleName!!, factory)
}

public inline fun <Convert : GodotObject> GDExtensionObjectPtr.castToOrNull(
    godotClassName: String,
    factory: (nativePtr: GDExtensionObjectPtr) -> Convert,
): Convert? {
    contract { callsInPlace(factory, InvocationKind.AT_MOST_ONCE) }

    return castToInternal(this, godotClassName, factory)
}

// -----------------------------------------------------------------------------
// GodotObject overloads (Actual)
// -----------------------------------------------------------------------------

public inline fun <Actual : GodotObject, reified Convert : GodotObject> Actual.castTo(
    factory: (nativePtr: GDExtensionObjectPtr) -> Convert,
): Convert {
    contract { callsInPlace(factory, InvocationKind.AT_MOST_ONCE) }

    return rawPtr.castTo(factory)
}

public inline fun <Actual : GodotObject, reified Convert : GodotObject> Actual.castTo(
    godotClassName: String,
    factory: (nativePtr: GDExtensionObjectPtr) -> Convert,
): Convert {
    contract { callsInPlace(factory, InvocationKind.AT_MOST_ONCE) }

    return rawPtr.castTo(godotClassName, factory)
}

public inline fun <Actual : GodotObject, reified Convert : GodotObject> Actual.castToOrNull(
    factory: (nativePtr: GDExtensionObjectPtr) -> Convert,
): Convert? {
    contract { callsInPlace(factory, InvocationKind.AT_MOST_ONCE) }

    return rawPtr.castToOrNull(factory)
}

public inline fun <Actual : GodotObject, reified Convert : GodotObject> Actual.castToOrNull(
    godotClassName: String,
    factory: (nativePtr: GDExtensionObjectPtr) -> Convert,
): Convert? {
    contract { callsInPlace(factory, InvocationKind.AT_MOST_ONCE) }

    return rawPtr.castToOrNull(godotClassName, factory)
}
