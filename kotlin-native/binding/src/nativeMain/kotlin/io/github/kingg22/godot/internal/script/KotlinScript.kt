@file:OptIn(InternalBinding::class)

package io.github.kingg22.godot.internal.script

import io.github.kingg22.godot.api.GodotError
import io.github.kingg22.godot.api.MethodFlags
import io.github.kingg22.godot.api.PropertyHint
import io.github.kingg22.godot.api.PropertyUsageFlags
import io.github.kingg22.godot.api.builtin.GodotArray
import io.github.kingg22.godot.api.builtin.GodotString
import io.github.kingg22.godot.api.builtin.StringName
import io.github.kingg22.godot.api.builtin.Variant
import io.github.kingg22.godot.api.builtin.VariantDictionary
import io.github.kingg22.godot.api.builtin.toStringName
import io.github.kingg22.godot.api.builtin.toVariant
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

    // A Kotlin `@Godot` class is always a concrete, instantiable ClassDB registration — never abstract.
    override fun _isAbstract(): Boolean = false

    override fun _getInstanceBaseType(): StringName = targetBaseClassName.toStringName()

    override fun _getLanguage(): ScriptLanguage = KotlinScriptRegistration.language

    override fun _reload(keepState: Boolean): GodotError = GodotError.OK

    // `forObject` is nullable (issue #141 / PR #142). `createKotlinScriptInstance` can also legitimately
    // return null for a real, non-null object — e.g. attaching a script to a node whose native class
    // doesn't match the script's declared base type, which Godot's own "Load Script" UI does not itself
    // reject (confirmed via real-editor testing: this used to reach the factory and corrupt memory).
    // Neither case is safe to `error()` on inside this callback (an uncaught Kotlin exception here aborts
    // the whole process, same failure mode as the #141 crash), so both fall back to the sentinel.
    override fun _instanceCreate(forObject: GodotObject?): COpaquePointer =
        forObject?.let { createKotlinScriptInstance(this, it) } ?: placeholderInstanceSentinel

    // Deprecated engine-side (`#ifndef DISABLE_DEPRECATED`) and has no real C++ call site in 4.7.1 — but
    // the dispatch table always exposes it regardless, so it must still resolve to something other than
    // the generated `TODO()` default.
    override fun _instanceHas(`object`: GodotObject?): Boolean = false

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

    // A non-INT Variant makes the C++ wrapper (script_language_extension.h) fall back to
    // `Script::get_script_method_argument_count`'s own default instead of trusting this as real data.
    override fun _getScriptMethodArgumentCount(method: StringName): Variant = Variant()

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

    // Optional on the C++ side (`GDVIRTUAL0RC`, not `_REQUIRED`) but kogot's dispatch table always exposes
    // it regardless, so leaving it unwired still resolves to the generated `TODO()` default. Confirmed via
    // real-editor testing + a self-built dev Godot binary under lldb: the editor Inspector's script-icon
    // fetch (`EditorData::get_script_icon` → `Script::get_class_icon_path`) reaches this on every attach,
    // and an uncaught Kotlin exception here crosses the GDExtension callback boundary as undefined
    // behavior — this was the root cause of a real SIGSEGV inside Godot's own code, not this extension's.
    override fun _getClassIconPathAsGdStr(): GodotString = GodotString()

    override fun _getScriptSignalList(): GodotArray<VariantDictionary> = GodotArray()

    // An earlier version returned an empty list here (matching `_getMethodInfo`'s "no reflection"
    // rationale) — but unlike that one, this list is NOT purely advisory: the editor Inspector cross-
    // references it against `KotlinScriptInstance`'s per-instance `get_property_list_func` while building
    // the "Script Variables" section, right after finishing the node's native properties. Real-editor
    // testing confirmed leaving it empty here (while the instance-level list correctly reports 3
    // properties) crashes the process (SIGSEGV) the moment the Inspector reaches that transition —
    // before it ever gets to a script property. Must stay in sync with `ScriptInstanceState.properties`.
    override fun _getScriptPropertyList(): GodotArray<VariantDictionary> {
        val result = GodotArray<VariantDictionary>()
        val entry = KotlinScriptRegistry[scriptPath] ?: return result
        for ((name, type) in entry.properties) {
            val dict = VariantDictionary()
            dict["name".toVariant()] = name.toVariant()
            dict["class_name".toVariant()] = entry.className.toVariant()
            dict["type".toVariant()] = type.value.toVariant()
            dict["hint".toVariant()] = PropertyHint.NONE.value.toVariant()
            dict["hint_string".toVariant()] = "".toVariant()
            dict["usage".toVariant()] = PropertyUsageFlags.DEFAULT.value.toVariant()
            result.pushBack(dict.toVariant())
        }
        return result
    }

    override fun _getScriptMethodList(): GodotArray<VariantDictionary> {
        val result = GodotArray<VariantDictionary>()
        val entry = KotlinScriptRegistry[scriptPath] ?: return result
        for ((name) in entry.methods) {
            val dict = VariantDictionary()
            dict["name".toVariant()] = name.toVariant()
            dict["args".toVariant()] = GodotArray<VariantDictionary>().toVariant()
            dict["default_args".toVariant()] = GodotArray<Variant>().toVariant()
            dict["flags".toVariant()] = MethodFlags.NORMAL.value.toVariant()
            dict["id".toVariant()] = 0.toVariant()
            dict["return".toVariant()] = VariantDictionary().toVariant()
            result.pushBack(dict.toVariant())
        }
        return result
    }

    override fun _getMembers(): GodotArray<StringName> = GodotArray()
}

private val placeholderInstanceSentinel: COpaquePointer by lazy { nativeHeap.alloc<ByteVar>().ptr }

private val emptyBaseScript: KotlinScript by lazy {
    val scriptPtr = createInstanceFunc("ScriptExtension", "KotlinScript", false, ::KotlinScript)
        ?: error("Failed to create the shared empty-base-script KotlinScript instance")
    KotlinScript(scriptPtr)
}
