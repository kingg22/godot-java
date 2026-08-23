package io.github.kingg22.kogot.gradle

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.gradle.api.Action
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.testfixtures.ProjectBuilder
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinDependencyHandler
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Mock-based unit tests for [KogotPlugin.wireDependencies]. This isolates its branch logic (which
 * KSP configuration a native target's processor dependency lands in, and the early-return when KSP
 * auto-apply is off) from real Kotlin/Native target construction, which would otherwise need a
 * downloaded Konan toolchain just to obtain a [KotlinNativeTarget] instance - see [KogotPluginTest]'s
 * class doc for why that's out of scope for a fast unit test.
 *
 * [org.gradle.api.Project], [KogotExtension] and every [org.gradle.api.artifacts.Configuration]
 * involved are real Gradle objects built with [ProjectBuilder] - configurations are simply created
 * (or not) per scenario, and dependency wiring is asserted on the real [org.gradle.api.artifacts.DependencySet].
 * Only the Kotlin Multiplatform DSL types that would otherwise require a real KMP target
 * ([KotlinMultiplatformExtension], [KotlinSourceSet], [KotlinDependencyHandler], [KotlinNativeTarget])
 * are mocked with MockK.
 */
class KogotPluginWireDependenciesTest {
    private val project = ProjectBuilder.builder().build()
    private val plugin = project.objects.newInstance(KogotPlugin::class.java)
    private lateinit var extension: KogotExtension
    private lateinit var kotlin: KotlinMultiplatformExtension
    private lateinit var sourceSets: NamedDomainObjectContainer<KotlinSourceSet>
    private lateinit var commonMain: KotlinSourceSet
    private lateinit var dependencyHandler: KotlinDependencyHandler

    @BeforeEach
    fun setUp() {
        extension = project.extensions.create(EXTENSION_NAME, KogotExtension::class.java, project)
        kotlin = mockk()
        sourceSets = mockk()
        commonMain = mockk()
        dependencyHandler = mockk(relaxed = true)

        every { kotlin.sourceSets } returns sourceSets
        every {
            sourceSets.getByName(KogotConventions.COMMON_MAIN_SOURCE_SET, any<Action<KotlinSourceSet>>())
        } answers {
            secondArg<Action<KotlinSourceSet>>().execute(commonMain)
            commonMain
        }
        every { commonMain.dependencies(any<KotlinDependencyHandler.() -> Unit>()) } answers {
            firstArg<KotlinDependencyHandler.() -> Unit>().invoke(dependencyHandler)
        }
    }

    private fun nativeTarget(targetName: String): KotlinNativeTarget {
        val target = mockk<KotlinNativeTarget>()
        every { target.name } returns targetName
        return target
    }

    @Test
    fun `always wires the commonMain annotations dependency, even with no native targets`() {
        plugin.wireDependencies(project, extension, kotlin, emptyList())

        verify { dependencyHandler.implementation("io.github.kingg22.kogot:kogot-annotations:0.1.0") }
    }

    @Test
    fun `uses the overridden kogotGroup and kogotVersion for the coordinate`() {
        extension.kogotGroup.set("com.example")
        extension.kogotVersion.set("9.9.9")

        plugin.wireDependencies(project, extension, kotlin, emptyList())

        verify { dependencyHandler.implementation("com.example:kogot-annotations:9.9.9") }
    }

    @Test
    fun `autoApplyKsp false skips wiring the processor dependency entirely`() {
        extension.autoApplyKsp.set(false)
        val target = nativeTarget("linuxX64")

        plugin.wireDependencies(project, extension, kotlin, listOf(target))

        assertNull(project.configurations.findByName("kspLinuxX64"))
        assertNull(project.configurations.findByName(KogotConventions.KSP_COMMON_MAIN_METADATA_CONFIGURATION))
    }

    @Test
    fun `adds the processor dependency to the target-specific ksp configuration when present`() {
        val kspConfig = project.configurations.create("kspLinuxX64")
        val target = nativeTarget("linuxX64")

        plugin.wireDependencies(project, extension, kotlin, listOf(target))

        val dependency = kspConfig.dependencies.single()
        assertEquals("io.github.kingg22.kogot", dependency.group)
        assertEquals("kogot-processor", dependency.name)
        assertEquals("0.1.0", dependency.version)
    }

    @Test
    fun `falls back to kspCommonMainMetadata when the target-specific configuration is absent`() {
        val metadataConfig = project.configurations.create(KogotConventions.KSP_COMMON_MAIN_METADATA_CONFIGURATION)
        val target = nativeTarget("linuxX64")

        plugin.wireDependencies(project, extension, kotlin, listOf(target))

        val dependency = metadataConfig.dependencies.single()
        assertEquals("kogot-processor", dependency.name)
    }

    @Test
    fun `prefers the target-specific configuration over the shared metadata one when both exist`() {
        val kspConfig = project.configurations.create("kspLinuxX64")
        val metadataConfig = project.configurations.create(KogotConventions.KSP_COMMON_MAIN_METADATA_CONFIGURATION)
        val target = nativeTarget("linuxX64")

        plugin.wireDependencies(project, extension, kotlin, listOf(target))

        assertEquals(1, kspConfig.dependencies.size)
        assertTrue(metadataConfig.dependencies.isEmpty())
    }

    @Test
    fun `neither configuration present skips the processor dependency without throwing`() {
        val target = nativeTarget("linuxX64")

        plugin.wireDependencies(project, extension, kotlin, listOf(target))

        assertNull(project.configurations.findByName("kspLinuxX64"))
        assertNull(project.configurations.findByName(KogotConventions.KSP_COMMON_MAIN_METADATA_CONFIGURATION))
    }

    @Test
    fun `wires every native target independently`() {
        val linux = project.configurations.create("kspLinuxX64")
        val macos = project.configurations.create("kspMacosArm64")

        plugin.wireDependencies(
            project,
            extension,
            kotlin,
            listOf(nativeTarget("linuxX64"), nativeTarget("macosArm64")),
        )

        assertEquals(1, linux.dependencies.size)
        assertEquals(1, macos.dependencies.size)
    }
}
