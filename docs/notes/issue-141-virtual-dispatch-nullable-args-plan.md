# Virtual dispatch: engine-class argument nullability (issue #141)

## Problem

Discovered real-editor-testing PR #134 (#42, Kotlin as a Godot script language): typing in a `.kt`
file open in Godot's built-in script editor crashed the whole Godot process (`SIGABRT`) with

```
Uncaught Kotlin exception: kotlin.IllegalArgumentException: Argument 2 (Object) was null
```

inside `ScriptLanguageExtensionVirtualCalls.completeCode`'s generated trampoline. The exception can't
cross the C callback boundary Kotlin/Native sets up for `staticCFunction`, so it aborts the process
instead of propagating anywhere catchable.

Root cause: `VirtualCallImplGen.appendArgRead`
(`codegen/api/kotlin-native/src/main/kotlin/io/github/kingg22/godot/codegen/extensionapi/impl/knative/impl/VirtualCallImplGen.kt`),
in the `ctx.isEngineClass(arg.type) || ctx.isSingleton(arg.type)` branch, gated the null-safe
`?.let { }` read vs. a `requireNotNull`-and-crash read on `arg.isNullable`
(`codegen/api/common/src/main/kotlin/io/github/kingg22/godot/codegen/models/extensionapi/MethodArg.kt:14`
— `type != "Variant" && defaultValue == "null"`, i.e. whether `extension_api.json` gives the argument an
explicit `default_value: "null"`). That heuristic is correct for the *forward* direction (Kotlin calling
into Godot, choosing to pass null to an optional parameter) but was being reused for the *reverse*
direction here too — Godot calling into a Kotlin override — where it doesn't apply: confirmed via `jq`
against `godot-version/v4_7_1/extension_api.json` that **zero** virtual-method arguments in the whole
API ever carry a `default_value` key at all, so `arg.isNullable` was unconditionally `false` for every
one of them. `ScriptLanguageExtension._complete_code`'s `owner: Object` argument has no default-value
metadata, yet Godot genuinely passes null for it whenever the script being edited isn't attached to a
live scene object (the ordinary case editing from the FileSystem dock).

## Impact

Not scoped to #42. Scanning `godot-version/v4_7_1/extension_api.json`: **142 of the engine's 1,437
virtual methods across 41 classes** have at least one engine-class-typed argument, all sharing this
crash-prone pattern — including common gameplay virtuals no kogot user has overridden yet:
`Node._input`/`_shortcut_input`/`_unhandled_input`/`_unhandled_key_input(event: InputEvent)`,
`Control._gui_input(event: InputEvent)`, `CollisionObject2D/3D._input_event(...)` (inherited by
`Area2D/3D`, `PhysicsBody2D/3D`), `RigidBody2D/3D._integrate_forces(state)`.

Also affects 10 overrides already shipped on PR #134's branch (`KotlinScriptLanguage.kt`,
`KotlinScript.kt`, `KotlinResourceFormatSaver.kt`) — out of scope for this fix to touch directly (that
branch rebases onto this one afterward and updates its own overrides).

## Prior art in this lineage

