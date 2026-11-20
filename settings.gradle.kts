pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "YysAssist"
// Shared: the wire protocol, the task engine (Android and future Windows clients) and the cloud server.
include(":core:protocol", ":core:engine", ":core:server")
// Clients. client/web (console) and client/site (home page and docs) are separate pnpm projects, not Gradle modules; client/windows comes later.
include(":client:android:app", ":client:android:vision", ":client:android:visionprobe", ":client:android:maaprobe")
