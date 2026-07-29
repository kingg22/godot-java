@file:OptIn(ExperimentalForeignApi::class)

import io.github.kingg22.godot.api.annotations.Godot
import io.github.kingg22.godot.api.builtin.Vector2
import io.github.kingg22.godot.api.core.node.Sprite2D
import io.github.kingg22.godot.api.utils.GD
import io.github.kingg22.godot.api.utils.print
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

@Godot class Sprite(nativePtr: COpaquePointer) : Sprite2D(nativePtr) {
    private var angle: Double = Random.nextDouble(0.0, PI * 2)
    private val speed: Double = Random.nextDouble(100.0, 600.0)
    var pos: Vector2 = Vector2.ZERO
    var windowSize: Vector2 = Vector2.ZERO
    var halfSize: Vector2 = Vector2.ZERO

    init {
        GD.print("a new Sprite was created with pointer ${nativePtr.rawValue}")
    }

    override fun _process(delta: Double) {
        // Prefers kotlin math functions instead of GD utils
        pos += Vector2(x = cos(angle), y = sin(angle)) * speed * delta
        position = pos

        if (pos.x < halfSize.x || pos.x > windowSize.x - halfSize.x) {
            angle = PI - angle
        }
        if (pos.y < halfSize.y || pos.y > windowSize.y - halfSize.y) {
            angle = -angle
        }
    }
}
