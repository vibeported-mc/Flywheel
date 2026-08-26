plugins {
    idea
    java
    `maven-publish`
    id("net.neoforged.moddev") version "2.0.144"
    id("flywheel.subproject")
}

val javaVersion: String by lazy { property("java_version") as String }
val artifactMcVersion: String by lazy { property("artifact_minecraft_version") as String }

val buildNumber: String? = if (System.getenv("RELEASE")?.equals("false", true) != false) {
    System.getenv("BUILD_NUMBER")
} else {
    null
}

group = property("vanillin_group") as String
version = "${property("vanillin_version")}" + (buildNumber?.let { "-$it" } ?: "")

base.archivesName = "vanillin-neoforge-$artifactMcVersion"

val main = sourceSets.getByName("main")

// Vanillin's platform-agnostic half lives alongside Flywheel's, in common/src/vanillin.
main.java.srcDir(rootProject.file("common/src/vanillin/java"))
main.resources.srcDir(rootProject.file("common/src/vanillin/resources"))

java {
    toolchain.languageVersion = JavaLanguageVersion.of(javaVersion)
    withSourcesJar()
}

neoForge {
    version = property("neoforge_version") as String

    mods.register(property("vanillin_id") as String) {
        sourceSet(main)
    }

    runs {
        create("client") {
            client()
        }
        configureEach {
            systemProperty("forge.logging.markers", "")
            systemProperty("forge.logging.console.level", "debug")
            jvmArgument("-XX:+IgnoreUnrecognizedVMOptions")
        }
    }
}

// Vanillin builds against the whole Flywheel jar rather than a single source set, because Flywheel
// is split across api/lib/backend/impl.
val flywheelJar = project(":neoforge").tasks.named<Jar>("jar")

repositories {
    mavenCentral()
    maven("https://api.modrinth.com/maven") {
        name = "Modrinth"
        content {
            includeGroup("maven.modrinth")
        }
    }
}

dependencies {
    compileOnly(files(flywheelJar))
    runtimeOnly(files(flywheelJar))

    compileOnly("org.jspecify:jspecify:1.0.0")

    compileOnly("maven.modrinth:sodium:${property("sodium_version")}-neoforge")
    compileOnly("maven.modrinth:iris:${property("iris_version")}-neoforge")
}

val replaceProperties = listOf(
    "mod_license",
    "mod_sources",
    "mod_issues",
    "mod_homepage",
    "flywheel_id",
    "vanillin_id",
    "vanillin_name",
    "vanillin_description",
    "flywheel_maven_version_range",
    "minecraft_maven_version_range",
    "neoforge_version_range",
).associateWith { property(it) as String }
    .plus("vanillin_version" to "${property("vanillin_version")}${buildNumber?.let { "-$it" } ?: ""}")

tasks.withType<ProcessResources>().configureEach {
    inputs.properties(replaceProperties)

    filesMatching(listOf("pack.mcmeta", "META-INF/neoforge.mods.toml")) {
        expand(replaceProperties)
    }
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from(rootProject.file("LICENSE.md")) {
        into("META-INF")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = javaVersion.toInt()
    options.compilerArgs.add("-Xdiags:verbose")
}

defaultPackageInfos {
    sources(main)
}

publishing {
    publications {
        register<MavenPublication>("mavenJava") {
            artifactId = "vanillin-neoforge-$artifactMcVersion"
            from(components["java"])
        }
    }
}
