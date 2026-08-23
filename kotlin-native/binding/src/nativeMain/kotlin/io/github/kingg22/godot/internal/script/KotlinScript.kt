package io.github.kingg22.godot.internal.script

import io.github.kingg22.godot.api.GodotError
import io.github.kingg22.godot.api.builtin.GodotString
import io.github.kingg22.godot.api.builtin.StringName
import io.github.kingg22.godot.api.builtin.toStringName
import io.github.kingg22.godot.api.core.ScriptLanguage
import io.github.kingg22.godot.api.core.refcounted.ScriptExtension
import io.github.kingg22.godot.internal.binding.InternalBinding
import kotlinx.cinterop.COpaquePointer

/**
 * The `Script` resource attached to a `.kt` file (issue #42). Kotlin/Native is AOT-compiled: this
 * resource carries only metadata (source path, target `@Godot` class) resolved at compile time via
 * [KotlinScriptRegistry] — there is no source text to parse or execute.
 *
 * [scriptPath]/[targetClassName]/[targetBaseClassName] are populated by [KotlinResourceFormatLoader]
 * right after construction, since Godot's `create_instance_func` contract only allows a bare
 * `(nativePtr)` constructor.
 *
 * Attaching the script's behavior to a live scene object ([_instanceCreate]) is implemented separately
 * (see #42 Fase 3); until then it keeps the inherited default (unimplemented) behavior.
 */
@InternalBinding
public class KotlinScript(nativePtr: COpaquePointer) : ScriptExtension(nativePtr) {
    public var scriptPath: String = ""
    public var targetClassName: String = ""
    public var targetBaseClassName: String = "Object"

    override fun _canInstantiate(): Boolean = targetClassName.isNotEmpty()

    override fun _hasSourceCode(): Boolean = false

    override fun _getSourceCodeAsGdStr(): GodotString = GodotString()

    override fun _setSourceCode(code: GodotString) {
        // No-op: AOT-compiled, there is no source text to store or interpret.
    }

    override fun _isValid(): Boolean = targetClassName.isNotEmpty()

    override fun _isTool(): Boolean = false

    override fun _getInstanceBaseType(): StringName = targetBaseClassName.toStringName()

    override fun _getLanguage(): ScriptLanguage = KotlinScriptRegistration.language

    override fun _reload(keepState: Boolean): GodotError = GodotError.OK
}
