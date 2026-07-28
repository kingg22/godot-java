package io.github.kingg22.godot.api.annotations

/**
 * Mark the following method as callable from GDScript, the editor, or other Godot APIs
 * (e.g. `Object.call()`, signal connections, `Callable`).
 *
 * Parameter and return types must be representable as a [io.github.kingg22.godot.api.builtin.Variant]
 * (primitives or Godot builtin types). Default parameter values are not supported yet.
 */
@Retention(SOURCE)
@Target(FUNCTION)
@MustBeDocumented
public annotation class ExportMethod
