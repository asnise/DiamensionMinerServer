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

        plugin.saveDefaultConfig();
        String profile = plugin.getConfig().getString("database.profile", "cloud").toLowerCase();

        String prefix = "databases." + profile + ".";
        String jdbcUrl = plugin.getConfig().getString(prefix + "jdbcUrl");
        String username = plugin.getConfig().getString(prefix + "username");
        String password = plugin.getConfig().getString(prefix + "password");

        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            jdbcUrl = plugin.getConfig().getString("database.jdbcUrl", "jdbc:postgresql://localhost:5432/dimensional_game");
            username = plugin.getConfig().getString("database.username", "postgres");
            password = plugin.getConfig().getString("database.password", "123456");
        }

        plugin.getLogger().info("Database Profile: [" + profile.toUpperCase() + "]");

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);

        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(10000);

        try {
            dataSource = new HikariDataSource(config);
            plugin.getLogger().info("HikariDataSource initialized successfully (" + jdbcUrl.replaceAll(":[^:@]+@", ":***@") + ")!");
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to initialize HikariDataSource: " + e.getMessage());
            e.printStackTrace();
        }
    }


    public void testConnection() {
        if (dataSource == null) {
            plugin.getLogger().severe("[ERROR] DataSource is null, cannot test connection!");
            return;
        }

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT 1;");
             ResultSet rs = stmt.executeQuery()) {

            if (rs.next()) {
                plugin.getLogger().info("[OK] Connected to dimensional_game Successfully via HikariCP!");
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("[ERROR] Failed to connect to dimensional_game! Reason: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private final java.util.Map<java.util.UUID, java.util.UUID> accountCache = new java.util.concurrent.ConcurrentHashMap<>();

    public java.util.UUID getAccountId(java.util.UUID minecraftUuid) {
        if (minecraftUuid == null) return null;
        java.util.UUID cached = accountCache.get(minecraftUuid);
        if (cached != null) return cached;

        if (dataSource == null) return minecraftUuid;

        String query = "SELECT account_id FROM player_identities WHERE provider = 'MINECRAFT' AND identifier = ? LIMIT 1;";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {
            stmt.setString(1, minecraftUuid.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    java.util.UUID accId = (java.util.UUID) rs.getObject("account_id");
                    accountCache.put(minecraftUuid, accId);
                    return accId;
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Could not lookup account_id for " + minecraftUuid + ": " + e.getMessage());
        }

        // Default fallback to minecraftUuid
        return minecraftUuid;
    }

    public void upsertPlayer(Player player) {
        if (dataSource == null) return;
        java.util.UUID uuid = player.getUniqueId();
        String name = player.getName();

        try (Connection conn = dataSource.getConnection()) {
            java.util.UUID accountId = getAccountId(uuid);
            if (accountId == null || accountId.equals(uuid)) {
                accountId = uuid;
            }

            // 1. Ensure user_accounts
            String upsertAccount = "INSERT INTO user_accounts (account_id, username, status) VALUES (?, ?, 'ACTIVE') "
                    + "ON CONFLICT (account_id) DO UPDATE SET username = EXCLUDED.username;";
            try (PreparedStatement stmt = conn.prepareStatement(upsertAccount)) {
                stmt.setObject(1, accountId);
                stmt.setString(2, name);
                stmt.executeUpdate();
            }

            // 2. Ensure player_identities
            String upsertIdentity = "INSERT INTO player_identities (identity_id, account_id, provider, identifier, display_name) "
                    + "VALUES (?, ?, 'MINECRAFT', ?, ?) "
                    + "ON CONFLICT (provider, identifier) DO UPDATE SET display_name = EXCLUDED.display_name;";
            try (PreparedStatement stmt = conn.prepareStatement(upsertIdentity)) {
                stmt.setObject(1, java.util.UUID.randomUUID());
                stmt.setObject(2, accountId);
                stmt.setString(3, uuid.toString());
                stmt.setString(4, name);
                stmt.executeUpdate();
            }

            accountCache.put(uuid, accountId);
            ensureAccounts(accountId);
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to upsert player/identity: " + e.getMessage());
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
        java.util.UUID accId = getAccountId(ownerId);
        String sql = "SELECT currency, balance FROM accounts WHERE owner_id = ?;";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, accId);
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