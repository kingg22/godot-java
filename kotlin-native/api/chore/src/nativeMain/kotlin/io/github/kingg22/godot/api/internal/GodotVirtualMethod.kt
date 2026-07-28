package io.github.kingg22.godot.api.internal

import org.jetbrains.annotations.ApiStatus

/** Marks a generated `open fun` stub as a dispatchable Godot engine virtual method named [godotName]. */
@ApiStatus.Internal
@Retention(BINARY)
@Target(FUNCTION)
public annotation class GodotVirtualMethod(val godotName: String)
