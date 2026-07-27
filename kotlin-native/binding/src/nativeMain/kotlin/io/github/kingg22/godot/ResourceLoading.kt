package io.github.kingg22.godot

import io.github.kingg22.godot.api.core.refcounted.Resource
import io.github.kingg22.godot.api.singleton.ResourceLoader
import io.github.kingg22.godot.api.utils.GD
import io.github.kingg22.godot.internal.ffi.GDExtensionObjectPtr
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/**
 * Loads the resource at [path] via [ResourceLoader] and safely casts it to [T].
 *
 * [typeHint] defaults to [T]'s simple name, following the same Kotlin-class-name-as-Godot-class-name
 * convention already used by [castTo]. There is no runtime Godot-class-name -> Kotlin-constructor
 * registry yet, so the caller must supply [factory] (e.g. `::Texture2D`) to construct [T] from the
 * loaded native pointer.
 *
 * @throws ClassCastException if the resource at [path] is not actually a [T].
 * @see ResourceLoader.load
 */
public inline fun <reified T : Resource> GD.load(
    path: String,
    typeHint: String = T::class.simpleName!!,
    cacheMode: ResourceLoader.CacheMode = ResourceLoader.CacheMode.REUSE,
    crossinline factory: (nativePtr: GDExtensionObjectPtr) -> T,
): T {
    contract { callsInPlace(factory, InvocationKind.AT_MOST_ONCE) }
    return ResourceLoader.instance.load(path, typeHint, cacheMode).castTo(factory)
}

/**
 * Like [load], but returns `null` instead of throwing when the resource at [path] is not a [T].
 */
public inline fun <reified T : Resource> GD.loadOrNull(
    path: String,
    typeHint: String = T::class.simpleName!!,
    cacheMode: ResourceLoader.CacheMode = ResourceLoader.CacheMode.REUSE,
    crossinline factory: (nativePtr: GDExtensionObjectPtr) -> T,
): T? {
    contract { callsInPlace(factory, InvocationKind.AT_MOST_ONCE) }
    return ResourceLoader.instance.load(path, typeHint, cacheMode).castToOrNull(factory)
}
