package io.github.kingg22.buildlogic.kotlin

import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.dsl.HasConfigurableKotlinCompilerOptions
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

fun <T : HasConfigurableKotlinCompilerOptions<*>> T.enableContextParameters() {
    compilerOptions.languageVersion.map { kotlinVersion ->
        kotlinVersion.version.toDoubleOrNull()?.let {
            if (it >= 2.4) {
                compilerOptions.freeCompilerArgs.add("-Xcontext-parameters")
            }
        }
    }
}

internal fun <T : HasConfigurableKotlinCompilerOptions<*>> T.commonConfiguration(kotlinVersion: Provider<String>) {
    compilerOptions {
        languageVersion.set(kotlinVersion.map(KotlinVersion::fromVersion))
        apiVersion.set(languageVersion)
        optIn.add("kotlin.contracts.ExperimentalContracts")
        freeCompilerArgs.addAll("-Xcontext-sensitive-resolution", "-Xreturn-value-checker=full")
        allWarningsAsErrors.set(true)
        extraWarnings.set(true)
    }
}

/**
 * The Kotlin Compiler adds intrinsic assertions which are only relevant
 * when the code is consumed by Java users. Therefore, we can turn this off
 * when code is being consumed by Kotlin users.
 */
fun KotlinJvmProjectExtension.disableJvmAssertions() {
    compilerOptions.freeCompilerArgs.addAll(
        "-Xno-param-assertions",
        "-Xno-call-assertions",
        "-Xno-receiver-assertions",
        "-Xwhen-expressions=indy",
    )
}
