package io.github.kingg22.godot.internal.binding

import io.github.kingg22.godot.api.builtin.StringName
import io.github.kingg22.godot.internal.ffi.GDExtensionClassCallVirtual
import io.github.kingg22.godot.internal.ffi.GDExtensionStringNamePtr

/** Resolves the [GDExtensionClassCallVirtual] trampoline for a `getVirtual` query, or `null` if unhandled. */
@InternalBinding
public inline fun resolveVirtualCall(
    funcNamePtr: GDExtensionStringNamePtr?,
    resolve: (StringName) -> GDExtensionClassCallVirtual?,
): GDExtensionClassCallVirtual? {
    if (funcNamePtr == null) return null
    return StringName(funcNamePtr).use(resolve)
}
