package io.github.consentgate.core.storage;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import java.util.jar.JarFile;

/**
 * The MariaDB driver, read from the plugin file once and then served from memory.
 * The driver looks up its codecs and login plugins through ServiceLoader on every connection, and ServiceLoader
 * reopens the JAR by path. Without this, renaming or replacing the plugin file while the server runs breaks every
 * new database connection.
 */
final class IsolatedDriver {
    private static final Set<String> ROOT_FILES = Set.of("mariadb.properties", "driver.properties", "deprecated.properties");
    private static java.sql.Driver shared;

    private IsolatedDriver() { }

    static synchronized java.sql.Driver get() throws SQLException {
        if (shared == null) {
            Class<?> anchor = org.mariadb.jdbc.Driver.class;
            try {
                shared = load(Path.of(anchor.getProtectionDomain().getCodeSource().getLocation().toURI()), anchor.getName(),
                        RemoteAcceptanceRepository.class.getClassLoader());
            } catch (URISyntaxException | RuntimeException ex) {
                throw new SQLException("Could not find the database driver in the plugin file.", ex);
            }
        }
        return shared;
    }

    static java.sql.Driver load(Path jar, String driverClass, ClassLoader parent) throws SQLException {
        String prefix = driverClass.substring(0, driverClass.lastIndexOf('.') + 1);
        var entries = new HashMap<String, byte[]>();
        try (var file = new JarFile(jar.toFile())) {
            for (var entry : Collections.list(file.entries())) {
                String name = entry.getName();
                if (!entry.isDirectory() && (name.startsWith(prefix.replace('.', '/'))
                        || name.startsWith("META-INF/services/" + prefix) || ROOT_FILES.contains(name))) {
                    try (InputStream input = file.getInputStream(entry)) { entries.put(name, input.readAllBytes()); }
                }
            }
            return (java.sql.Driver) Class.forName(driverClass, true, new MemoryLoader(entries, parent)).getConstructor().newInstance();
        } catch (IOException | ReflectiveOperationException | RuntimeException | LinkageError ex) {
            throw new SQLException("Could not load the database driver from the plugin file. Restart with the plugin file in place.", ex);
        }
    }

    /** Defines the driver's classes and serves its resources from memory, and leaves everything else to the parent. */
    private static final class MemoryLoader extends ClassLoader {
        static { registerAsParallelCapable(); }
        private final Map<String, byte[]> entries;
        private final URLStreamHandler handler = new URLStreamHandler() {
            @Override protected URLConnection openConnection(URL url) throws IOException {
                byte[] data = entries.get(url.getPath().substring(1));
                if (data == null) throw new FileNotFoundException(url.getPath());
                return new URLConnection(url) {
                    @Override public void connect() { }
                    @Override public InputStream getInputStream() { return new ByteArrayInputStream(data); }
                };
            }
        };

        MemoryLoader(Map<String, byte[]> entries, ClassLoader parent) {
            super("consentgate-mariadb", parent);
            this.entries = Map.copyOf(entries);
        }

        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!entries.containsKey(name.replace('.', '/') + ".class")) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) loaded = findClass(name);
                if (resolve) resolveClass(loaded);
                return loaded;
            }
        }

        @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = entries.get(name.replace('.', '/') + ".class");
            if (bytes == null) throw new ClassNotFoundException(name);
            return defineClass(name, bytes, 0, bytes.length);
        }

        @Override protected URL findResource(String name) {
            if (!entries.containsKey(name)) return null;
            try { return URL.of(new URI("consentgate-memory", null, "/" + name, null), handler); }
            catch (URISyntaxException | java.net.MalformedURLException ex) { throw new IllegalStateException(ex); }
        }

        // The parent holds the same names in the plugin file, which is exactly what must not be read again.
        @Override public URL getResource(String name) {
            URL own = findResource(name);
            return own != null ? own : super.getResource(name);
        }

        @Override public Enumeration<URL> getResources(String name) throws IOException {
            URL own = findResource(name);
            return own != null ? Collections.enumeration(List.of(own)) : super.getResources(name);
        }
    }
}
