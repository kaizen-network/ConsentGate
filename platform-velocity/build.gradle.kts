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
    implementation(project(":integration-bedrock"))
    compileOnly("com.velocitypowered:velocity-api:3.4.0-20260121.190037-118")
    testImplementation("com.velocitypowered:velocity-api:3.4.0-20260121.190037-118")
    annotationProcessor("com.velocitypowered:velocity-api:3.4.0-20260121.190037-118")
    compileOnly("com.github.retrooper:packetevents-api:2.13.0")
}
tasks.test {
    dependsOn(tasks.shadowJar)
    systemProperty("consentgate.velocityArtifact", tasks.shadowJar.get().archiveFile.get().asFile.absolutePath)
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
    relocate("org.mariadb.jdbc", "io.github.consentgate.internal.mariadb")
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}
