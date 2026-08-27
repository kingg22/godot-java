@file:OptIn(ExperimentalForeignApi::class, NativeRuntimeApi::class)

import io.github.kingg22.godot.api.annotations.Godot
import io.github.kingg22.godot.api.core.node.Node2D
import io.github.kingg22.godot.api.core.refcounted.Json
import io.github.kingg22.godot.api.core.refcounted.Texture2D
import io.github.kingg22.godot.api.utils.GD
import io.github.kingg22.godot.load
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.native.runtime.NativeRuntimeApi
import kotlinx.serialization.json.Json as KxJson

private val SCENARIO_FRAMES = listOf(10, 20, 30, 40, 50, 60, 70)

/**
 * Realistic threading edge cases for a Kotlin/Native + Godot game, run against the real Godot binary
 * (`threading_edge_cases.tscn`). Each scenario is fired from a different, spaced-out `_process()` frame
 * so their console output doesn't interleave, but most of them are genuinely concurrent with the main
 * thread and with each other once launched (they are not awaited).
 *
 * These are exploratory/diagnostic — see the console output of an actual run for the verdict on each
 * one, not this KDoc. Nothing here is asserted programmatically; this mirrors how the rest of
 * `mi-juego-prueba` validates behavior (print observed values, read the log).
 */
@Godot class ThreadingEdgeCases(nativePtr: COpaquePointer) : Node2D(nativePtr) {
    private var currentFrame = 0

    // Scenario 5 hands its result from a background thread to the main thread through here.
    private val jsonResultMutex = Mutex()
    private var jsonResultHealth: Int? = null

    override fun _process(delta: Double) {
        currentFrame += 1
        when (SCENARIO_FRAMES.indexOf(currentFrame)) {
            0 -> scenario0MutexCounterPureKotlin()
            1 -> scenario1ReadGodotObjectFromBackgroundThread()
            2 -> scenario2ConcurrentResourceLoadGuardedByMutex()
            3 -> scenario3ConcurrentResourceLoadUnguarded()
            4 -> scenario4DelayAcrossSuspensionHoldingGodotRef()
            5 -> scenario5ParseJsonPureKotlinOnBackgroundThread()
            6 -> scenario6ParseJsonWithGodotApiFromBackgroundThread()
        }

        // Scenario 5's handoff: check without blocking the main thread (tryLock, not the suspend lock).
        if (jsonResultMutex.tryLock()) {
            try {
                jsonResultHealth?.let {
                    println("[ThreadTest] scenario5: main thread picked up background-parsed health=$it")
                    jsonResultHealth = null
                }
            } finally {
                jsonResultMutex.unlock()
            }
        }
    }

    /** Baseline: does `kotlinx.coroutines.sync.Mutex` even work correctly in this Kotlin/Native setup? */
    private fun scenario0MutexCounterPureKotlin() {
        println("[ThreadTest] scenario0: mutex-guarded counter across 8 real worker threads x 1000 increments")
        val mutex = Mutex()
        var counter = 0
        var finished = 0
        val workerCount = 8
        val incrementsPerWorker = 1000
        repeat(workerCount) { workerId ->
            CoroutineScope(Dispatchers.Default).launch {
                repeat(incrementsPerWorker) {
                    mutex.withLock { counter++ }
                }
                finished++
                if (finished == workerCount) {
                    val expected = workerCount * incrementsPerWorker
                    println("[ThreadTest] scenario0: all workers done, counter=$counter (expected $expected) -> ${if (counter == expected) "OK" else "RACE DETECTED"}")
                }
                println("[ThreadTest] scenario0: worker $workerId finished")
            }
        }
    }

