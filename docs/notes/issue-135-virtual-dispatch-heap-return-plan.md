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
