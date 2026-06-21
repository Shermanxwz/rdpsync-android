pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\..*")
                includeGroupByRegex("androidx\\..*")
                includeGroupByRegex("android\\..*")
            }
        }
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

rootProject.name = "RdpSync"
include(":app")
