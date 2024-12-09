package io.github.kodysharma.getproto

import org.gradle.api.Project
import org.gradle.api.provider.Property
import javax.inject.Inject

open class GetProtoExtension @Inject constructor(project: Project) {
    val serverHost: Property<String> = project.objects.property(String::class.java)
    val serverPort: Property<Int> = project.objects.property(Int::class.java)
    val outputDir: Property<String> = project.objects.property(String::class.java)
        .convention("${project.layout.buildDirectory.get()}/generated/source/proto")
    val deadlineTime: Property<Long> = project.objects.property(Long::class.java).convention(15000L)
}