package com.dimensionalminer.core.dimensionalMinerCore;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class DatabaseManager {

    private HikariDataSource dataSource;
    private final JavaPlugin plugin;

    public DatabaseManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void connect() {
        plugin.getLogger().info("Connecting to PostgreSQL Database...");

        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            plugin.getLogger().severe("PostgreSQL JDBC Driver not found: " + e.getMessage());
            return;
        }

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:postgresql://localhost:5432/dimensional_miner");
        config.setUsername("postgres");
        config.setPassword("123456");

        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(10000);

        try {
            dataSource = new HikariDataSource(config);
            plugin.getLogger().info("HikariDataSource initialized successfully!");
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to initialize HikariDataSource: " + e.getMessage());
            e.printStackTrace();
        }
    }


    public void testConnection() {
        if (dataSource == null) {
            plugin.getLogger().severe("❌ DataSource is null, cannot test connection!");
            return;
        }

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT 1;");
             ResultSet rs = stmt.executeQuery()) {

            if (rs.next()) {
                plugin.getLogger().info("✅ Database Connected Successfully via HikariCP!");
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("❌ Failed to connect to the database! Reason: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void upsertPlayer(Player player) {
        if (dataSource == null) return;
        String sql = "INSERT INTO players (player_id, username) VALUES (?, ?) ON CONFLICT (player_id) DO UPDATE SET username = EXCLUDED.username;";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, player.getUniqueId());
            stmt.setString(2, player.getName());
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to upsert player: " + e.getMessage());
        }
    }

    public void ensureAccounts(java.util.UUID ownerId) {
        if (dataSource == null) return;
        String sql = "INSERT INTO accounts (account_id, owner_id, currency, balance) VALUES (?, ?, ?, 0) "
                + "ON CONFLICT (owner_id, currency) DO NOTHING;";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (String currency : new String[]{"COIN", "ENERGY"}) {
                stmt.setObject(1, java.util.UUID.randomUUID());
                stmt.setObject(2, ownerId);
                stmt.setString(3, currency);
                stmt.addBatch();
            }
            stmt.executeBatch();
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to ensure accounts: " + e.getMessage());
        }
    }

    public java.util.Map<String, Long> getBalances(java.util.UUID ownerId) {
        java.util.Map<String, Long> out = new java.util.HashMap<>();
        String sql = "SELECT currency, balance FROM accounts WHERE owner_id = ?;";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, ownerId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) out.put(rs.getString("currency"), rs.getLong("balance"));
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to read balances: " + e.getMessage());
        }
        return out;
    }

    public record WebLoginKeyResult(String key, boolean isNew, long remainingSeconds) {}

    public WebLoginKeyResult getOrCreateWebLoginKey(java.util.UUID playerId) {
        if (dataSource == null) return null;

        String invalidateSql = "UPDATE web_login_keys SET expires_at = now() WHERE player_id = ? AND used_at IS NULL;";

        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement invStmt = conn.prepareStatement(invalidateSql)) {
                invStmt.setObject(1, playerId);
                invStmt.executeUpdate();
            }

            String chars = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
            java.security.SecureRandom rng = new java.security.SecureRandom();
            String insertSql = "INSERT INTO web_login_keys (key_code, player_id, expires_at) VALUES (?, ?, now() + interval '10 minutes') "
                    + "ON CONFLICT DO NOTHING;";

            for (int attempt = 0; attempt < 10; attempt++) {
                StringBuilder sb = new StringBuilder(6);
                for (int i = 0; i < 6; i++) {
                    sb.append(chars.charAt(rng.nextInt(chars.length())));
                }
                String newKey = sb.toString();

                try (PreparedStatement insertStmt = conn.prepareStatement(insertSql)) {
                    insertStmt.setString(1, newKey);
                    insertStmt.setObject(2, playerId);
                    int updated = insertStmt.executeUpdate();
                    if (updated > 0) {
                        return new WebLoginKeyResult(newKey, true, 600);
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to get/create web login key: " + e.getMessage());
        }
        return null;
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            plugin.getLogger().info("Database connection closed.");
        }
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }
}