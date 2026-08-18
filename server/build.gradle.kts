plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.nosfabrica.observer.press.MainKt")
}

// Same rule as :generator. JitPack versions are commit hashes and Gradle keeps
// the lexicographically higher one, which for hashes is an arbitrary choice
// made silently.
configurations.all {
    resolutionStrategy {
        force(libs.quartz)
    }
}

dependencies {
    // The pipeline itself. The server decides who may ask and what happens to
    // the page; what an edition IS lives in one place, next door.
    implementation(project(":generator"))

    implementation(libs.quartz)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.anthropic)
    // Masthead continuity reads the model's raw output; jsoup is how.
    implementation(libs.jsoup)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.serialization.json)
    implementation(libs.sqlite.jdbc)
    runtimeOnly(libs.logback)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}

// The publish path against the real network, started by a person on purpose.
// See LiveRun.kt: it writes to somebody else's relay and somebody else's media
// server, so it is a main() in the test source set rather than a @Test -- out
// of the shipped jar, and out of `./gradlew test`.
tasks.register<JavaExec>("liveRun") {
    group = "verification"
    description = "Publish one already-generated page end to end, as a throwaway key."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.nosfabrica.observer.press.LiveRunKt")
    args = ((project.findProperty("liveArgs") as String?) ?: "").split(" ").filter { it.isNotBlank() }
}

// Ask one media server whether it will take an edition. See BlossomProbe.kt.
tasks.register<JavaExec>("blossomProbe") {
    group = "verification"
    description = "Ask a Blossom server which Content-Type it will accept for a page."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.nosfabrica.observer.press.BlossomProbeKt")
    args = ((project.findProperty("liveArgs") as String?) ?: "").split(" ").filter { it.isNotBlank() }
}
