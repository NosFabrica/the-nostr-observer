plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-library`
    application
}

kotlin {
    jvmToolchain(21)
}

// A library first: the runner prints with it. The `main` is a manual, local dry run.
application {
    mainClass.set("com.nosfabrica.observer.MainKt")
}

// JitPack versions are commit hashes, and hashes carry no order. Gradle keeps the
// lexicographically higher one when two parts of the graph disagree, which for hashes means an
// arbitrary one chosen silently. force() is the only thing that makes the pin mean what it says.
configurations.all {
    resolutionStrategy {
        force(libs.quartz)
    }
}

dependencies {
    api(libs.quartz)
    api(libs.crypto)
    api(libs.kotlinx.coroutines.core)
    api(libs.okhttp)
    implementation(libs.okhttp.coroutines)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.jsoup)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
