package io.github.kingg22.godot.codegen.extensionapi.impl.knative.impl

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.buildCodeBlock
import com.squareup.kotlinpoet.withIndent
import io.github.kingg22.godot.codegen.extensionapi.Context
import io.github.kingg22.godot.codegen.impl.buildLazyBlock
import io.github.kingg22.godot.codegen.models.extensionapi.domain.ResolvedEngineClass
import io.github.kingg22.godot.codegen.types.COPAQUE_POINTER

/**
 * Generates the constructor binding and `nativePtr` property for Godot engine classes.
 *
 * ## The fundamental model
 *
 * Engine classes generated from the extension API JSON are **wrappers** around a
 * `GDExtensionObjectPtr` (`COpaquePointer`). They do **not** construct Godot objects
 * themselves — the pointer always originates from Godot's side, delivered via:
 * - A method return value (e.g. `get_node()` returning a `Node`)
 * - A singleton accessor (`global_get_singleton`)
 * - A signal argument, a typed property getter, etc.
 *
 * `classdb_construct_object2` + `object_set_instance` are meant for **user-defined extension
 * classes** that are first registered with `classdb_register_extension_class5`. The classes
 * in the JSON are Godot's built-in classes — they already live in ClassDB and only need to be
 * *wrapped*, not constructed from our side.
 *
 * ## Constructor strategy
 *
 * | Class nature                       | Constructor generated                                      |
 * |------------------------------------|------------------------------------------------------------|
 * | **Singleton**                      | `private(nativePtr)` + companion `instance` lazy          |
 * | **Root (no inherits)**             | `internal(nativePtr)` — owns the property declaration     |
 * | **Derived instantiable**           | `internal(nativePtr) : super(nativePtr)`                  |
 * | **Non-instantiable (abstract)**    | `protected(nativePtr)` or `: super(nativePtr)`            |
 *
 * All constructors are non-public (internal/protected/private). The pointer is always
 * supplied externally by Godot, never created from Kotlin for these wrapper classes.
 *
 * ## `nativePtr` property
 *
 * Declared **only on the root** of the engine-class hierarchy (the class with no `inherits`,
 * currently `Object`). All other classes inherit it — no re-declaration needed.
 *
 * ## Singletons
 *
 * Singletons are the one case where we *do* call a GDExtension function at construction time,
 * but that function is `global_get_singleton` — it retrieves an **already-existing** Godot
 * singleton, it does not create anything new. The companion `instance` lazy delegates to
 * `GlobalBinding.getSingletonRaw` using the high-level `StringName(...).use { }` API.
 *
 * ## RefCounted
 *
 * `RefCounted` and its subclasses are `isRefcounted == true` in the JSON. Reference counting is a
 * Godot-side concern managed automatically when Godot constructs/destroys the object; our wrapper
 * simply holds the pointer. No special constructor logic is needed here beyond what every other
 * derived class gets.
 *
 * A wrapper materialized for an *existing* `RefCounted` (built via `castTo`/`GD.load`, not via our
 * own `create_instance_func`) implicitly receives an engine reference nothing currently releases —
 * see issue #114. Releasing it from a `kotlin.native.ref.Cleaner` was tried and reverted: Cleaners
 * run on Kotlin/Native's dedicated finalizer thread, and Godot's engine calls are not generally safe
 * off the main thread (reproducibly crashed with "The caller thread can't call the function
 * `propagate_notification()`..." as soon as a shared `Resource`'s wrapper was collected while the
 * main thread was still using the same object). Correctly releasing it needs a main-thread-deferred
 * mechanism (e.g. `Callable.callDeferred`) — tracked as a follow-up, not solved here.
 */
class EngineClassImplGen {
    private lateinit var implPackageRegistry: ImplementationPackageRegistry

    fun initialize(implRegistry: ImplementationPackageRegistry) {
        implPackageRegistry = implRegistry
    }

    // ── Public entry point ────────────────────────────────────────────────────

