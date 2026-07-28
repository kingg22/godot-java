package io.github.kingg22.godot.codegen.extensionapi.impl.knative.impl

import io.github.kingg22.godot.codegen.models.extensioninterface.GDExtensionInterface
import io.github.kingg22.godot.codegen.runtime.impl.RuntimePackageRegistry
import io.github.kingg22.godot.codegen.services.PackageRegistry

class ImplementationPackageRegistry(packageStr: String, interfaceModel: GDExtensionInterface) : PackageRegistry {
    private val runtimeBindingPackageRegistry: PackageRegistry = RuntimePackageRegistry(packageStr, interfaceModel)
    override val rootPackage: String = runtimeBindingPackageRegistry.rootPackage

    private val bindingPackage = runtimeBindingPackageRegistry.rootPackage
    private val builtinInternalBind = if (packageStr.endsWith(".api")) {
        "$packageStr.builtin.internal"
    } else {
        "$packageStr.api.builtin.internal"
    }
    private val apiInternalPackage = if (packageStr.endsWith(".api")) {
        "$packageStr.internal"
    } else {
        "$packageStr.api.internal"
    }
    private val ffiPackage = if (packageStr.endsWith(".api")) {
        "${packageStr.removeSuffix(".api")}.internal.ffi"
    } else {
        "$packageStr.internal.ffi"
    }

    private val typeToPackage: Map<String, String> = buildMap {
        put("allocConstTypePtrArray", bindingPackage)
        put("allocateBuiltinStorage", bindingPackage)
        put("allocGdBool", bindingPackage)
        put("readGdBool", bindingPackage)
        put("checkCallError", bindingPackage)
        put("toGdBool", bindingPackage)
        put("toBoolean", bindingPackage)
        put("getBuiltin", bindingPackage)
        put("setBuiltin", bindingPackage)
        put("toGDE", builtinInternalBind)
        put("anyToVariant", builtinInternalBind)
        put("anyToVariantOrNull", builtinInternalBind)
        put("getValue", builtinInternalBind)
        put("GDExtensionPtrUtilityFunction", ffiPackage)
        put("GDExtensionPtrGetter", ffiPackage)
        put("GDExtensionPtrSetter", ffiPackage)
        put("GDExtensionPtrConstructor", ffiPackage)
        put("GDExtensionPtrDestructor", ffiPackage)
        put("GDExtensionVariantFromTypeConstructorFunc", ffiPackage)
        put("GDExtensionTypeFromVariantConstructorFunc", ffiPackage)
        put("GDExtensionPtrBuiltInMethod", ffiPackage)
        put("GDExtensionMethodBindPtr", ffiPackage)
        put("GDExtensionPtrOperatorEvaluator", ffiPackage)
        put("GDExtensionObjectPtrVar", ffiPackage)
        put("GDExtensionClassCallVirtual", ffiPackage)
        put("InternalBinding", bindingPackage)
        put("getInstance", bindingPackage)
        put("toBoolean", bindingPackage)
        put("toGdBool", bindingPackage)
        put("checkGodotError", apiInternalPackage)
        put("CallableFactory", "$apiInternalPackage.callable")
    }

    override fun packageFor(godotName: String): String? = typeToPackage[godotName]
        ?: runtimeBindingPackageRegistry.packageFor(godotName)
}
