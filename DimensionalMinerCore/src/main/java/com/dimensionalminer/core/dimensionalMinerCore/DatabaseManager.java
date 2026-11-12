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

    /**
     * Creates the connection pool from config.yml (databases.&lt;profile&gt;.*).
     * Throws on any problem; onEnable then disables the plugin instead of running without a DB.
     */
    public void connect() throws SQLException {
        plugin.getLogger().info("Connecting to PostgreSQL Database...");

        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            throw new SQLException("PostgreSQL JDBC driver not found (plugin.yml libraries)", e);
        }

        plugin.saveDefaultConfig();
        // Add sections that newer versions introduced (tiers, combat.mobs, ...) to an existing config.yml.
        // Bukkit does not read nested sections from the jar defaults, so they must be in the file.
        // Values already in the file are never changed.
        plugin.getConfig().options().copyDefaults(true);
        plugin.saveConfig();
        plugin.reloadConfig(); // read the merged file back so explicit-default lookups see the new keys
        String profile = plugin.getConfig().getString("database.profile", "cloud").toLowerCase();

        String prefix = "databases." + profile + ".";
        String jdbcUrl = plugin.getConfig().getString(prefix + "jdbcUrl");
        String username = plugin.getConfig().getString(prefix + "username");
        String password = plugin.getConfig().getString(prefix + "password");

        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            // Legacy single-profile layout: database.jdbcUrl / username / password (no built-in defaults).
            jdbcUrl = plugin.getConfig().getString("database.jdbcUrl");
            username = plugin.getConfig().getString("database.username");
            password = plugin.getConfig().getString("database.password");
        }
        if (jdbcUrl == null || jdbcUrl.isBlank() || username == null || password == null) {
            throw new SQLException("config.yml has no complete database settings for profile '" + profile
                    + "' (databases." + profile + ".jdbcUrl / username / password)");
        }
        if (password.equals("CHANGE_ME")) {
            throw new SQLException("config.yml still has the placeholder password CHANGE_ME for profile '" + profile
                    + "'. Put the real password in plugins/DimensionalMinerCore/config.yml");
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
        } catch (Exception e) {
            // Hikari fails fast here when the server is unreachable or the credentials are wrong.
            throw new SQLException("Could not open the connection pool: " + e.getMessage(), e);
        }
        plugin.getLogger().info("HikariDataSource initialized (" + jdbcUrl.replaceAll(":[^:@]+@", ":***@") + ")");
    }

    /** Runs SELECT 1; throws if the database does not answer. */
    public void testConnection() throws SQLException {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT 1;");
             ResultSet rs = stmt.executeQuery()) {
            if (!rs.next()) throw new SQLException("SELECT 1 returned no row");
        }
        plugin.getLogger().info("[OK] Connected to dimensional_game via HikariCP");
    }

    private final java.util.Map<java.util.UUID, java.util.UUID> accountCache = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The account (user_accounts.account_id) behind a Minecraft UUID. Every table that stores a
     * player (reward_grants, web_login_keys, accounts, asset_locations, parties) references this id,
     * never the Minecraft UUID; they only happen to be equal for accounts created in-game.
     *
     * <p>Throws instead of guessing: on a lookup error or a missing identity, writing under the
     * Minecraft UUID would put rewards/keys/items on the wrong (or a non-existent) account.
     * Any thread; no Bukkit API. Players get an identity on join via {@link #upsertPlayer}.
     */
    public java.util.UUID getAccountId(java.util.UUID minecraftUuid) throws SQLException {
        if (minecraftUuid == null) throw new SQLException("minecraftUuid is null");
        java.util.UUID cached = accountCache.get(minecraftUuid);
        if (cached != null) return cached;

        java.util.UUID found = findAccountId(minecraftUuid);
        if (found == null) {
            throw new SQLException("No MINECRAFT identity for " + minecraftUuid + " (player never joined, or upsertPlayer failed)");
        }
        return found;
    }

    /** Like {@link #getAccountId} but returns null when the player has no identity yet. */
    private java.util.UUID findAccountId(java.util.UUID minecraftUuid) throws SQLException {
        String query = "SELECT account_id FROM player_identities WHERE provider = 'MINECRAFT' AND identifier = ? LIMIT 1;";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {
            stmt.setString(1, minecraftUuid.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    java.util.UUID accId = (java.util.UUID) rs.getObject("account_id");
                    accountCache.put(minecraftUuid, accId);
                    return accId;
                }
            }
        }
        return null;
    }

    /**
     * Ensures user_accounts, the MINECRAFT identity and the COIN/ENERGY wallets exist, and returns
     * the account id. A brand-new player gets account_id = their Minecraft UUID; an already linked
     * identity keeps whatever account it points to. Async threads only (blocking DB calls);
     * reading the player's UUID and name is safe off the main thread.
     */
    public java.util.UUID upsertPlayer(Player player) throws SQLException {
        java.util.UUID uuid = player.getUniqueId();
        String name = player.getName();

        java.util.UUID existing = findAccountId(uuid);
        java.util.UUID accountId = existing != null ? existing : uuid;

        try (Connection conn = getConnection()) {
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
            ensureAccounts(conn, accountId);
        }
        return accountId;
    }

    /** Creates the COIN and ENERGY wallets of an account if missing. */
    private void ensureAccounts(Connection conn, java.util.UUID accountId) throws SQLException {
        String sql = "INSERT INTO accounts (account_id, owner_id, currency, balance) VALUES (?, ?, ?, 0) "
                + "ON CONFLICT (owner_id, currency) DO NOTHING;";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (String currency : new String[]{"COIN", "ENERGY"}) {
                stmt.setObject(1, java.util.UUID.randomUUID());
                stmt.setObject(2, accountId);
                stmt.setString(3, currency);
                stmt.addBatch();
            }
            stmt.executeBatch();
        }
    }

    /** Wallet balances of an account id (from {@link #getAccountId}). Async threads only. */
    public java.util.Map<String, Long> getBalances(java.util.UUID accountId) throws SQLException {
        java.util.Map<String, Long> out = new java.util.HashMap<>();
        String sql = "SELECT currency, balance FROM accounts WHERE owner_id = ?;";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, accountId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) out.put(rs.getString("currency"), rs.getLong("balance"));
            }
        }
        return out;
    }

    public record WebLoginKeyResult(String key, long validSeconds) {}

    /**
     * Expires the account's unused keys and creates a fresh 6-character one-time key (10 minutes).
     * @param accountId user_accounts.account_id (from {@link #getAccountId}), not the Minecraft UUID
     * @return the new key, or null if no unique code could be generated
     */
    public WebLoginKeyResult createWebLoginKey(java.util.UUID accountId) throws SQLException {
        String invalidateSql = "UPDATE web_login_keys SET expires_at = now() WHERE player_id = ? AND used_at IS NULL;";

        try (Connection conn = getConnection()) {
            try (PreparedStatement invStmt = conn.prepareStatement(invalidateSql)) {
                invStmt.setObject(1, accountId);
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
                    insertStmt.setObject(2, accountId);
                    int updated = insertStmt.executeUpdate();
                    if (updated > 0) {
                        return new WebLoginKeyResult(newKey, 600);
                    }
                }
            }
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
        if (dataSource == null) throw new SQLException("Database pool is not initialized");
        return dataSource.getConnection();
    }
}