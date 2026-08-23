package io.github.kingg22.godot.internal.script

import io.github.kingg22.godot.api.GodotError
import io.github.kingg22.godot.api.builtin.GodotString
import io.github.kingg22.godot.api.builtin.StringName
import io.github.kingg22.godot.api.builtin.toStringName
import io.github.kingg22.godot.api.core.GodotObject
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
 * [_instanceCreate] delegates to `createKotlinScriptInstance` (Fase 3b). It is not yet reachable at
 * runtime: `ScriptExtension._instanceCreate`'s `void*` return type is unsupported by the current
 * virtual-dispatch codegen (same category of gap PR #136 fixed for heap-backed builtins, but scoped
 * `void*` out of), so Godot has no trampoline to call this override through yet — tracked separately.
 */
@InternalBinding
public class KotlinScript(
    nativePtr: COpaquePointer,
    public val scriptPath: String = "",
    private val targetClassName: String = "",
    private val targetBaseClassName: String = "",
) : ScriptExtension(nativePtr) {

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

    override fun _instanceCreate(forObject: GodotObject): COpaquePointer =
        createKotlinScriptInstance(this, forObject)
            ?: error("Failed to create a Kotlin script instance for $forObject (script path: $scriptPath)")
}
