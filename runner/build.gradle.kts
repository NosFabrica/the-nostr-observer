plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.nosfabrica.observer.runner.MainKt")
    applicationName = "observer-runner"
}

// Same rule as :generator: JitPack versions are commit hashes, which carry no order.
configurations.all {
    resolutionStrategy {
        force(libs.quartz)
    }
}

dependencies {
    implementation(project(":generator"))
    implementation(libs.kotlinx.serialization.json)

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
