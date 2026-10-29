repositories { maven("https://jitpack.io") }
// Build releases against the oldest supported API; override for newer-version compatibility checks.
val foliaApiVersion = providers.gradleProperty("foliaApiVersion").orElse("26.1.2.build.8-stable").get()
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
