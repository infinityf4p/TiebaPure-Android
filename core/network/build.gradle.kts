plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:protocol"))
    api(platform(libs.okhttp.bom))
    api(libs.okhttp)
    implementation(libs.kotlinx.coroutines.core)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation(libs.protobuf.java.lite)

    testImplementation(kotlin("test"))
    testImplementation(libs.mockwebserver3)
}
