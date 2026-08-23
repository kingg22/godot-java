# Virtual dispatch: typedarray::* return support (issue #139)

## Problem

Discovered while implementing [#42](https://github.com/kingg22/kogot/issues/42), same file #135 and
#137 fixed. `VirtualCallImplGen.isReturnSupported`
(`codegen/api/kotlin-native/src/main/kotlin/io/github/kingg22/godot/codegen/extensionapi/impl/knative/impl/VirtualCallImplGen.kt`)
has no branch for `typedarray::*` return types. A virtual method whose return type is `typedarray::X`
gets no `GDExtensionClassCallVirtual` trampoline at all: Godot's `get_virtual_func` returns `null` for
it and silently falls back to its own C++ default behavior. The Kotlin override compiles but is never
called.

Confirmed via `godot-version/v4_7_1/extension_api.json` — 9 required virtual methods are affected:
- `ScriptLanguageExtension._get_built_in_templates` (`typedarray::Dictionary`)
- `ScriptLanguageExtension._debug_get_current_stack_info` (`typedarray::Dictionary`)
- `ScriptLanguageExtension._get_public_functions` (`typedarray::Dictionary`)
- `ScriptLanguageExtension._get_public_annotations` (`typedarray::Dictionary`)
- `ScriptExtension._get_documentation` (`typedarray::Dictionary`)
- `ScriptExtension._get_script_signal_list` (`typedarray::Dictionary`)
- `ScriptExtension._get_script_method_list` (`typedarray::Dictionary`)
- `ScriptExtension._get_script_property_list` (`typedarray::Dictionary`)
- `ScriptExtension._get_members` (`typedarray::StringName`)

Explicitly out of scope: `ScriptExtension._profiling_get_accumulated_data`/`_profiling_get_frame_data`
— blocked by an unrelated native-struct-pointer *argument* type, not the return type this note covers.

Blocks #42: these `ScriptLanguageExtension`/`ScriptExtension` virtuals need to actually be invokable so
Kotlin overrides get called by the engine.

## Prior art in the codebase

- `#135` (`docs/notes/issue-135-virtual-dispatch-heap-return-plan.md`) added heap-backed builtin
  returns (String, Dictionary, Variant, ...) via placement-construction into `ret`, resolving the
  copy-constructor fptr generically through `ctx.model.builtins`/`copyConstructorIndex`.
- `#137` (`docs/notes/issue-137-virtual-dispatch-void-return-plan.md`) added `void*` returns.
- `typedarray::X` is a distinct case from both: `typeResolver.resolve()` already maps it to
  `GodotArray<X>` (confirmed in `KotlinNativeTypeResolver.resolvePlain`), but there is no
  `typedarray::X` entry in `ctx.model.builtins` — only the plain `Array` builtin exists there, so
  `copyConstructorIndex("typedarray::Dictionary")` would return `null` and `isReturnSupported` would
  keep rejecting it even with #135's existing `ctx.isBuiltin(returnType) && copyConstructorIndex(...)
  != null` check, since `ctx.isBuiltin("typedarray::Dictionary")` is itself `false` (it's not a builtin
  name, it's a typed-array type string).
- Godot's `Array::Array(const Array&)` copy constructor is a ref-counted share of the source's
  `ArrayPrivate*` (`_ref`), which carries the source's typed state (element type, script, class) along
  with it — so the *same* `Array` copy-constructor fptr #135 already generates for plain `Array`
  returns also correctly preserves typing for `typedarray::*` returns. No extra `set_typed`-equivalent
  call is needed here. This mirrors the forward ptrcall generator (`Shared.kt`'s `buildReturnAlloc`),
  which likewise treats `typedarray::*` identically to a plain builtin return once resolved.

## Scope

1. `isReturnSupported`: add a `returnType.startsWith("typedarray::") && copyConstructorIndex(...) !=
   null` branch, looking the copy-constructor index up against `"Array"` (via a new
   `heapBackedBuiltinName` helper) instead of the literal `typedarray::X` string.
2. Add `heapBackedBuiltinName(returnType: String): String` — returns `"Array"` for `typedarray::*`,
   otherwise the type unchanged — as the single place that maps a return type to the builtin name
   backing its native storage, used by `isReturnSupported`, `buildReturnWrite`, and
   `heapBackedReturnTypes`.
3. `buildReturnWrite`: extend the existing `ctx.isBuiltin(returnType)` branch to also match
   `returnType.startsWith("typedarray::")`, and resolve `copyCtorFptrPropertyName` against
   `heapBackedBuiltinName(returnType)` instead of `returnType` directly (so both plain builtins and
   `typedarray::*` share the exact same `arrayCopyCtorFptr` property when both appear in the same
   file).
4. `heapBackedReturnTypes`: add `typedarray::*` to the filtered set (unconditionally true — no
   CVar/BOOLEAN case ever applies to a typed-array return, unlike the `ctx.isBuiltin` types already
   handled), and map through `heapBackedBuiltinName` so the emitted fptr property is keyed by `"Array"`,
   not by each distinct `typedarray::X` string (avoiding duplicate `arrayCopyCtorFptr` properties in the
   same file when a class has multiple `typedarray::*`-returning virtuals of different element types).

## Outcome

Implemented as scoped, no surprises — structurally identical to the already-working
`dictionaryCopyCtorFptr`/`stringCopyCtorFptr` branches from #135, just routed through the new
`heapBackedBuiltinName` indirection:

- `isReturnSupported`: added the `typedarray::*` branch right after the existing builtin-copy-ctor
  check.
- `heapBackedBuiltinName`: added as a small private helper, doc-commented with the `Array::Array(const
  Array&)` ref-share reasoning above.
- `buildReturnWrite`: the builtin branch's condition became `ctx.isBuiltin(returnType) ||
  returnType.startsWith("typedarray::")`, and its `copyCtorFptrPropertyName(returnType)` call became
  `copyCtorFptrPropertyName(heapBackedBuiltinName(returnType))`.
- `heapBackedReturnTypes`: added the unconditional `typedarray::*` filter branch, and changed
  `.map { it.type }` to `.map { heapBackedBuiltinName(it.type) }` so `typedarray::Dictionary` and
  `typedarray::StringName` returns both collapse onto the single shared `"Array"` fptr property instead
  of (incorrectly) trying to emit one keyed by the literal typed-array string.

### Verification (mirroring #135/#137's methodology — real exit codes, no exit-code-masking pipes)

- `./gradlew assemble` — see build log below (grepped directly for `BUILD SUCCESSFUL`/`BUILD FAILED`,
  never piped through `tail`).
- `:kotlin-native:api:generated:generateGodotKotlinNativeApi` — regenerated the API; confirmed by
  reading the generated output directly:
  - All 9 target methods' `<EngineClass>VirtualCalls.kt` objects now carry a
    `GDExtensionClassCallVirtual` trampoline property, and the corresponding `open fun` stub in
    `ScriptLanguageExtension.kt`/`ScriptExtension.kt` carries `@GodotVirtualMethod("_...")`.
  - The `_profiling_get_accumulated_data`/`_profiling_get_frame_data` pair remains unannotated, as
    expected — still blocked by their native-struct-pointer argument, unrelated to this fix.
- `./gradlew spotlessApply` — reformatted nothing in the touched file; as a known repo quirk it also
  pulled in an unrelated reformat of
  `processor/src/main/kotlin/io/github/kingg22/kogot/processor/KogotProcessor.kt`, reverted with `git
  checkout --` to keep the diff scoped to this fix.

### What is NOT verified

Actual runtime behavior inside the Godot editor/engine is not verified by any of the above — only that
the trampolines now exist in generated source and that the generated code compiles. Whether Godot's
`get_virtual_func` actually resolves and calls these trampolines correctly at runtime, and whether the
`Array` copy-constructor's ref-share genuinely round-trips each method's specific typed state
(`Dictionary`-typed vs. `StringName`-typed) correctly through Godot's own `Array` handling on the C++
side, remains to be confirmed once #42 exercises these overrides end-to-end in the editor.
