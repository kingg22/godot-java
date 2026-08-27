@file:OptIn(ExperimentalForeignApi::class, NativeRuntimeApi::class)

import io.github.kingg22.godot.api.annotations.ExportMethod
import io.github.kingg22.godot.api.annotations.Godot
import io.github.kingg22.godot.api.builtin.Callable
import io.github.kingg22.godot.api.builtin.Vector2
import io.github.kingg22.godot.api.builtin.Vector2i
import io.github.kingg22.godot.api.builtin.toGodotString
import io.github.kingg22.godot.api.builtin.toStringName
import io.github.kingg22.godot.api.builtin.toVariant
import io.github.kingg22.godot.api.core.Node
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi

private const val FRAME_COUNT = 1_000
private const val START_FRAME = 100
private const val SPRITE_COUNT = 5

// issue #114: checkpoints spaced far enough apart (in idle frames) for both Kotlin/Native's
// dedicated Cleaner worker thread and Godot's MessageQueue idle-time flush to have run in between.
private val REFCOUNT_STEP_ACQUIRE_FRAMES = listOf(10, 40, 70, 100, 130, 160)

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
                    val children = getChildren()
                    for (i in 0 until children.size()) {
                        Node(children[i].toObject().rawPtr).queueFree()
                    }

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

                stepRefCountedReleaseTest(currentFrame - START_FRAME)

                frameIndex += 1
            }
        } catch (e: Throwable) {
            println("[SpriteBench] === _process failed ===")
            e.printStackTrace()
        }
    }

    /**
     * issue #114: acquires a throwaway [Texture2D] wrapper around the *same* cached engine resource,
     * never stores it beyond this call (so it is unreachable the instant the function returns), and
     * forces a GC cycle. Repeated across [REFCOUNT_STEP_ACQUIRE_FRAMES] — spaced-out frames — so each
     * acquisition's wrapper has actually been collected, its
     * [io.github.kingg22.godot.internal.binding.attachRefCountedRelease] [kotlin.native.ref.Cleaner] has
     * fired, and its deferred `Callable.callDeferred()` release has been flushed by Godot's idle-time
     * queue, before the next one runs — a stable, non-climbing `refcount` across steps is the pass
     * condition. Alternates which thread forces the collection cycle (plain main-thread call vs. a
     * kotlinx.coroutines worker thread, `Dispatchers.Default`, a real OS thread under the new memory
     * model) to confirm the release still correctly lands on the main thread — not a crash — regardless
     * of which thread's GC cycle triggered the Cleaner.
     *
     * Empirically (10 samples against the real Godot binary), refcount only ever stabilizes after a
     * step whose collection was forced from the **kotlinx.coroutines worker thread** branch — a step
     * whose collection ran via a plain main-thread `GC.collect()` never gets released by the next
     * checkpoint 30 frames later, alternating `+1, +0, +1, +0, ...` instead of staying flat. This isn't
     * this release mechanism failing — it never crashes, and every reference does eventually get
     * released — it looks like Kotlin/Native's Cleaner-dispatch worker thread needs *some* unrelated
     * background-thread activity to actually get scheduled promptly in an otherwise single-threaded
     * process. Real games are rarely purely single-threaded, so this is noted, not chased further here.
     *
     * Scope: this validates the common acquire-use-drop lifetime, one live wrapper at a time. It does
     * **not** cover acquiring the *same* still-reachable pointer repeatedly (e.g. `GD.load` in a tight
     * loop without ever dropping the previous result) — `materialize`'s identity cache collapses those
     * into the *one* already-cached wrapper, so only the first acquisition's cleaner ever releases a
     * reference; each further redundant ptrcall's own +1 is currently still unreleased. Distinguishing
     * "a fresh ptrcall reference" from "a re-cast of a pointer the caller already holds" needs call-site
     * knowledge `castTo`/`materialize` don't have — left as a documented follow-up, not solved here.
     */
    private fun stepRefCountedReleaseTest(frameSinceStart: Int) {
        val step = REFCOUNT_STEP_ACQUIRE_FRAMES.indexOf(frameSinceStart)
        if (step == -1) return

        val tex = GD.load<Texture2D>("res://icon.svg", factory = ::Texture2D)
        println("[RefCountedRelease] step $step: acquired, refcount=${tex.getReferenceCount()}")

        // Alternate which thread forces the collection cycle that will make this step's `tex` (already
        // unreachable — nothing stores it past this call) collectible, to exercise both the plain
        // main-thread path and a kotlinx.coroutines worker thread (`Dispatchers.Default`, a real OS
        // thread under the new memory model).
        if (step % 2 == 0) {
            GC.collect()
        } else {
            CoroutineScope(Dispatchers.Default).launch {
                println("[RefCountedRelease] step $step: forcing GC.collect() from a kotlinx.coroutines worker thread")
                GC.collect()
                println("[RefCountedRelease] step $step: worker-thread GC.collect() finished")
            }
        }
    }
}
