pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        maven("https://maven.neoforged.net/releases/") { name = "NeoForge" }
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "MineKUmusic-26"

// 共用代码（core + ui）在 ../common，作为组合构建引进来
includeBuild("../common")

// 26.x 起 Minecraft 不再混淆：Fabric 用新的 fabric-loom，NeoForge 用 ModDevGradle，
// 这两个和 modern/ 用的 Architectury Loom 不能放在同一个构建里，所以单独一个文件夹。
// 代码照样用 modern/src 里的（共用部分 + src/mc<版本>/）。
val targets = listOf("26.2-fabric", "26.2-neoforge", "26.3-fabric", "26.3-neoforge")
targets.forEach {
    include(it)
    project(":$it").projectDir = file("versions/$it")
}
