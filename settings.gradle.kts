pluginManagement {
    repositories {
        google {
            content {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        val agpVersion = "9.1.1"
        id("com.android.application") version agpVersion
        id("com.android.library") version agpVersion
        id("com.android.settings") version agpVersion
    }
}

plugins {
    id("com.android.settings")
}

android {
    minSdk = 28
    // 36 (Android 16) is the highest publicly downloadable SDK — API 37 is a Studio-only
    // preview, so CI can't fetch it. targetSdk 36 still enables edge-to-edge (>= 35).
    targetSdk = 36
    compileSdk = 36
    ndkVersion = "27.1.12297006"
    buildToolsVersion = "36.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        maven("https://api.xposed.info")
    }
}
include(":app")
