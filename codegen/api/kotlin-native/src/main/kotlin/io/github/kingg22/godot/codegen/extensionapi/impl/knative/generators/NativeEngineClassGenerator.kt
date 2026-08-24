package io.github.kingg22.godot.codegen.extensionapi.impl.knative.generators

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LONG
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeAliasSpec
import com.squareup.kotlinpoet.TypeSpec
import io.github.kingg22.godot.codegen.extensionapi.Context
import io.github.kingg22.godot.codegen.extensionapi.TypeResolver
import io.github.kingg22.godot.codegen.extensionapi.impl.knative.impl.EngineClassImplGen
import io.github.kingg22.godot.codegen.extensionapi.impl.knative.impl.EngineMethodImplGen
import io.github.kingg22.godot.codegen.extensionapi.impl.knative.impl.EnginePropertyImplGen
import io.github.kingg22.godot.codegen.extensionapi.impl.knative.impl.VirtualCallImplGen
import io.github.kingg22.godot.codegen.extensionapi.resolver.addKdocIfPresent
import io.github.kingg22.godot.codegen.extensionapi.resolver.experimentalApiAnnotation
import io.github.kingg22.godot.codegen.impl.createFile
import io.github.kingg22.godot.codegen.impl.renameGodotClass
import io.github.kingg22.godot.codegen.models.extensionapi.domain.ResolvedEngineClass
import io.github.kingg22.godot.codegen.types.API_STATUS_NON_EXTENSIBLE
import io.github.kingg22.godot.codegen.types.GODOT_VIRTUAL_METHOD
import io.github.kingg22.godot.codegen.utils.withExceptionContext

