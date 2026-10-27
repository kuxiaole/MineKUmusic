pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
rootProject.name = "MineKUmusic-server"
includeBuild("../common")
include("core", "folia", "velocity")
