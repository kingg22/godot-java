package io.github.kingg22.godot.internal.script

import io.github.kingg22.godot.api.GodotError
import io.github.kingg22.godot.api.builtin.GodotString
import io.github.kingg22.godot.api.builtin.PackedStringArray
import io.github.kingg22.godot.api.builtin.StringName
import io.github.kingg22.godot.api.builtin.Variant
import io.github.kingg22.godot.api.builtin.toGodotString
import io.github.kingg22.godot.api.builtin.toVariant
import io.github.kingg22.godot.api.core.refcounted.ResourceFormatLoader
import io.github.kingg22.godot.internal.binding.InternalBinding
import io.github.kingg22.godot.internal.binding.createInstanceFunc
import io.github.kingg22.godot.internal.script.KotlinScriptRegistry.KOTLIN_SCRIPT_EXTENSION
import kotlinx.cinterop.COpaquePointer

/**
 * Resolves a `.kt` script resource path to a [KotlinScript] wrapping the already-compiled `@Godot`
 * class recorded for that path in [KotlinScriptRegistry] (issue #42).
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
        if (KotlinScriptRegistry.contains(path.toKString())) "Script".toGodotString() else GodotString()

    override fun _load(path: GodotString, originalPath: GodotString, useSubThreads: Boolean, cacheMode: Int): Variant {
        val entry = KotlinScriptRegistry[path.toKString()]
            ?: return GodotError.FILE_NOT_FOUND.value.toVariant()

        val scriptPtr = createInstanceFunc(
            "ScriptExtension",
            "KotlinScript",
            false,
            ::KotlinScript,
        ) ?: return GodotError.CANT_CREATE.value.toVariant()

        val script = KotlinScript(scriptPtr, path.toKString(), entry.className, entry.baseClassName)
        return script.toVariant()
    }
}
