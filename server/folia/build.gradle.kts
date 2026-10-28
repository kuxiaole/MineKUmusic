repositories { maven("https://jitpack.io") }
dependencies {
    compileOnly("dev.folia:folia-api:26.2.build.7-beta")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") { isTransitive = false }
    testImplementation("dev.folia:folia-api:26.2.build.7-beta")
    testImplementation("com.github.MilkBowl:VaultAPI:1.7") { isTransitive = false }
}
tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("plugin.yml") { expand("version" to project.version) }
}
