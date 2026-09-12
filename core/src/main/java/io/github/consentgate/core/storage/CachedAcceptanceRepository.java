package io.github.consentgate.core.storage;

import io.github.consentgate.core.config.StorageConfig;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

/** A failed cache never supplies authority. All new decisions go to the primary. */
public final class CachedAcceptanceRepository implements AcceptanceRepository {
    private final AcceptanceRepository primary;
    private final StorageConfig.Cache config;
    private final Clock clock;
    private final Consumer<String> warning;
    private final Object[] players = new Object[64];
    private final String source;
    private final long startedWall;
    private final long startedNanos;
    private Connection cache;
    private FileChannel lockChannel;
    private FileLock lock;
    private long lastSeen;
    private long lastWall;
    private boolean clockStarted;
    private boolean warned;

    public CachedAcceptanceRepository(AcceptanceRepository primary, StorageConfig.Cache config, String source,
                                      Clock clock, Consumer<String> warning) {
        this.primary = Objects.requireNonNull(primary); this.config = Objects.requireNonNull(config);
        this.source = Objects.requireNonNull(source); this.clock = Objects.requireNonNull(clock); this.warning = Objects.requireNonNull(warning);
        startedWall = clock.millis(); startedNanos = System.nanoTime();
        Arrays.setAll(players, ignored -> new Object());
        if (!config.enabled() || config.freshnessSeconds() == 0) return;
        try {
            Files.createDirectories(config.file().getParent());
            lockChannel = FileChannel.open(config.file().resolveSibling(config.file().getFileName() + ".lock"), StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            lock = lockChannel.tryLock();
            if (lock == null) throw new IOException("Cache is already owned by another process");
            var marker = java.nio.ByteBuffer.allocate(1);
            boolean cleanShutdown = lockChannel.read(marker, 0) == 1 && marker.array()[0] == 0;
            markShutdown(false);
            Class.forName("org.sqlite.JDBC", true, getClass().getClassLoader());
            cache = DriverManager.getConnection("jdbc:sqlite:" + config.file());
            try (var statement = cache.createStatement()) {
                statement.execute("PRAGMA busy_timeout=1000");
                try (var rows = statement.executeQuery("PRAGMA quick_check")) {
                    if (!rows.next() || !"ok".equals(rows.getString(1))) throw new SQLException("Invalid cache");
                }
                try (var rows = statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")) {
                    while (rows.next()) if (!Set.of("cg_cached_checks", "cg_cache_clock").contains(rows.getString(1))) throw new SQLException("Not a ConsentGate cache file");
                }
                statement.execute("CREATE TABLE IF NOT EXISTS cg_cached_checks (player TEXT NOT NULL, scope TEXT NOT NULL, fingerprint TEXT NOT NULL, verified_at INTEGER NOT NULL, PRIMARY KEY(player,scope,fingerprint))");
                statement.execute("CREATE TABLE IF NOT EXISTS cg_cache_clock (id INTEGER PRIMARY KEY CHECK(id=1), last_seen INTEGER NOT NULL, version INTEGER NOT NULL)");
                try (var rows = statement.executeQuery("SELECT last_seen,version FROM cg_cache_clock WHERE id=1")) {
                    if (rows.next()) {
                        if (rows.getInt(2) != 1) throw new SQLException("Unsupported cache version");
                        lastSeen = rows.getLong(1);
                    }
                }
            }
            if (!cleanShutdown) {
                try (var statement = cache.createStatement()) { statement.executeUpdate("DELETE FROM cg_cached_checks"); }
            }
            tick();
        } catch (SQLException | IOException | ClassNotFoundException | RuntimeException ex) { disable(); }
    }

    @Override public boolean isAccepted(UUID player, String scope, Collection<ShownDocument> required) throws SQLException {
        synchronized (playerLock(player)) {
            String fingerprint = fingerprint(required);
            if (hit(player, scope, fingerprint)) return true;
            return verify(player, scope, required, fingerprint);
        }
    }

    @Override public boolean isAcceptedAuthoritatively(UUID player, String scope, Collection<ShownDocument> required) throws SQLException {
        synchronized (playerLock(player)) { return verify(player, scope, required, fingerprint(required)); }
    }

    private boolean verify(UUID player, String scope, Collection<ShownDocument> required, String fingerprint) throws SQLException {
        long started = clock.millis();
        boolean accepted = primary.isAcceptedAuthoritatively(player, scope, required);
        if (accepted) remember(player, scope, fingerprint, started);
        else invalidate(player, scope);
        return accepted;
    }

    @Override public void validateRevisions(String scope, Collection<ShownDocument> documents) throws SQLException { primary.validateRevisions(scope, documents); }

    @Override public void grant(UUID player, String scope, List<ShownDocument> shown, UUID request, Instant at, String method) throws SQLException {
        synchronized (playerLock(player)) {
            invalidate(player, scope);
            // Do not seed the cache from a void grant result, which may be an idempotent replay.
            primary.grant(player, scope, shown, request, at, method);
        }
    }

    @Override public void withdraw(UUID player, String scope, Collection<String> ids, UUID request, Instant at, String method) throws SQLException {
        synchronized (playerLock(player)) {
            // Invalidate before attempting the primary, including an unknown commit outcome.
            invalidate(player, scope);
            primary.withdraw(player, scope, ids, request, at, method);
        }
    }

    private synchronized boolean hit(UUID player, String scope, String fingerprint) {
        if (cache == null) return false;
        try {
            long now = tick();
            try (var query = cache.prepareStatement("SELECT verified_at FROM cg_cached_checks WHERE player=? AND scope=? AND fingerprint=?")) {
                query.setString(1, player.toString()); query.setString(2, scope); query.setString(3, fingerprint);
                try (var rows = query.executeQuery()) {
                    if (!rows.next()) return false;
                    long verified = rows.getLong(1);
                    return verified > 0 && verified <= now && now - verified < config.freshnessSeconds() * 1000L;
                }
            }
        } catch (SQLException | RuntimeException ex) { disable(); return false; }
    }

    private synchronized void remember(UUID player, String scope, String fingerprint, long verified) {
        if (cache == null) return;
        try {
            long now = tick();
            if (verified <= 0 || verified > now || now - verified >= config.freshnessSeconds() * 1000L) return;
            try (var insert = cache.prepareStatement("INSERT INTO cg_cached_checks(player,scope,fingerprint,verified_at) VALUES(?,?,?,?) ON CONFLICT(player,scope,fingerprint) DO UPDATE SET verified_at=excluded.verified_at")) {
                insert.setString(1, player.toString()); insert.setString(2, scope); insert.setString(3, fingerprint); insert.setLong(4, verified); insert.executeUpdate();
            }
            try (var clean = cache.prepareStatement("DELETE FROM cg_cached_checks WHERE verified_at < ?")) {
                clean.setLong(1, now - 30L * 86400000); clean.executeUpdate();
            }
            try (var trim = cache.prepareStatement("DELETE FROM cg_cached_checks WHERE rowid IN (SELECT rowid FROM cg_cached_checks ORDER BY verified_at DESC,rowid DESC LIMIT -1 OFFSET ?)")) {
                trim.setInt(1, config.maxEntries()); trim.executeUpdate();
            }
        } catch (SQLException | RuntimeException ex) { disable(); }
    }

    private synchronized void invalidate(UUID player, String scope) {
        if (cache == null) return;
        try (var statement = cache.prepareStatement("DELETE FROM cg_cached_checks WHERE player=? AND scope=?")) {
            statement.setString(1, player.toString()); statement.setString(2, scope); statement.executeUpdate();
        } catch (SQLException | RuntimeException ex) { disable(); }
    }

    private long tick() throws SQLException {
        // Wall-clock stalls must not extend an entry's lifetime within this process.
        long elapsed = Math.max(0, (System.nanoTime() - startedNanos) / 1000000L);
        long wall = clock.millis();
        long now = Math.max(wall, startedWall + elapsed);
        if (wall < lastWall || now < lastSeen || wall <= 0 || (!clockStarted && wall < lastSeen)) {
            try (var statement = cache.createStatement()) { statement.executeUpdate("DELETE FROM cg_cached_checks"); }
        }
        try (var statement = cache.prepareStatement("INSERT INTO cg_cache_clock(id,last_seen,version) VALUES(1,?,1) ON CONFLICT(id) DO UPDATE SET last_seen=excluded.last_seen")) {
            statement.setLong(1, now); statement.executeUpdate();
        }
        lastSeen = now;
        lastWall = wall;
        clockStarted = true;
        return now;
    }

    private String fingerprint(Collection<ShownDocument> documents) {
        var unique = new TreeMap<String, ShownDocument>();
        for (var document : documents) {
            var old = unique.putIfAbsent(document.documentId(), document);
            if (old != null && !old.equals(document)) throw new IllegalArgumentException("Conflicting document requirement");
        }
        if (unique.size() > 32) throw new IllegalArgumentException("At most 32 documents are allowed");
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            field(digest, source);
            for (var document : unique.values()) for (String value : List.of(document.documentId(), document.version(), document.locale(), document.contentHash(), document.contentSnapshot())) field(digest, value);
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static void field(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
    }
    private Object playerLock(UUID player) { return players[(Objects.requireNonNull(player).hashCode() & 0x7fffffff) % players.length]; }

    private synchronized void disable() {
        closeCache(false);
        if (!warned) {
            warned = true;
            try { warning.accept("ConsentGate local cache is unavailable until restart; primary database checks remain required."); }
            catch (RuntimeException ignored) { }
        }
    }
    private void markShutdown(boolean clean) throws IOException {
        lockChannel.write(java.nio.ByteBuffer.wrap(new byte[]{(byte) (clean ? 0 : 1)}), 0);
        lockChannel.force(true);
    }
    private synchronized void closeCache(boolean clean) {
        if (cache != null) {
            try {
                cache.close();
                if (clean && lock != null) markShutdown(true);
            } catch (SQLException | IOException ignored) { }
            cache = null;
        }
        if (lock != null) { try { lock.release(); } catch (IOException ignored) { } lock = null; }
        if (lockChannel != null) { try { lockChannel.close(); } catch (IOException ignored) { } lockChannel = null; }
    }
    @Override public void close() throws SQLException { closeCache(true); primary.close(); }
}
