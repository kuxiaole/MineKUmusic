repositories { maven("https://jitpack.io") }
dependencies {
    compileOnly("dev.folia:folia-api:26.2.build.7-beta")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") { isTransitive = false }
}
tasks.processResources { filesMatching("plugin.yml") { expand("version" to project.version) } }
