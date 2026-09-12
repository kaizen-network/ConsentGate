package io.github.consentgate.paper;

import org.junit.jupiter.api.Test;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import static org.junit.jupiter.api.Assertions.*;

class PaperArtifactTest {
    @Test void shadedParserWorksWithoutServerOrOtherPluginLibraries() throws Exception {
        var artifact = Path.of(System.getProperty("consentgate.paperArtifact"));
        try (var loader = new URLClassLoader(new java.net.URL[]{artifact.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            var driver = (java.sql.Driver) loader.loadClass("io.github.consentgate.internal.mariadb.Driver").getConstructor().newInstance();
            assertTrue(driver.acceptsURL("jdbc:mariadb://localhost/example"));
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass("org.mariadb.jdbc.Driver"));
            var parser = loader.loadClass("io.github.consentgate.paper.PaperSelections");
            var decode = parser.getDeclaredMethod("decode", String.class, List.class);
            decode.setAccessible(true);
            assertEquals(Map.of("rules", true), decode.invoke(null, "{document_0:1b}", List.of("rules")));
            assertNull(decode.invoke(null, "{document_0:2b}", List.of("rules")));
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass("net.kyori.adventure.nbt.TagStringIO"));
        }
        try (var jar = new JarFile(artifact.toFile())) {
            assertNotNull(jar.getEntry("META-INF/licenses/MariaDB-Connector-J.txt"));
            assertNotNull(jar.getEntry("db/mysql-v1.sql"));
            assertNotNull(jar.getEntry("META-INF/licenses/Examination.txt"));
            assertNotNull(jar.getEntry("META-INF/licenses/Adventure-NBT.txt"));
            try (var input = jar.getInputStream(jar.getEntry("io/github/consentgate/paper/PaperDialogs.class"))) {
                String bytecode = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1);
                assertTrue(bytecode.contains("net/kyori/adventure/nbt/api/BinaryTagHolder"));
                assertFalse(bytecode.contains("internal/adventurenbt/api"), "Paper API parameter types must not be relocated");
            }
        }
    }
}
