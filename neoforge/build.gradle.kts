plugins {
    idea
    java
    `maven-publish`
    id("net.neoforged.moddev") version "2.0.144"
    id("flywheel.subproject")
}

val javaVersion: String by lazy { property("java_version") as String }
val artifactMcVersion: String by lazy { property("artifact_minecraft_version") as String }

// CI stamps a build number onto dev builds; release builds publish the bare version.
val buildNumber: String? = if (System.getenv("RELEASE")?.equals("false", true) != false) {
    System.getenv("BUILD_NUMBER")
} else {
    null
}

group = property("flywheel_group") as String
version = "${property("flywheel_version")}" + (buildNumber?.let { "-$it" } ?: "")

base.archivesName = "flywheel-neoforge-$artifactMcVersion"

// Flywheel is split into api/lib/backend/impl(main). Each layer exists in both the platform-agnostic
// `common` directory and this platform directory; previously the common half was compiled by a
// separate Gradle project and consumed as classes. Since we only target NeoForge now, we simply
// compile both halves together, which keeps the source layout identical while dropping the
// cross-platform remapping machinery that has no Minecraft 26.2 equivalent.
val api = sourceSets.create("api")
val lib = sourceSets.create("lib")
val backend = sourceSets.create("backend")
val main = sourceSets.getByName("main")

val layers = listOf(api, lib, backend, main)

layers.forEach { sourceSet ->
    sourceSet.java.srcDir(rootProject.file("common/src/${sourceSet.name}/java"))
    sourceSet.resources.srcDir(rootProject.file("common/src/${sourceSet.name}/resources"))
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(javaVersion)
    withSourcesJar()
}

neoForge {
    version = property("neoforge_version") as String

    accessTransformers {
        publish(file("src/main/resources/META-INF/accesstransformer.cfg"))
    }

    layers.forEach { addModdingDependenciesTo(it) }

    mods.register(property("flywheel_id") as String) {
        layers.forEach { sourceSet(it) }
    }

    runs {
        create("client") {
            client()
        }
        create("server") {
            server()
            gameDirectory = project.file("run/server")
        }
        configureEach {
            systemProperty("forge.logging.markers", "")
            systemProperty("forge.logging.console.level", "debug")
            systemProperty("mixin.debug.export", "true")
            jvmArgument("-XX:+IgnoreUnrecognizedVMOptions")
            jvmArgument("-XX:+AllowEnhancedClassRedefinition")
        }
    }
}

// Each layer compiles and runs against the layers below it.
fun SourceSet.dependsOnLayers(vararg below: SourceSet) {
    dependencies {
        below.forEach {
            add(compileOnlyConfigurationName, it.output)
            add(runtimeOnlyConfigurationName, it.output)
        }
    }
}

lib.dependsOnLayers(api)
backend.dependsOnLayers(api, lib)
main.dependsOnLayers(api, lib, backend)

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
    // Minecraft 26.2 removed net.minecraft.MethodsReturnNonnullByDefault and friends in favor of
    // JSpecify, so that is what the generated package-info files (and our own @Nullable) use.
    layers.forEach { add(it.compileOnlyConfigurationName, "org.jspecify:jspecify:1.0.0") }

    // Flywheel's compat layers talk to these directly, but never require them at runtime.
    add(main.compileOnlyConfigurationName, "maven.modrinth:sodium:${property("sodium_version")}-neoforge")
    add(main.compileOnlyConfigurationName, "maven.modrinth:iris:${property("iris_version")}-neoforge")
}

val replaceProperties = listOf(
    "mod_license",
    "mod_sources",
    "mod_issues",
    "mod_homepage",
    "flywheel_id",
    "flywheel_name",
    "flywheel_description",
    "minecraft_maven_version_range",
    "neoforge_version_range",
).associateWith { property(it) as String }
    .plus("flywheel_version" to "${property("flywheel_version")}${buildNumber?.let { "-$it" } ?: ""}")

tasks.withType<ProcessResources>().configureEach {
    inputs.properties(replaceProperties)

    filesMatching(listOf("pack.mcmeta", "META-INF/neoforge.mods.toml")) {
        expand(replaceProperties)
    }
}

// The published mod jar carries every layer.
tasks.named<Jar>("jar") {
    layers.filter { it != main }.forEach { from(it.output) }
}

tasks.named<Jar>("sourcesJar") {
    layers.filter { it != main }.forEach { from(it.allSource) }
}

// Consumers compile against api + lib only.
val apiJar = tasks.register<Jar>("apiJar") {
    archiveBaseName = "flywheel-neoforge-api-$artifactMcVersion"
    from(api.output, lib.output)
}

val apiSourcesJar = tasks.register<Jar>("apiSourcesJar") {
    archiveBaseName = "flywheel-neoforge-api-$artifactMcVersion"
    archiveClassifier = "sources"
    from(api.allSource, lib.allSource)
}

tasks.named("assemble") {
    dependsOn(apiJar, apiSourcesJar)
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
    sources(api, lib, backend, main)
}

publishing {
    publications {
        register<MavenPublication>("mavenJava") {
            artifactId = "flywheel-neoforge-$artifactMcVersion"
            from(components["java"])
        }
        register<MavenPublication>("mavenApi") {
            artifactId = "flywheel-neoforge-api-$artifactMcVersion"
            artifact(apiJar)
            artifact(apiSourcesJar)
        }
    }
}
