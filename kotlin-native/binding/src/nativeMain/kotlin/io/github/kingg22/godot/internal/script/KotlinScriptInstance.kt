@file:OptIn(InternalBinding::class)

package io.github.kingg22.godot.internal.script

import io.github.kingg22.godot.api.MethodFlags
import io.github.kingg22.godot.api.PropertyHint
import io.github.kingg22.godot.api.PropertyUsageFlags
import io.github.kingg22.godot.api.builtin.GodotString
import io.github.kingg22.godot.api.builtin.StringName
import io.github.kingg22.godot.api.builtin.internal.toGDE
import io.github.kingg22.godot.api.builtin.toStringName
import io.github.kingg22.godot.api.core.GodotObject
import io.github.kingg22.godot.internal.binding.InternalBinding
import io.github.kingg22.godot.internal.binding.ScriptBinding
import io.github.kingg22.godot.internal.binding.StringBinding
import io.github.kingg22.godot.internal.ffi.*
import kotlinx.cinterop.*

/**
 * The live state behind one attached `.kt` script (issue #42 Fase 3): the target `@Godot` class
 * instance (constructed via [KotlinScriptRegistry.Entry.factory], wrapping the *same* native pointer as
 * [owner]), plus enough metadata to answer Godot's `GDExtensionScriptInstanceInfo3` callbacks.
 *
 * [targetPtr] is a separate [StableRef] (not this state's own) so it can be handed to the target
 * class's existing `_godot_get_*`/`_godot_set_*`/`_godot_call_*` trampolines unchanged — those already
 * expect their `instancePtr` argument to be a `StableRef<TargetClass>` pointer (see `getInstance<T>()`
 * in `BindingUtils.kt`), exactly the shape `createInstanceFunc` produces for ClassDB-registered
 * instances. Reusing them here avoids a second, duplicated dispatch path.
 */
private class ScriptInstanceState(
    val entry: KotlinScriptRegistry.Entry,
    val script: KotlinScript,
    val owner: GodotObject,
    /**
     * Null when [owner]'s actual native class doesn't match [KotlinScriptRegistry.Entry.baseClassName]
     * (see [createKotlinScriptInstance]) — [entry.factory] is never called in that case, since it assumes
     * the pointer really is a [KotlinScriptRegistry.Entry.baseClassName]-shaped object; calling it on a
     * mismatched one reads/writes through a field layout the object doesn't have (confirmed via
     * real-editor testing: SIGSEGV). [properties]/[methods] report empty whenever this is null, so every
     * callback below naturally no-ops without needing to null-check [targetPtr] itself.
     */
    val targetPtr: COpaquePointer?,
) {
    val properties get() = if (targetPtr != null) entry.properties else emptyList()
    val methods get() = if (targetPtr != null) entry.methods else emptyList()

    /** Set right after allocation (see [createKotlinScriptInstance]); freed by `free_func`. */
    lateinit var info: CPointer<GDExtensionScriptInstanceInfo3>
}

/** Interned so a repeated property/method/class name doesn't allocate a new native `StringName` per call. */
private val internedNames = mutableMapOf<String, StringName>()
private fun internedName(value: String): StringName = internedNames.getOrPut(value) { value.toStringName() }

/**
 * `GDExtensionPropertyInfo.hint_string` is a `GDExtensionStringPtr` (a `String`), NOT a `StringName` —
 * a distinct C++ class with a different binary layout. Passing an [internedName]'s `StringName` pointer
 * there type-confuses whatever later reads it as a `String` (e.g. the editor Inspector rendering a
 * property's hint text) into dereferencing memory through the wrong vtable — confirmed via real-editor
 * testing: SIGSEGV, only after `_instance_create` had already returned successfully.
 */
private val emptyHintString: GodotString by lazy { GodotString() }

private fun COpaquePointer?.state(): ScriptInstanceState? = this?.asStableRef<ScriptInstanceState>()?.get()

/**
 * Builds and registers a Godot "script instance" ([GDExtensionScriptInstanceInfo3]) for [forObject],
 * routing property get/set and method calls to the `@Export`/`@ExportMethod` members of the `@Godot`
 * class [script] resolves to (issue #42 Fase 3 — `KotlinScript._instanceCreate`).
 *
 * Kotlin/Native is AOT-compiled: [forObject] keeps its original native class (e.g. `Node2D`) — this does
 * not re-register it as a different ClassDB class, it only attaches a script-instance proxy the engine
 * routes through for the lifetime of the attachment.
 *
 * Known limitations (documented, not silently dropped): virtual lifecycle notifications (`_ready`,
 * `_process`, ...) are not routed through the attached-script path yet — only `@Export` properties and
 * `@ExportMethod` methods are. `get_property_state_func` is a no-op, so script-instance property values
 * are not captured for scene duplication/undo. Both are separate follow-up work, out of scope for #42.
 */
