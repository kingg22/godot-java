package io.github.kingg22.kogot.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinMultiplatformPluginWrapper
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

abstract class KogotPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create(EXTENSION_NAME, KogotExtension::class.java, project)

        project.plugins.withType(KotlinMultiplatformPluginWrapper::class.java) {
            // Everything below reads user-configured `kogot { ... }` values, which are only final
            // once the whole build script has run — so all of it must live in a single afterEvaluate.
            project.afterEvaluate {
                val kotlin = project.extensions.getByType(KotlinMultiplatformExtension::class.java)
                val nativeTargets = kotlin.targets.withType(KotlinNativeTarget::class.java).toList()

                if (extension.autoApplyKsp.get() && !project.plugins.hasPlugin(KogotConventions.KSP_PLUGIN_ID)) {
                    project.plugins.apply(KogotConventions.KSP_PLUGIN_ID)
                }

                if (extension.autoAddDependencies.get()) {
                    wireDependencies(project, extension, kotlin, nativeTargets)
                }

                nativeTargets.forEach { target ->
                    if (extension.generateEntryPoint.get()) {
                        registerEntryPointTask(project, extension, target)
                    }
                }
                registerCopyTasks(project, extension, nativeTargets)

                if (extension.generateGdextensionFile.get()) {
                    registerGdextensionTask(project, extension, nativeTargets)
                }

                registerGodotCliTasks(project, extension)
            }
        }
    }

    // internal (not private): lets the "test" source set unit/mock-test this in isolation, same
    // convention as KogotTaskRegistration.kt's internal top-level functions.
    internal fun wireDependencies(
        project: Project,
        extension: KogotExtension,
        kotlin: KotlinMultiplatformExtension,
        nativeTargets: List<KotlinNativeTarget>,
    ) {
        val kogotCoordinate = { artifact: String ->
            "${extension.kogotGroup.get()}:$artifact:${extension.kogotVersion.get()}"
        }

        kotlin.sourceSets.getByName(KogotConventions.COMMON_MAIN_SOURCE_SET) {
            it.dependencies {
                implementation(kogotCoordinate(KogotConventions.ARTIFACT_ANNOTATIONS))
            }
        }

        if (!extension.autoApplyKsp.get()) return

        nativeTargets.forEach { target ->
            val kspConfigurationName = KogotConventions.kspConfigurationName(target.name)
            val configName = when {
                project.configurations.findByName(kspConfigurationName) != null -> kspConfigurationName

                project.configurations.findByName(
                    KogotConventions.KSP_COMMON_MAIN_METADATA_CONFIGURATION,
                ) != null -> KogotConventions.KSP_COMMON_MAIN_METADATA_CONFIGURATION

                else -> null
            }

            if (configName != null) {
                project.dependencies.add(configName, kogotCoordinate(KogotConventions.ARTIFACT_PROCESSOR))
            } else {
                project.logger.warn(
                    "kogot: could not find a KSP configuration for target '${target.name}' " +
                        "($kspConfigurationName / ${KogotConventions.KSP_COMMON_MAIN_METADATA_CONFIGURATION}); " +
                        "processor dependency was not added.",
                )
            }
        }
    }
}
