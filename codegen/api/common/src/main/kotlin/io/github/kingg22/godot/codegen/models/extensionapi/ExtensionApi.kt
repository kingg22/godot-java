package io.github.kingg22.godot.codegen.models.extensionapi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class ExtensionApi(
    val header: Header,
    @SerialName("builtin_class_sizes") val builtinClassSizes: List<BuiltinSizes> = emptyList(),
    @SerialName("builtin_class_member_offsets") val builtinClassMemberOffsets: List<BuiltinClassMemberOffsets> =
        emptyList(),
    @SerialName("builtin_classes") val builtinClasses: List<BuiltinClass> = emptyList(),
    val classes: List<EngineClass> = emptyList(),
    @SerialName("global_constants") val globalConstants: List<GlobalConstant> = emptyList(),
    @SerialName("global_enums") val globalEnums: List<ApiEnum> = emptyList(),
    @SerialName("utility_functions") val utilityFunctions: List<UtilityFunction> = emptyList(),
    @SerialName("native_structures") val nativeStructures: List<NativeStructure> = emptyList(),
    val singletons: List<Singleton> = emptyList(),
)
