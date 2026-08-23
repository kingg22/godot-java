package io.github.kingg22.godot.internal.script

import io.github.kingg22.godot.api.core.ScriptLanguageExtensionVirtualCalls
import io.github.kingg22.godot.api.core.refcounted.ResourceFormatLoaderVirtualCalls
import io.github.kingg22.godot.api.core.refcounted.ResourceFormatSaverVirtualCalls
import io.github.kingg22.godot.api.core.refcounted.ScriptExtensionVirtualCalls
import io.github.kingg22.godot.api.singleton.Engine
import io.github.kingg22.godot.api.singleton.ResourceLoader
import io.github.kingg22.godot.api.singleton.ResourceSaver
import io.github.kingg22.godot.internal.binding.InternalBinding
import io.github.kingg22.godot.internal.binding.createInstanceFunc
import io.github.kingg22.godot.internal.binding.registerClass
import io.github.kingg22.godot.internal.binding.resolveVirtualCall
import io.github.kingg22.godot.internal.ffi.*
import kotlinx.cinterop.staticCFunction

/**
 * Registers Kotlin as a Godot scripting language: [KotlinScriptLanguage], [KotlinScript],
 * [KotlinResourceFormatLoader] and [KotlinResourceFormatSaver] as ClassDB extension classes, then
 * instantiates and hands one singleton of each to the engine (issue #42).
 *
 * Called once from the KSP-generated `onInitScene()` of every project that applies the Kogot processor
 * (see `GodotBindingGenerator.generateCallbacksFile`), alongside feeding that project's own
 * `ScriptFileRegistry` into [KotlinScriptRegistry].
 */
@InternalBinding
public object KotlinScriptRegistration {
    /** The single registered [KotlinScriptLanguage] instance, returned by [KotlinScript._getLanguage]. */
    public lateinit var language: KotlinScriptLanguage
        private set

    private var registered = false

