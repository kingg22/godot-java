package io.github.kingg22.kogot.gradle.settings

/**
 * Settings-level DSL surface (`kogot { export(...) }` in `settings.gradle.kts`), registered by
 * [KogotSettingsPlugin]. Collects [KogotExportSpec]s to apply once project objects exist.
 */
abstract class KogotSettingsExtension {
    internal val exports = mutableListOf<KogotExportSpec>()

    /** Registers an auto-managed export companion project for the game module at [modulePath]. */
    fun export(modulePath: String, configure: KogotExportSpec.() -> Unit = {}) {
        exports += KogotExportSpec(modulePath).apply(configure)
    }
}
