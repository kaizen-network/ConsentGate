plugins {
    `java-library`
    id("com.gradleup.shadow")
}
dependencies {
    implementation(project(":core"))
    implementation(project(":presentation"))
    compileOnly("io.papermc.paper:paper-api:1.21.7-R0.1-SNAPSHOT")
    implementation("net.kyori:adventure-nbt:4.26.1") { isTransitive = false }
    implementation("net.kyori:examination-api:1.3.0") { isTransitive = false }
    implementation("net.kyori:examination-string:1.3.0") { isTransitive = false }
    testImplementation("net.kyori:adventure-nbt:4.26.1")
    testImplementation("net.kyori:adventure-text-minimessage:4.26.1")
}
tasks.jar {
    enabled = false
}
tasks.shadowJar {
    archiveBaseName.set("ConsentGate-Paper")
    archiveClassifier.set("")
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
    relocate("org.yaml.snakeyaml", "io.github.consentgate.internal.snakeyaml")
    relocate("net.kyori.adventure.nbt", "io.github.consentgate.internal.adventurenbt") {
        exclude("net.kyori.adventure.nbt.api.**")
    }
    relocate("net.kyori.examination", "io.github.consentgate.internal.examination")
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}
val pluginVersion = version.toString()

tasks.test {
    dependsOn(tasks.shadowJar)
    systemProperty("consentgate.paperArtifact", tasks.shadowJar.get().archiveFile.get().asFile.absolutePath)
}
tasks.processResources {
    inputs.property("version", pluginVersion)
    filesMatching("plugin.yml") { expand("version" to pluginVersion) }
}
