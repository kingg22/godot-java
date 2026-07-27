---
name: kogot-repo-map
description: Quick navigation aid for the kogot codebase. Use when exploring modules, finding key files, understanding generated vs hand-written code, or needing to understand the project structure. Trigger on: exploring kogot, finding where something is implemented, understanding code flow.
---

# Kogot Repo Map

Quick navigation aid for the kogot codebase. Where to find what.

## Module Map

| Module                          | Purpose                                                                  | Key Files                                             |
|----------------------------------|---------------------------------------------------------------------------|--------------------------------------------------------|
| `codegen/`                       | Godot API model + KotlinPoet generation                                   | `models/extensionapi/`, `impl/KotlinPoetGenerator.kt` |
| `processor/`                     | KSP compiler plugin (extraction + validation + codegen, unified MVP module since commit `96af87c` — the old `analysis/` module was merged in here) | `KogotProcessor.kt`, `ksp/`, `model/`, `diagnostics/`, `generators/`, `resolver/` |
| `kotlin-native/api/annotations/` | User annotations: @Godot, @Export, @Rpc, @Tool, @RegisterSignal, ...       | -                                                      |
| `kotlin-native/api/generated/`   | **Generated** Godot API classes (2000+ files, produced by `codegen/`)     | `build/generated/`                                    |
| `kotlin-native/api/callable/`, `signal/`, `utils/`, `testing/`, `chore/` | User-facing API support modules                    | -                                                      |
| `kotlin-native/binding/`         | Runtime binding registration                                              | `ClassRegistrationHelpers.kt`, `SignalRegistration.kt`, `NodeVirtualCalls.kt` |
| `kotlin-native/ffi/`             | Low-level FFI to Godot C functions                                        | `.def` cinterop files                                 |
| `kotlin-native/runtime/`         | Kotlin/Native runtime support                                             | -                                                      |
| `jvm-ffm/`                       | Java FFM integration. **Paused/on hold**: this project is exploration-first — Kotlin/Native is being worked out completely first since it's the harder target; whatever gets learned there should replicate more easily to Java FFM afterward. Not being actively developed right now. | `api/`, `ffm/`, `runtime/`, `native-register/`        |
| `build-logic/`                   | Gradle convention plugins                                                 | `buildlogic.*.gradle.kts`                             |

## Generated vs Hand-Written

**Generated** (don't edit directly):
- `kotlin-native/api/generated/build/generated/**` - Godot API classes
- `processor/build/generated/**` - KSP processor output (per-user-class `*_Binding.kt`)

**Hand-written**:
- All source in `codegen/`, `processor/`
- `kotlin-native/annotations/` (now `kotlin-native/api/annotations/`), `binding/`, `ffi/`, `runtime/`

## Key Files by Task

| Task                        | Key Files                                              |
|-----------------------------|--------------------------------------------------------|
| Add new Godot builtin type  | `codegen/models/extensionapi/`, run codegen            |
| Add new engine class        | `codegen/` + run API generation                        |
| Fix KSP processor           | `processor/KogotProcessor.kt`                          |
| Add new annotation          | `kotlin-native/api/annotations/` + validation in `processor/KogotProcessor.kt` (no separate `validation/` dir) |
| Modify binding registration | `kotlin-native/binding/ClassRegistrationHelpers.kt`    |
| Change FFI layer            | `kotlin-native/ffi/` + `.def` files                    |
| Debug spritebench           | `mi-juego-prueba/kotlin_native_game/`                  |

## Code Flows

**Annotation flow:**
```
@Godot → processor/KogotProcessor → GodotBindingGenerator → *_Binding.kt
```

**Codegen flow:**
```
Godot JSON API → codegen/models/ → KotlinPoetGenerator → kotlin-native/api/generated/build/generated/
```

**Registration flow:**
```
register() → ClassRegistrationHelpers → GDExtension API → Godot ClassDB
```

## Docs to Read

- `docs/kogot_binding_philosophy.md` - Design principles
- `docs/roadmap-22feb.md` - Historical roadmap
- `docs/summary-6march.md` - Recent status
- `docs/signal-design.md` - Signal implementation
