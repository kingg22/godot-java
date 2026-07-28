package io.github.kingg22.kogot.processor.model

/** A Godot engine virtual method overridden by a `@Godot` user class, resolved via the KSP override chain. */
data class VirtualMethodOverride(
    val godotName: String,
    val enginePackageName: String,
    val engineClassShortName: String,
)
