repositories { maven("https://jitpack.io") }
// Override to verify the same source against the oldest supported Folia API.
val foliaApiVersion = providers.gradleProperty("foliaApiVersion").orElse("26.3.build.5-alpha").get()
dependencies {
    compileOnly("dev.folia:folia-api:$foliaApiVersion")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") { isTransitive = false }
    testImplementation("dev.folia:folia-api:$foliaApiVersion")
    testImplementation("com.github.MilkBowl:VaultAPI:1.7") { isTransitive = false }
}
tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("plugin.yml") { expand("version" to project.version) }
}
