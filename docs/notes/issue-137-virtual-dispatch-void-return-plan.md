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

(filled in after implementation + verification)
