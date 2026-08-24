package io.github.kingg22.godot.internal.script

import io.github.kingg22.godot.api.GodotError
import io.github.kingg22.godot.api.builtin.GodotArray
import io.github.kingg22.godot.api.builtin.GodotString
import io.github.kingg22.godot.api.builtin.PackedStringArray
import io.github.kingg22.godot.api.builtin.StringName
import io.github.kingg22.godot.api.builtin.Variant
import io.github.kingg22.godot.api.builtin.VariantArray
import io.github.kingg22.godot.api.builtin.VariantDictionary
import io.github.kingg22.godot.api.builtin.toGodotString
import io.github.kingg22.godot.api.builtin.toVariant
import io.github.kingg22.godot.api.core.GodotObject
import io.github.kingg22.godot.api.core.ScriptLanguage
import io.github.kingg22.godot.api.core.ScriptLanguageExtension
import io.github.kingg22.godot.api.core.refcounted.Script
import io.github.kingg22.godot.api.native.ScriptLanguageExtensionProfilingInfo
import io.github.kingg22.godot.api.singleton.ProjectSettings
import io.github.kingg22.godot.internal.binding.InternalBinding
import io.github.kingg22.godot.internal.binding.createInstanceFunc
import io.github.kingg22.godot.internal.script.KotlinScriptRegistry.KOTLIN_SCRIPT_EXTENSION
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.alloc
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr

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

    override fun _getDocCommentDelimiters(): PackedStringArray = packedStringArrayOf("///", "/** */")

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
        result["valid".toVariant()] = KotlinScriptRegistry.contains(ProjectSettings.instance.globalizePath(path.toKString())).toVariant()
        return result
    }

    override fun _getRecognizedExtensions(): PackedStringArray = packedStringArrayOf(KOTLIN_SCRIPT_EXTENSION)

    override fun _createScript(): GodotObject = newEmptyKotlinScript()

    // Matches Kotlin's own file-naming convention (PascalCase classes live in PascalCase-or-related files).
    override fun _preferredFileNameCasing(): ScriptLanguage.ScriptNameCasing = ScriptLanguage.ScriptNameCasing.PASCAL_CASE

    // ── Remaining required virtuals (issue #42) ────────────────────────────
    // Kotlin/Native is AOT-compiled: there is no in-editor parser, debugger, profiler, or code-generation
    // facility to back any of these with real behavior — each returns the safe "unsupported"/empty
    // default the engine falls back to for a language without that capability. Godot's
    // `GDVIRTUAL_REQUIRED_CALL` macro still demands every one of these be overridden (confirmed via
    // real-editor testing: leaving any of them unwired logs "Required virtual method ... must be
    // overridden" on every editor start), so all are wired even though most are no-ops here.

    override fun _isControlFlowKeyword(keyword: GodotString): Boolean = false

    override fun _makeTemplate(template: GodotString, className: GodotString, baseClassName: GodotString): Script =
        newEmptyKotlinScript()

    override fun _isUsingTemplates(): Boolean = false

    override fun _validatePathAsGdStr(path: GodotString): GodotString = GodotString()

    override fun _supportsDocumentation(): Boolean = false

    override fun _canInheritFromFile(): Boolean = false

    override fun _findFunction(function: GodotString, code: GodotString): Int = -1

    override fun _makeFunctionAsGdStr(
        className: GodotString,
        functionName: GodotString,
        functionArgs: PackedStringArray,
    ): GodotString = GodotString()

    override fun _canMakeFunction(): Boolean = false

    override fun _openInExternalEditor(script: Script?, line: Int, column: Int): GodotError = GodotError.UNAVAILABLE

    override fun _overridesExternalEditor(): Boolean = false

    // `owner` is nullable (issue #141 / PR #142): Godot passes null whenever the script being edited
    // isn't attached to any live scene object — the ordinary case editing from the FileSystem dock.
    override fun _completeCode(code: GodotString, path: GodotString, owner: GodotObject?): VariantDictionary =
        VariantDictionary()

    override fun _lookupCode(
        code: GodotString,
        symbol: GodotString,
        path: GodotString,
        owner: GodotObject?,
    ): VariantDictionary = VariantDictionary()

    override fun _autoIndentCodeAsGdStr(code: GodotString, fromLine: Int, toLine: Int): GodotString = code

    override fun _addGlobalConstant(name: StringName, value: Variant) {}

    override fun _addNamedGlobalConstant(name: StringName, value: Variant) {}

    override fun _removeNamedGlobalConstant(name: StringName) {}

    override fun _threadEnter() {}

    override fun _threadExit() {}

    override fun _debugGetErrorAsGdStr(): GodotString = GodotString()

    override fun _debugGetStackLevelCount(): Int = 0

    override fun _debugGetStackLevelLine(level: Int): Int = 0

    override fun _debugGetStackLevelFunctionAsGdStr(level: Int): GodotString = GodotString()

    override fun _debugGetStackLevelSourceAsGdStr(level: Int): GodotString = GodotString()

    override fun _debugGetStackLevelLocals(level: Int, maxSubitems: Int, maxDepth: Int): VariantDictionary =
        VariantDictionary()

    override fun _debugGetStackLevelMembers(level: Int, maxSubitems: Int, maxDepth: Int): VariantDictionary =
        VariantDictionary()

    // Non-null by contract (Original type `void*`); there is no interpreter stack to point into, so this
    // hands back a stable, never-dereferenced sentinel allocated once (not per call).
    override fun _debugGetStackLevelInstance(level: Int): COpaquePointer = debugStackLevelInstanceSentinel

    override fun _debugGetGlobals(maxSubitems: Int, maxDepth: Int): VariantDictionary = VariantDictionary()

    override fun _debugParseStackLevelExpressionAsGdStr(
        level: Int,
        expression: GodotString,
        maxSubitems: Int,
        maxDepth: Int,
    ): GodotString = GodotString()

    override fun _reloadAllScripts() {}

    override fun _reloadScripts(scripts: VariantArray, softReload: Boolean) {}

    override fun _reloadToolScript(script: Script?, softReload: Boolean) {}

    override fun _getPublicConstants(): VariantDictionary = VariantDictionary()

    override fun _profilingStart() {}

    override fun _profilingStop() {}

    override fun _profilingSetSaveNativeCalls(enable: Boolean) {}

    // Required (GDVIRTUAL2R_REQUIRED, unlike the rest of this block): 0 means "no profiling data
    // available", the same answer `_profilingStart`/`_profilingStop` being no-ops already implies.
    override fun _profilingGetAccumulatedData(infoArray: CPointer<ScriptLanguageExtensionProfilingInfo>, infoMax: Int): Int = 0

    override fun _profilingGetFrameData(infoArray: CPointer<ScriptLanguageExtensionProfilingInfo>, infoMax: Int): Int = 0

    override fun _frame() {}

    override fun _handlesGlobalClassType(type: GodotString): Boolean = false

    override fun _getGlobalClassName(path: GodotString): VariantDictionary = VariantDictionary()

    // Unblocked by issue #139 / PR #140 (typedarray::* virtual-return codegen support) — same
    // AOT/no-parser rationale as the rest of this block.
    override fun _getBuiltInTemplates(`object`: StringName): GodotArray<VariantDictionary> = GodotArray()

    override fun _debugGetCurrentStackInfo(): GodotArray<VariantDictionary> = GodotArray()

    override fun _getPublicFunctions(): GodotArray<VariantDictionary> = GodotArray()

    override fun _getPublicAnnotations(): GodotArray<VariantDictionary> = GodotArray()

    private fun newEmptyKotlinScript(): KotlinScript {
        val scriptPtr = createInstanceFunc("ScriptExtension", "KotlinScript", false, ::KotlinScript)
            ?: error("Failed to create a KotlinScript instance")
        return KotlinScript(scriptPtr)
    }
}

private val debugStackLevelInstanceSentinel: COpaquePointer by lazy { nativeHeap.alloc<ByteVar>().ptr }

private val KOTLIN_RESERVED_WORDS = arrayOf(
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface",
    "is", "null", "object", "package", "return", "super", "this", "throw", "true", "try", "typealias",
    "typeof", "val", "var", "when", "while",
)

private fun packedStringArrayOf(vararg values: String): PackedStringArray {
    val array = PackedStringArray()
    values.forEach { val _ = array.pushBack(it) }
    return array
}
