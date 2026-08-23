package io.github.kingg22.godot.internal.script

import io.github.kingg22.godot.internal.binding.InternalBinding

/**
 * Runtime-populated map from a `.kt` source file path to the already-compiled `@Godot` class it
 * declares (issue #42 — Kotlin as a Godot script language).
 *
 * Kotlin/Native is AOT-compiled, so there is nothing to parse at runtime: each KSP-processed project
 * feeds its own compile-time-generated `ScriptFileRegistry` (see `GodotBindingGenerator`) into this
 * shared registry once, from its generated `onInitScene()`.
 */
@InternalBinding
public object KotlinScriptRegistry {

    /** The file extension `.kt` files are recognized under as a Godot script resource (issue #42). */
    public const val KOTLIN_SCRIPT_EXTENSION: String = "kt"

    /**
     * An addressable `@Godot` class for a script file: the Godot parent class it registers under, plus
     * its `@Export` properties/methods — reusing the same `<Class>_Binding` trampolines ClassDB
     * registration already generated, so [KotlinScriptInstance] can answer script-instance
     * `get`/`set`/`call` requests without a second, duplicated dispatch mechanism.
     */
    public data class Entry(
        val className: String,
        val baseClassName: String,
        val properties: List<ScriptPropertyDescriptor> = emptyList(),
        val methods: List<ScriptMethodDescriptor> = emptyList(),
    )

    private val entries = mutableMapOf<String, Entry>()

    public fun registerAll(from: Map<String, Entry>) {
        entries.putAll(from)
    }

    public operator fun get(path: String): Entry? = entries[path]

    public operator fun contains(path: String): Boolean = entries.containsKey(path)
}
