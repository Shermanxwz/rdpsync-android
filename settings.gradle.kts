pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\..*")
                includeGroupByRegex("androidx\\..*")
                includeGroupByRegex("android\\..*")
            }
        }
        maven {
            url = uri("https://maven-central.storage-download.googleapis.com/maven2")
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven {
            url = uri("https://maven-central.storage-download.googleapis.com/maven2")
        }
        mavenCentral()
    }
}

rootProject.name = "RdpSync"
include(":app")
