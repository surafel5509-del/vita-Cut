pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
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

rootProject.name = "VitaCut"

include(":app")

include(":core:common")
include(":core:model")
include(":core:timeline")
include(":core:database")
include(":core:datastore")
include(":core:media")
include(":core:rendering")
include(":core:export")
include(":core:captions")
include(":core:ai")
include(":core:designsystem")

include(":domain")
include(":data")

include(":feature:home")
include(":feature:editor")
include(":feature:export")
include(":feature:settings")
include(":feature:templates")
