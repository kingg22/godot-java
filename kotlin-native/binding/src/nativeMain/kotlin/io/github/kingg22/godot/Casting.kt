package io.github.kingg22.godot

import io.github.kingg22.godot.api.builtin.toStringName
import io.github.kingg22.godot.api.core.GodotObject
import io.github.kingg22.godot.internal.binding.InternalBinding
import io.github.kingg22.godot.internal.binding.materialize
import io.github.kingg22.godot.internal.ffi.GDExtensionObjectPtr

/**
 * `ClassDBBinding.getClassTagRaw` + `ObjectBinding.castToRaw` (GDExtension's `classdb_get_class_tag` /
 * `object_cast_to`) were deprecated in Godot 4.7: "Use the `is_class` method on `Object` to check if an
 * object can be cast instead. If true, the previous pointer can be reinterpreted as a pointer to the
 * target type." There is no pointer-level cast to perform on the Kotlin/Native side either: a
 * [GDExtensionObjectPtr] is already an opaque, type-erased pointer, so "reinterpreting" it is just
 * handing the same pointer value to [factory].
 *
 * [factory] only ever runs for a native pointer's *first* sighting — [materialize] resolves every
 * later cast of the same pointer to the one already-built Kotlin wrapper (see issue #114) instead of
 * fabricating a new one, which for a stateful `@Godot` class would silently discard its live fields,
 * and for a `RefCounted` would leak the engine reference this cast implicitly receives.
 */
@PublishedApi
@OptIn(InternalBinding::class)
internal fun <Convert : GodotObject> castToInternal(
    rawPtr: GDExtensionObjectPtr,
    godotClassName: String,
    factory: (nativePtr: GDExtensionObjectPtr) -> Convert,
): Convert? {
    val isRequestedClass = godotClassName.toStringName().use { className ->
        GodotObject(rawPtr).isClass(className)
    }
    if (!isRequestedClass) return null

    return materialize(rawPtr, factory)
}

// -----------------------------------------------------------------------------
// GDExtensionObjectPtr overloads
// -----------------------------------------------------------------------------

public inline fun <reified Convert : GodotObject> GDExtensionObjectPtr.castTo(
    noinline factory: (nativePtr: GDExtensionObjectPtr) -> Convert,
): Convert = castTo(Convert::class.simpleName!!, factory)

public fun <Convert : GodotObject> GDExtensionObjectPtr.castTo(
    godotClassName: String,
    factory: (nativePtr: GDExtensionObjectPtr) -> Convert,
): Convert = castToInternal(this, godotClassName, factory)
    ?: throw ClassCastException("Failed to cast pointer to $godotClassName")

public inline fun <reified Convert : GodotObject> GDExtensionObjectPtr.castToOrNull(
    noinline factory: (nativePtr: GDExtensionObjectPtr) -> Convert,
): Convert? = castToInternal(this, Convert::class.simpleName!!, factory)

public fun <Convert : GodotObject> GDExtensionObjectPtr.castToOrNull(
    godotClassName: String,
    factory: (nativePtr: GDExtensionObjectPtr) -> Convert,
): Convert? = castToInternal(this, godotClassName, factory)

// -----------------------------------------------------------------------------
// GodotObject overloads (Actual)
// -----------------------------------------------------------------------------

public inline fun <Actual : GodotObject, reified Convert : GodotObject> Actual.castTo(
    noinline factory: (nativePtr: GDExtensionObjectPtr) -> Convert,
): Convert = rawPtr.castTo(factory)

public inline fun <Actual : GodotObject, reified Convert : GodotObject> Actual.castTo(
    godotClassName: String,
    noinline factory: (nativePtr: GDExtensionObjectPtr) -> Convert,
): Convert = rawPtr.castTo(godotClassName, factory)

public inline fun <Actual : GodotObject, reified Convert : GodotObject> Actual.castToOrNull(
    noinline factory: (nativePtr: GDExtensionObjectPtr) -> Convert,
): Convert? = rawPtr.castToOrNull(factory)

public inline fun <Actual : GodotObject, reified Convert : GodotObject> Actual.castToOrNull(
    godotClassName: String,
    noinline factory: (nativePtr: GDExtensionObjectPtr) -> Convert,
): Convert? = rawPtr.castToOrNull(godotClassName, factory)
