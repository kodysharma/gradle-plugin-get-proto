package io.github.kodysharma.getproto

import com.google.protobuf.DescriptorProtos.DescriptorProto
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto
import com.google.protobuf.DescriptorProtos.FileDescriptorProto
import io.grpc.Deadline
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.reflection.v1alpha.ServerReflectionGrpcKt
import io.grpc.reflection.v1alpha.ServerReflectionResponse
import io.grpc.reflection.v1alpha.serverReflectionRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

abstract class GetProtoTask : DefaultTask() {
    @get:Input
    abstract val serverHost: Property<String>

    @get:Input
    abstract val serverPort: Property<Int>

    @get:Input
    abstract val outputDir: Property<String>

    @get:Input
    abstract val deadlineTime: Property<Long>

    private fun getServerHost(): String = serverHost.get()

    private fun getServerPort(): Int = serverPort.get()

    private fun getOutputDir(): String = outputDir.get()


    private val fileDescriptors = mutableListOf<FileDescriptorProto>()

    init {
    }

    @TaskAction
    fun action() {
        println("Connecting to gRPC server at ${getServerHost()}:${getServerPort()}")
        println("Output directory: ${getOutputDir()}")

        Files.createDirectories(Paths.get(getOutputDir()))
        val channel = createChannel()
        val stub = ServerReflectionGrpcKt.ServerReflectionCoroutineStub(channel)

        try {
            runBlocking(Dispatchers.IO) {
                val services = retryWithExponentialBackoff {
                    stub.withWaitForReady()
                        .withDeadline(Deadline.after(deadlineTime.get(), TimeUnit.SECONDS))
                        .listServices()
                }

                services
                    .filter { !it.endsWith("ServerReflection") }
                    .forEach { service ->
                        println("Getting file descriptor for service: $service")
                        val fd =
                            retryWithExponentialBackoff {
                                stub
                                    .withWaitForReady()
                                    .withDeadline(
                                        Deadline.after(
                                            deadlineTime.get(),
                                            TimeUnit.SECONDS
                                        )
                                    )
                                    .getFileContainingSymbol(service)
                            }
                        fileDescriptors.add(fd)
                        processFileDescriptor(stub, fd, getOutputDir())
                    }
            }

        } finally {
            channel.shutdown()
            if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                channel.shutdownNow()
            }
        }
    }


    private suspend fun <T> retryWithExponentialBackoff(
        maxRetries: Int = 5,
        initialDelay: Long = 1000L,
        factor: Double = 2.0,
        block: suspend () -> T,
    ): T {
        var currentDelay = initialDelay
        repeat(maxRetries - 1) {
            try {
                return block()
            } catch (e: Exception) {
                println("Request failed: ${e.message}. Retrying in $currentDelay ms...")
                delay(currentDelay)
                currentDelay = (currentDelay * factor).toLong()
            }
        }
        return block()
    }


    private suspend fun ServerReflectionGrpcKt.ServerReflectionCoroutineStub.getFileByName(name: String): FileDescriptorProto {
        val requestFlow = flowOf(
            serverReflectionRequest {
                fileByFilename = name
            }
        )

        val response = mutableListOf<ServerReflectionResponse>()
        serverReflectionInfo(requestFlow).collect { response.add(it) }

        return response.flatMap { it.fileDescriptorResponse.fileDescriptorProtoList }
            .map { FileDescriptorProto.parseFrom(it) }
            .first()
    }

    private suspend fun ServerReflectionGrpcKt.ServerReflectionCoroutineStub.getFileContainingSymbol(
        symbol: String,
    ): FileDescriptorProto {
        val requestFlow = flowOf(
            serverReflectionRequest {
                fileContainingSymbol = symbol
            }
        )

        val response = mutableListOf<ServerReflectionResponse>()
        serverReflectionInfo(requestFlow).collect { response.add(it) }

        return response.flatMap { it.fileDescriptorResponse.fileDescriptorProtoList }
            .map { FileDescriptorProto.parseFrom(it) }
            .first()
    }

    private suspend fun ServerReflectionGrpcKt.ServerReflectionCoroutineStub.listServices(): List<String> {
        val response = mutableListOf<ServerReflectionResponse>()
        val requestFlow = flowOf(
            serverReflectionRequest {
                listServices = ""
            }
        )

        serverReflectionInfo(requestFlow).collect { response.add(it) }
        return response.flatMap { it.listServicesResponse.serviceList }
            .map { it.name }
    }


    private fun createChannel(): ManagedChannel {
        val host = getServerHost()
        val port = getServerPort()
        // Determine if we should use plaintext or transport security based on the port number
        return if (port == 443) {
            ManagedChannelBuilder.forAddress(host, port)
                .useTransportSecurity()
                .maxInboundMessageSize(20 * 1024 * 1024)
                .build()
        } else {
            ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .maxInboundMessageSize(20 * 1024 * 1024)
                .build()
        }
    }


    private suspend fun processFileDescriptor(
        stub: ServerReflectionGrpcKt.ServerReflectionCoroutineStub,
        fileDescriptor: FileDescriptorProto,
        outputDir: String,
    ) {
        val protoFile = File(outputDir, fileDescriptor.name)
        protoFile.parentFile.mkdirs()

        val protoContent = buildString {
            appendLine("syntax = \"${fileDescriptor.syntax}\";")
            appendLine()
            if (fileDescriptor.hasPackage()) {
                appendLine("package ${fileDescriptor.`package`};")
                appendLine()
            }

            // Add imports
            fileDescriptor.dependencyList.forEach { dependency ->
                appendLine("import \"$dependency\";")
                if (dependency.startsWith("google/protobuf")) return@forEach
                val fd = getDependencyDescriptor(stub, dependency)
                processFileDescriptor(stub, fd, outputDir)
            }

            // Add messages
            fileDescriptor.messageTypeList.forEach { message ->
                appendLine(generateMessageType(message, fileDescriptor.`package`))
            }

            // Add enums
            fileDescriptor.enumTypeList.forEach { enum ->
                appendLine(generateEnumType(enum))
            }

            // Add services
            generateService(fileDescriptor).forEach { service ->
                appendLine(service)
            }
        }

        protoFile.writeText(protoContent)
        println("Generated: ${fileDescriptor.name}")
    }

    private suspend fun getDependencyDescriptor(
        stub: ServerReflectionGrpcKt.ServerReflectionCoroutineStub,
        dependency: String,
    ): FileDescriptorProto {
        return this.fileDescriptors.firstOrNull { it.name == dependency } ?: stub.getFileByName(
            dependency
        ).also { this.fileDescriptors.add(it) }
    }


    private fun generateMessageType(message: DescriptorProto, scope: String): String {
        return buildString {
            appendLine("message ${message.name} {")

            // First handle nested enums at message level
            message.enumTypeList.forEach { enum ->
                append(generateEnumType(enum).prependIndent("  "))
                appendLine()
            }

            // Handle nested messages
            message.nestedTypeList.forEach { nestedMessage ->
                append(
                    generateMessageType(nestedMessage, "${scope}.${message.name}").prependIndent(
                        "  "
                    )
                )
                appendLine()
            }

            var level = 1
            val tab = "  "
            message.fieldList
                .filter { it.hasOneofIndex() }
                .groupBy { it.oneofIndex }
                .forEach { entry ->
                    val oneOf = message.oneofDeclList[entry.key]

                    if (entry.value.size == 1 && oneOf.name.contains(entry.value.first().name)) {
                        val f = entry.value.first()
                        val typeString = f.getTypeString()
                            .substringAfter(scope)
                            .substringAfter(message.name)
                            .removePrefix(".")

                        appendLine("${tab.repeat(level)}${f.getLabelString()} $typeString ${f.name} = ${f.number};")

                    } else {

                        appendLine("${tab.repeat(level)}oneof ${oneOf.name} {")
                        level++
                        entry.value.forEach { f ->
                            val typeString = f.getTypeString()
                                .substringAfter(scope)
                                .substringAfter(message.name)
                                .removePrefix(".")
                            appendLine("${tab.repeat(level)}  $typeString ${f.name} = ${f.number};")
                        }
                        level--
                        appendLine("${tab.repeat(level)}}")
                    }
                }

            message.fieldList
                .filter { !it.hasOneofIndex() }
                .forEach { f ->
                    val typeString = f.getTypeString()
                        .substringAfter(scope)
                        .substringAfter(message.name)
                        .removePrefix(".")

                    if (!f.hasOneofIndex()) {
                        append(tab.repeat(level))
                        appendLine("${f.getLabelString()} $typeString ${f.name} = ${f.number};")
                    }
                }

            append("}")
        }
    }

    private fun generateEnumType(enum: EnumDescriptorProto): String {
        // Implementation for enum generation
        return buildString {
            appendLine("enum ${enum.name} {")
            enum.valueList.forEach { value ->
                appendLine("  ${value.name} = ${value.number};")
            }
            appendLine("}")
        }
    }

    private fun generateService(fileDescriptor: FileDescriptorProto): List<String> {
        val pkg = fileDescriptor.`package`

        return fileDescriptor.serviceList.map {
            buildString {
                appendLine("service ${it.name} {")
                it.methodList.forEach { method ->
                    val inputType = method.inputType.substringAfter(pkg).removePrefix(".")
                    val outputType = method.outputType.substringAfter(pkg).removePrefix(".")
                    appendLine("  rpc ${method.name}($inputType) returns ($outputType);")
                }
                appendLine("}")
            }
        }
    }

    private fun FieldDescriptorProto.getLabelString() = when (this.label) {
        FieldDescriptorProto.Label.LABEL_OPTIONAL -> "optional"
        FieldDescriptorProto.Label.LABEL_REQUIRED -> "required"
        FieldDescriptorProto.Label.LABEL_REPEATED -> "repeated"
        else -> ""
    }

    private fun FieldDescriptorProto.getTypeString() = when (this.type) {
        FieldDescriptorProto.Type.TYPE_DOUBLE -> "double"
        FieldDescriptorProto.Type.TYPE_FLOAT -> "float"
        FieldDescriptorProto.Type.TYPE_INT64 -> "int64"
        FieldDescriptorProto.Type.TYPE_UINT64 -> "uint64"
        FieldDescriptorProto.Type.TYPE_INT32 -> "int32"
        FieldDescriptorProto.Type.TYPE_FIXED64 -> "fixed64"
        FieldDescriptorProto.Type.TYPE_FIXED32 -> "fixed32"
        FieldDescriptorProto.Type.TYPE_BOOL -> "bool"
        FieldDescriptorProto.Type.TYPE_STRING -> "string"
        FieldDescriptorProto.Type.TYPE_BYTES -> "bytes"
        FieldDescriptorProto.Type.TYPE_UINT32 -> "uint32"
        FieldDescriptorProto.Type.TYPE_SFIXED32 -> "sfixed32"
        FieldDescriptorProto.Type.TYPE_SFIXED64 -> "sfixed64"
        FieldDescriptorProto.Type.TYPE_SINT32 -> "sint32"
        FieldDescriptorProto.Type.TYPE_SINT64 -> "sint64"
        FieldDescriptorProto.Type.TYPE_ENUM, FieldDescriptorProto.Type.TYPE_MESSAGE -> typeName.removePrefix(
            "."
        )

        else -> ""
    }
}