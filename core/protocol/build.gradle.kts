import com.google.protobuf.gradle.id

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.protobuf)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

sourceSets {
    main {
        proto {
            srcDir(rootProject.file("proto"))
        }
    }
}

dependencies {
    api(libs.protobuf.kotlin.lite)
    api(libs.protobuf.java.lite)

    testImplementation(kotlin("test"))
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.35.1"
    }

    generateProtoTasks {
        all().configureEach {
            builtins {
                named("java") {
                    option("lite")
                }
                id("kotlin") {
                    option("lite")
                }
            }
        }
    }
}
