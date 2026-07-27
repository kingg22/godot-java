package io.github.kingg22.godot.codegen.runtime.impl

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.withIndent
import io.github.kingg22.godot.codegen.impl.buildLazyBlock
import io.github.kingg22.godot.codegen.impl.createFile
import io.github.kingg22.godot.codegen.models.config.CodegenOptions
import io.github.kingg22.godot.codegen.models.extensioninterface.GDExtensionInterface
import io.github.kingg22.godot.codegen.models.extensioninterface.Interface
import io.github.kingg22.godot.codegen.types.K_SUPPRESS
import io.github.kingg22.godot.codegen.types.cinteropCstr
import io.github.kingg22.godot.codegen.types.cinteropInvoke
import io.github.kingg22.godot.codegen.types.cinteropReinterpret
import io.github.kingg22.godot.codegen.types.memScoped

private val PLATFORM_TYPES = setOf(
    "string_new_with_wide_chars",
    "string_new_with_wide_chars_and_len",
    "string_to_wide_chars",
    "string_operator_plus_eq_wcstr",
)

class RuntimeFFIGenerator(private val packageName: String) {
    private val convenienceGen = RuntimeFFIConvenienceGen()

    fun generate(interfaceModel: GDExtensionInterface, options: CodegenOptions): Sequence<FileSpec> {
        val packageRegistry = RuntimePackageRegistry(packageName, interfaceModel)
        val resolver = RuntimeTypeResolver(interfaceModel, packageName)
        val skipPlatformSpecificApis = options.skipPlatformSpecificApis

        return interfaceModel.interfaces
            .groupBy { prefixOf(it) }
            .asSequence()
            .map { (prefix, interfaces) ->
                context(packageRegistry, resolver) {
                    generateGroupFile(prefix, interfaces, skipPlatformSpecificApis)
                }
            }
    }

    context(packageRegistry: RuntimePackageRegistry, resolver: RuntimeTypeResolver)
    private fun generateGroupFile(
        prefix: String,
        interfaces: List<Interface>,
        skipPlatformSpecificApis: Boolean,
    ): FileSpec {
        val className = packageRegistry.bindingClassName(prefix)

        val typeSpec = TypeSpec
            .objectBuilder(className)
            .addKdoc(
                "Runtime bindings for `%L_*` symbols loaded through `getProcAddress`.\n\n" +
                    "Generated from `gdextension_interface.json`.",
                prefix,
            )
            .addAnnotation(
                AnnotationSpec
                    .builder(K_SUPPRESS)
                    .addMember("%S", "DEPRECATION")
                    .addMember("%S", "DEPRECATION_ERROR")
                    .build(),
            )
            .apply {
                interfaces.forEach { iface ->
                    if (skipPlatformSpecificApis && iface.name in PLATFORM_TYPES) return@forEach
                    addProperty(buildFunctionPointerProperty(iface))
                    addFunction(buildRawWrapper(iface))
                    convenienceGen.buildConvenienceWrappers(iface).forEach(::addFunction)
                }
            }
            .build()

        return createFile(typeSpec, className.simpleName, packageRegistry.rootPackage)
    }

    context(packageRegistry: RuntimePackageRegistry, resolver: RuntimeTypeResolver)
    private fun buildFunctionPointerProperty(iface: Interface): PropertySpec {
        val aliasType = resolver.resolveInterfaceAlias(iface)
        val propertyName = functionPointerPropertyName(iface)
        val procAddressHolder = packageRegistry.classNameForOrDefault("BindingProcAddressHolder")

        return PropertySpec
            .builder(propertyName, aliasType)
            .addModifiers(KModifier.PRIVATE)
            .apply {
                iface.deprecated?.let { addAnnotation(deprecatedAnnotation(it)) }
            }
            .delegate(
                buildLazyBlock {
                    beginControlFlow("%M", memScoped)
                        .addStatement("%T.getProcAddress(%S.%M.ptr)", procAddressHolder, iface.name, cinteropCstr)
                        .withIndent {
                            addStatement("?.%M()", cinteropReinterpret)
                            addStatement("?: error(%S)", "Failed to load Godot symbol '${iface.name}'")
                        }
                    endControlFlow()
                },
            )
            .build()
    }

    context(resolver: RuntimeTypeResolver)
    private fun buildRawWrapper(iface: Interface): FunSpec {
        val propertyName = functionPointerPropertyName(iface)
        val parameters = iface.arguments.mapIndexed { index, argument ->
            buildParameter(argument, index)
        }
        val hasReturn = iface.returnValue != null && iface.returnValue!!.type != "void"

        return FunSpec
            .builder(rawWrapperName(iface))
            .apply {
                parameters.forEach(::addParameter)
                if (hasReturn) {
                    returns(resolver.resolve(iface.returnValue!!))
                }

                addKdoc("C symbol: `%L`.", iface.name)
                val kdoc = buildKdoc(iface)
                if (kdoc.isNotBlank()) {
                    addKdoc("\n\n%L", kdoc)
                }
                iface.deprecated?.let { addAnnotation(deprecatedAnnotation(it)) }
            }
            .addCode(
                buildInvokeCode(
                    propertyName = propertyName,
                    parameterNames = parameters.map(ParameterSpec::name),
                    hasReturn = hasReturn,
                ),
            )
            .build()
    }

    private fun buildInvokeCode(propertyName: String, parameterNames: List<String>, hasReturn: Boolean): CodeBlock {
        val invocation = CodeBlock.of(
            "%N.%M(%L)",
            propertyName,
            cinteropInvoke,
            parameterNames.joinToString(", "),
        )

        return CodeBlock.builder().apply {
            if (hasReturn) {
                addStatement("return %L", invocation)
            } else {
                addStatement("%L", invocation)
            }
        }.build()
    }
}
