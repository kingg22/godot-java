@file:OptIn(ExperimentalForeignApi::class)

import io.github.kingg22.godot.api.annotations.ExportMethod
import io.github.kingg22.godot.api.annotations.Godot
import io.github.kingg22.godot.api.builtin.Callable
import io.github.kingg22.godot.api.builtin.Vector2
import io.github.kingg22.godot.api.builtin.Vector2i
import io.github.kingg22.godot.api.builtin.toGodotString
import io.github.kingg22.godot.api.builtin.toStringName
import io.github.kingg22.godot.api.builtin.toVariant
import io.github.kingg22.godot.api.core.Node
import io.github.kingg22.godot.api.core.Object
import io.github.kingg22.godot.api.core.SceneTree
import io.github.kingg22.godot.api.core.node.Node2D
import io.github.kingg22.godot.api.core.node.TextEdit
import io.github.kingg22.godot.api.core.node.Window
import io.github.kingg22.godot.api.core.refcounted.Texture2D
import io.github.kingg22.godot.api.singleton.Engine
import io.github.kingg22.godot.api.utils.GD
import io.github.kingg22.godot.api.utils.print
import io.github.kingg22.godot.binding.instantiate
import io.github.kingg22.godot.castTo
import io.github.kingg22.godot.load
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi

private const val FRAME_COUNT = 1_000
private const val START_FRAME = 100
private const val SPRITE_COUNT = 5

@Godot class TestOne(nativePtr: COpaquePointer) : Node2D(nativePtr) {
    private val frameTimes = DoubleArray(FRAME_COUNT) { 0.0 }
    private var currentFrame = 0
    private var frameIndex = 0
    private var windowSize: Vector2 = Vector2.ZERO

    private val callable = Callable {
        println("Hello from Kotlin inside a Callable!")
    }

    private val callable2 = Callable { id: Long ->
        println("Hello from Kotlin inside a Callable with id: $id")
        id
    }

    init {
        GD.print("A new SpriteBench was created with pointer ${nativePtr.rawValue}")
    }

    @ExportMethod
    fun addTwo(a: Int, b: Int): Int = a + b

    override fun _ready() {
        try {
            println("[SpriteBench] _ready started")

            // Try to create Texture2D - but first let's test WITHOUT texture
            val icon = GD.load<Texture2D>("res://icon.svg", factory = ::Texture2D)
            println("[SpriteBench] Texture2D wrapper created")

            // get root from the engine singleton
            val mainloop: SceneTree = Engine.instance.getMainLoop().castTo(::SceneTree)
            // use mainloop to call the get_root from the scenetree class
            val root: Window = mainloop.root // return as object
            // use root as the object for the window class method to call get_size
            val vpwh: Vector2i = root.size // return as Vector2i

            windowSize = Vector2(from = vpwh)

            val halfSize: Vector2 = icon.getSize() / 2.0
            println(
                "[SpriteBench] Window: (${windowSize.x}, ${windowSize.y}), HalfSize: (${halfSize.x}, ${halfSize.y})",
            )

            println("[SpriteBench] Creating $SPRITE_COUNT sprites")
            for (i in 0..<SPRITE_COUNT) {
                try {
                    // Prefer the top-level factory function, this is a fallback way!!
                    val sprite: Sprite = instantiate()
                    sprite.halfSize = halfSize
                    sprite.windowSize = windowSize
                    sprite.pos = windowSize / 2.0
                    sprite.position = sprite.pos
                    sprite.texture = icon
                    addChild(node = sprite)
                    if (i % 1000 == 0) {
                        println("[SpriteBench] Added $i sprites")
                    }
                } catch (e: Throwable) {
                    println("[SpriteBench] === Sprite $i addChild failed ===")
                    e.printStackTrace()
                    throw e
                }
            }
            println("[SpriteBench] All $SPRITE_COUNT sprites added successfully")

            println("[SpriteBench] _ready finished, going to call deferred callable")
            var returnVariant = callable.call()
            println(
                "[SpriteBench] Callable returned: ${returnVariant.stringify().toKString()}, is nil: ${returnVariant.isNil()}",
            )
            println("[SpriteBench] calling callable2 with 15 as args")
            returnVariant = callable2.call(15L.toVariant())
            println(
                "[SpriteBench] Callable2 returned: ${returnVariant.stringify().toKString()}, is nil: ${returnVariant.isNil()}, value: ${returnVariant.toIntOrNull()}",
            )

            println("[SpriteBench] calling @ExportMethod addTwo(3, 4) via Object.call")
            val addTwoResult = call("addTwo".toStringName(), 3.toVariant(), 4.toVariant())
            println(
                "[SpriteBench] addTwo returned: ${addTwoResult.toIntOrNull()} (expected 7)",
            )
        } catch (e: Throwable) {
            println("[SpriteBench] === _ready failed ===")
            e.printStackTrace()
        }
    }

    override fun _process(delta: Double) {
        try {
            currentFrame += 1

            if (currentFrame >= START_FRAME) {
                if (frameIndex == FRAME_COUNT) {
                    println("[SpriteBench] Frame count reached, freeing children")
                    val _ = getChildren().map(Callable { child: Object -> Node(child.rawPtr).queueFree() })

                    val outText = StringBuilder(FRAME_COUNT * 12)
                    for (t in frameTimes) {
                        outText.append("(").append(t).appendLine(")")
                    }
                    val edit = TextEdit()
                    edit.text = outText.toString().toGodotString()
                    edit.size = windowSize
                    addChild(node = edit)
                    println("[SpriteBench] TextEdit added")
                } else if (frameIndex < FRAME_COUNT) {
                    frameTimes[frameIndex] = delta
                }

                frameIndex += 1
            }
        } catch (e: Throwable) {
            println("[SpriteBench] === _process failed ===")
            e.printStackTrace()
        }
    }
}
