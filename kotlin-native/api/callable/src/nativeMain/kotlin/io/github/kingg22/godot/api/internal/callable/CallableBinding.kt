package io.github.kingg22.godot.api.internal.callable

import io.github.kingg22.godot.api.GodotNative
import io.github.kingg22.godot.internal.binding.BindingProcAddressHolder
import io.github.kingg22.godot.internal.binding.CallableBinding
import io.github.kingg22.godot.internal.binding.InternalBinding
import io.github.kingg22.godot.internal.binding.VariantBinding
import io.github.kingg22.godot.internal.binding.getInstance
import io.github.kingg22.godot.internal.ffi.GDExtensionCallError
import io.github.kingg22.godot.internal.ffi.GDExtensionCallErrorType
import io.github.kingg22.godot.internal.ffi.GDExtensionCallableCustomCall
import io.github.kingg22.godot.internal.ffi.GDExtensionCallableCustomInfo2
import io.github.kingg22.godot.internal.ffi.GDExtensionConstVariantPtr
import io.github.kingg22.godot.internal.ffi.GDExtensionConstVariantPtrVar
import io.github.kingg22.godot.internal.ffi.GDExtensionInt
import io.github.kingg22.godot.internal.ffi.GDExtensionVariantPtr
import kotlinx.cinterop.*

/**
 * This is a low-level binding for custom callables. You must use `CallabeFactory` to create custom callables or use the `Callable` class.
 *
 * The initialization of `CallableFactory` link properties of this class. Must not be invoked before that happens,
 * otherwise the binding throws errors with null values.
 *
 * Currently missing correct support to convert arguments to kotlin types. So must use `Any?` or exact `Variant` types,
 * `Variant` itself as argument is not supported.
 *
 * Currently, all callables are `static` for Godot, missing linking to the object. This can lead to incorrect freeing.
 */
@InternalBinding
public object CallableBinding {
    public var onError: (message: String) -> Unit = ::println
    public var onVariantToKotlin: (GDExtensionConstVariantPtr?) -> Any? = { null }
    public var onToVariant: (Any?) -> GodotNative? = { null }

    // Create the GDExtensionCallableCustomInfo2 struct using cValue
    public fun createCustomInfo2(lambda: Function<*>, objectId: ULong = 0uL): CValue<GDExtensionCallableCustomInfo2> =
        createCustomInfo2(
            // Create a KotlinCallable wrapper and store it in a StableRef on userdata
            storeKotlinFunctionAsKotlinCallable(lambda),
            createCustomCall(),
            objectId,
        )

    public fun createCustomCall(): GDExtensionCallableCustomCall = staticCFunction {
            userdata: COpaquePointer?,
            arguments: CArrayPointer<GDExtensionConstVariantPtrVar>?,
            argumentCount: GDExtensionInt,
            rReturn: GDExtensionVariantPtr?,
            rError: CPointer<GDExtensionCallError>?,
        ->
        val callable = userdata?.getInstance<KotlinCallable>()

        if (callable == null || rReturn == null || rError == null) {
            onError(
                "Invalid arguments received from Godot to Callable custom call: $arguments, $argumentCount, $rReturn, $rError, $userdata == $callable",
            )
            return@staticCFunction
        }

        fun fillCallError(
            error: GDExtensionCallErrorType,
            expected: Int = callable.arity().toInt(),
            argument: Int = argumentCount.toInt(),
        ) {
            val callError = rError.pointed
            callError.error = error
            callError.expected = expected
            callError.argument = argument
        }

        if (argumentCount != callable.arity()) {
            onError("[kogot]: Error: callable called with $argumentCount arguments, expected ${callable.arity()}")
            fillCallError(
                if (argumentCount < callable.arity()) {
                    GDEXTENSION_CALL_ERROR_TOO_FEW_ARGUMENTS
                } else {
                    GDEXTENSION_CALL_ERROR_TOO_MANY_ARGUMENTS
                },
            )
            return@staticCFunction
        }

        if (argumentCount != 0L && arguments == null) {
            onError("[kogot]: Error: callable called with null array of arguments, expected $argumentCount")
            fillCallError(GDEXTENSION_CALL_ERROR_INVALID_ARGUMENT)
            return@staticCFunction
        }

        // FIXME validate arguments order is preserved
        val arguments = Array(argumentCount.toInt()) { i ->
            // FIXME requires apropiate conversion to Kotlin types. Requires supports downcast, Variant, KString
            onVariantToKotlin(arguments?.get(i))
        }

        val result: Any? = runCatching { callable.invoke(arguments) }.getOrElse { exception ->
            onError(
                "[kogot]: Error calling callable ${callable.arity()} args, fallback to null: $exception",
            )
            fillCallError(GDEXTENSION_CALL_ERROR_INVALID_METHOD)
            return@staticCFunction
        }

        // TODO: properly convert return types to Kotlin types
        // (aka if the user request T, the argument must preserve the type T, not unbox or box unrequested)
        val resultVariant = onToVariant(result) ?: run {
            onError("[kogot]: Error converting callable result ($result) to Variant, fallback to null")
            fillCallError(GDEXTENSION_CALL_ERROR_INVALID_METHOD)
            return@staticCFunction
        }

        fillCallError(GDEXTENSION_CALL_OK, 0, 0)
        VariantBinding.newCopyRaw(rReturn, resultVariant.rawPtr)
    }

    // Call the low-level Godot function to create custom callable and fill the callable object
    public fun createCustom2(callableCustomInfo2: CValue<GDExtensionCallableCustomInfo2>, callablePtr: COpaquePointer) {
        memScoped {
            CallableBinding.customCreate2Raw(
                callablePtr,
                callableCustomInfo2.ptr,
            )
        }
    }

    /** Wraps a [Kotlin function][Function] into a [KotlinCallable] using [StableRef] */
    public fun storeKotlinFunctionAsKotlinCallable(lambda: Function<*>): COpaquePointer =
        StableRef.create(wrapLambda(lambda)).asCPointer()

    /**
     * Creates a [GDExtensionCallableCustomInfo2] struct.
     * Delegates to [CallableTrampolines]
     * @param userdata Expects a [KotlinCallable] as [COpaquePointer]
     * @param call The custom function to call this callable
     * @param objectId 0 means _custom_, not bind to object
     */
    public fun createCustomInfo2(
        userdata: COpaquePointer,
        call: GDExtensionCallableCustomCall,
        objectId: ULong = 0uL,
    ): CValue<GDExtensionCallableCustomInfo2> = cValue {
        object_id = objectId
        callable_userdata = userdata
        call_func = call
        token = BindingProcAddressHolder.library
        is_valid_func = CallableTrampolines.isValidFunc
        free_func = CallableTrampolines.freeFunc
        hash_func = CallableTrampolines.hashFunc
        equal_func = CallableTrampolines.equalFunc
        less_than_func = CallableTrampolines.lessThanFunc
        to_string_func = CallableTrampolines.toStringFunc
        get_argument_count_func = CallableTrampolines.getArgumentCountFunc
    }
}
