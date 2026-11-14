plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.nosfabrica.observer.service.MainKt")
    applicationName = "observer-service"
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
    implementation(libs.postgresql)

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
