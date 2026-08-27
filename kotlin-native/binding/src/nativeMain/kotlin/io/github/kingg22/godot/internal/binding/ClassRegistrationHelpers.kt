package io.github.kingg22.godot.internal.binding

import io.github.kingg22.godot.api.builtin.toStringName
import io.github.kingg22.godot.api.core.GodotObject
import io.github.kingg22.godot.api.core.Node
import io.github.kingg22.godot.internal.ffi.*
import kotlinx.cinterop.*
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/**
 * Notification function that calls [Node._ready] when [Node.NOTIFICATION_READY] is received.
 *
 * Godot sends `NOTIFICATION_READY` (value 13) when a node enters the scene tree and is ready.
 * This function dispatches it to the Kotlin instance's `_ready` method.
 */
@InternalBinding
public val notificationFunc: GDExtensionClassNotification2 = staticCFunction { instancePtr, notification, _ ->
    when (notification.toLong()) {
        Node.NOTIFICATION_READY -> {
            // println("[Kogot] NotificationFunc: Received Node.NOTIFICATION_READY, calling _ready to $instancePtr")
            instancePtr.getInstance<Node>()._ready()
        }

        else -> {
            // Floods the console with notifications, not useful
            // println("[Kogot] NotificationFunc: Received notification: $notification")
        }
    }
}

/**
 * Creates a create_instance function for the given class.
 *
 * Must be called with [staticCFunction]
 *
 * @param T The class type to instantiate
 * @param parentClassName The Godot parent class name (e.g., "Node2D", "Sprite2D")
 * @param factory A lambda that creates a new instance of T wrapped in StableRef
 */
@InternalBinding
public fun <T : GodotObject> createInstanceFunc(
    parentClassName: String,
    className: String,
    notifyPostInitialize: Boolean = false,
    factory: (parentPtr: GDExtensionObjectPtr) -> T,
): GDExtensionObjectPtr? {
    contract { callsInPlace(factory, InvocationKind.AT_MOST_ONCE) }

    try {
        // println("[Kogot] CreateInstance: Creating $parentClassName instance")
        val base = parentClassName.toStringName().use { str ->
            ClassDBBinding.constructObject3Raw(str.rawPtr)
        } ?: error("Failed to construct base $parentClassName")
        // println("[Kogot] CreateInstance: Base $parentClassName constructed. $base")

        val instance = try {
            factory(base)
        } catch (e: Exception) {
            // println("[Kogot] CreateInstance: Failed to create instance of $className with $parentClassName: $base")
            e.printStackTrace()
            return null
        }
        val selfRef = StableRef.create(instance)
        val selfPtr = selfRef.asCPointer()

        className.toStringName().use { str ->
            ObjectBinding.setInstanceRaw(
                base,
                str.rawPtr,
                selfPtr,
            )
        }

        memScoped {
            ObjectBinding.setInstanceBindingRaw(
                pO = base,
                pToken = BindingProcAddressHolder.library,
                // Mirrors `instance` for castTo/GD.load/instantiate<T>() lookups (issue #114) — the
                // instance itself is kept alive for its whole native lifetime by `setInstanceRaw`
                // above, so this mirror is safe to be a weak reference; see wrapForEagerBinding.
                pBinding = wrapForEagerBinding(instance),
                pCallbacks = cValue<GDExtensionInstanceBindingCallbacks> {
                    create_callback = null
                    free_callback = identityFreeCallback
                    reference_callback = null
                }.ptr,
            )
        }

        // Send NOTIFICATION_POSTINITIALIZE if Godot requests it
        if (notifyPostInitialize) {
            // println("[Kogot] CreateInstance: Sending NOTIFICATION_POSTINITIALIZE to $className ($instance)")
            instance.notification(GodotObject.NOTIFICATION_POSTINITIALIZE.toInt())
        }

        /*
    println(
        "[Kogot] CreateInstance: Instance of $className created successfully, instance: ${instance.rawPtr}. $instance",
    )
         */

        return base
    } catch (e: Exception) {
        println("[Kogot] FATAL CreateInstance: Failed to create instance of $className with $parentClassName")
        e.printStackTrace()
        return null
    }
}

