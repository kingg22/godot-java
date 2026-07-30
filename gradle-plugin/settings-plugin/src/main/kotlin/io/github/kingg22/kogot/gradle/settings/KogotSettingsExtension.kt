package io.github.kingg22.kogot.gradle.settings

import org.gradle.api.Action

/**
 * Settings-level DSL surface (`kogot { export(...) }` in `settings.gradle.kts`), registered by
 * [KogotSettingsPlugin]. Collects [KogotExportSpec]s to apply once project objects exist.
 */
abstract class KogotSettingsExtension {
    internal val exports = mutableListOf<KogotExportSpec>()

    /** Registers an auto-managed export companion project for the game module at [modulePath]. */
    @JvmOverloads
    fun export(modulePath: String, configure: Action<KogotExportSpec> = {}) {
        exports += KogotExportSpec(modulePath).apply { configure.execute(this) }
    }
}
