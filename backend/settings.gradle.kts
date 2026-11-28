pluginManagement {
    val officialReposOnly = providers.gradleProperty("officialReposOnly").orNull == "true"

    repositories {
        if (!officialReposOnly) {
            maven {
                name = "aliyunGradlePlugin"
                url = uri("https://maven.aliyun.com/repository/gradle-plugin")
            }
            maven {
                name = "aliyunCentral"
                url = uri("https://maven.aliyun.com/repository/central")
            }
        }
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    val officialReposOnly = providers.gradleProperty("officialReposOnly").orNull == "true"

    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (!officialReposOnly) {
            maven {
                name = "aliyunCentral"
                url = uri("https://maven.aliyun.com/repository/central")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "upcpocket-backend"
