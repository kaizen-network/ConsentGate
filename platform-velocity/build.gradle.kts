plugins {
    `java-library`
    id("com.gradleup.shadow")
}
dependencies {
    implementation(project(":core"))
    compileOnly("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
    annotationProcessor("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
    compileOnly("com.github.retrooper:packetevents-api:2.13.0")
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
