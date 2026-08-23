import org.gradle.plugin.devel.tasks.PluginUnderTestMetadata
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-gradle-plugin`
    alias(libs.plugins.pluginPublish)
}

group = property("GROUP").toString()
version = property("VERSION").toString()

dependencies {
    implementation(kotlin("stdlib"))
    implementation(gradleApi())

    implementation(libs.kotlinpoet)
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

// See :plugin's build.gradle.kts for why these two suites exist and what each is for.
@Suppress("UnstableApiUsage")
testing {
    suites {
        getByName<JvmTestSuite>("test") {
            useJUnitJupiter(libs.versions.junit5.get())
            dependencies {
                implementation(libs.mockk)
            }
        }

        register<JvmTestSuite>("functionalTest") {
            useJUnitJupiter(libs.versions.junit5.get())
            dependencies {
                implementation(project())
                implementation(gradleTestKit())
            }

            targets {
                all {
                    testTask.configure {
                        shouldRunAfter(tasks.named("test"))
                    }
                }
            }
        }
    }
}

// The generated export companion build.gradle.kts applies io.github.kingg22.kogot (from :plugin),
// not just io.github.kingg22.kogot.settings - gradlePlugin.testSourceSets only injects *this*
// module's own runtime classpath, so a functional test that actually configures that companion
// project (rather than just asserting on its generated text) needs a combined classpath. Built with
// a second PluginUnderTestMetadata task instance (see https://docs.gradle.org/current/userguide/test_kit.html),
// the same task type gradlePlugin.testSourceSets wires up automatically, but pointed at a classpath
// that also includes :plugin.
val functionalTestExtraPluginClasspath = configurations.create("functionalTestExtraPluginClasspath")

dependencies {
    functionalTestExtraPluginClasspath(project(":plugin"))
}

val combinedFunctionalTestPluginClasspath = tasks.register<PluginUnderTestMetadata>(
    "combinedFunctionalTestPluginClasspath",
) {
    pluginClasspath.from(sourceSets.main.get().runtimeClasspath, functionalTestExtraPluginClasspath)
    outputDirectory.set(layout.buildDirectory.dir("combinedFunctionalTestPluginClasspath"))
}

tasks.named<Test>("functionalTest") {
    dependsOn(combinedFunctionalTestPluginClasspath)
    systemProperty(
        "combinedPluginClasspathDir",
        combinedFunctionalTestPluginClasspath.get().outputDirectory.get().asFile.absolutePath,
    )
}

gradlePlugin {
    website.set(property("WEBSITE").toString())
    vcsUrl.set(property("VCS_URL").toString())
    testSourceSets.add(sourceSets["functionalTest"])

    plugins {
        create("${property("ID")}.settings") {
            id = "${property("ID")}.settings"
            implementationClass = "io.github.kingg22.kogot.gradle.settings.KogotSettingsPlugin"
            version = property("VERSION").toString()
            description = "Settings-level plugin (issue #25): auto-manages export companion " +
                "projects for kogot game modules, so a game module never needs its own " +
                "binaries.sharedLib() and stays fast to assemble"
            displayName = "Kogot Gradle Settings Plugin"
            tags.set(listOf("godot", "gdextension", "kotlin-native", "kogot"))
        }
    }
}

tasks.named("check") {
    dependsOn(testing.suites.named("functionalTest"))
}
