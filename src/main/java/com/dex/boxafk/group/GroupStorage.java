package com.dex.boxafk.group;

import java.io.File;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SQLite at {@code plugins/BoxAFK/data.db}. One connection; every method is {@code synchronized} because it is used
 * from the main thread (enable / disable) and from the single write thread of {@link GroupManager}.
 * Only salted IP hashes are stored, never an IP.
 */
public final class GroupStorage {

    public record Row(UUID uuid, String ipHash, String name) {
    }

    private final Logger log;
    private Connection conn;

    public GroupStorage(Logger log) {
        this.log = log;
    }

    public synchronized void open(File file) throws SQLException {
        File dir = file.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            throw new SQLException("Cannot create " + dir);
        }
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite driver not found", e);
        }
        conn = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA busy_timeout=5000");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS links (
                        uuid    TEXT PRIMARY KEY,
                        ip_hash TEXT NOT NULL,
                        name    TEXT NOT NULL
                    )
                    """);
            st.execute("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
        }
    }

    /** The salt for IP hashes, created on first start. Losing it only means every group must link again. */
    public synchronized String salt() throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT value FROM meta WHERE key = 'salt'");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) return rs.getString(1);
        }
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String salt = HexFormat.of().formatHex(bytes);
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO meta (key, value) VALUES ('salt', ?)")) {
            ps.setString(1, salt);
            ps.executeUpdate();
        }
        return salt;
    }

    public synchronized List<Row> loadAll() throws SQLException {
        List<Row> rows = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT uuid, ip_hash, name FROM links")) {
            while (rs.next()) {
                try {
                    rows.add(new Row(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getString(3)));
                } catch (IllegalArgumentException e) {
                    log.warning("Skipping link row with a bad UUID: " + rs.getString(1));
                }
            }
        }
        return rows;
    }

    public synchronized void upsert(UUID uuid, String ipHash, String name) {
        if (conn == null) return;
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO links (uuid, ip_hash, name) VALUES (?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET ip_hash = excluded.ip_hash, name = excluded.name
                """)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, ipHash);
            ps.setString(3, name);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.log(Level.SEVERE, "Could not save link of " + name, e);
        }
    }

    public synchronized void delete(UUID uuid) {
        if (conn == null) return;
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM links WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.log(Level.SEVERE, "Could not delete link of " + uuid, e);
        }
    }

    public synchronized void close() {
        if (conn == null) return;
        try {
            conn.close();
        } catch (SQLException e) {
            log.log(Level.WARNING, "Could not close the database", e);
        }
        conn = null;
    }
}
