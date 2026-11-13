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
        force(libs.assistant.core)
    }
}

dependencies {
    implementation(project(":generator"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.assistant.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    // TestPostgres (a throwaway cluster, or CI's service), BrainstormSchema, capturingLogs.
    testImplementation(testFixtures(libs.assistant.core))
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
