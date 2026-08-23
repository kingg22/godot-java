package io.github.kingg22.godot.internal.script

import io.github.kingg22.godot.api.GodotError
import io.github.kingg22.godot.api.builtin.GodotString
import io.github.kingg22.godot.api.builtin.PackedStringArray
import io.github.kingg22.godot.api.builtin.StringName
import io.github.kingg22.godot.api.builtin.Variant
import io.github.kingg22.godot.api.builtin.toGodotString
import io.github.kingg22.godot.api.builtin.toVariant
import io.github.kingg22.godot.api.core.refcounted.ResourceFormatLoader
import io.github.kingg22.godot.api.singleton.ProjectSettings
import io.github.kingg22.godot.internal.binding.InternalBinding
import io.github.kingg22.godot.internal.binding.createInstanceFunc
import io.github.kingg22.godot.internal.script.KotlinScriptRegistry.KOTLIN_SCRIPT_EXTENSION
import kotlinx.cinterop.COpaquePointer

/**
 * Resolves a `.kt` script resource path to a [KotlinScript] wrapping the already-compiled `@Godot`
 * class recorded for that path in [KotlinScriptRegistry] (issue #42).
 *
 * [KotlinScriptRegistry] is keyed by KSP's `filePath`: an absolute filesystem path, since that's all
 * the compile-time processor knows. Godot instead calls `_load`/`_getResourceTypeAsGdStr` with a
 * `res://`-relative virtual path. [ProjectSettings.globalizePath] converts the latter to the former
 * (it is a no-op for paths that are already absolute), so every registry lookup below normalizes
 * through it first — otherwise every `.kt` resource would report as not found.
 */
@InternalBinding
public class KotlinResourceFormatLoader(nativePtr: COpaquePointer) : ResourceFormatLoader(nativePtr) {
    override fun _getRecognizedExtensions(): PackedStringArray {
        val array = PackedStringArray()
        val _ = array.pushBack(KOTLIN_SCRIPT_EXTENSION.toGodotString())
        return array
    }

    override fun _handlesType(type: StringName): Boolean = type.toString() == "Script"

    override fun _getResourceTypeAsGdStr(path: GodotString): GodotString =
        if (KotlinScriptRegistry.contains(globalizedPath(path.toKString()))) "Script".toGodotString() else GodotString()

    override fun _load(path: GodotString, originalPath: GodotString, useSubThreads: Boolean, cacheMode: Int): Variant {
        val normalizedPath = globalizedPath(path.toKString())
        val entry = KotlinScriptRegistry[normalizedPath]
            ?: return GodotError.FILE_NOT_FOUND.value.toVariant()

        // The factory below is what `createInstanceFunc` StableRef-binds to the native pointer — every
        // virtual call (`_can_instantiate`, `_is_valid`, ...) dispatches to THIS Kotlin object via that
        // binding, not to whatever gets constructed afterward. Building a second `KotlinScript(scriptPtr,
        // ...)` here to carry `entry`'s values (as an earlier version of this did) silently leaves the
        // bound instance on its all-empty defaults — `_canInstantiate()` then reports false forever. A
        // fresh `KotlinScript(scriptPtr)` below is still needed to return a `Variant`, but its own field
        // values don't matter for that: `toVariant()` only wraps the shared native pointer.
        val scriptPtr = createInstanceFunc("ScriptExtension", "KotlinScript", false) { ptr ->
            KotlinScript(ptr, normalizedPath, entry.className, entry.baseClassName)
        } ?: return GodotError.CANT_CREATE.value.toVariant()

        return KotlinScript(scriptPtr).toVariant()
    }

    private fun globalizedPath(path: String): String = ProjectSettings.instance.globalizePath(path)
}
