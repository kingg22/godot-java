package io.github.kingg22.kogot.processor.generators.kotlin

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.MemberName

private const val KOTLIN_PKG = "kotlin"
val K_SUPPRESS = ClassName(KOTLIN_PKG, "Suppress")
val K_OPT_IN = ClassName(KOTLIN_PKG, "OptIn")
private const val GODOT_PKG = "io.github.kingg22.godot"
private const val GODOT_INTERNAL_BINDING_PKG = "$GODOT_PKG.internal.binding"

val InternalBindingClassName = ClassName(GODOT_INTERNAL_BINDING_PKG, "InternalBinding")
private const val K_CINTEROP_PKG = "kotlinx.cinterop"
val ExperimentalForeignApi = ClassName(K_CINTEROP_PKG, "ExperimentalForeignApi")
val COpaquePointerClassName = ClassName(K_CINTEROP_PKG, "COpaquePointer")

val STATIC_C_FUNCTION = MemberName(K_CINTEROP_PKG, "staticCFunction")
val MEM_SCOPED = MemberName(K_CINTEROP_PKG, "memScoped")
val C_VALUE = MemberName(K_CINTEROP_PKG, "cValue")
val AS_STABLE_REF = MemberName(K_CINTEROP_PKG, "asStableRef", true)
val POINTED = MemberName(K_CINTEROP_PKG, "pointed", true)
val POINTED_VALUE = MemberName(K_CINTEROP_PKG, "value", true)
val CINTEROP_GET = MemberName(K_CINTEROP_PKG, "get", true)

val ObjectBindingClassName = ClassName(GODOT_INTERNAL_BINDING_PKG, "ObjectBinding")
val BindingProcAddressHolderClassName = ClassName(GODOT_INTERNAL_BINDING_PKG, "BindingProcAddressHolder")
val ClassDBBindingClassName = ClassName(GODOT_INTERNAL_BINDING_PKG, "ClassDBBinding")
val InstanceStorageClassName = ClassName(GODOT_INTERNAL_BINDING_PKG, "InstanceStorage")
val RESOLVE_VIRTUAL_CALL = MemberName(GODOT_INTERNAL_BINDING_PKG, "resolveVirtualCall")

private const val GODOT_INTERNAL_FFI_PKG = "$GODOT_PKG.internal.ffi"
val GDExtensionObjectPtrClassName = ClassName(GODOT_INTERNAL_FFI_PKG, "GDExtensionObjectPtr")
val GDExtensionInstanceBindingCallbacksClassName =
    ClassName(GODOT_INTERNAL_FFI_PKG, "GDExtensionInstanceBindingCallbacks")
val GDExtensionBoolClassName = ClassName(GODOT_INTERNAL_FFI_PKG, "GDExtensionBool")
val GDExtensionBoolTrueMember = MemberName(GODOT_INTERNAL_FFI_PKG, "TRUE")

val BindingInitializationCallbacksClassName =
    ClassName(GODOT_INTERNAL_BINDING_PKG, "BindingInitializationCallbacks")

val AS_STRING_NAME = MemberName("$GODOT_PKG.api.builtin", "asStringName")

val CREATE_FREE_INSTANCE_FUN = MemberName(GODOT_INTERNAL_BINDING_PKG, "createFreeInstanceFunc")

val REGISTER_CLASS = MemberName(GODOT_INTERNAL_BINDING_PKG, "registerClass")

val REGISTER_CUSTOM_SIGNAL = MemberName(GODOT_INTERNAL_BINDING_PKG, "registerCustomSignal")

val REGISTER_PROPERTY = MemberName(GODOT_INTERNAL_BINDING_PKG, "registerProperty")

val REGISTER_METHOD = MemberName(GODOT_INTERNAL_BINDING_PKG, "registerMethod")

val REGISTER_METHOD_WITH_GETTER_SETTER =
    MemberName(GODOT_INTERNAL_BINDING_PKG, "registerPropertyWithGetterSetter")

val REGISTER_SIGNAL_CLASS_NAME = ClassName("$GODOT_PKG.api.annotations", "RegisterSignal")

