# Virtual dispatch: void* return support (issue #137)

## Problem

Discovered while implementing [#42](https://github.com/kingg22/kogot/issues/42), same file #135
fixed. `VirtualCallImplGen.isReturnSupported`
(`codegen/api/kotlin-native/src/main/kotlin/io/github/kingg22/godot/codegen/extensionapi/impl/knative/impl/VirtualCallImplGen.kt`,
lines 60-73) has no branch for `returnType == "void*"`. A virtual method whose return type is `void*`
gets no `GDExtensionClassCallVirtual` trampoline at all: Godot's `get_virtual_func` returns `null` for
it and silently falls back to its own C++ default (no-op) behavior. The Kotlin override compiles but
is never called.

`#135`'s outcome section explicitly scoped this out: "`void*` returns (`_instance_create`,
`_debug_get_stack_level_instance`, ...) remain unsupported, as scoped — out of reach of this mechanism
(not a builtin placement-construct, no copy constructor to look up)". This note picks that up as its
own, narrower fix.

Confirmed via `godot-version/v4_7_1/extension_api.json` — exactly 3 virtual methods return `void*`,
all as the plain string `"void*"` (no `"const void*"` return ever appears in the JSON, unlike
argument types which do use both forms):
- `ScriptExtension._instance_create`
- `ScriptExtension._placeholder_instance_create`
- `ScriptLanguageExtension._debug_get_stack_level_instance`

Blocks #42 Fase 3: `ScriptExtension._instance_create` needs to actually be invokable so a Kotlin
`KotlinScript._instanceCreate(forObject: GodotObject): COpaquePointer` override gets called by the
engine.

## Prior art in the codebase

- `#135` (`docs/notes/issue-135-virtual-dispatch-heap-return-plan.md`) fixed heap-backed builtin
  returns (String, Dictionary, Variant, ...) via placement-construction into `ret`. `void*` is
  structurally simpler: no builtin, no copy constructor — just a raw opaque pointer write.
- The existing `ctx.isEngineClass(returnType)` branch in `buildReturnWrite` already does exactly this
  shape of write — `ret?.reinterpret<COpaquePointerVar>()?.pointed?.value = result.rawPtr` — for
  engine-class returns. The only difference for `void*` is that `result` is already the raw
  `COpaquePointer` (per `KotlinNativeTypeResolver.resolvePointer`, which maps `void*` directly to
  `COpaquePointer`, confirmed in `KotlinNativeTypeResolverTest.kt`), not a wrapper object with a
  `.rawPtr` property — so the write is the same minus the `.rawPtr` suffix.

## Scope

