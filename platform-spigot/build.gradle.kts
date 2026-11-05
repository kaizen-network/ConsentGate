plugins {
    `java-library`
    id("com.gradleup.shadow")
}
repositories {
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
}
// 1.21.8 is the last Spigot line that bundles bungeecord-dialog 1.21-R0.3. Code built against it also links on R0.4 (1.21.10 and newer).
val spigotApi = "org.spigotmc:spigot-api:1.21.8-R0.1-20250930.150928-11"
dependencies {
    implementation(project(":core"))
    implementation(project(":presentation"))
    implementation(project(":integration-bedrock"))
    compileOnly(spigotApi)
    testImplementation(spigotApi)
    // Spigot ships no Adventure. These are shaded and relocated; Gson comes from Spigot.
    implementation("net.kyori:adventure-text-minimessage:4.26.1") { exclude(group = "org.jetbrains") }
    implementation("net.kyori:adventure-text-serializer-gson:4.26.1") {
        exclude(group = "com.google.code.gson")
        exclude(group = "org.jetbrains")
    }
    implementation("org.bstats:bstats-bukkit:3.2.1")
}
tasks.jar {
    enabled = false
}
tasks.shadowJar {
    archiveBaseName.set("ConsentGate-Spigot")
    archiveClassifier.set("")
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
    relocate("org.yaml.snakeyaml", "io.github.consentgate.internal.snakeyaml")
    relocate("org.mariadb.jdbc", "io.github.consentgate.internal.mariadb")
    relocate("net.kyori", "io.github.consentgate.internal.kyori")
    relocate("org.bstats", "io.github.consentgate.internal.bstats")
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}
val pluginVersion = version.toString()

tasks.test {
    dependsOn(tasks.shadowJar)
    systemProperty("consentgate.spigotArtifact", tasks.shadowJar.get().archiveFile.get().asFile.absolutePath)
}
tasks.processResources {
    inputs.property("version", pluginVersion)
    filesMatching("plugin.yml") { expand("version" to pluginVersion) }
}