val REGISTER_SIGNAL_PARAM_CLASS_NAME = ClassName("$GODOT_PKG.api.annotations", "RegisterSignal", "Param")

val VARIANT_CLASS_NAME = ClassName("$GODOT_PKG.api.builtin", "Variant")
val VARIANT_TYPE_CLASS_NAME = VARIANT_CLASS_NAME.nestedClass("Type")
val TO_VARIANT = MemberName("$GODOT_PKG.api.builtin", "toVariant", true)
val VARIANT_GET_VALUE = MemberName("$GODOT_PKG.api.builtin", "getValue", true)
val VARIANT_GET_VALUE_OR_NULL = MemberName("$GODOT_PKG.api.builtin", "getValueOrNull", true)
val VECTOR2_CLASS_NAME = ClassName("$GODOT_PKG.api.builtin", "Vector2")
val VECTOR3_CLASS_NAME = ClassName("$GODOT_PKG.api.builtin", "Vector3")

val CREATE_INSTANCE_FUN = MemberName(GODOT_INTERNAL_BINDING_PKG, "createInstanceFunc")

val ClassDBClassName = ClassName("$GODOT_PKG.api.singleton", "ClassDB")
val GDExtensionCallErrorType = ClassName(GODOT_INTERNAL_FFI_PKG, "GDExtensionCallErrorType")
val GDExtensionClassMethodCall = ClassName(GODOT_INTERNAL_FFI_PKG, "GDExtensionClassMethodCall")

val PropertyHintClassName = ClassName("$GODOT_PKG.api", "PropertyHint")
val PropertyUsageFlagsClassName = ClassName("$GODOT_PKG.api", "PropertyUsageFlags")
val PropertyUsageFlagsDefaultMember = MemberName("$GODOT_PKG.api", "DEFAULT")
val TO_GDE = MemberName("$GODOT_PKG.api.builtin.internal", "toGDE")
val ARRAY_OF = MemberName(KOTLIN_PKG, "arrayOf")

val GDEXTENSION_METHOD_FLAGS_CLASS_NAME = ClassName(GODOT_INTERNAL_FFI_PKG, "GDExtensionClassMethodFlags")

val METHOD_ARGUMENT_CLASS_NAME = ClassName(GODOT_INTERNAL_BINDING_PKG, "MethodArgument")

val REGISTER_METHOD_GETTER = MemberName(GODOT_INTERNAL_BINDING_PKG, "registerMethodGetter")

val REGISTER_METHOD_SETTER = MemberName(GODOT_INTERNAL_BINDING_PKG, "registerMethodSetter")

val METHOD_FLAGS_CLASS_NAME = ClassName(GODOT_PKG, "MethodFlags")
val ENUM_MASK_CLASS_NAME = ClassName(GODOT_PKG, "EnumMask")

val TO_ENUM_MASK = MemberName(GODOT_PKG, "toEnumMask")
val GET_INSTANCE_PTR = MemberName(GODOT_INTERNAL_BINDING_PKG, "getInstance", true)
val VARIANT_BINDING = ClassName(GODOT_INTERNAL_BINDING_PKG, "VariantBinding")
val CallErrorWritePtr = MemberName(GODOT_INTERNAL_BINDING_PKG, "write", true)

private const val GODOT_INTERNAL_SCRIPT_PKG = "$GODOT_PKG.internal.script"
val KOTLIN_SCRIPT_REGISTRY = ClassName(GODOT_INTERNAL_SCRIPT_PKG, "KotlinScriptRegistry")
val KOTLIN_SCRIPT_REGISTRY_ENTRY = KOTLIN_SCRIPT_REGISTRY.nestedClass("Entry")
val KOTLIN_SCRIPT_REGISTRATION = ClassName(GODOT_INTERNAL_SCRIPT_PKG, "KotlinScriptRegistration")
val KOTLIN_SCRIPT_PROPERTY_DESCRIPTOR = ClassName(GODOT_INTERNAL_SCRIPT_PKG, "ScriptPropertyDescriptor")
val KOTLIN_SCRIPT_METHOD_DESCRIPTOR = ClassName(GODOT_INTERNAL_SCRIPT_PKG, "ScriptMethodDescriptor")
