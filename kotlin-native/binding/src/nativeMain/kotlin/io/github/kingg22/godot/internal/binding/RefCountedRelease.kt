@file:OptIn(InternalBinding::class)

package io.github.kingg22.godot.internal.binding

import io.github.kingg22.godot.api.builtin.StringName
import io.github.kingg22.godot.api.core.RefCounted
import io.github.kingg22.godot.api.internal.callable.CallableFactory
import io.github.kingg22.godot.internal.ffi.GDExtensionMethodBindPtr
import io.github.kingg22.godot.internal.ffi.GDExtensionObjectPtr
import kotlinx.cinterop.memScoped
import kotlin.native.ref.createCleaner

private val unreferenceMethodBind: GDExtensionMethodBindPtr by lazy(LazyThreadSafetyMode.PUBLICATION) {
    StringName("RefCounted").use { cn ->
        StringName("unreference").use { mn ->
            ClassDBBinding.getMethodBindRaw(cn.rawPtr, mn.rawPtr, 2_240_911_060L)
                ?: error("Missing method bind 'RefCounted.unreference'")
        }
    }
}

/**
 * Attaches the release [kotlin.native.ref.Cleaner] a freshly [materialize]d [RefCounted] wrapper needs:
 * `castTo`/`GD.load` wrap an *existing* engine object whose ptrcall return already transferred an owned
 * reference to Kotlin that nothing else will ever drop (see issue #114). Only ever called from
 * [materialize]'s first-sighting branch — a wrapper resolved from the identity cache, or one built by
 * our own `createInstanceFunc` (registered custom classes, whose lifetime already follows Godot's own
 * ClassDB instance/free-instance bookkeeping), never goes through here.
 *
 * The cleaner runs on Kotlin/Native's dedicated finalizer thread once the Kotlin wrapper is
 * unreachable — it deliberately never calls the engine directly from there (that crashed
 * reproducibly, see [io.github.kingg22.godot.codegen.extensionapi.impl.knative.impl.EngineClassImplGen]'s
 * "RefCounted" doc). Instead it only builds a [io.github.kingg22.godot.api.builtin.Callable] and pushes
 * it onto Godot's `MessageQueue` via `callDeferred()` — safe to call from any thread — so the actual
 * `unreference()`/`object_destroy()` runs on the main thread at the next idle frame.
 */
@InternalBinding
public fun attachRefCountedRelease(instance: RefCounted, rawPtr: GDExtensionObjectPtr) {
    instance.kogotReleaseCleaner = createCleaner(rawPtr, ::deferRefCountedRelease)
}

private fun deferRefCountedRelease(rawPtr: GDExtensionObjectPtr) {
    val callable = CallableFactory.create {
        val shouldFree = memScoped {
            val retPtr = allocGdBool()
            ObjectBinding.methodBindPtrcallRaw(unreferenceMethodBind, rawPtr, null, retPtr)
            retPtr.readGdBool()
        }
        if (shouldFree) ObjectBinding.destroyRaw(rawPtr)
    }
    callable.callDeferred()
}
