plugins { `java-library` }

dependencies {
    implementation("org.yaml:snakeyaml:2.7")
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")
    implementation("org.mariadb.jdbc:mariadb-java-client:3.5.10") { isTransitive = false }
}

tasks.test { useJUnitPlatform { excludeTags("remote-database") } }

tasks.register<Test>("remoteDatabaseTest") {
    dependsOn(":platform-paper:shadowJar", ":platform-velocity:shadowJar")
    description = "Run opt-in integration tests against a dedicated MySQL/MariaDB database."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    systemProperty("consentgate.paperArtifact", rootProject.file("platform-paper/build/libs/ConsentGate-Paper-${project.version}.jar").absolutePath)
    systemProperty("consentgate.velocityArtifact", rootProject.file("platform-velocity/build/libs/ConsentGate-Velocity-${project.version}.jar").absolutePath)
    useJUnitPlatform { includeTags("remote-database") }
    outputs.upToDateWhen { false }
    doFirst {
        require(System.getenv("CG_TEST_DB_ALLOW_WRITES") == "true") { "Set CG_TEST_DB_ALLOW_WRITES=true for the dedicated test database" }
        require(System.getenv("CG_TEST_DB_DATABASE")?.startsWith("consentgate_test_") == true) { "Database must start with consentgate_test_" }
    }
}
