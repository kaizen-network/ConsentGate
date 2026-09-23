plugins {
    base
    id("com.gradleup.shadow") version "9.6.1" apply false
}

allprojects {
    group = "io.github.consentgate"
    version = "0.1.0"
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://repo.codemc.io/repository/maven-releases/")
    }
}

subprojects {
    apply(plugin = "java-library")
    dependencyLocking {
        lockAllConfigurations()
        // Gradle stores timestamped Maven builds under their base SNAPSHOT version in lock files.
        // These five coordinates are fixed explicitly and checked by verification-metadata.xml.
        ignoredDependencies.addAll("io.papermc.paper:paper-api", "com.velocitypowered:velocity-api",
            "com.velocitypowered:velocity-brigadier", "org.geysermc.geyser:api", "org.geysermc.event:events")
    }
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            val pinned = when ("${requested.group}:${requested.name}") {
                "org.geysermc.event:events" -> "1.1-20230815.153219-4"
                "com.velocitypowered:velocity-brigadier" -> "1.0.0-20210613.082804-10"
                else -> null
            }
            if (pinned != null) useVersion(pinned)
        }
    }
    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    }
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(21)
        options.encoding = "UTF-8"
    }
    tasks.withType<Test>().configureEach { useJUnitPlatform() }
    tasks.withType<Jar>().configureEach {
        if (name != "shadowJar") from(rootProject.file("LICENSE")) { into("META-INF") }
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:5.13.4"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }
}
