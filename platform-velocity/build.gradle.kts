plugins {
    `java-library`
    id("com.gradleup.shadow")
}
repositories {
    maven("https://repo.opencollab.dev/main/")
}
dependencies {
    implementation(project(":core"))
    implementation(project(":presentation"))
    compileOnly("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
    testImplementation("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
    annotationProcessor("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
    compileOnly("com.github.retrooper:packetevents-api:2.13.0")
    compileOnly("org.geysermc.geyser:api:2.10.0-SNAPSHOT")
    testImplementation("org.geysermc.geyser:api:2.10.0-SNAPSHOT")
    testImplementation("net.kyori:adventure-text-minimessage:4.26.1")
}
sourceSets.test {
    resources.srcDir(project(":presentation").file("src/main/resources"))
}
tasks.jar {
    enabled = false
}
tasks.shadowJar {
    archiveBaseName.set("ConsentGate-Velocity")
    archiveClassifier.set("")
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
    relocate("org.yaml.snakeyaml", "io.github.consentgate.internal.snakeyaml")
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}