    public fun registerKotlinScriptLanguageSupport() {
        if (registered) return
        registered = true

        registerClass<KotlinScriptLanguage>(
            "KotlinScriptLanguage",
            "ScriptLanguageExtension",
            staticCFunction { _, notifyPostInitialize ->
                createInstanceFunc(
                    "ScriptLanguageExtension",
                    "KotlinScriptLanguage",
                    notifyPostInitialize == GDExtensionBool.TRUE,
                    ::KotlinScriptLanguage,
                )
            },
            staticCFunction { _, funcNamePtr, _ ->
                resolveVirtualCall(funcNamePtr) { funcName ->
                    when (funcName) {
                        "_get_name" as Any -> ScriptLanguageExtensionVirtualCalls.getName
                        "_init" as Any -> ScriptLanguageExtensionVirtualCalls.init
                        "_get_type" as Any -> ScriptLanguageExtensionVirtualCalls.getType
                        "_get_extension" as Any -> ScriptLanguageExtensionVirtualCalls.getExtension
                        "_finish" as Any -> ScriptLanguageExtensionVirtualCalls.finish
                        "_get_reserved_words" as Any -> ScriptLanguageExtensionVirtualCalls.getReservedWords
                        "_get_comment_delimiters" as Any -> ScriptLanguageExtensionVirtualCalls.getCommentDelimiters
                        "_get_string_delimiters" as Any -> ScriptLanguageExtensionVirtualCalls.getStringDelimiters
                        "_has_named_classes" as Any -> ScriptLanguageExtensionVirtualCalls.hasNamedClasses
                        "_validate" as Any -> ScriptLanguageExtensionVirtualCalls.validate
                        "_get_recognized_extensions" as Any -> ScriptLanguageExtensionVirtualCalls.getRecognizedExtensions
                        "_supports_builtin_mode" as Any -> ScriptLanguageExtensionVirtualCalls.supportsBuiltinMode
                        "_create_script" as Any -> ScriptLanguageExtensionVirtualCalls.createScript
                        else -> null
                    }
                }
            },
        )

        registerClass<KotlinScript>(
            "KotlinScript",
            "ScriptExtension",
            staticCFunction { _, notifyPostInitialize ->
                createInstanceFunc(
                    "ScriptExtension",
                    "KotlinScript",
                    notifyPostInitialize == GDExtensionBool.TRUE,
                    ::KotlinScript,
                )
            },
            staticCFunction { _, funcNamePtr, _ ->
                resolveVirtualCall(funcNamePtr) { funcName ->
                    when (funcName) {
                        "_can_instantiate" as Any -> ScriptExtensionVirtualCalls.canInstantiate
                        "_get_source_code" as Any -> ScriptExtensionVirtualCalls.getSourceCode
                        "_set_source_code" as Any -> ScriptExtensionVirtualCalls.setSourceCode
                        "_has_source_code" as Any -> ScriptExtensionVirtualCalls.hasSourceCode
                        "_is_valid" as Any -> ScriptExtensionVirtualCalls.isValid
                        "_is_tool" as Any -> ScriptExtensionVirtualCalls.isTool
                        "_get_instance_base_type" as Any -> ScriptExtensionVirtualCalls.getInstanceBaseType
                        "_get_language" as Any -> ScriptExtensionVirtualCalls.getLanguage
                        "_reload" as Any -> ScriptExtensionVirtualCalls.reload
                        else -> null
                    }
                }
            },
        )

        registerClass<KotlinResourceFormatLoader>(
            "KotlinResourceFormatLoader",
            "ResourceFormatLoader",
            staticCFunction { _, notifyPostInitialize ->
                createInstanceFunc(
                    "ResourceFormatLoader",
                    "KotlinResourceFormatLoader",
                    notifyPostInitialize == GDExtensionBool.TRUE,
                    ::KotlinResourceFormatLoader,
                )
            },
            staticCFunction { _, funcNamePtr, _ ->
                resolveVirtualCall(funcNamePtr) { funcName ->
                    when (funcName) {
                        "_get_recognized_extensions" as Any -> ResourceFormatLoaderVirtualCalls.getRecognizedExtensions
                        "_handles_type" as Any -> ResourceFormatLoaderVirtualCalls.handlesType
                        "_get_resource_type" as Any -> ResourceFormatLoaderVirtualCalls.getResourceType
                        "_load" as Any -> ResourceFormatLoaderVirtualCalls.load
                        else -> null
                    }
                }
            },
        )

        registerClass<KotlinResourceFormatSaver>(
            "KotlinResourceFormatSaver",
            "ResourceFormatSaver",
            staticCFunction { _, notifyPostInitialize ->
                createInstanceFunc(
                    "ResourceFormatSaver",
                    "KotlinResourceFormatSaver",
                    notifyPostInitialize == GDExtensionBool.TRUE,
                    ::KotlinResourceFormatSaver,
                )
            },
            staticCFunction { _, funcNamePtr, _ ->
                resolveVirtualCall(funcNamePtr) { funcName ->
                    when (funcName) {
                        "_get_recognized_extensions" as Any -> ResourceFormatSaverVirtualCalls.getRecognizedExtensions
                        "_recognize" as Any -> ResourceFormatSaverVirtualCalls.recognize
                        "_save" as Any -> ResourceFormatSaverVirtualCalls.save
                        else -> null
                    }
                }
            },
        )

        val languagePtr = createInstanceFunc("ScriptLanguageExtension", "KotlinScriptLanguage", false, ::KotlinScriptLanguage)
            ?: error("Failed to create the KotlinScriptLanguage singleton")
        language = KotlinScriptLanguage(languagePtr)
        val _ = Engine.instance.registerScriptLanguage(language)

        val loaderPtr = createInstanceFunc("ResourceFormatLoader", "KotlinResourceFormatLoader", false, ::KotlinResourceFormatLoader)
            ?: error("Failed to create the KotlinResourceFormatLoader singleton")
        ResourceLoader.instance.addResourceFormatLoader(KotlinResourceFormatLoader(loaderPtr))

        val saverPtr = createInstanceFunc("ResourceFormatSaver", "KotlinResourceFormatSaver", false, ::KotlinResourceFormatSaver)
            ?: error("Failed to create the KotlinResourceFormatSaver singleton")
        ResourceSaver.instance.addResourceFormatSaver(KotlinResourceFormatSaver(saverPtr))
    }
}
