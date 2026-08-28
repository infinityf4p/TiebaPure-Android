plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val releaseSigningEnvironment = listOf(
    "TIEBAPURE_ANDROID_KEYSTORE",
    "TIEBAPURE_ANDROID_STORE_PASSWORD",
    "TIEBAPURE_ANDROID_KEY_ALIAS",
    "TIEBAPURE_ANDROID_KEY_PASSWORD",
).associateWith(System::getenv)
val configuredReleaseSigningValues = releaseSigningEnvironment.values.count { !it.isNullOrEmpty() }

check(configuredReleaseSigningValues == 0 || configuredReleaseSigningValues == releaseSigningEnvironment.size) {
    "Android release signing requires either all or none of: ${releaseSigningEnvironment.keys.joinToString()}"
}

val releaseSigningConfigured = configuredReleaseSigningValues == releaseSigningEnvironment.size
val offlineLicenseAssetsDirectory = layout.buildDirectory.dir("generated/offline-license-assets")
val generateOfflineLicenseAssets by tasks.registering(Sync::class) {
    into(offlineLicenseAssetsDirectory)
    from(rootProject.file("LICENSE")) {
        into("licenses")
        rename { "GPL-3.0-only.txt" }
    }
    from(rootProject.file("LICENSES")) {
        into("licenses")
    }
    from(rootProject.file("THIRD_PARTY_NOTICES.md")) {
        into("licenses")
    }
}

android {
    namespace = "dev.infinityf4p.tiebapure"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "dev.infinityf4p.tiebapure"
        minSdk = 23
        targetSdk = 36
        versionCode = 5
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    buildFeatures {
        compose = true
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(checkNotNull(releaseSigningEnvironment["TIEBAPURE_ANDROID_KEYSTORE"]))
                storePassword = checkNotNull(releaseSigningEnvironment["TIEBAPURE_ANDROID_STORE_PASSWORD"])
                keyAlias = checkNotNull(releaseSigningEnvironment["TIEBAPURE_ANDROID_KEY_ALIAS"])
                keyPassword = checkNotNull(releaseSigningEnvironment["TIEBAPURE_ANDROID_KEY_PASSWORD"])
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    sourceSets {
        getByName("main").assets.srcDir(offlineLicenseAssetsDirectory)
    }
}

tasks.named("preBuild").configure {
    dependsOn(generateOfflineLicenseAssets)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:network"))
    implementation(project(":core:data"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:media"))
    implementation(project(":feature:home"))
    implementation(project(":feature:forum"))
    implementation(project(":feature:search"))
    implementation(project(":feature:thread"))
    implementation(project(":feature:account"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:composer"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
