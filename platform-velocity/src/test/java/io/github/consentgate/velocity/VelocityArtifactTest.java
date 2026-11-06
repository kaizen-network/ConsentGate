package io.github.consentgate.velocity;

import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Properties;
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
            // bStats refuses to start unless it is relocated.
            assertNotNull(jar.getEntry("io/github/consentgate/internal/bstats/velocity/Metrics.class"));
            assertNull(jar.getEntry("org/bstats/MetricsBase.class"));
            assertNotNull(jar.getEntry("META-INF/licenses/bStats.txt"));
        }
    }

    // Regression: the driver read its service files from the JAR path on every connection, so renaming the plugin file broke all database work.
    @Test void remoteDriverKeepsWorkingAfterThePluginFileIsGone(@org.junit.jupiter.api.io.TempDir Path temp) throws Exception {
        var copy = Files.copy(Path.of(System.getProperty("consentgate.velocityArtifact")), temp.resolve("ConsentGate-Velocity.jar"));
        java.sql.Driver driver;
        try (var loader = new URLClassLoader(new java.net.URL[]{copy.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            Method get = loader.loadClass("io.github.consentgate.core.storage.IsolatedDriver").getDeclaredMethod("get");
            get.setAccessible(true);
            driver = (java.sql.Driver) get.invoke(null);
        }
        Files.delete(copy);
        int port;
        try (var socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) { port = socket.getLocalPort(); }
        String url = "jdbc:mariadb://127.0.0.1:" + port + "/consentgate_test";
        var properties = new Properties();
        properties.setProperty("user", "nobody");
        properties.setProperty("connectTimeout", "2000");
        assertThrows(SQLException.class, () -> driver.connect(url, properties));

        ClassLoader isolated = driver.getClass().getClassLoader();
        assertEquals("consentgate-memory", isolated.getResource("META-INF/services/io.github.consentgate.internal.mariadb.plugin.Codec").getProtocol());
        Class<?> configuration = isolated.loadClass("io.github.consentgate.internal.mariadb.Configuration");
        Object parsed = configuration.getMethod("parse", String.class).invoke(null, url);
        assertTrue(((Object[]) configuration.getMethod("codecs").invoke(parsed)).length > 0);
        Method login = isolated.loadClass("io.github.consentgate.internal.mariadb.plugin.authentication.AuthenticationPluginLoader")
                .getMethod("get", String.class, configuration);
        for (String type : List.of("mysql_native_password", "caching_sha2_password", "client_ed25519")) {
            Object factory = login.invoke(null, type, parsed);
            assertEquals(type, isolated.loadClass("io.github.consentgate.internal.mariadb.plugin.AuthenticationPluginFactory").getMethod("type").invoke(factory));
        }
    }
}