1. `isReturnSupported`: add `if (returnType == "void*") return true`, matching the plain-string form
   confirmed in `extension_api.json` (no `const` prefix stripping needed on the return side, unlike
   `isArgSupported`'s argument-side check).
2. `buildReturnWrite`: add a `returnType == "void*"` branch, structurally identical to the
   `ctx.isEngineClass(returnType)` branch but writing `result` directly instead of `result.rawPtr`.
3. `isArgSupported`/argument-side `void*` handling is explicitly untouched — out of scope, still
   unsupported per the type-support matrix in `docs/technical-design/virtual-dispatch.md`.
4. `ScriptExtension._instance_create` takes one `Object`-typed argument (already supported via the
   existing `ctx.isEngineClass(arg.type)` branch in `appendArgRead`) and returns `void*` — no other
   changes to `buildTrampoline`'s arg-read flow are needed for this method shape.

## Outcome

Implemented as scoped, no surprises:

- `isReturnSupported`: added `if (returnType == "void*") return true` right after the `Variant` check,
  before the `typeResolver.resolve()` call (unneeded for this branch).
- `buildReturnWrite`: added a `returnType == "void*"` branch right after the `ctx.isEngineClass`
  branch, writing `ret?.reinterpret<COpaquePointerVar>()?.pointed?.value = result` — confirmed by
  reading the generated `ScriptExtensionVirtualCalls.kt` output that `result` (from
  `typeResolver.resolve()` resolving `void*` to `COpaquePointer`) needs no `.rawPtr` unwrap, unlike the
  engine-class branch it's modeled on.
- No changes needed to `buildTrampoline`/`appendArgRead`: `ScriptExtension._instance_create`'s single
  `Object`-typed argument was already supported via the existing `ctx.isEngineClass(arg.type)` branch.

### Verification (mirroring #135's corrected methodology — real exit codes, no exit-code-masking pipes)

- `:codegen:api:kotlin-native:compileKotlin` — `BUILD SUCCESSFUL`, exit 0.
- `:codegen:api:kotlin-native:test` + `:codegen:api:common:test` — `BUILD SUCCESSFUL`, exit 0.
- `:kotlin-native:api:generated:generateGodotKotlinNativeApi` — regenerated the API; confirmed by
  reading the output directly (this worktree had no prior generated output to diff against, so
  verified by content inspection instead of a before/after diff):
  - `ScriptExtensionVirtualCalls.kt` now has `instanceCreate` and `placeholderInstanceCreate`
    `GDExtensionClassCallVirtual` properties (previously would not exist at all).
  - `ScriptExtension.kt`'s `_instanceCreate`/`_placeholderInstanceCreate` now carry
    `@GodotVirtualMethod("_instance_create")` / `@GodotVirtualMethod("_placeholder_instance_create")`.
  - `ScriptLanguageExtensionVirtualCalls.kt` now has `debugGetStackLevelInstance`, and
    `ScriptLanguageExtension.kt`'s `_debugGetStackLevelInstance` carries
    `@GodotVirtualMethod("_debug_get_stack_level_instance")`.
  - Generated trampoline body for `instanceCreate` (confirms the write is exactly as designed):
    ```kotlin
    public val instanceCreate: GDExtensionClassCallVirtual =
            staticCFunction { instancePtr, args, ret ->
        val instance = instancePtr.getInstance<ScriptExtension>()
        val arg0Ptr = requireNotNull(args?.`get`(0)).reinterpret<COpaquePointerVar>().pointed.`value`
        val arg0 = GodotObject(requireNotNull(arg0Ptr) { "Argument 0 (Object) was null" })
        val result = instance._instanceCreate(arg0)
        ret?.reinterpret<COpaquePointerVar>()?.pointed?.`value` = result
    }
    ```
- `:kotlin-native:api:generated:compileKotlinMacosArm64` — `BUILD SUCCESSFUL`, exit 0 (28s).
- `:kotlin-native:api:generated:compileKotlinLinuxX64` — `BUILD SUCCESSFUL`, exit 0 (28s).
- `:kotlin-native:api:generated:compileKotlinMingwX64` — `BUILD SUCCESSFUL`, exit 0 (26s).
- Ran each task individually/scoped, never a single all-in-one `./gradlew assemble` (per #135's finding
  that it OOMs on this machine from resource contention across concurrently-compiling targets).
- `./gradlew spotlessApply` reformatted the touched file (no actual diff — it was already compliant)
  and, as a known repo quirk, also pulled in an unrelated reformat of
  `processor/src/main/kotlin/io/github/kingg22/kogot/processor/KogotProcessor.kt`; reverted that file
  with `git checkout --` to keep the diff scoped to this fix.

### What is NOT verified

Actual runtime behavior inside the Godot editor/engine is not verified by any of the above — only that
the trampoline now exists in generated source and that the generated code compiles across all three
Kotlin/Native targets. Whether Godot's `get_virtual_func` actually resolves and calls this trampoline
correctly at runtime, and whether the raw `COpaquePointer` round-trips correctly through Godot's own
`void*` handling on the C++ side, remains to be confirmed once #42's `KotlinScript._instanceCreate`
override is exercised end-to-end in the editor.
