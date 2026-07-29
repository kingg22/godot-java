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

    // Applied on the consumer's behalf, so must be on the plugin's own classpath.
    // NOTE: this is a *project* plugin module only (see :settings-plugin for the Settings-level
    // plugin). Do not add a Settings-plugin registration to this module: bundling kotlin-gradle-plugin
    // as `implementation` here is required for KogotPlugin to work, but the same dependency on a
    // Settings plugin's classpath poisons `plugins { id("org.jetbrains.kotlin.jvm") version X }`
    // resolution build-wide (confirmed empirically: Gradle reports the plugin "already on the
    // classpath with an unknown version" even when the versions are identical).
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.ksp.gradle.plugin)

    // Used to generate the @CName entry point instead of hand-built strings.
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
        create(property("ID").toString()) {
            id = property("ID").toString()
            implementationClass = property("IMPLEMENTATION_CLASS").toString()
            version = property("VERSION").toString()
            description = property("DESCRIPTION").toString()
            displayName = property("DISPLAY_NAME").toString()
            tags.set(listOf("godot", "gdextension", "kotlin-native", "kogot"))
        }
    }
}

tasks.register("setupPluginUploadFromEnvironment") {
    doLast {
        val key = System.getenv("GRADLE_PUBLISH_KEY")
        val secret = System.getenv("GRADLE_PUBLISH_SECRET")

        if (key == null || secret == null) {
            throw GradleException(
                "GRADLE_PUBLISH_KEY and/or GRADLE_PUBLISH_SECRET are not defined environment variables",
            )
        }

        System.setProperty("gradle.publish.key", key)
        System.setProperty("gradle.publish.secret", secret)
    }
}
