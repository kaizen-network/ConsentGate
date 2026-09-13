plugins { `java-library` }

repositories { maven("https://repo.opencollab.dev/main/") }

dependencies {
    api(project(":presentation"))
    compileOnly("org.geysermc.geyser:api:2.10.0-20260604.180820-30")
    testImplementation("org.geysermc.geyser:api:2.10.0-20260604.180820-30")
    testImplementation("net.kyori:adventure-text-minimessage:4.26.1")
}

sourceSets.test {
    resources.srcDir(project(":presentation").file("src/main/resources"))
}

for (platform in listOf("paper", "velocity")) {
    val artifact = configurations.create("${platform}Artifact") {
        isCanBeConsumed = false
        isCanBeResolved = true
        isTransitive = false
    }
    dependencies.add(artifact.name, dependencies.project(mapOf(
        "path" to ":platform-$platform", "configuration" to "shadowRuntimeElements"
    )))
    val tests = sourceSets.test.get()
    val packagedTest = tasks.register<Test>("${platform}ArtifactTest") {
        description = "Checks native Bedrock delivery using the packaged $platform plugin."
        group = "verification"
        dependsOn(tasks.testClasses)
        testClassesDirs = tests.output.classesDirs
        // Keep file-based test documents first, then the JAR, with no loose renderer classes.
        classpath = files(tests.output.resourcesDir) + artifact + (tests.runtimeClasspath - sourceSets.main.get().output)
        filter { includeTestsMatching("io.github.consentgate.bedrock.GeyserBedrockBridgeTest") }
        doFirst { systemProperty("consentgate.bedrockArtifact", artifact.singleFile.absolutePath) }
    }
    tasks.check { dependsOn(packagedTest) }
}

tasks.jar {
    eachFile {
        if (path == "META-INF/LICENSE") path = "META-INF/consentgate-bedrock-LICENSE"
    }
}
