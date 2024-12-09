
plugins {
    `java-gradle-plugin`
    `kotlin-dsl`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.plugin.publish)
    alias(libs.plugins.protobuf)
}

version = "0.1.0"
group = "com.codeasur"

dependencies {
    implementation(libs.grpc.netty)

    api(libs.grpc.stub)
    api(libs.grpc.protobuf)
    api(libs.protobuf.java.util)
    api(libs.protobuf.kotlin)

    api(libs.grpc.kotlin.stub)
    api(libs.kotlinx.coroutines.core)
}

testing {
    suites {
        // Configure the built-in test suite
        val test by getting(JvmTestSuite::class) {
            // Use Kotlin Test test framework
            useKotlinTest("2.0.20")
        }

        // Create a new test suite
        val functionalTest by registering(JvmTestSuite::class) {
            // Use Kotlin Test test framework
            useKotlinTest("2.0.20")

            dependencies {
                // functionalTest test suite depends on the production code in tests
                implementation(project())
            }

            targets {
                all {
                    // This test suite should run after the built-in test suite has run its tests
                    testTask.configure { shouldRunAfter(test) }
                }
            }
        }
    }
}

gradlePlugin {
    vcsUrl.set("https://github.com/Neerajsh8851/gradle-plugin-get-proto")
    website.set("https://github.com/Neerajsh8851/gradle-plugin-get-proto")

    val getProto by plugins.creating {
        id = "com.codeasur.getproto"
        implementationClass = "com.codeasur.GetProtoPlugin"
        displayName = "GetProto"
        description = "A plugin to generate proto files from a remote server by reflection"
        tags.set(listOf("get-proto", "reflection", "proto", "grpc"))

    }
}

gradlePlugin.testSourceSets.add(sourceSets["functionalTest"])

tasks.named<Task>("check") {
    // Include functionalTest as part of the check lifecycle
    dependsOn(testing.suites.named("functionalTest"))
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
    }
}

sourceSets {
    main {
        proto {
            srcDir("src/proto")
        }
    }
}

protobuf {
    protoc {
        artifact = libs.protoc.asProvider().get().toString()
    }
    plugins {
        create("grpc") {
            artifact = libs.protoc.gen.grpc.java.get().toString()
        }
        create("grpckt") {
            artifact = libs.protoc.gen.grpc.kotlin.get().toString() + ":jdk8@jar"
        }
    }
    generateProtoTasks {
        all().forEach {
            it.plugins {
                create("grpc")
                create("grpckt")
            }
            it.builtins {
                create("kotlin")
            }
        }
    }
}

