# inspect-object-repro

A minimal `@tool` `EditorPlugin` that headlessly drives `EditorInterface.inspect_object()` on a
node with a script attached, to reproduce editor-Inspector crashes without needing a display.

## Why this exists

While implementing issue [#42](https://github.com/kingg22/kogot/issues/42) (Kotlin as a Godot
script language), the editor crashed only when a script's owning node was actually opened in the
Inspector — a code path that a plain functional test (`load()` / `set_script()` / `get()` /
`set()` / `.call()`, no UI) never exercises, since the Inspector fetches things a running script
never touches on its own (e.g. the script's icon via `EditorData::get_script_icon`,
`_getScriptPropertyList()`/`_getScriptMethodList()` reconciliation, `get_class_category_func`).

Godot's `--headless` mode still boots the full editor and its `EditorNode`/`EditorInspector`
machinery — `EditorInterface.inspect_object()` runs the same way it would with a display attached.
This plugin calls it from `_enter_tree()`, so `godot --headless --editor --path <project>` becomes
a scriptable repro for Inspector-only crashes: no manual clicking, no display server, safe to run
under `lldb -b` or capture a `--headless`-friendly backtrace/crash report from.

This is how the root cause of the `#42` script-attach crash chain was found and confirmed fixed —
including an infinite loop in `EditorData::get_script_icon`'s base-script inheritance walk
(see [#143](https://github.com/kingg22/kogot/issues/143)), which a non-Inspector test could never
have caught.

## Usage

1. Copy this folder into the target project's `addons/` directory (e.g.
   `mi-juego-prueba/addons/inspect-object-repro/`).
2. Edit `plugin.gd`'s hardcoded script path (`res://kotlin_native_game/.../SpriteBench.kt`) to
   point at whatever script you want to reproduce a crash with.
3. Enable it in that project's `project.godot`:
   ```ini
   [editor_plugins]
   enabled=PackedStringArray("res://addons/inspect-object-repro/plugin.cfg")
   ```
4. Run headlessly:
   ```bash
   godot --headless --editor --path <project>
   ```
   Prints `=== EDITOR INSPECT TEST END: NO CRASH ===` and calls `get_tree().quit()` if nothing
   crashed. A crash instead produces Godot's usual backtrace/crash-report output.
5. Remove both the `addons/` copy and the `project.godot` `[editor_plugins]` section afterward —
   this is a debugging aid, not something a project should ship or keep enabled permanently. It
   was deliberately kept **out of** `mi-juego-prueba/` (the repo's own sample project) for the
   same reason.

For interactive debugging (real stack traces, breakpoints), attach `lldb` to a locally-built Godot
*dev* binary (`scons platform=<platform> target=editor dev_build=yes`) instead of a release
download — release builds lack the `get-task-allow` entitlement `lldb` needs on macOS.
