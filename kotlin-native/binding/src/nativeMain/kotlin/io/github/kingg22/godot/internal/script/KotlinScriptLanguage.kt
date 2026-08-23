package io.github.kingg22.godot.internal.script

import io.github.kingg22.godot.api.builtin.GodotString
import io.github.kingg22.godot.api.builtin.PackedStringArray
import io.github.kingg22.godot.api.builtin.VariantDictionary
import io.github.kingg22.godot.api.builtin.toGodotString
import io.github.kingg22.godot.api.builtin.toVariant
import io.github.kingg22.godot.api.core.GodotObject
import io.github.kingg22.godot.api.core.ScriptLanguageExtension
import io.github.kingg22.godot.internal.binding.InternalBinding
import io.github.kingg22.godot.internal.binding.createInstanceFunc
import kotlinx.cinterop.COpaquePointer

/**
 * Registers Kotlin as a Godot scripting language so `.kt` files with an addressable `@Godot` class
 * appear in the editor's "Attach Script" dialog (issue #42).
 *
 * Kotlin/Native is AOT-compiled: a `.kt` "script" is never parsed or executed at runtime here, it is a
 * pointer to a class that was already compiled and registered in ClassDB. This class only reports
 * language metadata and validates/creates [KotlinScript] resources; attaching a script's behavior to a
 * live scene object ([KotlinScript._instanceCreate]) is implemented separately (see #42 Fase 3).
 */
@InternalBinding
public class KotlinScriptLanguage(nativePtr: COpaquePointer) : ScriptLanguageExtension(nativePtr) {
    override fun _getNameAsGdStr(): GodotString = "Kotlin".toGodotString()

    override fun _init() {}

    override fun _getTypeAsGdStr(): GodotString = "KotlinScript".toGodotString()

    override fun _getExtensionAsGdStr(): GodotString = KOTLIN_SCRIPT_EXTENSION.toGodotString()

    override fun _finish() {}

    override fun _getReservedWords(): PackedStringArray = packedStringArrayOf(*KOTLIN_RESERVED_WORDS)

    override fun _getCommentDelimiters(): PackedStringArray = packedStringArrayOf("//", "/* */")

    override fun _getStringDelimiters(): PackedStringArray = packedStringArrayOf("\" \"", "\"\"\" \"\"\"")

    override fun _hasNamedClasses(): Boolean = true

    override fun _supportsBuiltinMode(): Boolean = false

    override fun _validate(
        script: GodotString,
        path: GodotString,
        validateFunctions: Boolean,
        validateErrors: Boolean,
        validateWarnings: Boolean,
        validateSafeLines: Boolean,
    ): VariantDictionary {
        // Kotlin/Native is AOT-compiled: there is no in-editor parser to run `script` against. Presence
        // in the compile-time registry (populated from the KSP-generated `ScriptFileRegistry`) is the
        // only validation available.
        val result = VariantDictionary()
        result["valid".toVariant()] = KotlinScriptRegistry.contains(path.toKString()).toVariant()
        return result
    }

    override fun _getRecognizedExtensions(): PackedStringArray = packedStringArrayOf(KOTLIN_SCRIPT_EXTENSION)

    override fun _createScript(): GodotObject {
        val scriptPtr = createInstanceFunc("ScriptExtension", "KotlinScript", false, ::KotlinScript)
            ?: error("Failed to create a KotlinScript instance")
        return KotlinScript(scriptPtr)
    }
}

private val KOTLIN_RESERVED_WORDS = arrayOf(
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface",
    "is", "null", "object", "package", "return", "super", "this", "throw", "true", "try", "typealias",
    "typeof", "val", "var", "when", "while",
)

private fun packedStringArrayOf(vararg values: String): PackedStringArray {
    val array = PackedStringArray()
    values.forEach { val _ = array.pushBack(it.toGodotString()) }
    return array
}
