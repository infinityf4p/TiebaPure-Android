plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":core:model"))
    api(kotlin("test"))
    api(platform(libs.okhttp.bom))
    api(libs.mockwebserver3)
}
