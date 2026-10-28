// Platform-independent queue, provider and connection coordinator.
dependencies {
    api("me.sorihiko.dango:sources:${project.version}")
    // Both target server APIs provide Adventure 5.2; do not bundle a second copy.
    compileOnlyApi("net.kyori:adventure-api:5.2.0")
    compileOnly("net.kyori:adventure-text-serializer-plain:5.2.0")
    testImplementation("net.kyori:adventure-text-serializer-plain:5.2.0")
    compileOnly("net.kyori:adventure-text-minimessage:5.2.0")
    testImplementation("net.kyori:adventure-text-minimessage:5.2.0")
}
tasks.processResources {
    from(rootDir.resolve("../config.properties.example"))
    from(rootDir.resolve("../messages.properties.example"))
}
