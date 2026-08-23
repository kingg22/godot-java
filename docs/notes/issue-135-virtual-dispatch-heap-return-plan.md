# Virtual dispatch: heap-backed builtin return support (issue #135)

## Problem

Discovered while implementing [#42](https://github.com/kingg22/kogot/issues/42). `VirtualCallImplGen.isReturnSupported` (`codegen/api/kotlin-native/src/main/kotlin/io/github/kingg22/godot/codegen/extensionapi/impl/knative/impl/VirtualCallImplGen.kt`) only wires a `GDExtensionClassCallVirtual` trampoline for a virtual method when its return type is `void`, a CVar primitive, `bool`, `enum::`/`bitfield::`, or an engine class. Heap-backed builtins as a **return** type — `String`, `PackedStringArray`, `Dictionary`, `Variant`, `void*` — get no trampoline at all: Godot's `get_virtual_func` returns `null` for those names and silently falls back to its own C++ default. The Kotlin override compiles but is never called.

This is already documented as a known, deliberate gap in `docs/technical-design/virtual-dispatch.md` (issue #20). It blocks #42 directly: `ScriptLanguageExtension._get_name`/`_get_extension`/`_get_reserved_words`/`_validate`/`_get_recognized_extensions`, `ResourceFormatLoader._load`, and `ScriptExtension._instance_create` all return one of these unsupported types.

## Prior art in the codebase

- `kotlin-native/binding/.../ClassRegistrationHelpers.kt`'s `createToStringFunc` already placement-constructs a `String` into a caller-provided pointer via `StringBinding.newWithUtf16CharsRaw(outStrPtr, ...)` — this is the *forward* direction (Kotlin -> Godot) I need for virtual-call returns.
- `GodotBindingGenerator.generateGetterMethodTrampoline` boxes any builtin generically through `VariantBinding.newCopyRaw(returnValue, obj.prop.toVariant().rawPtr)` for `@Export` property getters.
- #127 (closed) fixed the *reverse* direction (Godot -> Kotlin, i.e. `Shared.kt`'s `buildReturnAlloc`/`buildReturnRead`) for **value-type** builtins (`Vector2i`, `Color`, ...): a different bug, different code path, but confirms the raw-bytes-at-address ABI assumption for ptrcall builtin returns.

## Scope

Extend `VirtualCallImplGen.isReturnSupported` + `buildReturnWrite` to placement-construct heap-backed builtin returns directly into the `ret: GDExtensionTypePtr` Godot provides, starting with `String` and `PackedStringArray` (needed for #42's required methods), extending to `Dictionary`/`Variant` if tractable in the same pass. `void*` (`_instance_create`, `_debug_get_stack_level_instance`, ...) is a separate, narrower case — a raw opaque pointer write, not a builtin placement-construct — evaluated separately.

This is general codegen infrastructure affecting any of the 106 engine classes with virtual methods in this shape, not specific to #42.

## Outcome

Implemented generically for **all** heap-backed builtins with a copy constructor (not just String/PackedStringArray) — the mechanism turned out not to need per-type special-casing:

- `Variant` returns reuse the existing `VariantBinding.newCopyRaw` path directly.
- Every other builtin (`String`, `PackedStringArray`, `Dictionary`, `StringName`, ...) placement-constructs into `ret` via `VariantBinding.getPtrConstructorRaw(variantType, copyCtorIndex)` — the exact same mechanism every generated builtin wrapper's own `constructor(from: T)` already uses (confirmed by reading the generated `PackedStringArray.kt`). The copy-constructor index is resolved from `ctx.model.builtins` (the constructor whose single argument type matches the builtin itself), not a hand-picked/assumed index — avoids the class of bug #127 found in the forward ptrcall path.
- The fptr is cached once per (file, builtin return type) as a file-scoped `private val ... by lazy`, mirroring `BuiltinClassImplGen.buildTopLevelFptrProperties`, not re-looked-up per call.
- One dead-code bug caught and fixed before landing: `ctx.isBuiltin(...)` also matches primitive Godot type names (`"bool"`, `"int"`, ...), which would've generated unused `boolCopyCtorFptr`/`intCopyCtorFptr` properties for return types already handled by an earlier branch — excluded via the same CVar/`BOOLEAN` check `buildReturnWrite`'s `when` already uses.

`void*` returns (`_instance_create`, `_debug_get_stack_level_instance`, ...) remain unsupported, as scoped — out of reach of this mechanism (not a builtin placement-construct, no copy constructor to look up) and not needed to unblock #42's Fase 2.

### A real bug my first verification pass missed

First implementation called `safeIdentifier(method.name)` (e.g. `_validatePath`) for every trampoline. For a `String`-returning virtual, though, `TypeOverloadGenerator.GodotStringMapping` renames the GodotString-typed original to `<name>AsGdStr` and repurposes the plain name for an all-Kotlin convenience overload — **including its parameters**: whenever a method both returns `String` and takes a `String` argument, the plain name's parameter type is *also* Kotlin `String`, not `GodotString`. `appendArgRead` always builds `GodotString`-typed arguments, so calling the plain name broke for every such method (`_validatePath(path: String): String` was being called with a `GodotString` argument) — a real "Argument type mismatch: actual type is 'GodotString', but 'String' was expected" compile error, not just a return-side issue. Fixed by calling `<name>AsGdStr` whenever the return type is `String`, which is `GodotString`-typed throughout (args and return) — this also removed the need for the separate `GodotString(result).use { }` return-side bridge entirely, since `result` is already `GodotString`.

This was caught only because a genuinely full `./gradlew assemble` (not a scoped module compile) was run and its real exit code checked — an earlier verification pass had piped `assemble`'s output through `tail`, which silently replaced the reported exit code with `tail`'s (always 0), masking 4 failed compile tasks across ~15 files. Re-verified without any exit-code-masking pipe:
- `:codegen:api:kotlin-native:compileKotlin` + `:codegen:api:kotlin-native:test` + `:codegen:api:common:test` — pass.
- `:kotlin-native:api:generated:compileKotlinMacosArm64` / `compileKotlinLinuxX64` / `compileKotlinMingwX64` — all three pass (`BUILD SUCCESSFUL`, confirmed `EXIT_CODE=0` from the log itself, not a tool wrapper's summary).
- `:kotlin-native:api:generated:compileNativeMainKotlinMetadata` — passes in isolation.
- `:mi-juego-prueba:kotlin_native_game:export:linkDebugSharedMacosArm64` (the actual consumer app linking against the generated klib) — passes in isolation.
- A single all-in-one `./gradlew assemble` run OOMs (`GC overhead limit exceeded` in `compileNativeMainKotlinMetadata`) on this machine when every target/module compiles concurrently — confirmed to be resource contention, not a regression, by the isolated passes above.