/**
 * Creates a free_instance function that disposes the per-instance StableRef.
 *
 * `userData` (Godot's `p_class_userdata`) is the *per-class* [StableRef] created once in
 * [registerClass], shared by every instance of that class — it must **not** be disposed here, only
 * when the class itself is unregistered. Disposing it on every instance free used to double-dispose
 * it (and leave it dangling for the class's other, still-alive instances) as soon as a second instance
 * of the same class was ever freed; see issue #114.
 */
@InternalBinding
public val freeInstanceFunc: GDExtensionClassFreeInstance = staticCFunction { _, ptr ->
    // println("[Kogot] FreeInstance: Freeing instance: $ptr")
    ptr?.asStableRef<Any>()?.dispose()
}

@InternalBinding
public val createToStringFunc: GDExtensionClassToString = staticCFunction { instancePtr, isValidPtr, outStrPtr ->
    if (isValidPtr == null) return@staticCFunction
    if (instancePtr == null) {
        isValidPtr.pointed.value = GDExtensionBool.FALSE
        return@staticCFunction
    }

    val toStringMsg = try {
        val instance = instancePtr.asStableRef<Any>().get()
        instance.toString().utf16
    } catch (e: Exception) {
        println("Failed to get toString() for $instancePtr")
        e.printStackTrace()
        isValidPtr.pointed.value = GDExtensionBool.FALSE
        return@staticCFunction
    }

    isValidPtr.pointed.value = GDExtensionBool.TRUE

    memScoped {
        StringBinding.newWithUtf16CharsRaw(outStrPtr, toStringMsg.ptr)
    }
}

@InternalBinding
public fun classCreationInfo6(
    createInstance: GDExtensionClassCreateInstance2,
    getVirtual: GDExtensionClassGetVirtual2,
    ptrUserData: COpaquePointer,
    freeInstance: GDExtensionClassFreeInstance = freeInstanceFunc,
    isVirtual: GDExtensionBool = GDExtensionBool.FALSE,
    isAbstract: GDExtensionBool = GDExtensionBool.FALSE,
    isExposed: GDExtensionBool = GDExtensionBool.TRUE,
): CValue<GDExtensionClassCreationInfo6> = cValue {
    is_virtual = isVirtual
    is_abstract = isAbstract
    is_exposed = isExposed
    set_func = null
    get_func = null
    get_property_list_func = null
    free_property_list_func = null
    property_can_revert_func = null
    property_get_revert_func = null
    validate_property_func = null
    notification_func = notificationFunc
    to_string_func = createToStringFunc
    reference_func = null
    unreference_func = null
    recreate_instance_func = null
    get_virtual_func = getVirtual
    get_virtual_call_data_func = null
    call_virtual_with_data_func = null
    class_userdata = ptrUserData
    create_instance_func = createInstance
    free_instance_func = freeInstance
}

/**
 * Registers a class with Godot's ClassDB.
 *
 * @param T The Kotlin class type
 * @param className The Godot class name to register
 * @param parentClassName The Godot parent class name (e.g., "Node2D", "Sprite2D")
 * @param createInstance The create_instance function
 * @param getVirtual The get_virtual function
 */
@InternalBinding
public inline fun <reified T : GodotObject> registerClass(
    className: String,
    parentClassName: String,
    createInstance: GDExtensionClassCreateInstance2,
    getVirtual: GDExtensionClassGetVirtual2,
) {
    // println("[Kogot] Registering $className extends $parentClassName")

    val info = classCreationInfo6(createInstance, getVirtual, StableRef.create(T::class).asCPointer())

    className.toStringName().use { classStringName ->
        parentClassName.toStringName().use { parentStringName ->
            memScoped {
                ClassDBBinding.registerExtensionClass6Raw(
                    BindingProcAddressHolder.library,
                    classStringName.rawPtr,
                    parentStringName.rawPtr,
                    info.ptr,
                )
            }
        }
    }

    // println("[Kogot] Registered class: '$className' extends '$parentClassName' successfully")
}
