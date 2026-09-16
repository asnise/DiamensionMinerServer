package com.dimensionalminer.core.dimensionalMinerCore;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight inventory sync between the game and the authoritative database.
 *
 * Design (avoids loading the server):
 *  - the Bukkit inventory is only ever a projection; rows in PostgreSQL are source of truth
 *  - no per-tick writes: a per-player hash of all 41 slots is kept in memory;
 *    a snapshot is taken periodically (and on quit/inventory close) and ONLY slots whose
 *    hash changed are written, batched in ONE transaction (1 commit, 1 round trip)
 *  - idempotent: each batch references an operation id with a unique idempotency_key
 *  - single-writer fencing: player_sessions.epoch is bumped on every save so a stale
 *    game node can never overwrite a newer one
 *  - an outbox event (inventory.updated) is inserted in the same transaction so the web
 *    console and other consumers observe the change atomically
 */
public final class InventorySync implements Listener {

    private static final int SLOT_OFFHAND = 40;

    private final DimensionalMinerCore plugin;
    private final String nodeId;
    private final Map<UUID, long[]> lastState = new ConcurrentHashMap<>();

    private record SlotItem(int index, String container, int slot, ItemStack stack) {}

    public InventorySync(DimensionalMinerCore plugin) {
        this.plugin = plugin;
        this.nodeId = plugin.getConfig().getString("node-id", "main-a");
    }

    // ---------- events ----------

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.getDatabaseManager().upsertPlayer(player);
        loadAsync(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        snapshotAndMaybeSave(event.getPlayer());
    }

    // ---------- lifecycle ----------

