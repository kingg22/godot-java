package io.github.kingg22.godot.internal.script

import io.github.kingg22.godot.api.GodotError
import io.github.kingg22.godot.api.builtin.GodotString
import io.github.kingg22.godot.api.builtin.PackedStringArray
import io.github.kingg22.godot.api.builtin.toGodotString
import io.github.kingg22.godot.api.builtin.toStringName
import io.github.kingg22.godot.api.core.refcounted.Resource
import io.github.kingg22.godot.api.core.refcounted.ResourceFormatSaver
import io.github.kingg22.godot.internal.binding.InternalBinding
import io.github.kingg22.godot.internal.script.KotlinScriptRegistry.KOTLIN_SCRIPT_EXTENSION
import kotlinx.cinterop.COpaquePointer

/**
 * Saving is out of scope for issue #42: a `.kt` script is an AOT-compiled source file, not an
 * editor-authored resource, so there is nothing meaningful to write back to disk. This saver only
 * recognizes `KotlinScript` resources so the engine doesn't fall back to a generic saver for them —
 * every save attempt fails explicitly rather than silently corrupting the source file.
 *
 * [Resource] arguments here are always plain [Resource] wrappers, never downcast to `KotlinScript`:
 * the `_recognize`/`_get_recognized_extensions` trampolines construct their argument using the
 * statically declared parameter type from the API (see `ResourceFormatSaverVirtualCalls`), not the
 * argument's actual registered class. Identity must be checked with [Resource.isClass] against the
 * ClassDB name instead of a Kotlin `is` check.
 */
@InternalBinding
public class KotlinResourceFormatSaver(nativePtr: COpaquePointer) : ResourceFormatSaver(nativePtr) {
    override fun _getRecognizedExtensions(resource: Resource): PackedStringArray {
        val array = PackedStringArray()
        if (resource.isClass(KOTLIN_SCRIPT_CLASS_NAME)) {
            val _ = array.pushBack(KOTLIN_SCRIPT_EXTENSION.toGodotString())
        }
        return array
    }

    override fun _recognize(resource: Resource): Boolean = resource.isClass(KOTLIN_SCRIPT_CLASS_NAME)

    override fun _save(resource: Resource, path: GodotString, flags: UInt): GodotError = GodotError.FAILED
}

private val KOTLIN_SCRIPT_CLASS_NAME = "KotlinScript".toStringName()