Same file, same discovery context (scoping #42/PR #134): #137/PR #138 (`void*` return support) and
#139/PR #140 (`typedarray::*` return support) fixed analogous type-support gaps on the *return* side of
this same reverse-dispatch trampoline. This issue is the *argument* side, and unlike those two isn't a
missing-support gap (the method already compiles and dispatches either way) — it's a silent-crash
correctness bug in an already-"supported" code path.

## Scope

1. `appendArgRead`'s `ctx.isEngineClass(arg.type) || ctx.isSingleton(arg.type)` branch: always take the
   `?.let { }` null-safe read, unconditionally — stop consulting `arg.isNullable` for this branch
   entirely.
2. Consequence: the trampoline's local variable becomes `Type?` regardless of `arg.isNullable`, so the
   `open fun` stub's own declared parameter type has to accept null too, or the generated call
   `instance.method(arg0, ...)` won't type-check. `NativeMethodGenerator.buildMethod`/`buildParameter`
   gets a `forceNullableEngineArgs`/`forceNullable` parameter (default `false`, so every other call site
   — builtin constructors, static methods, utility functions, non-virtual instance methods — is
   byte-for-byte unaffected), wired at `NativeEngineClassGenerator`'s standalone-instance-method call
   site as `forceNullableEngineArgs = method.isVirtual`. Forward (non-virtual) method signatures stay
   exactly `arg.isNullable`-derived, unchanged — `EngineMethodImplGen`/`Shared.kt` (the forward ptrcall
   marshalling generators) are untouched, and don't need to be: `EngineMethodImplGen.buildMethodBody`
   already short-circuits virtual methods to a `TODO()`/empty body before ever reaching the
   argument-marshalling code that would care about the parameter's nullability.
3. Leave the `ctx.isBuiltin(arg.type) -> "val %N = %T(%L)"` branch alone: builtin/value-typed virtual
   arguments are passed by value via Godot's ptrcall convention — always a valid, possibly
   default/empty, memory location, never a null pointer. Confirmed via the same `jq` query: no
   builtin-typed virtual argument in `extension_api.json` is ever marked nullable either, consistent
   with that ABI guarantee.
4. Update the Argument row of `docs/technical-design/virtual-dispatch.md`'s type-support matrix, and add
   a dedicated explanatory subsection (mirroring #137/#139's inline matrix notes).
5. No test added: neither #135, #137, nor #139 (equally deep changes to this same generator) added a
   unit test for `VirtualCallImplGen`/`NativeMethodGenerator` — there is no existing test file for
   either, and building one from scratch (a `Context` with a populated `ResolvedApiModel.engineClasses`,
   a real `TypeResolver`, an `ImplementationPackageRegistry`) is a disproportionate amount of net-new
   test scaffolding for this fix alone. Matching precedent rather than introducing it unilaterally here.

## Outcome

Implemented as scoped:

- `VirtualCallImplGen.appendArgRead`: the `ctx.isEngineClass(arg.type) || ctx.isSingleton(arg.type)`
  branch's `if (arg.isNullable) { ?.let } else { requireNotNull }` became a single unconditional
  `?.let { }` statement.
- `NativeMethodGenerator.buildMethod`/`buildParameter`: added `forceNullableEngineArgs`/`forceNullable`
  parameters (both default `false`).
- `NativeEngineClassGenerator`: standalone instance method call site passes
  `forceNullableEngineArgs = method.isVirtual`.
- `docs/technical-design/virtual-dispatch.md`: Argument row + new explanatory subsection.

### Verification (mirroring #135/#137/#139's methodology — real exit codes, no exit-code-masking pipes)

- `:kotlin-native:api:generated:generateGodotKotlinNativeApi` — regenerated; confirmed by reading the
  generated output directly:
  - `ScriptLanguageExtension.kt`'s `_completeCode`: `owner` parameter is now `GodotObject?` (both the
    `GodotString`-typed original and its all-Kotlin-`String` sibling overload).
  - `ScriptLanguageExtensionVirtualCalls.kt`'s `completeCode` trampoline: `arg2 = arg2Ptr?.let {
    GodotObject(it) }`, no `requireNotNull` on the engine-class argument.
  - `Node.kt`'s `_input`: `event` parameter is now `InputEvent?`; `NodeVirtualCalls.kt`'s `input`
    trampoline: `arg0 = arg0Ptr?.let { InputEvent(it) }`.
- `./gradlew assemble` — see build log below (grepped directly for `BUILD SUCCESSFUL`/`BUILD FAILED`,
  never piped through `tail`).
- `./gradlew spotlessApply` — see below; any unrelated reformat of
  `processor/src/main/kotlin/io/github/kingg22/kogot/processor/KogotProcessor.kt` reverted with `git
  checkout --`, per the known repo quirk #135/#137/#139 also hit.
- Checked no other `main`-reachable code (outside PR #134's own branch, which is out of scope) overrides
  a now-affected virtual with a non-nullable signature: `grep -rln "override fun _" --include="*.kt" .`
  (excluding `build/`, `jvm-ffm/`) only turns up `mi-juego-prueba/kotlin_native_game/`'s
  `Sprite.kt`/`TestOne.kt`/`SpriteBench.kt`, overriding `_ready()` (no args) and `_process(delta:
  Double)` (primitive, not engine-class) — neither affected by this fix.

### What is NOT verified

Actual runtime behavior in the Godot editor/engine is not verified by any of the above — only that the
generated code compiles and matches the intended null-safe shape. Whether this actually stops the
original `_complete_code` crash end-to-end in the editor remains to be confirmed once #42/PR #134
rebases onto this fix and re-exercises the script editor.
