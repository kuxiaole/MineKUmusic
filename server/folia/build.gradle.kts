dependencies { compileOnly("dev.folia:folia-api:26.2.build.7-beta") }
tasks.processResources { filesMatching("plugin.yml") { expand("version" to project.version) } }
