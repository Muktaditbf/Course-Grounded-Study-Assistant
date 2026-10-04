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
        // BlurView (real frosted-glass blur behind the floating navigation bar)
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "StudyAssistant"
include(":app")
