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
    // 禁止子模块私自定义仓库：依赖来源必须唯一，避免多仓库混用的问题
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "starveil"

include(":app")
include(":core:common")
include(":core:model")
include(":core:network")
include(":core:hongguo")
include(":core:data")
include(":core:designsystem")
include(":feature:home")
include(":feature:shortplay")
include(":feature:player")
include(":feature:user")
include(":feature:topic")
include(":feature:live")
include(":feature:rank")
include(":feature:pay")
include(":feature:detail")
