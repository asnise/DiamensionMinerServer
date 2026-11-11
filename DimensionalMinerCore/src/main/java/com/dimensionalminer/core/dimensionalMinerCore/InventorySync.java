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
import org.bukkit.persistence.PersistentDataType;

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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import net.kyori.adventure.text.Component;

/**
 * Inventory sync between the game and the authoritative database.
 *
 * <ul>
 *   <li>Rows in PostgreSQL are the source of truth; the Bukkit inventory is a projection.</li>
 *   <li>A per-player hash of all 41 slots is kept in memory. Snapshots are taken every 15s
 *       (timer in {@code onEnable}), on quit, and after mailbox claims; only slots whose hash
 *       changed are written, in one transaction per snapshot.</li>
 *   <li>All DB work for one player (load and every save) runs through a per-player chain on
 *       {@link #ioExecutor}, so it commits in the order it was captured. A rejoin's load waits
 *       for the quit save. Different players still run in parallel.</li>
 *   <li>Nothing is saved for a player until their load has succeeded; otherwise an empty
 *       pre-load inventory would delete their DB rows. A failed load kicks the player.</li>
 *   <li>{@link #shutdown()} flushes and waits for every pending write before the pool closes.</li>
 * </ul>
 *
 * Each slot stores the full serialized ItemStack in material_stacks.item_data (enchants,
 * durability, name, lore, PDC), plus definition_id/quantity for the web console.
 *
 * Not covered yet: player_sessions.epoch is bumped but not checked, so two servers writing the
 * same player is not fenced (see Task.md 10.2).
 */
public final class InventorySync implements Listener {

    private static final int SLOT_OFFHAND = 40;
    private static final int IO_THREADS = 4;
    private static final long SHUTDOWN_WAIT_SECONDS = 20;

    private final DimensionalMinerCore plugin;
    private final String nodeId;
    private final Map<UUID, long[]> lastState = new ConcurrentHashMap<>();
    /** Players whose inventory was loaded from the DB this session. Only these are ever saved. */
    private final Set<UUID> loaded = ConcurrentHashMap.newKeySet();
    /** Tail of each player's DB work chain. */
    private final Map<UUID, CompletableFuture<Void>> chains = new ConcurrentHashMap<>();
    private final ExecutorService ioExecutor = Executors.newFixedThreadPool(IO_THREADS, r -> {
        Thread t = new Thread(r, "DMCore-InventorySync");
        t.setDaemon(true);
        return t;
    });

    /** A changed slot captured on the main thread. {@code data} is the full serialized ItemStack. */
    private record SlotItem(int index, String container, int slot, String defId, int quantity, byte[] data, boolean empty) {}

    /** A slot read from the DB (IO thread); turned into an ItemStack on the main thread. */
    private record StoredSlot(String defId, int quantity, byte[] data) {}

    public InventorySync(DimensionalMinerCore plugin) {
        this.plugin = plugin;
        this.nodeId = plugin.getConfig().getString("node-id", "main-a");
    }

    // ---------- events ----------

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        loadAsync(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        snapshotAndMaybeSave(player);
        loaded.remove(player.getUniqueId());
        lastState.remove(player.getUniqueId());
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST)
    public void onDeath(org.bukkit.event.entity.PlayerDeathEvent event) {
        event.setKeepInventory(true);
        event.getDrops().clear();
        event.setKeepLevel(true);
        event.setDroppedExp(0);
    }

    // ---------- lifecycle ----------

