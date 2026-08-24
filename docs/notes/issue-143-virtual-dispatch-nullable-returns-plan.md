# Virtual dispatch: engine-class return nullability (issue #143)

## Problem

`VirtualCallImplGen.buildReturnWrite`
(`codegen/api/kotlin-native/src/main/kotlin/io/github/kingg22/godot/codegen/extensionapi/impl/knative/impl/VirtualCallImplGen.kt`)'s
`ctx.isEngineClass(returnType)` branch unconditionally writes `result.rawPtr` for any virtual method
whose return type is an engine class. Meanwhile the generated Kotlin stub for that same virtual
(`NativeMethodGenerator.buildMethod`'s `.returns(returnTypeSpec)`) always declares the return type
non-nullable — there was no return-side equivalent of `buildParameter`'s existing `forceNullable`
parameter, which `NativeEngineClassGenerator.kt` already passed for virtual-method *arguments*
(`forceNullableEngineArgs = method.isVirtual`, see issue #141/PR #142).

`COpaquePointer` (the type behind `GodotObject.rawPtr`) cannot itself represent a null address —
kotlinx.cinterop's own `CPointer<T>` is non-null by construction; a null address can only ever surface
as a Kotlin `null` reference. With the return type forced non-nullable, no Kotlin override of one of
these virtuals could ever hand back "no value" to Godot — every override was forced to always return
*some* real object.

## Impact

Confirmed via real-editor testing on PR #134 (issue #42, Kotlin as a Godot script language).
`ScriptExtension._get_base_script` is a required virtual (`is_required: true` in `extension_api.json`)
whose C++ caller, `EditorData::get_script_icon` (`editor/editor_data.cpp:1213-1239`), walks the
base-script chain with **no cycle detection or iteration limit**:

```cpp
Ref<Script> base_scr = ResourceLoader::load(p_script_path, "Script");
while (base_scr.is_valid()) {
    ...
    icon_path = base_scr->get_class_icon_path();   // crash site
    ...
    base_scr = base_scr->get_base_script();          // line 1239, no guard
}
```

`Ref<T>::is_valid()` is a plain null-pointer check — it does **not** call the script's own `_is_valid()`
virtual. A Kotlin `@Godot` class always inherits its native ClassDB parent, never another `Script`
resource, so there's genuinely no base script to report — but `KotlinScript._getBaseScript()` was forced
to return a real, non-null object every time (a shared sentinel), including when called *on that
sentinel itself*. The loop never terminates. Confirmed via a self-built Godot 4.7.1 dev binary under
lldb: the object handed back each iteration is a legitimately valid, non-corrupted `ScriptExtension*`
(ruling out memory corruption) — the crash (SIGSEGV inside Godot's own `EditorData::get_script_icon`) is
a downstream consequence of the unbounded loop, not a direct null-deref.

Scanning `godot-version/v4_7_1/extension_api.json`: **43 virtual methods across 30 classes** return an
engine-class type, all sharing this same architectural gap — no override of any of them can ever express
"no value." Beyond `ScriptExtension._get_base_script`, several are documented in Godot's own C++ headers
as legitimately nullable ("return nullptr if unimplemented" or similar): `AudioEffect._instantiate`,
`Control._make_custom_tooltip`, `EditorPlugin._get_plugin_icon`, `Mesh._surface_get_material`,
`PhysicsServer2D/3DExtension._space_get_direct_state`/`_body_get_direct_state`, `Texture2D._get_image`,
`VideoStreamPlayback._get_texture`, `ScriptLanguageExtension._create_script` (`is_required: false`).

## Prior art in this lineage

Same file, same discovery lineage (scoping PR #134/#42): #137/PR #138 (`void*` return support),
#139/PR #140 (`typedarray::*` return support), #141/PR #142 (argument nullability — the direct sibling
of this issue, on the opposite side of the same trampoline). This issue mirrors #141/#142's approach but
for the return side instead of the argument side.

## Scope

1. `NativeMethodGenerator.buildMethod` gets a `forceNullableEngineReturn: Boolean = false` parameter
   (default `false`, so every other call site — builtin constructors, static methods, utility functions,
   non-virtual instance methods — is byte-for-byte unaffected) that makes `returnTypeSpec` nullable
   (`.copy(nullable = true)`) when the return type resolves to an engine class or singleton and the flag
   is set.
2. `NativeEngineClassGenerator.kt`'s standalone-instance-method `buildMethod(...)` call site (the same
   one that already passes `forceNullableEngineArgs = method.isVirtual`) passes
   `forceNullableEngineReturn = method.isVirtual` alongside it. The static-methods call site is left
   untouched — static methods are never virtual, matching how it never received
   `forceNullableEngineArgs` either.
3. `VirtualCallImplGen.buildReturnWrite`'s `ctx.isEngineClass(returnType)` branch gets a null check on
   `result` before writing `.rawPtr`: `result?.rawPtr` instead of `result.rawPtr`, writing a null pointer
   into the `COpaquePointerVar`'s (nullable) `.value` when `result` is null — mirroring how
   `appendArgRead`'s sibling engine-class branch already does `val %N = %N?.let { %T(it) }` for the read
   side.
4. Applied unconditionally to all 43 affected virtuals, matching how #141/#142 applied unconditionally
   to arguments regardless of any JSON metadata — not special-cased to `_get_base_script`, and not
   conditioned on `is_required` (`extension_api.json`'s `is_required: true` on `_get_base_script` means
   the virtual itself must be implemented, not that its return value can't be null).
5. Update the Return row of `docs/technical-design/virtual-dispatch.md`'s type-support matrix, and add a
   dedicated explanatory subsection (mirroring #141's Argument-side subsection).
6. No test added: matching precedent from #135/#137/#139/#141, none of which added a unit test for this
   generator — there is no existing test file for `VirtualCallImplGen`/`NativeMethodGenerator`, and
   building the scaffolding from scratch (a `Context` with a populated `ResolvedApiModel.engineClasses`,
   a real `TypeResolver`, an `ImplementationPackageRegistry`) is disproportionate to this fix alone.

## Outcome

Implemented as scoped:

- `NativeMethodGenerator.buildMethod`: added `forceNullableEngineReturn` parameter (default `false`).
  The return type is computed from the original `Triple` as before, then conditionally widened to
  nullable via `.copy(nullable = true)` when `forceNullableEngineReturn` is set and the original JSON
  return type resolves to an engine class or singleton (`originalType != null &&
  (context.isEngineClass(originalType) || context.isSingleton(originalType))`).
- `NativeEngineClassGenerator`: standalone instance method call site passes
  `forceNullableEngineReturn = method.isVirtual` alongside the existing `forceNullableEngineArgs`.
- `VirtualCallImplGen.buildReturnWrite`: the `ctx.isEngineClass(returnType)` branch's write changed from
  `result.rawPtr` to `result?.rawPtr` — a one-token null-safe-call change, since `result`'s static type
  is now `Type?` for every affected virtual once the two generator changes above are wired.
- `docs/technical-design/virtual-dispatch.md`: Return row + new explanatory subsection.

### Verification (mirroring #135/#137/#139/#141's methodology — real exit codes, no exit-code-masking pipes)

- `:kotlin-native:api:generated:generateGodotKotlinNativeApi` — regenerated; confirmed by reading the
  generated output directly:
  - `ScriptExtension.kt`'s `_getBaseScript()`: return type is now `Script?`, and the `TODO()` stub body
    is unchanged (still a stub — only the declared type changed).
  - `ScriptExtensionVirtualCalls.kt`'s `getBaseScript` trampoline: writes
    `ret?.reinterpret<COpaquePointerVar>()?.pointed?.value = result?.rawPtr`, null-safe on the
    engine-class return.
  - Spot-checked `Texture2D._get_image` / `Mesh._surface_get_material` and other affected virtuals from
    the issue's list — same nullable-return + null-safe-write shape.
- `./gradlew assemble` — see PR body for the actual log result.
- `./gradlew spotlessApply` — any unrelated reformat of
  `processor/src/main/kotlin/io/github/kingg22/kogot/processor/KogotProcessor.kt` reverted with `git
  checkout --`, per the known repo quirk #135/#137/#139/#141 also hit.
- Checked no other `main`-reachable code (outside PR #134's own branch, which is out of scope) overrides
  a now-affected virtual with a non-nullable return signature: `grep -rln "override fun _" --include="*.kt" .`
  (excluding `build/` and any other worktree paths) — see PR body for the actual result.

### What is NOT verified

Actual runtime behavior in the Godot editor/engine is not verified by any of the above — only that the
generated code compiles and matches the intended nullable-return shape. Whether this actually stops the
`ScriptExtension._get_base_script` infinite loop end-to-end in the editor remains to be confirmed once
#42/PR #134 rebases onto this fix and re-exercises the script editor with a real `KotlinScript`.
