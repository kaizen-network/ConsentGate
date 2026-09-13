plugins { `java-library` }

repositories { maven("https://repo.opencollab.dev/main/") }

dependencies {
    api(project(":presentation"))
    compileOnly("org.geysermc.geyser:api:2.10.0-SNAPSHOT")
    testImplementation("org.geysermc.geyser:api:2.10.0-SNAPSHOT")
    testImplementation("net.kyori:adventure-text-minimessage:4.26.1")
}

sourceSets.test {
    resources.srcDir(project(":presentation").file("src/main/resources"))
}

tasks.jar {
    eachFile {
        if (path == "META-INF/LICENSE") path = "META-INF/consentgate-bedrock-LICENSE"
    }
}
