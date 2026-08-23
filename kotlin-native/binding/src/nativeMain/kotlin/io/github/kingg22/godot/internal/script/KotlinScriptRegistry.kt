package io.github.kingg22.godot.internal.script

import io.github.kingg22.godot.internal.binding.InternalBinding

/** The file extension `.kt` files are recognized under as a Godot script resource (issue #42). */
public const val KOTLIN_SCRIPT_EXTENSION: String = "kt"

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
    /** An addressable `@Godot` class for a script file, and the Godot parent class it registers under. */
    public data class Entry(val className: String, val baseClassName: String)

    private val entries = mutableMapOf<String, Entry>()

    public fun register(path: String, className: String, baseClassName: String) {
        entries[path] = Entry(className, baseClassName)
    }

    public fun find(path: String): Entry? = entries[path]

    public fun contains(path: String): Boolean = entries.containsKey(path)
}
