package dev.engine_room.gradle.nullability

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.SkipWhenEmpty
import org.gradle.api.tasks.TaskAction
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.writeText

// Adapted from https://github.com/FabricMC/fabric/blob/31787236d242247e0b6c4ae806b1cfaa7042a62c/gradle/package-info.gradle, which is licensed under Apache 2.0.
open class GeneratePackageInfosTask: DefaultTask() {
    @SkipWhenEmpty
    @InputDirectory
    val sourceRoot: DirectoryProperty = project.objects.directoryProperty()

    @OutputDirectory
    val outputDir: DirectoryProperty = project.objects.directoryProperty()

    @TaskAction
    fun run() {
        val output = outputDir.get().asFile.toPath()
        output.toFile().deleteRecursively()
        val root = sourceRoot.get().asFile.toPath()

        Files.walk(root).use { stream ->
            stream.filter { Files.isDirectory(it) }.forEach { dir -> generateFor(root, output, dir) }
        }
    }

    private fun generateFor(root: Path, output: Path, dir: Path) {
        val containsJava = Files.list(dir).use { list ->
            list.anyMatch { it.isRegularFile() && it.name.endsWith(".java") }
        }

        if (!containsJava || dir.resolve("package-info.java").exists()) {
            return
        }

        val relativePath = root.relativize(dir)
        val target = output.resolve(relativePath)
        Files.createDirectories(target)

        // Minecraft's own nullability defaults (MethodsReturnNonnullByDefault and friends) were
        // removed in 26.x in favor of JSpecify, which Minecraft itself is now annotated with.
        val packageName = relativePath.toString().replace(java.io.File.separator, ".")
        target.resolve("package-info.java").writeText(
            """
            |@NullMarked
            |package $packageName;
            |
            |import org.jspecify.annotations.NullMarked;
            |
            """.trimMargin()
        )
    }
}
