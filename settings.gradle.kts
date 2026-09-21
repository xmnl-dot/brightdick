pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.application") version "9.2.1"
        // KGP 2.2.x 不认识 AGP 9 的新扩展类型（会报 BaseExtension ClassCastException），必须 2.4.x
        id("org.jetbrains.kotlin.android") version "2.4.10"
        id("org.jetbrains.kotlin.plugin.compose") version "2.4.10"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "HyperBrightnessCurve"
include(":app")
