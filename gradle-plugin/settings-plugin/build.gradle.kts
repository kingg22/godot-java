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

    testImplementation(libs.junit)
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

gradlePlugin {
    website.set(property("WEBSITE").toString())
    vcsUrl.set(property("VCS_URL").toString())

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
