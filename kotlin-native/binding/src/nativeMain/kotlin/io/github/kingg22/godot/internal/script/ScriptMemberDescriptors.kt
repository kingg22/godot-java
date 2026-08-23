package io.github.kingg22.godot.internal.script

import io.github.kingg22.godot.api.builtin.Variant
import io.github.kingg22.godot.internal.ffi.GDExtensionClassMethodCall

/**
 * An `@Export` property of a `@Godot` class, referencing the same `GDExtensionClassMethodCall`
 * trampolines `<Class>_Binding` already generated for ClassDB registration (issue #42 — reused, not
 * duplicated, by [KotlinScriptInstance] to answer script-instance `get`/`set` calls by property name).
 */
public data class ScriptPropertyDescriptor(
    val name: String,
    val type: Variant.Type,
    val getter: GDExtensionClassMethodCall,
    val setter: GDExtensionClassMethodCall?,
)

/**
 * An `@ExportMethod` of a `@Godot` class, referencing the same `GDExtensionClassMethodCall` trampoline
 * `<Class>_Binding` already generated for ClassDB registration (issue #42 — reused by
 * [KotlinScriptInstance] to answer script-instance `call` requests by method name).
 */
public data class ScriptMethodDescriptor(
    val name: String,
    val argumentCount: Int,
    val call: GDExtensionClassMethodCall,
)
