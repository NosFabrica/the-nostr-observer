rootProject.name = "the-nostr-observer"

include(":generator")

dependencyResolutionManagement {
    repositories {
        // Quartz is a Kotlin Multiplatform library with Android in its graph:
        // it pulls androidx.sqlite, which is published to Google's Maven and
        // nowhere else. Without this the resolution failure names the missing
        // AndroidX artifact and not the reason, which reads like a broken pin.
        google()
        mavenCentral()
        // Quartz ships from JitPack. See gradle/libs.versions.toml for why the
        // pin is a commit hash and why every module force()s it.
        maven("https://jitpack.io")
    }
}
