plugins { `java-library` }

dependencies {
    api(project(":core"))
    compileOnlyApi("net.kyori:adventure-text-minimessage:4.26.1")
    testImplementation("net.kyori:adventure-text-minimessage:4.26.1")
}
tasks.jar {
    eachFile {
        if (path == "META-INF/LICENSE") path = "META-INF/consentgate-presentation-LICENSE"
    }
}
