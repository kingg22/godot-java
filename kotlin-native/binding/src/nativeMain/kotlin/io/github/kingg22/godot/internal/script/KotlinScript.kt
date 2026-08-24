@file:OptIn(InternalBinding::class)

package io.github.kingg22.godot.internal.script

import io.github.kingg22.godot.api.GodotError
import io.github.kingg22.godot.api.builtin.GodotArray
import io.github.kingg22.godot.api.builtin.GodotString
import io.github.kingg22.godot.api.builtin.StringName
import io.github.kingg22.godot.api.builtin.Variant
import io.github.kingg22.godot.api.builtin.VariantDictionary
import io.github.kingg22.godot.api.builtin.toStringName
import io.github.kingg22.godot.api.core.GodotObject
import io.github.kingg22.godot.api.core.ScriptLanguage
import io.github.kingg22.godot.api.core.refcounted.Script
import io.github.kingg22.godot.api.core.refcounted.ScriptExtension
import io.github.kingg22.godot.internal.binding.InternalBinding
import io.github.kingg22.godot.internal.binding.createInstanceFunc
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.alloc
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr

/**
 * The `Script` resource attached to a `.kt` file (issue #42). Kotlin/Native is AOT-compiled: this
 * resource carries only metadata (source path, target `@Godot` class) resolved at compile time via
 * [KotlinScriptRegistry] — there is no source text to parse or execute.
 *
 * [scriptPath]/[targetClassName]/[targetBaseClassName] are populated by [KotlinResourceFormatLoader]
 * right after construction, since Godot's `create_instance_func` contract only allows a bare
 * `(nativePtr)` constructor.
 *
 * [_instanceCreate] delegates to `createKotlinScriptInstance`, building the `GDExtensionScriptInstanceInfo3`
 * proxy that routes property get/set and method calls to the target `@Godot` class (issue #42 Fase 3).
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

    // `forObject` is nullable (issue #141 / PR #142): fall back to the never-dereferenced sentinel rather
    // than crashing if Godot ever calls this without an object — `error()` stays reserved for a genuine
    // registry miss on a real, non-null object, which indicates an actual bug worth surfacing loudly.
    override fun _instanceCreate(forObject: GodotObject?): COpaquePointer =
        forObject?.let {
            createKotlinScriptInstance(this, it)
                ?: error("Failed to create a Kotlin script instance for $it (script path: $scriptPath)")
        } ?: placeholderInstanceSentinel

    // ── Remaining required virtuals (issue #42) ────────────────────────────
    // Same rationale as `KotlinScriptLanguage`: Kotlin/Native is AOT-compiled, so most of these have no
    // real behavior to report and return the safe "unsupported"/empty default instead of failing the
    // required-override check the engine enforces (see real-editor testing notes there).

    override fun _editorCanReloadFromFile(): Boolean = false

    // Non-null by contract, but a Kotlin `@Godot` class inherits from its native ClassDB parent, never
    // from another Script resource — there is no real base script to report. Godot's own inheritance-walk
    // callers check `is_valid()` (this shared instance reports `_isValid() == false`), not null, so one
    // reused sentinel is enough; allocating a fresh native object per call here would leak one on every
    // Inspector/editor refresh, since this is queried far more routinely than `_createScript()`.
    override fun _getBaseScript(): Script = emptyBaseScript

    override fun _getGlobalName(): StringName = StringName()

    override fun _inheritsScript(script: Script?): Boolean = false

    // True placeholder support (Inspector properties without running the class in-editor) is issue #125;
    // until then this reuses the real instance proxy so the editor at least gets a working object instead
    // of nothing, falling back to a never-dereferenced sentinel if the registry lookup fails or `forObject`
    // is null (issue #141 / PR #142 — the latter is now possible per the generated signature).
    override fun _placeholderInstanceCreate(forObject: GodotObject?): COpaquePointer =
        forObject?.let { createKotlinScriptInstance(this, it) } ?: placeholderInstanceSentinel

    override fun _getDocClassName(): StringName = StringName()

    override fun _hasMethod(method: StringName): Boolean =
        KotlinScriptRegistry[scriptPath]?.methods?.any { it.name == method.toString() } == true

    override fun _hasStaticMethod(method: StringName): Boolean = false

    override fun _getMethodInfo(method: StringName): VariantDictionary = VariantDictionary()

    override fun _hasScriptSignal(signal: StringName): Boolean = false

    override fun _hasPropertyDefaultValue(property: StringName): Boolean = false

    override fun _getPropertyDefaultValue(property: StringName): Variant = Variant()

    override fun _updateExports() {}

    override fun _getMemberLine(member: StringName): Int = -1

    override fun _getConstants(): VariantDictionary = VariantDictionary()

    override fun _isPlaceholderFallbackEnabled(): Boolean = true

    override fun _getRpcConfig(): Variant = Variant()

    // Unblocked by issue #139 / PR #140 (typedarray::* virtual-return codegen support) — same
    // AOT/no-reflection rationale as `_getMethodInfo`/`_getConstants` above.
    override fun _getDocumentation(): GodotArray<VariantDictionary> = GodotArray()

    override fun _getScriptSignalList(): GodotArray<VariantDictionary> = GodotArray()

    override fun _getScriptMethodList(): GodotArray<VariantDictionary> = GodotArray()

    override fun _getScriptPropertyList(): GodotArray<VariantDictionary> = GodotArray()

    override fun _getMembers(): GodotArray<StringName> = GodotArray()
}

private val placeholderInstanceSentinel: COpaquePointer by lazy { nativeHeap.alloc<ByteVar>().ptr }

private val emptyBaseScript: KotlinScript by lazy {
    val scriptPtr = createInstanceFunc("ScriptExtension", "KotlinScript", false, ::KotlinScript)
        ?: error("Failed to create the shared empty-base-script KotlinScript instance")
    KotlinScript(scriptPtr)
}
