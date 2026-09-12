package io.github.consentgate.velocity;

import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VelocityArtifactTest {
    @Test void remoteDriverIsIsolatedAndIncludesSchemaAndLicense() throws Exception {
        var path = Path.of(System.getProperty("consentgate.velocityArtifact"));
        try (var loader = new URLClassLoader(new java.net.URL[]{path.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
             var jar = new JarFile(path.toFile())) {
            var driver = (java.sql.Driver) loader.loadClass("io.github.consentgate.internal.mariadb.Driver").getConstructor().newInstance();
            assertTrue(driver.acceptsURL("jdbc:mariadb://localhost/example"));
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass("org.mariadb.jdbc.Driver"));
            assertNotNull(jar.getEntry("META-INF/licenses/MariaDB-Connector-J.txt"));
            assertNotNull(jar.getEntry("db/mysql-v1.sql"));
        }
    }
}
