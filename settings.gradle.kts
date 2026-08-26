pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://maven.neoforged.net/releases/") {
            name = "NeoForged"
        }
        maven("https://maven.parchmentmc.org")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version("0.9.0")
}

rootProject.name = "Flywheel"

include("neoforge")
// Vanillin is not part of this port: Minecraft 26.2 replaced the item model pipeline
// (BakedModel + ItemOverrides -> ItemModel / ItemStackRenderState) and the minecart movement system,
// so its item and minecart visuals need reimplementing rather than porting. Sources are kept, and
// the mechanical relocations have already been applied to them.
// include("vanillinNeoForge")
