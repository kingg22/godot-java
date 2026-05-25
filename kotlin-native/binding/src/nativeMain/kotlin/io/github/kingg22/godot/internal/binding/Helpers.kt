package io.github.kingg22.godot.internal.binding

import io.github.kingg22.godot.internal.ffi.GDExtensionCallError
import io.github.kingg22.godot.internal.ffi.GDExtensionCallErrorType
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.pointed

@InternalBinding
public fun CPointer<GDExtensionCallError>?.write(
    error: GDExtensionCallErrorType = GDEXTENSION_CALL_OK,
    argument: Int = 0,
    expected: Int = 0,
) {
    if (this == null) return
    this.pointed.error = error
    this.pointed.argument = argument
    this.pointed.expected = expected
}
