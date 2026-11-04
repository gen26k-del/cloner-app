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
        maven("https://www.jitpack.io")
    }
}
rootProject.name = "ClonerApp"
include(":app")

// BlackBox ভার্চুয়াল ইঞ্জিন (ALEX5402/NewBlackbox ফর্ক)
include(":blackbox:Bcore")
include(":blackbox:black-reflection")
include(":blackbox:compiler")
