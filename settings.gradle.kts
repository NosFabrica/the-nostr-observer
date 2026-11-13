// Named, not left to the default: a machine whose init script adds a mirror to the
// plugin repositories would otherwise lose the plugin portal, and spotless with it.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "the-nostr-observer"

include(":generator")
include(":service")

dependencyResolutionManagement {
    repositories {
        // Quartz is a Kotlin Multiplatform library with Android in its graph:
        // it pulls androidx.sqlite, which is published to Google's Maven and
        // nowhere else. Without this the resolution failure names the missing
        // AndroidX artifact and not the reason, which reads like a broken pin.
        // JitPack's groups are asked of JitPack alone: Central answers a lookup
        // for one with a 429 as often as a 404, and a 429 fails the build.
        google { content { excludeGroupByRegex("com\\.github\\..*") } }
        mavenCentral { content { excludeGroupByRegex("com\\.github\\..*") } }
        // Quartz ships from JitPack. See gradle/libs.versions.toml for why the
        // pin is a commit hash and why every module force()s it.
        maven("https://jitpack.io")
    }
}

// `-PquartzDir=../quartz` builds Quartz from a local checkout instead of the JitPack pin: for
// working on both at once, or while JitPack hasn't built the pinned commit yet. Every module
// that asks for the pinned artifact gets the checkout's `:quartz` project instead.
val quartzDir = providers.gradleProperty("quartzDir").orNull
if (quartzDir != null) {
    includeBuild(quartzDir) {
        dependencySubstitution {
            substitute(module("com.github.vitorpamplona.quartz:quartz")).using(project(":quartz"))
        }
    }
}