    /**
     * Configures the primary constructor and the `nativePtr` property on [classBuilder],
     * and fills [companionBuilder] if the class is a singleton.
     *
     * Must be called after
     * [io.github.kingg22.godot.codegen.impl.extensionapi.knative.generators.NativeEngineClassGenerator.buildBaseClass]
     * has already set the class modifiers and superclass type.
     *
     * @param cls             The resolved engine class being generated.
     * @param classBuilder    The class [TypeSpec.Builder] to configure.
     * @param className       The [ClassName] of the class under generation.
     * @param companionBuilder The companion object builder, populated only for singletons.
     */
    context(ctx: Context)
    fun configureConstructor(
        cls: ResolvedEngineClass,
        classBuilder: TypeSpec.Builder,
        companionBuilder: TypeSpec.Builder,
        className: ClassName,
    ) {
        val isRoot = cls.raw.inherits == null
        when {
            cls.isSingleton -> configureSingleton(cls, classBuilder, className, companionBuilder)
            isRoot -> configureRoot(classBuilder)
            else -> configureDerived(classBuilder, cls)
        }

        if (!cls.isSingleton || isRoot) {
            classBuilder.addFunction(
                FunSpec
                    .constructorBuilder()
                    .callThisConstructor(
                        buildCodeBlock {
                            add("\n")
                            withIndent {
                                beginControlFlow(
                                    "%T(%S).use { cls ->",
                                    ctx.classNameForOrDefault("StringName"),
                                    cls.shortName,
                                )
                                withIndent {
                                    addStatement("%T", ctx.classNameForOrDefault("ClassDB"))
                                    withIndent {
                                        addStatement(".instance")
                                        addStatement(".instantiate(cls)")
                                        addStatement(".toObject()")
                                        addStatement(".rawPtr")
                                    }
                                }
                                endControlFlow()
                            }
                        },
                    )
                    .build(),
            )
        }
    }

    // ── Singleton ─────────────────────────────────────────────────────────────

    /**
     * Singletons use a private constructor and expose a single lazy `instance`.
     *
     * The pointer is obtained via `GlobalBinding.getSingletonRaw` — this retrieves an
     * **already-existing** Godot singleton object, it does not create anything new.
     *
     * Generated output for e.g. `Engine`:
     * ```kotlin
     * private constructor(nativePtr: COpaquePointer)
     * internal val nativePtr: COpaquePointer
     *
     * // inside companion object (filled via companionBuilder):
     * val instance: Engine by lazy(PUBLICATION) {
     *     StringName("Engine").use { sn ->
     *         Engine(GlobalBinding.getSingletonRaw(sn.rawPtr)
     *             ?: error("Singleton 'Engine' not found in Godot"))
     *     }
     * }
     * ```
     */
    context(_: Context)
    private fun configureSingleton(
        cls: ResolvedEngineClass,
        classBuilder: TypeSpec.Builder,
        className: ClassName,
        companionBuilder: TypeSpec.Builder,
    ) {
        val nativePtrParam = nativePtrParam()
        classBuilder.primaryConstructor(
            FunSpec
                .constructorBuilder()
                .apply {
                    // Special case: EditorInterface is a singleton that is retrieved in Editor
                    if (cls.isSingletonExtensible || cls.name == "EditorInterface") {
                        addModifiers(KModifier.INTERNAL)
                    } else {
                        addModifiers(KModifier.PRIVATE)
                    }
                }
                .addParameter(nativePtrParam)
                .build(),
        )
        classBuilder.addSuperclassConstructorParameter("%N", nativePtrParam)
        companionBuilder.addProperty(buildSingletonInstanceProp(cls, className))
    }

