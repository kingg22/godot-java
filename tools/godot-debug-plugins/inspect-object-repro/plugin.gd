@tool
extends EditorPlugin

func _enter_tree():
	call_deferred("_run_test")

func _run_test():
	print("=== EDITOR INSPECT TEST START ===")
	var sb_script = load("res://kotlin_native_game/src/nativeMain/kotlin/SpriteBench.kt")
	print("loaded: ", sb_script)
	var node = Node2D.new()
	node.set_script(sb_script)
	print("script attached, calling EditorInterface.inspect_object()...")
	EditorInterface.inspect_object(node)
	print("inspect_object did not crash, waiting a frame...")
	await get_tree().process_frame
	await get_tree().process_frame
	print("frames processed, still alive")
	node.free()
	print("=== EDITOR INSPECT TEST END: NO CRASH ===")
	get_tree().quit()
