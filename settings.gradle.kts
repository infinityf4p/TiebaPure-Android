pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "TiebaPure"

include(
    ":app",
    ":core:model",
    ":core:protocol",
    ":core:network",
    ":core:data",
    ":core:designsystem",
    ":core:media",
    ":core:testing",
    ":feature:home",
    ":feature:forum",
    ":feature:search",
    ":feature:thread",
    ":feature:account",
    ":feature:composer",
    ":feature:settings",
)
