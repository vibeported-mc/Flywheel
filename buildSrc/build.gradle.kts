plugins {
    `kotlin-dsl`
    idea
}

repositories {
    gradlePluginPortal()
    mavenCentral()
    maven("https://maven.neoforged.net/releases/") {
        name = "NeoForged"
    }
}

idea.module {
    isDownloadJavadoc = true
    isDownloadSources = true
}

gradlePlugin {
    plugins {
        create("subprojectPlugin") {
            id = "flywheel.subproject"
            implementationClass = "dev.engine_room.gradle.subproject.SubprojectPlugin"
        }
    }
}
