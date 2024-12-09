package io.github.kodysharma.getproto

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.register

class GetProtoPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        // Register the extension for get-proto task
        val extension = project.extensions.create("getProto", GetProtoExtension::class.java)

        project.tasks.register<GetProtoTask>("get-proto") {
            serverHost.set(
                project.provider { extension.serverHost.get() }
            )
            serverPort.set(
                project.provider { extension.serverPort.get() }
            )

            outputDir.set(
                project.provider { extension.outputDir.get() }
            )

            deadlineTime.set(
                project.provider { extension.deadlineTime.get() }
            )
        }
    }
}