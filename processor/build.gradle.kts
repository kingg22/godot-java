plugins {
    alias(libs.plugins.kotlin.library.conventions)
    alias(libs.plugins.kotlin.styles.conventions)
}

dependencies {
    implementation(libs.ksp.api)
    implementation(libs.kotlinpoet.ksp)
}
