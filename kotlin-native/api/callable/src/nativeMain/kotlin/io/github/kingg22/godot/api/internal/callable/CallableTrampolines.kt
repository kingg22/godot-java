package io.github.kingg22.godot.api.internal.callable

import io.github.kingg22.godot.internal.binding.InternalBinding
import io.github.kingg22.godot.internal.binding.StringBinding
import io.github.kingg22.godot.internal.binding.getInstance
import io.github.kingg22.godot.internal.ffi.*
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.set
import kotlinx.cinterop.staticCFunction

/**
 * Static C trampolines for custom callable callbacks.
 *
 * These trampolines are invoked by Godot when a custom callable is called.
 * They extract the userdata (which is a StableRef containing a Long ID),
 * retrieve the KotlinCallable from the map, and dispatch to it.
 */
@InternalBinding
public object CallableTrampolines {

    /**
     * Free trampoline - invoked when the callable is destroyed.
     * Cleans up the userdata from the map.
     */
    public val freeFunc: GDExtensionCallableCustomFree = staticCFunction { userdata ->
        println("Freeing callable with userdata: $userdata")
        val ref = userdata?.asStableRef<KotlinCallable>()
        ref?.dispose()
    }

    /**
     * Hash trampoline - returns hash of the callable.
     */
    public val hashFunc: GDExtensionCallableCustomHash = staticCFunction { userdata ->
        userdata?.getInstance<KotlinCallable>()?.hashCode()?.toUInt() ?: 0u
    }

    /**
     * Is valid trampoline - checks if callable is still valid.
     */
    public val isValidFunc: GDExtensionCallableCustomIsValid = staticCFunction { userdata ->
        val callable = userdata?.getInstance<KotlinCallable>()
        if (callable != null && callable.isValid()) GDExtensionBool.TRUE else GDExtensionBool.FALSE
    }

    /**
     * Equal trampoline - checks equality with another callable.
     */
    public val equalFunc: GDExtensionCallableCustomEqual = staticCFunction { userdataA, userdataB ->
        val callableA = userdataA?.getInstance<KotlinCallable>()
        val callableB = userdataB?.getInstance<KotlinCallable>()
        if (callableA != null && callableB != null && callableA == callableB) {
            GDExtensionBool.TRUE
        } else {
            GDExtensionBool.FALSE
        }
    }

    /**
     * Less than trampoline - comparison for sorting.
     */
    public val lessThanFunc: GDExtensionCallableCustomLessThan = staticCFunction { userdataA, userdataB ->
        val callableA = userdataA?.getInstance<KotlinCallable>()
        val callableB = userdataB?.getInstance<KotlinCallable>()
        if (callableA != null && callableB != null && callableA.lessThan(callableB)) {
            GDExtensionBool.TRUE
        } else {
            GDExtensionBool.FALSE
        }
    }

    /**
     * Argument count trampoline - returns the number of arguments.
     */
    public val getArgumentCountFunc: GDExtensionCallableCustomGetArgumentCount = staticCFunction { userdata, rIsValid ->
        val callable = userdata?.getInstance<KotlinCallable>()
        val arity = callable?.arity() ?: 0L
        if (rIsValid != null) {
            val validFlag = if (callable != null && callable.isValid()) GDExtensionBool.TRUE else GDExtensionBool.FALSE
            rIsValid[0] = validFlag
        }
        arity
    }

    /**
     * To string trampoline - returns string representation for debugging.
     */
    public val toStringFunc: GDExtensionCallableCustomToString = staticCFunction { userdata, rIsValid, rReturnPtr ->
        val callable = userdata?.getInstance<KotlinCallable>()
        if (rIsValid != null) {
            val validFlag = if (callable != null && callable.isValid()) GDExtensionBool.TRUE else GDExtensionBool.FALSE
            rIsValid[0] = validFlag
        }
        val string = callable?.toString() ?: "Invalid Callable"
        StringBinding.newWithUtf8Chars(rReturnPtr, string)
    }
}
