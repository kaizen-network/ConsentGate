plugins {
    `java-library`
    id("com.gradleup.shadow")
}
dependencies {
    implementation(project(":core"))
    compileOnly("io.papermc.paper:paper-api:1.21.7-R0.1-SNAPSHOT")
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
