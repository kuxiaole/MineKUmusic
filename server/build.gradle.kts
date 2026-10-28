import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import java.util.Properties

plugins { id("com.gradleup.shadow") version "9.2.2" apply false }
val releaseVersion = Properties().apply { rootDir.resolve("../version.properties").inputStream().use { load(it) } }.getProperty("version")
subprojects {
    apply(plugin = "java-library")
    group = "me.sorihiko.dango.server"
    version = releaseVersion
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
    extensions.configure<JavaPluginExtension> { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
    tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8"; options.release.set(25) }
    dependencies {
        "implementation"("me.sorihiko.dango:protocol:$releaseVersion")
        "implementation"("com.google.code.gson:gson:2.13.2")
        "testImplementation"("org.junit.jupiter:junit-jupiter:5.11.4")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }
    tasks.withType<Test>().configureEach { useJUnitPlatform() }
    if (name != "core") {
        apply(plugin = "com.gradleup.shadow")
        dependencies { "implementation"(project(":core")) }
        tasks.named<ShadowJar>("shadowJar") {
            archiveBaseName.set("MineKUmusic-server")
            archiveClassifier.set(if (project.name == "folia") "" else project.name)
            destinationDirectory.set(rootProject.layout.buildDirectory.dir("libs"))
            relocate("com.google.gson", "me.sorihiko.dango.server.libs.gson")
            relocate("kotlin", "me.sorihiko.dango.server.libs.kotlin")
            relocate("com.google.zxing", "me.sorihiko.dango.server.libs.zxing")
            from(rootDir.resolve("../LICENSE"))
            from(rootDir.resolve("../CREDITS.md"))
            from(rootDir.resolve("../common/ui/src/main/resources/META-INF/licenses/ZXing-Apache-2.0.txt")) {
                into("META-INF/licenses")
            }
        }
        tasks.named("build") { dependsOn("shadowJar") }
    }
}
