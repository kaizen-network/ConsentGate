package io.github.consentgate.spigot;

import com.google.gson.Gson;
import io.github.consentgate.core.config.DialogAppearance;
import org.junit.jupiter.api.Test;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.jar.JarFile;
import static org.junit.jupiter.api.Assertions.*;

class SpigotArtifactTest {
    // Spigot has no Adventure, so the JAR must format text with its own relocated copy and Spigot's Gson only.
    @Test void shadedAdventureFormatsTextWithoutServerAdventure() throws Exception {
        var artifact = Path.of(System.getProperty("consentgate.spigotArtifact"));
        var gson = Gson.class.getProtectionDomain().getCodeSource().getLocation();
        try (var loader = new URLClassLoader(new URL[]{artifact.toUri().toURL(), gson}, ClassLoader.getPlatformClassLoader())) {
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass("net.kyori.adventure.text.Component"));
            var appearance = loader.loadClass(DialogAppearance.class.getName()).getMethod("defaults").invoke(null);
            var formatter = loader.loadClass("io.github.consentgate.presentation.SafeTextFormatter")
                    .getConstructor(appearance.getClass()).newInstance(appearance);
            var component = formatter.getClass().getMethod("title", String.class).invoke(formatter, "<bold>Server rules</bold>");
            var serializer = loader.loadClass("io.github.consentgate.internal.kyori.adventure.text.serializer.gson.GsonComponentSerializer");
            var instance = serializer.getMethod("gson").invoke(null);
            var json = (String) serializer.getMethod("serialize", loader.loadClass("io.github.consentgate.internal.kyori.adventure.text.Component"))
                    .invoke(instance, component);
            assertTrue(json.contains("Server rules") && json.contains("\"bold\":true") && json.contains("\"color\":\"gold\""), json);
            var driver = (java.sql.Driver) loader.loadClass("io.github.consentgate.internal.mariadb.Driver").getConstructor().newInstance();
            assertTrue(driver.acceptsURL("jdbc:mariadb://localhost/example"));
        }
        try (var jar = new JarFile(artifact.toFile())) {
            assertNotNull(jar.getEntry("plugin.yml"));
            assertNotNull(jar.getEntry("db/mysql-v1.sql"));
            for (String notice : java.util.List.of("Adventure.txt", "Examination.txt", "Option.txt", "bStats.txt", "MariaDB-Connector-J.txt", "SnakeYAML.txt")) {
                assertNotNull(jar.getEntry("META-INF/licenses/" + notice), notice);
            }
            // bStats refuses to start unless it is relocated.
            assertNotNull(jar.getEntry("io/github/consentgate/internal/bstats/bukkit/Metrics.class"));
            var unrelocated = jar.stream().map(entry -> entry.getName())
                    .filter(name -> name.startsWith("net/") || name.startsWith("com/") || name.startsWith("org/bstats/")
                            || name.startsWith("org/yaml/") || name.startsWith("org/mariadb/") || name.startsWith("org/jetbrains/"))
                    .toList();
            assertEquals(java.util.List.of(), unrelocated);
        }
    }
}
