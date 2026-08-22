# kogot Gradle Plugin

Gradle tooling for kogot-based Kotlin/Native Godot projects (issue [#25](https://github.com/kingg22/kogot/issues/25)). This is a standalone composite build (`rootProject.name = "kogot-gradle-plugin"`), separate from the root `kogot` build, with two published plugins:

| Plugin ID                    | Applied in               | Implementation class                                                   |
|-------------------------------|---------------------------|--------------------------------------------------------------------------|
| `io.github.kingg22.kogot`     | a KMP game/library module | [`KogotPlugin`](plugin/src/main/kotlin/io/github/kingg22/kogot/gradle/KogotPlugin.kt) |
| `io.github.kingg22.kogot.settings` | `settings.gradle.kts` | [`KogotSettingsPlugin`](settings-plugin/src/main/kotlin/io/github/kingg22/kogot/gradle/settings/KogotSettingsPlugin.kt) |

## `plugin`: `io.github.kingg22.kogot`

Applied on top of the Kotlin Multiplatform plugin. On `afterEvaluate` (so it can read the final `kogot { ... }` config) it:

1. Applies the KSP plugin and wires the kogot annotations/processor dependencies into `commonMain` and every native target's KSP configuration.
2. Registers a `generateKogotEntryPoint<Target>` task per Kotlin/Native target that emits the `@CName` entry point aggregating every `*_Binding.register()` call (via the KSP-generated `GeneratedBindings` class).
3. Registers a `copyKogotBinary<Target><BuildType>` task per `binaries.sharedLib()` output, plus a `copyKogotBinaries` aggregator over all of them.
4. Registers `generateKogotGdextension`, which writes the `.gdextension` manifest with one `[libraries]` entry per target/build-type.
5. Registers `runGodot` / `testGodot`, thin wrappers around the `godot` CLI (`--path`, `--headless`, extra args).

### Configuration (`kogot { ... }` extension)

Every property has a `gradle.properties` fallback so CI/local overrides don't require touching the build script. See [`KogotConventions`](plugin/src/main/kotlin/io/github/kingg22/kogot/gradle/KogotConventions.kt) for the exact key/default for each one — it is the single source of truth, nothing else in the plugin should hardcode a `kogot.*` property name.

| Property | `gradle.properties` key | Default | Purpose |
|---|---|---|---|
| `kogotVersion` | `kogot.version` | `0.1.0` | Version used to resolve kogot's own published artifacts. |
| `kogotGroup` | `kogot.group` | `io.github.kingg22.kogot` | Maven group used to resolve kogot's own published artifacts. |
| `autoApplyKsp` | `kogot.autoApplyKsp` | `true` | Apply the KSP plugin automatically. |
| `autoAddDependencies` | `kogot.autoAddDependencies` | `true` | Auto-wire the kogot annotations/processor dependencies. |
| `godotVersion` | `kogot.godotVersion` | `4.7.1` | Written as `.gdextension` `compatibility_minimum`. |
| `godotExecutable` | `kogot.godotExecutable` | auto-detected from `PATH` (`godot4`/`godot`) | Executable used by `runGodot`/`testGodot`. |
| `godotProjectDir` | `kogot.godotProjectDir` | — | Godot project consuming the compiled GDExtension. |
| `entrySymbol` | `kogot.entrySymbol` | `godot_kotlin_init` | `.gdextension` `entry_symbol` and the generated `@CName`. |
| `entryPointPackage` | `kogot.entryPointPackage` | `generated` | Package of the generated entry-point file. |
| `generateEntryPoint` | `kogot.generateEntryPoint` | `true` | Whether to register the entry-point generation task. |
| `generateGdextensionFile` | `kogot.generateGdextensionFile` | `true` | Whether to register `generateKogotGdextension`. |
| `libraryBaseName` | `kogot.libraryBaseName` | the Gradle project name | Base name for the compiled shared library / `.gdextension` file name. |
| `godotCliArgs` | — | empty | Extra raw CLI args forwarded to `runGodot`/`testGodot`. |
| `runtimePackage` | `kogot.runtimePackage` | `io.github.kingg22.godot.internal` | Base package of kogot's own Kotlin/Native runtime. |
| `binaryOutputDir` | `kogot.binaryOutputDir` | `bin` | Directory (relative to `godotProjectDir`) binaries are copied into / `.gdextension` paths point at. |
| `minInitializationLevel` | `kogot.minInitializationLevel` | `GDEXTENSION_INITIALIZATION_SCENE` | `GDExtensionInitializationLevel` the generated entry point attaches registrations to. |
| `compatibilityMaximum` | `kogot.compatibilityMaximum` | unset | `.gdextension` `compatibility_maximum`. |
| `reloadable` | `kogot.reloadable` | `false` | `.gdextension` `reloadable`. |
| `androidAarPlugin` | `kogot.androidAarPlugin` | `false` | `.gdextension` `android_aar_plugin`. |
| `icons` | — | empty | `.gdextension` `[icons]`: class name -> 16x16 SVG path. |
| `gdextensionDependencies` | — | empty | `.gdextension` `[dependencies]`: feature tag -> {source path -> destination subdir}. Named `gdextensionDependencies`, not `dependencies`, to avoid colliding with `Project.dependencies { }`. |
| `generatedBindingsPackage` | `kogot.generatedBindingsPackage` | `generated` | Package of the KSP-generated `BindingInitializationCallbacks` aggregator. |
| `generatedBindingsClassName` | `kogot.generatedBindingsClassName` | `GeneratedBindings` | Class name of that aggregator. |

### Generated tasks

- `generateKogotEntryPoint<Target>` — one per Kotlin/Native target.
- `copyKogotBinary<Target><BuildType>` — one per shared-lib binary; `copyKogotBinaries` aggregates all of them.
- `generateKogotGdextension` — writes the `.gdextension` manifest, depends on every target's link tasks.
- `runGodot` / `testGodot` — launch the Godot CLI (editor / headless test run respectively).

## `settings-plugin`: `io.github.kingg22.kogot.settings`

Applied in `settings.gradle.kts`, once, at the repo/settings level:

```kotlin
plugins { id("io.github.kingg22.kogot.settings") }

kogot {
    export(":app") {
        targets = listOf(NativeTargetPreset.LINUX_X64, NativeTargetPreset.MACOS_ARM64)
        godotProjectDir = file("app-godot-project")
        libraryBaseName = "my_game"
        entrySymbol = "godot_kotlin_init"
    }
}
```

For each `export(...)` call it includes a companion project at `<modulePath>:export` (path segment configurable via `KogotExportSpec.exportProjectName`) and generates its `build.gradle.kts` — the module itself stays a single, plain KMP module and never needs to declare `binaries.sharedLib()` or apply the `io.github.kingg22.kogot` plugin directly. See [`KogotExportSpec`](settings-plugin/src/main/kotlin/io/github/kingg22/kogot/gradle/settings/KogotExportSpec.kt) for every configurable field (`targets` is required — not inferred from the main module, see the class/plugin doc comments for why) and [`KogotSettingsPlugin`](settings-plugin/src/main/kotlin/io/github/kingg22/kogot/gradle/settings/KogotSettingsPlugin.kt) for why the build script is generated text instead of typed KGP DSL calls.

Running `kogotExport` on the main module builds the companion project (`assemble`), copies its binaries (`copyKogotBinaries`), and generates its `.gdextension` manifest (unless `generateGdextensionFile = false`).

## Building / testing this composite build

```bash
cd gradle-plugin
./gradlew build
./gradlew publishToMavenLocal   # to consume from a local kogot-based project
```