    context(context: Context)
    private fun buildSingletonInstanceProp(cls: ResolvedEngineClass, className: ClassName): PropertySpec {
        val stringNameClass = context.classNameForOrDefault("StringName")
        val globalBinding = implPackageRegistry.classNameForOrDefault("GlobalBinding")

        return PropertySpec
            .builder("instance", className)
            .delegate(
                buildLazyBlock {
                    beginControlFlow("%T(%S).use { sn ->", stringNameClass, cls.name)
                    addStatement("val nativePtr = %T.getSingletonRaw(sn.rawPtr)", globalBinding)
                    withIndent {
                        addStatement("?: error(%S)", "Singleton '${cls.name}' not found in Godot")
                    }
                    addStatement("%T(nativePtr)", className)
                    endControlFlow()
                },
            )
            .build()
    }

    // ── Root (no inherits) ────────────────────────────────────────────────────

    /**
     * Root engine class — no Godot parent (currently only `Object`).
     *
     * Declares the `nativePtr` property and an `internal` constructor that receives it.
     * All other classes in the hierarchy will call `super(nativePtr)` via their own constructor.
     *
     * Generated output:
     * ```kotlin
     * internal constructor(nativePtr: COpaquePointer)
     * internal val nativePtr: COpaquePointer
     * ```
     */
    context(ctx: Context)
    private fun configureRoot(classBuilder: TypeSpec.Builder) {
        classBuilder.primaryConstructor(
            FunSpec
                .constructorBuilder()
                .addParameter(nativePtrParam("rawPtr"))
                .build(),
        )
            .addSuperinterface(ctx.classNameForOrDefault("GodotNative"))
            .addProperty(nativePtrProp())
    }

    // ── Derived (has inherits) ────────────────────────────────────────────────

    /**
     * Derived engine class — inherits from another engine class.
     *
     * Generates an `internal` constructor that simply forwards `nativePtr` to the superclass.
     * The `nativePtr` property is **not** re-declared — it lives on the root class.
     *
     * KotlinPoet emits `: super(nativePtr)` automatically because `buildBaseClass` calls
     * `builder.superclass(...)` and this constructor's parameter name matches the
     * `superclassConstructorParameter` set there.
     *
     * Visibility follows the class nature:
     * - Instantiable → `internal` (Godot or other Kotlin code can hand us a pointer)
     * - Non-instantiable (abstract) → `protected` (only subclasses call this)
     *
     * Generated output for `Node` (instantiable, inherits `Object`):
     * ```kotlin
     * internal constructor(nativePtr: COpaquePointer) : super(nativePtr)
     * ```
     *
     * Generated output for `VisualInstance3D` (non-instantiable, inherits `Node3D`):
     * ```kotlin
     * protected constructor(nativePtr: COpaquePointer) : super(nativePtr)
     * ```
     */
    private fun configureDerived(classBuilder: TypeSpec.Builder, cls: ResolvedEngineClass) {
        // Visibility is already on classBuilder via addModifiers in buildBaseClass;
        // mirror it on the constructor: abstract → protected, open → internal.
        // We inspect the already-added modifiers to stay consistent.
        val nativePtrParam = nativePtrParam()

        classBuilder.primaryConstructor(
            FunSpec
                .constructorBuilder()
                .apply { if (!cls.isInstantiable) addModifiers(KModifier.INTERNAL) }
                .addParameter(nativePtrParam)
                .build(),
        ).addSuperclassConstructorParameter("%N", nativePtrParam)
        // KotlinPoet will emit `: super(nativePtr)` because buildBaseClass calls
        // builder.superclass(...) and addSuperclassConstructorParameter("nativePtr").
    }

    // ── Shared helpers ────────────────────────────────────────────────────────

    private fun nativePtrParam(name: String = "nativePtr"): ParameterSpec =
        ParameterSpec.builder(name, COPAQUE_POINTER).build()

    /**
     * `internal val nativePtr: COpaquePointer` initialized from the constructor param.
     * Only emitted on the root class (no `inherits`).
     */
    private fun nativePtrProp(): PropertySpec = PropertySpec
        .builder("rawPtr", COPAQUE_POINTER, KModifier.OVERRIDE)
        .initializer("rawPtr")
        .build()
}