    /**
     * A very common real pattern: a Godot object created on the main thread gets its reference handed
     * into a background coroutine (e.g. to compute something about it), which then calls a real Godot
     * method (a ptrcall) on it — deliberately OFF the main thread, to see whether that specific call is
     * actually safe or not, rather than assuming it isn't.
     */
    private fun scenario1ReadGodotObjectFromBackgroundThread() {
        val tex = GD.load<Texture2D>("res://icon.svg", factory = ::Texture2D)
        println("[ThreadTest] scenario1: main thread refcount=${tex.getReferenceCount()}")
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val size = tex.getSize()
                val refcount = tex.getReferenceCount()
                println("[ThreadTest] scenario1: background thread read size=$size refcount=$refcount (ptrcall ran OFF the main thread on purpose)")
            } catch (e: Throwable) {
                println("[ThreadTest] scenario1: background thread read THREW: ${e::class.simpleName}: ${e.message}")
            }
        }
    }

    /**
     * Recommended real pattern: several coroutines want the same cached resource concurrently; a
     * [Mutex] serializes the actual engine calls so no two `GD.load()`/property-read ptrcalls into
     * Godot ever run at the truly same instant, even though the coroutines themselves run on distinct
     * real OS threads (`Dispatchers.Default`).
     */
    private fun scenario2ConcurrentResourceLoadGuardedByMutex() {
        println("[ThreadTest] scenario2: 4 workers loading the same resource, serialized by a Mutex")
        val mutex = Mutex()
        repeat(4) { workerId ->
            CoroutineScope(Dispatchers.Default).launch {
                mutex.withLock {
                    val tex = GD.load<Texture2D>("res://icon.svg", factory = ::Texture2D)
                    println("[ThreadTest] scenario2: worker $workerId (guarded) loaded, refcount=${tex.getReferenceCount()}")
                }
            }
        }
    }

    /**
     * The dangerous version of scenario 2: the SAME 4 concurrent loads, but with no [Mutex] — 4 real
     * threads calling `ResourceLoader.load()`/`GD.load()` on the exact same resource at the same time,
     * with nothing serializing the ptrcalls. This is what happens when a dev forgets to guard shared
     * engine access from multiple coroutines. Godot's own docs call out `ResourceLoader` specifically as
     * one of the few APIs designed to tolerate multi-threaded loading, so this scenario is also a check
     * of whether that guarantee actually holds from Kotlin/Native's ptrcall path.
     */
    private fun scenario3ConcurrentResourceLoadUnguarded() {
        println("[ThreadTest] scenario3: 4 workers loading the same resource, UNGUARDED (no Mutex)")
        repeat(4) { workerId ->
            CoroutineScope(Dispatchers.Default).launch {
                try {
                    val tex = GD.load<Texture2D>("res://icon.svg", factory = ::Texture2D)
                    println("[ThreadTest] scenario3: worker $workerId (unguarded) loaded, refcount=${tex.getReferenceCount()}")
                } catch (e: Throwable) {
                    println("[ThreadTest] scenario3: worker $workerId (unguarded) THREW: ${e::class.simpleName}: ${e.message}")
                }
            }
        }
    }

    /**
     * `delay()` suspends the coroutine; when it resumes, `Dispatchers.Default` may resume it on a
     * *different* real OS thread than the one that started it. This holds a live Godot reference across
     * that suspension point to confirm nothing about the identity/release machinery cares which physical
     * thread is running the continuation.
     */
    private fun scenario4DelayAcrossSuspensionHoldingGodotRef() {
        CoroutineScope(Dispatchers.Default).launch {
            val tex = GD.load<Texture2D>("res://icon.svg", factory = ::Texture2D)
            println("[ThreadTest] scenario4: before delay, refcount=${tex.getReferenceCount()}")
            delay(300)
            println("[ThreadTest] scenario4: after delay (possibly different thread), refcount=${tex.getReferenceCount()}")
        }
    }

    /**
     * The recommended real pattern for CPU-bound work like JSON parsing: do it with pure Kotlin
     * (`kotlinx.serialization`, no Godot calls at all) on a background thread, then hand only the
     * resulting plain Kotlin value back to the main thread — never touch Godot from the worker thread.
     */
    private fun scenario5ParseJsonPureKotlinOnBackgroundThread() {
        println("[ThreadTest] scenario5: parsing JSON with kotlinx.serialization on a background thread")
        CoroutineScope(Dispatchers.Default).launch {
            val jsonText = """{"name": "goblin", "health": 42, "tags": ["enemy", "boss"]}"""
            val parsed = KxJson.parseToJsonElement(jsonText).jsonObject
            val health = parsed.getValue("health").jsonPrimitive.int
            println("[ThreadTest] scenario5: parsed on background thread, health=$health; handing off to main thread")
            jsonResultMutex.withLock { jsonResultHealth = health }
        }
    }

    /**
     * The risky version of scenario 5: use Godot's *own* [Json] class directly from a background
     * thread instead of a pure-Kotlin JSON library, to see whether that specific engine API tolerates
     * being called off the main thread or not.
     */
    private fun scenario6ParseJsonWithGodotApiFromBackgroundThread() {
        println("[ThreadTest] scenario6: parsing JSON with Godot's own Json class from a background thread")
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val variant = Json.parseString("""{"name": "orc", "health": 77}""")
                println("[ThreadTest] scenario6: Godot Json.parseString from background thread succeeded: ${variant.stringify().toKString()}")
            } catch (e: Throwable) {
                println("[ThreadTest] scenario6: Godot Json.parseString from background thread THREW: ${e::class.simpleName}: ${e.message}")
            }
        }
    }
}
