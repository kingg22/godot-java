package io.github.kingg22.godot.sample

import io.github.kingg22.godot.api.builtin.StringName
import io.github.kingg22.godot.api.builtin.toStringName
import io.github.kingg22.godot.api.singleton.ClassDB
import io.github.kingg22.godot.internal.binding.BindingProcAddressHolder
import io.github.kingg22.godot.internal.binding.InternalBinding
import io.github.kingg22.godot.internal.binding.ObjectBinding
import io.github.kingg22.godot.internal.binding.getInstance
import io.github.kingg22.godot.internal.ffi.GDExtensionClassCreateInstance2
import io.github.kingg22.godot.internal.ffi.GDExtensionClassFreeInstance
import io.github.kingg22.godot.internal.ffi.GDExtensionClassGetVirtual2
import io.github.kingg22.godot.internal.ffi.GDExtensionClassNotification2
import io.github.kingg22.godot.internal.ffi.GDExtensionInstanceBindingCallbacks
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.cValue
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.staticCFunction
import kotlin.reflect.KClass

val notificationFunc: GDExtensionClassNotification2 = staticCFunction { instancePtr, notification, reversed ->
    val _ = instancePtr
    val _ = notification
    val _ = reversed
}

val createInstanceFunc: GDExtensionClassCreateInstance2 = staticCFunction { _, _ ->
    println("CreateInstanceFunc: Creating CustomSignalClass instance")
    val base = ClassDB.instance.instantiate("Node".toStringName()).toObject().rawPtr
    println("CreateInstanceFunc: Base Node constructed")
    val self = CustomSignalClass(base)
    println("CreateInstanceFunc: Wrapper constructed")
    val selfRef = StableRef.create(self)
    println("CreateInstanceFunc: Ref created")
    val selfPtr = selfRef.asCPointer()
    println("CreateInstanceFunc: setInstance")
    ObjectBinding.setInstanceRaw(
        base,
        "CustomSignalClass".toStringName().rawPtr,
        selfPtr,
    )
    memScoped {
        ObjectBinding.setInstanceBindingRaw(
            pO = base,
            pToken = BindingProcAddressHolder.library,
            pBinding = selfPtr,
            pCallbacks = cValue<GDExtensionInstanceBindingCallbacks> {
                create_callback = null
                free_callback = null
                reference_callback = null
            }.ptr,
        )
    }
    println("CreateInstanceFunc: Instance created successfully")
    // TODO this is correct? createInstance expect returns the ptr of base or the custom class?
    return@staticCFunction base
}

val freeInstanceFunc: GDExtensionClassFreeInstance = staticCFunction { _, ptr ->
    require(ptr != null)
    println("FreeInstanceFunc: Freeing $ptr")
    // WTF is this?
    ptr.asStableRef<Any>().dispose()
}

@OptIn(InternalBinding::class)
val getVirtualFunc: GDExtensionClassGetVirtual2 = staticCFunction { classPtr, funcName, _ ->
    requireNotNull(funcName)

    try {
        val clazz = classPtr.getInstance<KClass<*>>()
        println("GetVirtualFunc: Getting virtual of $clazz (${clazz::class.qualifiedName}) $funcName")
        val stringName = StringName(funcName)
        println("GetVirtualFunc: Function name TODO...")
        if (clazz == CustomSignalClass::class) {
            println("GetVirtualFunc: Class is CustomSignalClass!")
            val readyStringName = "_ready".toStringName()
            if (stringName == readyStringName.also { it.close() }) {
                println("GetVirtualFunc: func is _ready")
                return@staticCFunction staticCFunction { instancePtr, _, _ ->
                    val instance = instancePtr.getInstance<CustomSignalClass>()
                    println("_ready called on CustomSignalClass")
                    instance._ready()
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }

    staticCFunction { _, _, _ -> }
}