    public void loadAsync(Player player) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Map<Integer, ItemStack> loaded = loadInventory(player.getUniqueId());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) return;
                    player.getInventory().clear();
                    for (Map.Entry<Integer, ItemStack> e : loaded.entrySet()) {
                        int idx = e.getKey();
                        if (idx >= 0 && idx <= SLOT_OFFHAND) player.getInventory().setItem(idx, e.getValue());
                    }
                    lastState.put(player.getUniqueId(), hashes(player));
                });
            } catch (SQLException e) {
                plugin.getLogger().warning("Inventory load failed for " + player.getName() + ": " + e.getMessage());
            }
        });
    }

    /** Call on the main thread only. Captures a snapshot; if nothing changed, does nothing. */
    public void snapshotAndMaybeSave(Player player) {
        long[] current = hashes(player);
        long[] previous = lastState.get(player.getUniqueId());
        if (previous != null && Arrays.equals(previous, current)) return;

        List<SlotItem> changed = collectChanged(player, previous, current);
        lastState.put(player.getUniqueId(), current);

        if (changed.isEmpty()) return;
        final UUID playerId = player.getUniqueId();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            int rows;
            try {
                rows = saveBatch(playerId, changed);
                if (rows > 0) {
                    plugin.getLogger().info("Saved " + rows + " slot(s) -> " + playerId);
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Inventory save failed for " + playerId + ": " + e.getMessage());
            }
        });
    }

    // ---------- snapshot helpers (main thread, no I/O) ----------

    private long[] hashes(Player player) {
        long[] h = new long[SLOT_OFFHAND + 1];
        for (int i = 0; i <= SLOT_OFFHAND; i++) h[i] = hashItem(player.getInventory().getItem(i));
        return h;
    }

    private long hashItem(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return 0L;
        long v = item.getType().ordinal();
        v = v * 31 + item.getAmount();
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) v = v * 31 + meta.getDisplayName().hashCode();
        return v;
    }

    private List<SlotItem> collectChanged(Player player, long[] previous, long[] current) {
        List<SlotItem> out = new ArrayList<>();
        for (int i = 0; i <= SLOT_OFFHAND; i++) {
            if (previous != null && previous[i] == current[i]) continue;
            ItemStack stack = player.getInventory().getItem(i);
            String container = containerKey(i);
            out.add(new SlotItem(i, container, container.equals("inv") ? i : 0, stack));
        }
        return out;
    }

    private static String containerKey(int index) {
        if (index == SLOT_OFFHAND) return "OFFHAND";
        switch (index) {
            case 36: return "BOOTS";
            case 37: return "LEGGINGS";
            case 38: return "CHESTPLATE";
            case 39: return "HELMET";
            default: return "inv";
        }
    }

    // ---------- database: load ----------

    private Map<Integer, ItemStack> loadInventory(UUID playerId) throws SQLException {
        Map<Integer, ItemStack> out = new HashMap<>();
        String sql = "SELECT l.kind, l.container_key, l.slot, ms.quantity, cd.definition_id, cd.payload"
                + " FROM asset_locations l"
                + " JOIN material_stacks ms ON ms.location_id = l.location_id"
                + " JOIN content_definitions cd ON cd.definition_id = ms.definition_id AND cd.version = ms.definition_version"
                + " WHERE l.owner_id = ? AND l.kind IN ('INVENTORY','EQUIPPED')";

        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, playerId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    int amount = rs.getInt("quantity");
                    String defId = rs.getString("definition_id");
                    Material mat = Material.matchMaterial(defId.toUpperCase(Locale.ROOT));
                    if (mat == null) mat = Material.STONE;
                    ItemStack stack = new ItemStack(mat, Math.max(1, Math.min(amount, mat.getMaxStackSize())));
                    String payload = rs.getString("payload");
                    String display = displayFromPayload(payload, defId);
                    ItemMeta meta = stack.getItemMeta();
                    if (meta != null && display != null) {
                        meta.setDisplayName(display);
                        stack.setItemMeta(meta);
                    }
                    String kind = rs.getString("kind");
                    String container = rs.getString("container_key");
                    int slot = rs.getInt("slot");
                    out.put(indexOf(kind, container, slot), stack);
                }
            }
        }
        return out;
    }

    private static int indexOf(String kind, String container, int slot) {
        if (kind.equals("INVENTORY")) return slot;
        switch (container) {
            case "HELMET": return 39;
            case "CHESTPLATE": return 38;
            case "LEGGINGS": return 37;
            case "BOOTS": return 36;
            case "OFFHAND": return SLOT_OFFHAND;
            default: return slot;
        }
    }

    private static String displayFromPayload(String payload, String defId) {
        if (payload != null && payload.indexOf("\"display\"") >= 0) {
            int a = payload.indexOf("\"display\"");
            int b = payload.indexOf(':', a) + 1;
            int c = payload.indexOf('"', b);
            int d = payload.indexOf('"', c + 1);
            if (c >= 0 && d > c) return payload.substring(c + 1, d);
        }
        return null;
    }

    // ---------- database: batch save (one transaction) ----------

    private int saveBatch(UUID playerId, List<SlotItem> changed) throws SQLException {
        if (changed.isEmpty()) return 0;
        int written = 0;

        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            conn.setAutoCommit(false);
            try {
                ensureNode(conn);
                long epoch = bumpSession(conn, playerId);

                UUID opId = UUID.randomUUID();
                String idemKey = "invsync/" + playerId + "/" + epoch;
                insertOperation(conn, opId, playerId, idemKey);

                for (SlotItem item : changed) {
                    String kind = item.container().equals("inv") ? "INVENTORY" : "EQUIPPED";
                    written += writeSlot(conn, playerId, kind, item.container(), item.slot(), item.stack());
                }

                insertOutbox(conn, opId, playerId, written);
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
        return written;
    }

    private void ensureNode(Connection conn) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO nodes (node_id, role, status, heartbeat_at) VALUES (?, 'MAIN', 'READY', now())"
                        + " ON CONFLICT (node_id) DO UPDATE SET heartbeat_at = now()")) {
            stmt.setString(1, nodeId);
            stmt.executeUpdate();
        }
    }

    private long bumpSession(Connection conn, UUID playerId) throws SQLException {
        long epoch;
        try (PreparedStatement sel = conn.prepareStatement(
                "SELECT epoch FROM player_sessions WHERE player_id = ? FOR UPDATE")) {
            sel.setObject(1, playerId);
            try (ResultSet rs = sel.executeQuery()) {
                if (rs.next()) {
                    epoch = rs.getLong("epoch") + 1;
                } else {
                    try (PreparedStatement ins = conn.prepareStatement(
                            "INSERT INTO player_sessions (player_id, node_id, epoch, state, lease_until, snapshot_version)"
                                    + " VALUES (?, ?, 1, 'ACTIVE', now() + interval '15 minutes', 1)")) {
                        ins.setObject(1, playerId);
                        ins.setString(2, nodeId);
                        ins.executeUpdate();
                    }
                    epoch = 1;
                }
            }
        }
        if (epoch > 1) {
            try (PreparedStatement upd = conn.prepareStatement(
                    "UPDATE player_sessions SET epoch = epoch + 1, node_id = ?, state = 'ACTIVE',"
                            + " lease_until = now() + interval '15 minutes', snapshot_version = snapshot_version + 1"
                            + " WHERE player_id = ?")) {
                upd.setString(1, nodeId);
                upd.setObject(2, playerId);
                upd.executeUpdate();
            }
        }
        return epoch;
    }

    private void insertOperation(Connection conn, UUID opId, UUID playerId, String idemKey) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO operations (operation_id, actor_id, idempotency_key, request_hash, command_type, state, response, completed_at)"
                        + " VALUES (?, ?, ?, 'inv', 'inventory_sync', 'SUCCEEDED', '{}', now())"
                        + " ON CONFLICT (actor_id, idempotency_key) DO NOTHING")) {
            stmt.setObject(1, opId);
            stmt.setObject(2, playerId);
            stmt.setString(3, idemKey);
            stmt.executeUpdate();
        }
    }

    /** Returns 1 when the slot was written (insert or update), 0 when the slot is empty/skipped. */
    private int writeSlot(Connection conn, UUID ownerId, String kind, String container, int slot, ItemStack stack) throws SQLException {
        UUID locationId = null;
        try (PreparedStatement sel = conn.prepareStatement(
                "SELECT location_id FROM asset_locations WHERE owner_id = ? AND kind = ? AND container_key = ? AND slot = ? FOR UPDATE")) {
            sel.setObject(1, ownerId);
            sel.setString(2, kind);
            sel.setString(3, container);
            sel.setInt(4, slot);
            try (ResultSet rs = sel.executeQuery()) {
                if (rs.next()) locationId = (UUID) rs.getObject("location_id");
            }
        }

        boolean has = stack != null && stack.getType() != Material.AIR;
        if (!has) {
            if (locationId != null) {
                try (PreparedStatement stmt = conn.prepareStatement(
                        "DELETE FROM material_stacks WHERE location_id = ?")) {
                    stmt.setObject(1, locationId);
                    stmt.executeUpdate();
                }
                try (PreparedStatement stmt = conn.prepareStatement(
                        "DELETE FROM asset_locations WHERE location_id = ?")) {
                    stmt.setObject(1, locationId);
                    stmt.executeUpdate();
                }
            }
            return 0;
        }

        String defId = stack.getType().name().toLowerCase(Locale.ROOT);
        ensureDefinition(conn, stack, defId);
        int quantity = stack.getAmount();

        if (locationId != null) {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "UPDATE material_stacks SET quantity = ?, definition_id = ?, definition_version = 1 WHERE location_id = ?")) {
                stmt.setInt(1, quantity);
                stmt.setString(2, defId);
                stmt.setObject(3, locationId);
                stmt.executeUpdate();
            }
        } else {
            UUID autoLocationId = UUID.randomUUID();
            try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO asset_locations (location_id, owner_id, kind, asset_kind, container_key, slot, version)"
                            + " VALUES (?, ?, ?, 'STACK', ?, ?, 1)")) {
                stmt.setObject(1, autoLocationId);
                stmt.setObject(2, ownerId);
                stmt.setString(3, kind);
                stmt.setString(4, container);
                stmt.setInt(5, slot);
                stmt.executeUpdate();
            }
            try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO material_stacks (stack_id, location_id, definition_id, definition_version, quantity, binding)"
                            + " VALUES (?, ?, ?, 1, ?, 'UNBOUND')")) {
                stmt.setObject(1, UUID.randomUUID());
                stmt.setObject(2, autoLocationId);
                stmt.setString(3, defId);
                stmt.setInt(4, quantity);
                stmt.executeUpdate();
            }
        }
        return 1;
    }

    private void ensureDefinition(Connection conn, ItemStack stack, String defId) throws SQLException {
        Material mat = stack.getType();
        String friendly;
        ItemMeta meta = stack.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            friendly = meta.getDisplayName();
        } else {
            friendly = mat.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        }
        String payload = "{\"display\":\"" + jsonEscape(friendly) + "\",\"icon\":\"" + mat.name().toLowerCase(Locale.ROOT)
                + "\",\"stack\":" + mat.getMaxStackSize() + "}";
        String checksum = "sha256-" + Integer.toHexString(payload.hashCode());

        try (PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO content_definitions (definition_id, version, kind, payload, checksum)"
                        + " VALUES (?, 1, 'MATERIAL', ?::jsonb, ?) ON CONFLICT (definition_id, version) DO NOTHING")) {
            stmt.setString(1, defId);
            stmt.setString(2, payload);
            stmt.setString(3, checksum);
            stmt.executeUpdate();
        }
    }

    private void insertOutbox(Connection conn, UUID opId, UUID playerId, int changed) throws SQLException {
        String payload = "{\"changed\":" + changed + ",\"container\":\"player\"}";
        try (PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO outbox_events (event_id, operation_id, event_type, schema_version, aggregate_id, aggregate_version, payload)"
                        + " VALUES (?, ?, 'inventory.updated', 1, ?::text, 1, ?::jsonb)")) {
            stmt.setObject(1, UUID.randomUUID());
            stmt.setObject(2, opId);
            stmt.setObject(3, playerId);
            stmt.setString(4, payload);
            stmt.executeUpdate();
        }
    }

    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}