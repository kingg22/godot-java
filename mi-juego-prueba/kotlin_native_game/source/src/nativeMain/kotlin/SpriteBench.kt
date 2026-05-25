@file:OptIn(ExperimentalForeignApi::class)

import io.github.kingg22.godot.api.GodotEnum
import io.github.kingg22.godot.api.GodotError
import io.github.kingg22.godot.api.annotations.Export
import io.github.kingg22.godot.api.annotations.Godot
import io.github.kingg22.godot.api.annotations.RegisterSignal
import io.github.kingg22.godot.api.builtin.Callable
import io.github.kingg22.godot.api.builtin.Signal
import io.github.kingg22.godot.api.builtin.StringName
import io.github.kingg22.godot.api.builtin.Variant
import io.github.kingg22.godot.api.builtin.toStringName
import io.github.kingg22.godot.api.builtin.toVariant
import io.github.kingg22.godot.api.core.node.Node2D
import io.github.kingg22.godot.internal.binding.VariantBinding
import io.github.kingg22.godot.internal.binding.allocConstTypePtrArray
import io.github.kingg22.godot.internal.ffi.GDExtensionPtrBuiltInMethod
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value

@Godot class SpriteBench(nativePtr: COpaquePointer) : Node2D(nativePtr) {

    @Export var health: Int = 100

    @Export var speed: Float = 1.0f

    @Export var spriteName: String = "SpriteBench"

    private val hintStr = "hint".toStringName()
    private val punchStr = "punch".toStringName()

    @RegisterSignal
    private val hint by lazy { Signal(this, hintStr) }

    @RegisterSignal(RegisterSignal.Param(Variant.Type.INT, "value"))
    private val punch by lazy { Signal(this, punchStr) }

    private val callable1 = Callable {
        println("Callable1: received")
    }

    private val callable2 = Callable { id: Long ->
        println("Callable2: received $id")
    }

    override fun _ready() {
        println("[SpriteBench] _ready started")

        // ✅ connect después de validar
        if (hint.isConnected(callable1)) {
            println("hint already connected to callable1")
        } else {
            val result1 = hint.connect(callable1)
            println("connect result: $result1 : ${GodotEnum.fromValue<GodotError>(result1)}")
        }

        if (punch.isConnected(callable2)) {
            println("punch already connected to callable2")
        } else {
            val result = punch.connect(callable2)
            println("connect punch result: $result : ${GodotEnum.fromValue<GodotError>(result)}")
        }

        try {
            println("[SpriteBench] emitting **hint** via Signal.emit fixed")
            val error = hint.emitFix()
            println("emitFix hint returned: $error")

            println("[SpriteBench] emitting **punch** via Signal.emit fixed")
            val error2 = punch.emitFix(12L.toVariant())
            println("emitFix punch returned: $error2")

            println("[SpriteBench] emitting **hint** via emitSignal")
            val result = emitSignal(hintStr)
            println("emitSignal hint result: $result")
            println("[SpriteBench] emitting **punch** via emitSignal")
            val result2 = emitSignal(punchStr, 15L.toVariant())
            println("emitSignal punch result2: $result2")
        } catch (e: Exception) {
            println("[SpriteBench] failed: ${e.message}")
            e.printStackTrace()
        } finally {
            println("[SpriteBench] _ready finished")
        }
    }
}

fun Signal.emitFix(vararg args: Variant): GodotError = memScoped {
    val retPtr = alloc<LongVar>()
    methodSignalEmit_3286317445_Fn.invoke(
        rawPtr,
        allocConstTypePtrArray(*args.map { it.rawPtr }.toTypedArray()),
        retPtr.ptr,
        args.size,
    )
    return GodotEnum.fromValue(retPtr.value)
}

private val methodSignalEmit_3286317445_Fn: GDExtensionPtrBuiltInMethod by lazy(PUBLICATION) {
    StringName("emit").use { name ->
        VariantBinding.instance.getPtrBuiltinMethodRaw(GDEXTENSION_VARIANT_TYPE_SIGNAL, name.rawPtr, 3_286_317_445L)
            ?: error("Missing builtin method 'Signal.emit' hash: 3286317445")
    }
}