class NativeEngineClassGenerator(
    private val typeResolver: TypeResolver,
    private val body: EngineMethodImplGen,
    private val propertyGen: EnginePropertyImplGen,
    private val methodGen: NativeMethodGenerator,
    private val overloadGen: TypeOverloadGenerator,
    private val enumGenerator: NativeEnumGenerator,
    private val engineClassImplGen: EngineClassImplGen,
    private val virtualCallImplGen: VirtualCallImplGen,
) {

    context(context: Context)
    fun generateFile(cls: ResolvedEngineClass): FileSpec {
        val packageName = context.packageForOrDefault(cls.name)
        val spec = generateSpec(cls)
        return createFile(spec, spec.name!!, packageName) {
            addProperties(body.buildTopLevelFptrProperties(cls))
        }
    }

    context(context: Context)
    fun generateSpec(cls: ResolvedEngineClass): TypeSpec {
        val raw = cls.raw
        withExceptionContext({ "Generating class '${cls.name}'" }) {
            val classNameStr = cls.name.renameGodotClass()
            val packageName = context.packageForOrDefault(cls.name)
            val className = ClassName(packageName, classNameStr)
            val isSingleton = cls.isSingleton
            val classBuilder = buildBaseClass(cls, className, isSingleton)

            // Companion Object para Statics y Singleton Instance
            val companionBuilder = TypeSpec.companionObjectBuilder()

            engineClassImplGen.configureConstructor(cls, classBuilder, companionBuilder, className)

            val (staticMethods, instanceMethods) = raw.methods.partition { it.isStatic }

            // Sintetizar properties desde métodos getter/setter
            val standaloneMethods = propertyGen.generateProperties(cls, instanceMethods, classBuilder)

            // Métodos standalone (no forman parte de una property)
            standaloneMethods.forEach { method ->
                val original = methodGen.buildMethod(
                    method = method,
                    className = cls.name,
                    codeBody = body.buildMethodBody(method, cls.name),
                    // Reverse dispatch (VirtualCallImplGen) always null-checks engine-class/singleton
                    // args on the way in — the stub's own parameter type has to accept null too, or the
                    // generated trampoline body won't type-check against it.
                    forceNullableEngineArgs = method.isVirtual,
                    // Mirror of the above for the return side: COpaquePointer (behind rawPtr) can't
                    // itself represent a null address, so an override's only way to hand back "no
                    // value" for an engine-class/singleton return is a null reference. The trampoline's
                    // return write (VirtualCallImplGen.buildReturnWrite) already null-checks `result`
                    // before writing .rawPtr, so declaring the return type nullable is safe and required.
                    forceNullableEngineReturn = method.isVirtual,
                ) {
                    if (method.isVirtual) {
                        addModifiers(KModifier.OPEN)
                        if (virtualCallImplGen.isSupported(method)) {
                            addAnnotation(
                                AnnotationSpec.builder(GODOT_VIRTUAL_METHOD).addMember("%S", method.name).build(),
                            )
                        }
                    }

                    if (method.name == "get" || method.name == "set") addModifiers(KModifier.OPERATOR)
                }

                classBuilder.addFunctions(
                    overloadGen.buildOverloadsForMethod(
                        method,
                        original,
                        TypeOverloadGenerator.GodotStringMapping,
                    ).ifEmpty {
                        listOf(original)
                    },
                )
            }

            raw.constants.forEach { constant ->
                withExceptionContext({ "Error generating class constant '${constant.name}'" }) {
                    companionBuilder.addProperty(
                        PropertySpec
                            .builder(constant.name, LONG, KModifier.CONST)
                            .initializer("%L", constant.value)
                            .addKdocIfPresent(constant)
                            .experimentalApiAnnotation(cls.name, constant.name)
                            .build(),
                    )
                }
            }

            staticMethods.forEach { method ->
                val original = methodGen.buildMethod(
                    method,
                    cls.name,
                    codeBody = body.buildMethodBody(method, cls.name),
                )

                companionBuilder.addFunctions(
                    overloadGen.buildOverloadsForMethod(
                        method,
                        original,
                        TypeOverloadGenerator.GodotStringMapping,
                    ).ifEmpty {
                        listOf(original)
                    },
                )
            }

            if (isSingleton || raw.constants.isNotEmpty() || staticMethods.isNotEmpty()) {
                classBuilder.addType(companionBuilder.build())
            }

            cls.enums.forEach { enum ->
                if (context.isSpecializedClass(enum.shortName)) return@forEach
                val enumSpec = enumGenerator.generateSpec(enum)
                classBuilder.addType(enumSpec)
                if (enum.shortName != enumSpec.name && enum.shortName.all { it.isUpperCase() }) {
                    classBuilder.addTypeAlias(
                        TypeAliasSpec
                            .builder(enum.shortName, className.nestedClass(enumSpec.name!!))
                            .build(),
                    )
                }
            }

            return classBuilder.build()
        }
    }

    context(ctx: Context)
    private fun buildBaseClass(cls: ResolvedEngineClass, className: ClassName, isSingleton: Boolean): TypeSpec.Builder {
        val builder = TypeSpec
            .classBuilder(className)
            .experimentalApiAnnotation(cls.name)

        // Modificadores de clase
        when {
            (cls.isAbstract || !cls.isInstantiable) && !isSingleton -> {
                if (ctx.extensionApi.classes.any { it.inherits == cls.name }) {
                    builder.addAnnotation(API_STATUS_NON_EXTENSIBLE)
                    builder.addModifiers(KModifier.OPEN)
                }
                // Final classes
            }

            isSingleton -> {
                // Final class, internal constructor
                if (cls.isSingletonExtensible) {
                    builder.addAnnotation(API_STATUS_NON_EXTENSIBLE)
                    builder.addModifiers(KModifier.OPEN)
                }
            }

            else -> builder.addModifiers(KModifier.OPEN)
        }

        // Herencia
        cls.raw.inherits?.let { superClassType ->
            builder.superclass(typeResolver.resolve(superClassType))
        }

        return builder.addKdocIfPresent(cls.raw)
    }
}
