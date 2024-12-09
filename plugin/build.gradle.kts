plugins {
    // Apply the Java Gradle plugin development plugin to add support for developing Gradle plugins
    `java-gradle-plugin`
    `kotlin-dsl`

    // Apply the Kotlin JVM plugin to add support for Kotlin.
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.plugin.publish)
}

version = "0.1.0"
group = "com.codeasur"

dependencies {
    implementation(project(":reflection-service"))
    implementation(libs.grpc.netty)
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
