pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        // 国内镜像在前：Maven Central 直连在这台机器上会偶发 TLS 握手被掐断
        maven("https://repo.huaweicloud.com/repository/maven/")
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        maven("https://repo.huaweicloud.com/repository/maven/")
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        mavenCentral()
    }
}

rootProject.name = "AvPlayKMP"

include(":composeApp")