    public void loadAsync(Player player) {
        final UUID playerId = player.getUniqueId();
        enqueue(playerId, () -> {
            Map<Integer, StoredSlot> fromDb;
            try {
                plugin.getDatabaseManager().upsertPlayer(player);
                fromDb = loadInventory(playerId);
            } catch (Exception e) {
                plugin.getLogger().severe("Inventory load failed for " + player.getName() + ": " + e.getMessage());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    // Playing on without a loaded inventory would let the next save wipe the DB copy.
                    if (player.isOnline()) {
                        player.kick(Component.text("Could not load your inventory from the database. Please rejoin in a moment."));
                    }
                });
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                player.getInventory().clear();
                for (Map.Entry<Integer, StoredSlot> e : fromDb.entrySet()) {
                    int idx = e.getKey();
                    if (idx < 0 || idx > SLOT_OFFHAND) continue;
                    ItemStack stack = toItemStack(e.getValue(), player.getName(), idx);
                    if (stack != null) player.getInventory().setItem(idx, stack);
                }
                lastState.put(playerId, hashes(player));
                loaded.add(playerId);
            });
        });
    }

    /**
     * Call on the main thread only. Captures the changed slots now and queues the write
     * behind any earlier load/save of the same player. Does nothing before the load succeeded.
     */
    public void snapshotAndMaybeSave(Player player) {
        final UUID playerId = player.getUniqueId();
        if (!loaded.contains(playerId)) return;

        long[] current = hashes(player);
        long[] previous = lastState.get(playerId);
        if (previous != null && Arrays.equals(previous, current)) return;

        List<SlotItem> changed = collectChanged(player, previous, current);
        lastState.put(playerId, current);
        if (changed.isEmpty()) return;

        enqueue(playerId, () -> {
            try {
                int rows = saveBatch(playerId, changed);
                if (rows > 0) {
                    plugin.getLogger().info("Saved " + rows + " slot(s) -> " + playerId);
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("Inventory save failed for " + playerId + ": " + e.getMessage());
            }
        });
    }

    /**
     * Call from onDisable before the DB pool is closed. Snapshots every online player, then
     * blocks until all queued loads/saves have finished (bounded wait).
     */
    public void shutdown() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            snapshotAndMaybeSave(p);
        }
        // Wait on the chains first: a link that has not started yet is only submitted to the
        // executor when its predecessor finishes, so shutting the executor down early would reject it.
        CompletableFuture<?>[] pending = chains.values().toArray(new CompletableFuture<?>[0]);
        try {
            CompletableFuture.allOf(pending).get(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            plugin.getLogger().severe("Inventory saves still running after " + SHUTDOWN_WAIT_SECONDS
                    + "s at shutdown; recent changes may be lost.");
        } catch (Exception e) {
            plugin.getLogger().severe("Error while waiting for inventory saves at shutdown: " + e.getMessage());
        }
        ioExecutor.shutdown();
    }

    /** Runs {@code task} on the IO pool after every earlier task of the same player. */
    private void enqueue(UUID playerId, Runnable task) {
        CompletableFuture<Void> tail = chains.compute(playerId, (id, prev) -> {
            CompletableFuture<Void> base = prev != null ? prev : CompletableFuture.completedFuture(null);
            return base.thenRunAsync(task, ioExecutor).exceptionally(ex -> {
                plugin.getLogger().severe("Inventory task failed for " + id + ": " + ex);
                return null;
            });
        });
        tail.whenComplete((r, ex) -> chains.remove(playerId, tail));
    }

    // ---------- snapshot helpers (main thread, no I/O) ----------

    private long[] hashes(Player player) {
        long[] h = new long[SLOT_OFFHAND + 1];
        for (int i = 0; i <= SLOT_OFFHAND; i++) h[i] = hashItem(player.getInventory().getItem(i));
        return h;
    }

    /** Covers type, amount and the whole item meta (enchants, damage, name, lore, PDC). 0 = empty slot. */
    private long hashItem(ItemStack item) {
        if (item == null || item.getType().isAir()) return 0L;
        long h = item.hashCode();
        return h == 0L ? 1L : h;
    }

    private List<SlotItem> collectChanged(Player player, long[] previous, long[] current) {
        List<SlotItem> out = new ArrayList<>();
        for (int i = 0; i <= SLOT_OFFHAND; i++) {
            if (previous != null && previous[i] == current[i]) continue;
            ItemStack stack = player.getInventory().getItem(i);
            String container = containerKey(i);
            int slot = container.equals("inv") ? i : 0;
            if (stack == null || stack.getType().isAir()) {
                out.add(new SlotItem(i, container, slot, null, 0, null, true));
            } else {
                String defId = null;
                ItemMeta meta = stack.getItemMeta();
                if (meta != null && plugin.getContentRegistryManager() != null) {
                    defId = meta.getPersistentDataContainer().get(plugin.getContentRegistryManager().getDefIdKey(), PersistentDataType.STRING);
                }
                if (defId == null || defId.isBlank()) {
                    defId = stack.getType().name().toLowerCase(Locale.ROOT);
                }
                int quantity = stack.getAmount();
                // definition_id + quantity stay for the web console; item_data is what the game restores.
                out.add(new SlotItem(i, container, slot, defId, quantity, stack.serializeAsBytes(), false));
            }
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

    /** IO thread: reads raw slot rows only; ItemStacks are built on the main thread by {@link #toItemStack}. */
    private Map<Integer, StoredSlot> loadInventory(UUID playerId) throws SQLException {
        Map<Integer, StoredSlot> out = new HashMap<>();
        UUID ownerId = plugin.getDatabaseManager().getAccountId(playerId);
        String sql = "SELECT l.kind, l.container_key, l.slot, ms.quantity, ms.definition_id, ms.item_data"
                + " FROM asset_locations l"
                + " JOIN material_stacks ms ON ms.location_id = l.location_id"
                + " WHERE l.owner_id = ? AND l.kind IN ('INVENTORY','EQUIPPED')";

        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, ownerId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    StoredSlot stored = new StoredSlot(rs.getString("definition_id"), rs.getInt("quantity"), rs.getBytes("item_data"));
                    out.put(indexOf(rs.getString("kind"), rs.getString("container_key"), rs.getInt("slot")), stored);
                }
            }
        }
        return out;
    }

    /**
     * Main thread. Restores the exact item from item_data when present. Rows saved before item_data
     * existed are rebuilt from definition_id + quantity (enchants etc. were never stored for those).
     * Returns null for an unknown legacy id instead of guessing; the DB row is left untouched until
     * the player puts something else in that slot.
     */
    private ItemStack toItemStack(StoredSlot s, String playerName, int index) {
        if (s.data() != null && s.data().length > 0) {
            try {
                return ItemStack.deserializeBytes(s.data());
            } catch (Exception e) {
                plugin.getLogger().warning("Could not deserialize item in slot " + index + " for " + playerName
                        + " (" + s.defId() + "), falling back to its definition: " + e.getMessage());
            }
        }
        String defId = s.defId();
        int amount = Math.max(1, s.quantity());
        if (plugin.getContentRegistryManager() != null && plugin.getContentRegistryManager().hasItem(defId)) {
            return plugin.getContentRegistryManager().createItemStack(defId, amount);
        }
        Material mat = defId == null ? null : Material.matchMaterial(defId.toUpperCase(Locale.ROOT));
        if (mat == null || !mat.isItem() || mat.isAir()) {
            plugin.getLogger().warning("Unknown item '" + defId + "' in slot " + index + " for " + playerName + "; slot left empty.");
            return null;
        }
        return new ItemStack(mat, Math.min(amount, mat.getMaxStackSize()));
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

    // ---------- database: batch save (one transaction) ----------

    private int saveBatch(UUID playerId, List<SlotItem> changed) throws SQLException {
        if (changed.isEmpty()) return 0;
        int written = 0;
        UUID ownerId = plugin.getDatabaseManager().getAccountId(playerId);

        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            conn.setAutoCommit(false);
            try {
                ensureNode(conn);
                long epoch = bumpSession(conn, ownerId);

                UUID opId = UUID.randomUUID();
                String idemKey = "invsync/" + ownerId + "/" + epoch;
                insertOperation(conn, opId, ownerId, idemKey);

                for (SlotItem item : changed) {
                    String kind = item.container().equals("inv") ? "INVENTORY" : "EQUIPPED";
                    written += writeSlot(conn, ownerId, kind, item.container(), item.slot(), item.defId(), item.quantity(), item.data(), item.empty());
                }

                insertOutbox(conn, opId, ownerId, written);
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
    private int writeSlot(Connection conn, UUID ownerId, String kind, String container, int slot, String defId, int quantity, byte[] data, boolean empty) throws SQLException {
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

        if (empty) {
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

        if (locationId != null) {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "UPDATE material_stacks SET quantity = ?, definition_id = ?, definition_version = 1, item_data = ? WHERE location_id = ?")) {
                stmt.setInt(1, quantity);
                stmt.setString(2, defId);
                stmt.setBytes(3, data);
                stmt.setObject(4, locationId);
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
                    "INSERT INTO material_stacks (stack_id, location_id, definition_id, definition_version, quantity, binding, item_data)"
                            + " VALUES (?, ?, ?, 1, ?, 'UNBOUND', ?)")) {
                stmt.setObject(1, UUID.randomUUID());
                stmt.setObject(2, autoLocationId);
                stmt.setString(3, defId);
                stmt.setInt(4, quantity);
                stmt.setBytes(5, data);
                stmt.executeUpdate();
            }
        }
        return 1;
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
}