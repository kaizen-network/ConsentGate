plugins {
    `java-library`
    id("com.gradleup.shadow")
}
dependencies {
    implementation(project(":core"))
    implementation(project(":presentation"))
    compileOnly("io.papermc.paper:paper-api:1.21.7-R0.1-SNAPSHOT")
    implementation("net.kyori:adventure-nbt:4.26.1") { isTransitive = false }
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
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}
val pluginVersion = version.toString()
tasks.processResources {
    inputs.property("version", pluginVersion)
    filesMatching("plugin.yml") { expand("version" to pluginVersion) }
}