@InternalBinding
public fun createKotlinScriptInstance(script: KotlinScript, forObject: GodotObject): COpaquePointer? {
    val entry = KotlinScriptRegistry[script.scriptPath] ?: return null
    // `entry.factory` assumes `forObject`'s native pointer is really an instance of `entry.baseClassName`
    // (e.g. Sprite.kt's factory constructs a Sprite2D-shaped wrapper) — Godot's own "Load Script"/Attach
    // Script UI does not itself reject attaching a script whose declared base type doesn't match the
    // target node. On a mismatch, `targetPtr` stays null instead of calling the factory (see
    // ScriptInstanceState's KDoc) — the instance is still created and returned (Godot's `_instance_create`
    // contract has no way to signal "declined" short of a real pointer), it just answers every
    // property/method query as empty rather than corrupting memory through the wrong field layout.
    val targetPtr = if (forObject.isClass(internedName(entry.baseClassName))) {
        StableRef.create(entry.factory(forObject.rawPtr)).asCPointer()
    } else {
        null
    }
    val state = ScriptInstanceState(entry, script, forObject, targetPtr)
    val selfPtr = StableRef.create(state).asCPointer()

    val info = nativeHeap.alloc<GDExtensionScriptInstanceInfo3> {
        set_func = staticCFunction { pInstance, pName, pValue ->
            val s = pInstance.state()
            val name = pName?.let { StringName(it).toString() }
            val descriptor = s?.properties?.firstOrNull { it.name == name }
            val setter = descriptor?.setter
            if (s == null || setter == null) {
                GDExtensionBool.FALSE
            } else {
                memScoped {
                    val argsArray = allocArray<COpaquePointerVar>(1)
                    argsArray[0] = pValue
                    val errorPtr = alloc<GDExtensionCallError>().ptr
                    setter.invoke(null, s.targetPtr, argsArray.reinterpret(), 1L, null, errorPtr)
                    if (errorPtr.pointed.error == GDExtensionCallErrorType.GDEXTENSION_CALL_OK) {
                        GDExtensionBool.TRUE
                    } else {
                        GDExtensionBool.FALSE
                    }
                }
            }
        }

        get_func = staticCFunction { pInstance, pName, rRet ->
            val s = pInstance.state()
            val name = pName?.let { StringName(it).toString() }
            val descriptor = s?.properties?.firstOrNull { it.name == name }
            if (s == null || descriptor == null) {
                GDExtensionBool.FALSE
            } else {
                memScoped {
                    val errorPtr = alloc<GDExtensionCallError>().ptr
                    descriptor.getter.invoke(null, s.targetPtr, null, 0L, rRet, errorPtr)
                }
                GDExtensionBool.TRUE
            }
        }

        get_property_list_func = staticCFunction { pInstance, rCount ->
            val s = pInstance.state()
            if (s == null) {
                rCount?.pointed?.value = 0u
                null
            } else {
                val properties = s.properties
                rCount?.pointed?.value = properties.size.toUInt()
                if (properties.isEmpty()) {
                    null
                } else {
                    val classNameStr = internedName(s.entry.className)
                    nativeHeap.allocArray<GDExtensionPropertyInfo>(properties.size) { index ->
                        val prop = properties[index]
                        type = prop.type.toGDE()
                        name = internedName(prop.name).rawPtr
                        class_name = classNameStr.rawPtr
                        hint = PropertyHint.NONE.value.toUInt()
                        hint_string = emptyHintString.rawPtr
                        usage = PropertyUsageFlags.DEFAULT.value.toUInt()
                    }
                }
            }
        }

        free_property_list_func = staticCFunction { _, pList, _ ->
            if (pList != null) nativeHeap.free(pList)
        }

        get_class_category_func = null

        property_can_revert_func = staticCFunction { _, _ -> GDExtensionBool.FALSE }

        property_get_revert_func = staticCFunction { _, _, _ -> GDExtensionBool.FALSE }

        get_owner_func = staticCFunction { pInstance -> pInstance.state()?.owner?.rawPtr }

        get_property_state_func = staticCFunction { _, _, _ ->
            // No-op: property values aren't captured for scene duplication/undo yet, see file-level KDoc.
        }

        get_method_list_func = staticCFunction { pInstance, rCount ->
            val s = pInstance.state()
            if (s == null) {
                rCount?.pointed?.value = 0u
                null
            } else {
                val methods = s.methods
                rCount?.pointed?.value = methods.size.toUInt()
                if (methods.isEmpty()) {
                    null
                } else {
                    val emptyStr = internedName("")
                    nativeHeap.allocArray<GDExtensionMethodInfo>(methods.size) { index ->
                        val method = methods[index]
                        name = internedName(method.name).rawPtr
                        return_value.type = GDEXTENSION_VARIANT_TYPE_NIL
                        return_value.name = emptyStr.rawPtr
                        return_value.class_name = emptyStr.rawPtr
                        return_value.hint = PropertyHint.NONE.value.toUInt()
                        return_value.hint_string = emptyHintString.rawPtr
                        return_value.usage = PropertyUsageFlags.DEFAULT.value.toUInt()
                        flags = MethodFlags.NORMAL.value.toUInt()
                        id = 0
                        argument_count = method.argumentCount.toUInt()
                        arguments = null
                        default_argument_count = 0u
                        default_arguments = null
                    }
                }
            }
        }

        free_method_list_func = staticCFunction { _, pList, _ ->
            if (pList != null) nativeHeap.free(pList)
        }

        get_property_type_func = staticCFunction { pInstance, pName, rIsValid ->
            val s = pInstance.state()
            val name = pName?.let { StringName(it).toString() }
            val descriptor = s?.properties?.firstOrNull { it.name == name }
            rIsValid?.pointed?.value = if (descriptor != null) GDExtensionBool.TRUE else GDExtensionBool.FALSE
            descriptor?.type?.toGDE() ?: GDEXTENSION_VARIANT_TYPE_NIL
        }

        validate_property_func = staticCFunction { _, _ -> GDExtensionBool.TRUE }

        has_method_func = staticCFunction { pInstance, pName ->
            val s = pInstance.state()
            val name = pName?.let { StringName(it).toString() }
            if (s != null && s.methods.any { it.name == name }) GDExtensionBool.TRUE else GDExtensionBool.FALSE
        }

        get_method_argument_count_func = staticCFunction { pInstance, pName, rIsValid ->
            val s = pInstance.state()
            val name = pName?.let { StringName(it).toString() }
            val descriptor = s?.methods?.firstOrNull { it.name == name }
            rIsValid?.pointed?.value = if (descriptor != null) GDExtensionBool.TRUE else GDExtensionBool.FALSE
            descriptor?.argumentCount?.toLong() ?: 0L
        }

        call_func = staticCFunction { pInstance, pMethod, pArgs, pArgumentCount, rReturn, rError ->
            val s = pInstance.state()
            val name = pMethod?.let { StringName(it).toString() }
            val descriptor = s?.methods?.firstOrNull { it.name == name }
            if (s == null || descriptor == null) {
                rError?.pointed?.error = GDExtensionCallErrorType.GDEXTENSION_CALL_ERROR_INVALID_METHOD
            } else {
                descriptor.call.invoke(null, s.targetPtr, pArgs, pArgumentCount, rReturn, rError)
            }
        }

        notification_func = staticCFunction { _, _, _ ->
            // No-op: lifecycle notifications aren't routed through the attached-script path yet, see file-level KDoc.
        }

        to_string_func = staticCFunction { pInstance, rIsValid, rOut ->
            val s = pInstance.state()
            if (s == null) {
                rIsValid?.pointed?.value = GDExtensionBool.FALSE
            } else {
                rIsValid?.pointed?.value = GDExtensionBool.TRUE
                val message = "<${s.entry.className}#kotlin_script>".utf16
                memScoped {
                    StringBinding.newWithUtf16CharsRaw(rOut, message.ptr)
                }
            }
        }

        refcount_incremented_func = staticCFunction { _ ->
            // No-op: kogot does not track custom refcounting for script instances.
        }

        refcount_decremented_func = staticCFunction { _ -> GDExtensionBool.TRUE }

        get_script_func = staticCFunction { pInstance -> pInstance.state()?.script?.rawPtr }

        is_placeholder_func = staticCFunction { _ -> GDExtensionBool.FALSE }

        set_fallback_func = staticCFunction { _, _, _ -> GDExtensionBool.FALSE }

        get_fallback_func = staticCFunction { _, _, _ -> GDExtensionBool.FALSE }

        get_language_func = staticCFunction { _ -> KotlinScriptRegistration.language.rawPtr }

        free_func = staticCFunction { pInstance ->
            if (pInstance != null) {
                val ref = pInstance.asStableRef<ScriptInstanceState>()
                val freedState = ref.get()
                freedState.targetPtr?.asStableRef<Any>()?.dispose()
                nativeHeap.free(freedState.info)
                ref.dispose()
            }
        }
    }
    state.info = info.ptr

    return ScriptBinding.instanceCreate3Raw(info.ptr, selfPtr)
}
