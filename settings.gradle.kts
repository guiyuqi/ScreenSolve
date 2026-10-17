pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // 用 PREFER_SETTINGS 而不是 FAIL_ON_PROJECT_REPOS：
    // 本机的 ~/.gradle/init.gradle（环境镜像自带，往项目里加阿里云/腾讯镜像）会注入 project 级仓库，
    // 严格模式下会直接构建失败。这里以 settings 里的仓库为准，project 级注入只告警不报错。
    // 实测 dl.google.com / repo1.maven.org 直连有 5~9 MB/s，不需要走镜像。
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ScreenSolve"
include(":app")
