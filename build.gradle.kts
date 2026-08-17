plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.diffplug.spotless)
}

// Read out of the catalog HERE, in root-project scope. Inside `allprojects` the
// `libs` accessor is not in scope and the failure says only that an extension
// named 'libs' does not exist, which does not point anywhere near the cause.
val appVersion = libs.versions.app.get()
val ktlintVersion = libs.versions.ktlint.get()

allprojects {
    group = "com.nosfabrica.observer"
    version = appVersion
}

spotless {
    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**")
        ktlint(ktlintVersion)
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude("**/build/**")
        ktlint(ktlintVersion)
    }
}
