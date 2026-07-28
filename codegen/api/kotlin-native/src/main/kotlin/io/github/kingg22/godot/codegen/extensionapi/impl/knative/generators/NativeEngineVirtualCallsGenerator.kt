package io.github.kingg22.godot.codegen.extensionapi.impl.knative.generators

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.TypeSpec
import io.github.kingg22.godot.codegen.extensionapi.Context
import io.github.kingg22.godot.codegen.extensionapi.impl.knative.impl.VirtualCallImplGen
import io.github.kingg22.godot.codegen.impl.createFile
import io.github.kingg22.godot.codegen.impl.renameGodotClass
import io.github.kingg22.godot.codegen.models.extensionapi.domain.ResolvedEngineClass

/** Emits the `<EngineClass>VirtualCalls` object for classes that have at least one dispatchable virtual. */
class NativeEngineVirtualCallsGenerator(private val body: VirtualCallImplGen) {

    context(context: Context)
    fun generateFile(cls: ResolvedEngineClass): FileSpec? {
        val supported = cls.raw.methods.filter { body.isSupported(it) }
        if (supported.isEmpty()) return null

        val classNameStr = cls.name.renameGodotClass()
        val packageName = context.packageForOrDefault(cls.name)
        val engineClassName = ClassName(packageName, classNameStr)
        val objectName = "${classNameStr}VirtualCalls"

        val typeSpec = TypeSpec
            .objectBuilder(objectName)
            .addAnnotation(body.internalBindingClassName())
            .addProperties(supported.map { body.buildTrampoline(it, engineClassName) })
            .build()

        return createFile(typeSpec, objectName, packageName)
    }
}
