package dev.engine_room.gradle.subproject

import dev.engine_room.gradle.nullability.PackageInfosExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

class SubprojectPlugin: Plugin<Project> {
    override fun apply(project: Project) {
        project.extensions.create("defaultPackageInfos", PackageInfosExtension::class.java, project)
    }
}
