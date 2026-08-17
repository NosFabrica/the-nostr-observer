rootProject.name = "the-nostr-observer"

include(":generator")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // Quartz ships from JitPack. See gradle/libs.versions.toml for why the
        // pin is a commit hash and why every module force()s it.
        maven("https://jitpack.io")
    }
}
