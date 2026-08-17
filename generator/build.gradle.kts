plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.nosfabrica.observer.MainKt")
}

// JitPack versions are commit hashes, and hashes carry no order. Gradle keeps
// the lexicographically higher one when two parts of the graph disagree, which
// for hashes means an arbitrary one chosen silently. force() is the only thing
// that makes the pin mean what it says. Same rule as vespa-relay's modules.
configurations.all {
    resolutionStrategy {
        force(libs.quartz)
    }
}

dependencies {
    implementation(libs.quartz)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.jsoup)
    implementation(libs.anthropic)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}
