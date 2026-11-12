package com.dimensionalminer.core.dimensionalMinerCore.mining;

import com.dimensionalminer.core.dimensionalMinerCore.DimensionalMinerCore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.inventory.ItemStack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Mining nodes (GDD section 3, Task.md section 2).
 *
 * <p>State per node lives in {@code mine_nodes} (one row per channel world). In memory a node is
 * READY, RESERVED (a break is being recorded) or DEPLETED. Everything here runs on the main thread
 * except the DB calls, which run async and hop back.
 *
 * <p>Mining a node: the vanilla break is cancelled; after reach / pickaxe / rate / state checks the
 * node is RESERVED (one winner per node, main thread) and one transaction records the operation
 * {@code mine/<channel>/<node>/<generation>} and flips the row READY -> DEPLETED only if the
 * generation still matches. Only after that commit does the player get the loot (inventory first,
 * overflow to the mailbox). If the DB is down nothing is handed out.
 *
 * <p>Respawning: a time queue processed once per second with a per-tick budget. The new
 * generation is written to the DB before the node becomes mineable again, so a crash can never
 * reopen an old generation.
 */
public final class MineNodeManager implements Listener {

    private enum State { READY, RESERVED, DEPLETED }

    private static final class Node {
        final String nodeId;      // "<world>:<x>_<y>_<z>"
        final String world;
        final int x, y, z;
        String oreId;
        long generation;
        State state;
        long respawnAtMillis;
        boolean respawnInFlight;

        Node(String nodeId, String world, int x, int y, int z, String oreId, long generation, State state, long respawnAtMillis) {
            this.nodeId = nodeId; this.world = world; this.x = x; this.y = y; this.z = z;
            this.oreId = oreId; this.generation = generation; this.state = state; this.respawnAtMillis = respawnAtMillis;
        }
    }

    private record BlockKey(String world, int x, int y, int z) {}
    private record ChunkKey(String world, int cx, int cz) {}

    private static final int RESPAWN_BUDGET_PER_TICK = 25;
    private static final long RATE_MIN_INTERVAL_MS = 120;
    private static final int RATE_MAX_IN_WINDOW = 12;
    private static final long RATE_WINDOW_MS = 5_000;
    private static final long DB_RETRY_MS = 10_000;

    private final DimensionalMinerCore plugin;
    private final String channelId;
    private final Map<BlockKey, Node> byBlock = new HashMap<>();
    private final Map<ChunkKey, List<Node>> byChunk = new HashMap<>();
    private final PriorityQueue<Node> respawnQueue = new PriorityQueue<>((a, b) -> Long.compare(a.respawnAtMillis, b.respawnAtMillis));
    private final Map<UUID, Deque<Long>> recentBreaks = new HashMap<>();
    private boolean loaded = false;

    public MineNodeManager(DimensionalMinerCore plugin) {
        this.plugin = plugin;
        this.channelId = plugin.getConfig().getString("node-id", "main-a");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, this::loadFromDb);
        Bukkit.getScheduler().runTaskTimer(plugin, this::processRespawns, 20L, 20L);
    }

    public String getChannelId() { return channelId; }

    // ---------- loading ----------

    private void loadFromDb() {
        List<Node> rows = new ArrayList<>();
        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            try (PreparedStatement n = conn.prepareStatement(
                    "INSERT INTO nodes (node_id, role, status, heartbeat_at) VALUES (?, 'MAIN', 'READY', now())"
                            + " ON CONFLICT (node_id) DO UPDATE SET heartbeat_at = now()")) {
                n.setString(1, channelId);
                n.executeUpdate();
            }
            try (PreparedStatement st = conn.prepareStatement(
                    "SELECT node_id, dimension_id, block_x, block_y, block_z, generation, state, respawn_at, ore_definition_id"
                            + " FROM mine_nodes WHERE channel_id = ?")) {
                st.setString(1, channelId);
                try (ResultSet rs = st.executeQuery()) {
                    while (rs.next()) {
                        java.sql.Timestamp ts = rs.getTimestamp("respawn_at");
                        rows.add(new Node(rs.getString("node_id"), rs.getString("dimension_id"),
                                rs.getInt("block_x"), rs.getInt("block_y"), rs.getInt("block_z"),
                                rs.getString("ore_definition_id"), rs.getLong("generation"),
                                "READY".equals(rs.getString("state")) ? State.READY : State.DEPLETED,
                                ts == null ? 0L : ts.getTime()));
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("[Mining] Could not load mine nodes: " + e.getMessage() + " (mining disabled until restart)");
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Node n : rows) register(n);
            loaded = true;
            plugin.getLogger().info("[Mining] Loaded " + rows.size() + " mine node(s) for channel " + channelId);
        });
    }

    private void register(Node n) {
        byBlock.put(new BlockKey(n.world, n.x, n.y, n.z), n);
        byChunk.computeIfAbsent(new ChunkKey(n.world, n.x >> 4, n.z >> 4), k -> new ArrayList<>()).add(n);
        if (n.state == State.DEPLETED) respawnQueue.add(n);
        applyVisual(n);
    }

    private void unregister(Node n) {
        byBlock.remove(new BlockKey(n.world, n.x, n.y, n.z));
        List<Node> list = byChunk.get(new ChunkKey(n.world, n.x >> 4, n.z >> 4));
        if (list != null) list.remove(n);
        respawnQueue.remove(n);
    }

    /** Puts the right block in the world if that chunk is loaded (otherwise on ChunkLoadEvent). */
    private void applyVisual(Node n) {
        World w = Bukkit.getWorld(n.world);
        if (w == null || !w.isChunkLoaded(n.x >> 4, n.z >> 4)) return;
        OreType ore = plugin.getContentRegistryManager().getOreType(n.oreId);
        if (ore == null) return;
        Block b = w.getBlockAt(n.x, n.y, n.z);
        org.bukkit.Material want = n.state == State.DEPLETED ? ore.depletedBlock() : ore.block();
        if (b.getType() != want) b.setType(want, false);
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        List<Node> list = byChunk.get(new ChunkKey(event.getWorld().getName(), event.getChunk().getX(), event.getChunk().getZ()));
        if (list != null) for (Node n : list) applyVisual(n);
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        String name = event.getWorld().getName();
        for (Node n : byBlock.values()) if (n.world.equals(name)) applyVisual(n);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        recentBreaks.remove(event.getPlayer().getUniqueId());
    }

    // ---------- mining ----------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockBreak(BlockBreakEvent event) {
        Block b = event.getBlock();
        Node n = byBlock.get(new BlockKey(b.getWorld().getName(), b.getX(), b.getY(), b.getZ()));
        if (n == null) return;
        // The node block itself never really breaks: no vanilla drops/EXP, and map protection is skipped.
        event.setCancelled(true);
        event.setDropItems(false);
        event.setExpToDrop(0);
        tryMine(event.getPlayer(), n);
    }

    private void tryMine(Player player, Node n) {
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
            player.sendActionBar(Component.text("Mining nodes can only be mined in survival (/mine node remove to delete one).", NamedTextColor.GRAY));
            return;
        }
        OreType ore = plugin.getContentRegistryManager().getOreType(n.oreId);
        LootTable loot = ore == null ? null : plugin.getContentRegistryManager().getLootTable(ore.lootTableId());
        if (ore == null || loot == null) {
            player.sendActionBar(Component.text("This node's ore type is not loaded.", NamedTextColor.RED));
            return;
        }
        if (n.state == State.RESERVED) return;
        if (n.state == State.DEPLETED) {
            long secs = Math.max(1, (n.respawnAtMillis - System.currentTimeMillis() + 999) / 1000);
            player.sendActionBar(Component.text(ore.displayName() + " is depleted. Respawns in " + secs + "s.", NamedTextColor.YELLOW));
            return;
        }

        // Anti-abuse (GDD: flag for review, never auto-ban)
        Location center = new Location(player.getWorld(), n.x + 0.5, n.y + 0.5, n.z + 0.5);
        double reach = reachOf(player) + 1.0;
        double dist = player.getEyeLocation().distance(center);
        if (dist > reach) {
            flag(player, n, String.format("reach %.1f > %.1f", dist, reach));
            return;
        }
        int tier = OreType.ToolTier.of(player.getInventory().getItemInMainHand().getType());
        if (tier < ore.requiredToolTier()) {
            player.sendActionBar(Component.text("Needs a " + OreType.ToolTier.name(ore.requiredToolTier())
                    + " pickaxe or better.", NamedTextColor.RED));
            return;
        }
        if (!rateOk(player)) {
            flag(player, n, "break rate too high");
            player.sendActionBar(Component.text("Slow down!", NamedTextColor.RED));
            return;
        }

        Map<String, Integer> rolled = loot.roll();
        List<ItemStack> stacks;
        try {
            stacks = RewardItems.build(plugin.getContentRegistryManager(), rolled);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("[Mining] Loot table " + loot.id() + " of " + ore.id() + ": " + e.getMessage());
            player.sendActionBar(Component.text("This node is misconfigured. Please tell an admin.", NamedTextColor.RED));
            return;
        }

        // Reserve: only one break per node can be in flight (main thread => no race).
        n.state = State.RESERVED;
        final long generation = n.generation;
        final UUID minecraftId = player.getUniqueId();
        final String dropsJson = new com.google.gson.Gson().toJson(rolled);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            MineResult result = recordDepletion(minecraftId, n, generation, ore.respawnSeconds(), dropsJson);
            Bukkit.getScheduler().runTask(plugin, () -> finishMine(player, n, ore, result, stacks, rolled));
        });
    }

    private enum MineOutcome { OK, STALE, DB_ERROR }
    private record MineResult(MineOutcome outcome, UUID accountId, long respawnAtMillis) {}

    /** Async. One transaction: operation (idempotent per node generation) + READY -> DEPLETED. */
    private MineResult recordDepletion(UUID minecraftId, Node n, long generation, int respawnSeconds, String dropsJson) {
        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            UUID accountId = plugin.getDatabaseManager().getAccountId(minecraftId);
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement op = conn.prepareStatement(
                        "INSERT INTO operations (operation_id, actor_id, idempotency_key, request_hash, command_type, state, response, completed_at)"
                                + " VALUES (?, ?, ?, ?, 'MINE_NODE', 'SUCCEEDED', ?::jsonb, now())"
                                + " ON CONFLICT (actor_id, idempotency_key) DO NOTHING")) {
                    op.setObject(1, UUID.randomUUID());
                    op.setObject(2, accountId);
                    op.setString(3, "mine/" + channelId + "/" + n.nodeId + "/" + generation);
                    op.setString(4, n.oreId);
                    op.setString(5, dropsJson);
                    if (op.executeUpdate() == 0) {
                        conn.rollback();
                        return new MineResult(MineOutcome.STALE, accountId, 0);
                    }
                }
                long respawnAt;
                try (PreparedStatement up = conn.prepareStatement(
                        "UPDATE mine_nodes SET state = 'DEPLETED', respawn_at = now() + make_interval(secs => ?)"
                                + " WHERE channel_id = ? AND node_id = ? AND state = 'READY' AND generation = ?"
                                + " RETURNING respawn_at")) {
                    up.setInt(1, respawnSeconds);
                    up.setString(2, channelId);
                    up.setString(3, n.nodeId);
                    up.setLong(4, generation);
                    try (ResultSet rs = up.executeQuery()) {
                        if (!rs.next()) {
                            conn.rollback();
                            return new MineResult(MineOutcome.STALE, accountId, 0);
                        }
                        respawnAt = rs.getTimestamp(1).getTime();
                    }
                }
                conn.commit();
                return new MineResult(MineOutcome.OK, accountId, respawnAt);
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("[Mining] Could not record depletion of " + n.nodeId + ": " + e.getMessage());
            return new MineResult(MineOutcome.DB_ERROR, null, 0);
        }
    }

    /** Main thread, after the DB answered. */
    private void finishMine(Player player, Node n, OreType ore, MineResult result,
                            List<ItemStack> stacks, Map<String, Integer> rolled) {
        if (result.outcome() != MineOutcome.OK) {
            if (n.state == State.RESERVED) n.state = State.READY;
            if (result.outcome() == MineOutcome.STALE) {
                // Someone (or another server) already took this generation: resync from the DB.
                reloadNode(n);
            }
            if (player.isOnline()) {
                player.sendActionBar(Component.text(result.outcome() == MineOutcome.DB_ERROR
                        ? "Mining is unavailable right now (database). Nothing was taken."
                        : "This node was just mined by someone else.", NamedTextColor.RED));
            }
            return;
        }

        n.state = State.DEPLETED;
        n.respawnAtMillis = result.respawnAtMillis();
        respawnQueue.add(n);
        applyVisual(n);

        String summary = RewardItems.describe(plugin.getContentRegistryManager(), rolled);
        // Durability (5.4): a gear pickaxe wears once per successful node break.
        if (player.isOnline() && plugin.getGearItems() != null)
            plugin.getGearItems().wear(player, org.bukkit.inventory.EquipmentSlot.HAND, 1);
        // Server XP (9.4) per node mined.
        if (player.isOnline() && plugin.getProgression() != null) plugin.getProgression().onMine(player, ore.id());
        if (!player.isOnline()) {
            mailAll(result.accountId(), n, stacks);
            return;
        }
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(stacks.stream().map(ItemStack::clone).toArray(ItemStack[]::new));
        if (!leftovers.isEmpty()) {
            mailAll(result.accountId(), n, leftovers.values());
            player.sendMessage(Component.text("[Mining] Inventory full: the rest went to your /grants mailbox.", NamedTextColor.YELLOW));
        }
        plugin.getInventorySync().snapshotAndMaybeSave(player);
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.6f, 1.3f);
        player.sendActionBar(Component.text("+ " + summary, NamedTextColor.GREEN));
    }

    private void mailAll(UUID accountId, Node n, Collection<ItemStack> stacks) {
        String payload = RewardItems.mailboxPayload(plugin.getContentRegistryManager(), stacks, "MINE_OVERFLOW");
        String sourceKey = "mine/" + channelId + "/" + n.nodeId + "/" + n.generation + "/overflow";
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement st = conn.prepareStatement(
                         "INSERT INTO reward_grants (grant_id, player_id, source_key, payload, state)"
                                 + " VALUES (?, ?, ?, ?::jsonb, 'AVAILABLE') ON CONFLICT (source_key) DO NOTHING")) {
                st.setObject(1, UUID.randomUUID());
                st.setObject(2, accountId);
                st.setString(3, sourceKey);
                st.setString(4, payload);
                st.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("[Mining] Lost overflow for " + accountId + " (" + sourceKey + "), payload="
                        + payload + ": " + e.getMessage());
            }
        });
    }

    private void reloadNode(Node n) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement st = conn.prepareStatement(
                         "SELECT generation, state, respawn_at FROM mine_nodes WHERE channel_id = ? AND node_id = ?")) {
                st.setString(1, channelId);
                st.setString(2, n.nodeId);
                try (ResultSet rs = st.executeQuery()) {
                    if (!rs.next()) return;
                    long gen = rs.getLong(1);
                    boolean ready = "READY".equals(rs.getString(2));
                    java.sql.Timestamp ts = rs.getTimestamp(3);
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        n.generation = gen;
                        n.state = ready ? State.READY : State.DEPLETED;
                        n.respawnAtMillis = ts == null ? 0L : ts.getTime();
                        respawnQueue.remove(n);
                        if (!ready) respawnQueue.add(n);
                        applyVisual(n);
                    });
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("[Mining] Could not reload node " + n.nodeId + ": " + e.getMessage());
            }
        });
    }

    private double reachOf(Player player) {
        try {
            AttributeInstance a = player.getAttribute(Attribute.BLOCK_INTERACTION_RANGE);
            if (a != null) return a.getValue();
        } catch (Throwable ignored) {
            // attribute not present on this server version: fall through to the vanilla default
        }
        return 4.5;
    }

    private boolean rateOk(Player player) {
        long now = System.currentTimeMillis();
        Deque<Long> times = recentBreaks.computeIfAbsent(player.getUniqueId(), k -> new ArrayDeque<>());
        while (!times.isEmpty() && now - times.peekFirst() > RATE_WINDOW_MS) times.pollFirst();
        Long last = times.peekLast();
        if (last != null && now - last < RATE_MIN_INTERVAL_MS) return false;
        if (times.size() >= RATE_MAX_IN_WINDOW) return false;
        times.addLast(now);
        return true;
    }

    private void flag(Player player, Node n, String reason) {
        plugin.getLogger().warning("[Mining][FLAG] " + player.getName() + " (" + player.getUniqueId() + ") at node "
                + n.nodeId + ": " + reason);
    }

    // ---------- respawn queue ----------

    private void processRespawns() {
        long now = System.currentTimeMillis();
        int budget = RESPAWN_BUDGET_PER_TICK;
        while (budget-- > 0) {
            Node n = respawnQueue.peek();
            if (n == null || n.respawnAtMillis > now) return;
            respawnQueue.poll();
            if (n.state != State.DEPLETED || n.respawnInFlight) continue;
            n.respawnInFlight = true;
            final long gen = n.generation;
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                boolean ok;
                try (Connection conn = plugin.getDatabaseManager().getConnection();
                     PreparedStatement st = conn.prepareStatement(
                             "UPDATE mine_nodes SET state = 'READY', generation = generation + 1, respawn_at = NULL"
                                     + " WHERE channel_id = ? AND node_id = ? AND state = 'DEPLETED' AND generation = ?")) {
                    st.setString(1, channelId);
                    st.setString(2, n.nodeId);
                    st.setLong(3, gen);
                    ok = st.executeUpdate() == 1;
                } catch (SQLException e) {
                    plugin.getLogger().warning("[Mining] Respawn of " + n.nodeId + " failed, retrying: " + e.getMessage());
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        n.respawnInFlight = false;
                        n.respawnAtMillis = System.currentTimeMillis() + DB_RETRY_MS;
                        respawnQueue.add(n);
                    });
                    return;
                }
                final boolean updated = ok;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    n.respawnInFlight = false;
                    if (updated) {
                        n.generation = gen + 1; // committed before the node becomes mineable again
                        n.state = State.READY;
                        n.respawnAtMillis = 0L;
                        applyVisual(n);
                    } else {
                        reloadNode(n);
                    }
                });
            });
        }
    }

    // ---------- admin (used by /mine) ----------

    public boolean isLoaded() { return loaded; }

    public String describe(Block b) {
        Node n = byBlock.get(new BlockKey(b.getWorld().getName(), b.getX(), b.getY(), b.getZ()));
        if (n == null) return null;
        String extra = n.state == State.DEPLETED
                ? " · respawns in " + Math.max(0, (n.respawnAtMillis - System.currentTimeMillis()) / 1000) + "s"
                : "";
        return n.nodeId + " · ore=" + n.oreId + " · gen=" + n.generation + " · " + n.state + extra;
    }

    /** Counts nodes per ore type within radius of a location. */
    public Map<String, Integer> countNear(Location loc, int radius) {
        Map<String, Integer> out = new HashMap<>();
        String w = loc.getWorld().getName();
        for (Node n : byBlock.values()) {
            if (!n.world.equals(w)) continue;
            if (Math.abs(n.x - loc.getBlockX()) > radius || Math.abs(n.z - loc.getBlockZ()) > radius) continue;
            out.merge(n.oreId + " (" + n.state + ")", 1, Integer::sum);
        }
        return out;
    }

    /**
     * Creates (or re-types) a node at the same block in every given world. Async DB, then registers
     * in memory and places the ore block. Calls back on the main thread with an error text or null.
     */
    public void addNode(List<String> worlds, int x, int y, int z, String oreId, Consumer<String> done) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<Node> created = new ArrayList<>();
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement st = conn.prepareStatement(
                         "INSERT INTO mine_nodes (channel_id, node_id, dimension_id, block_x, block_y, block_z, generation, state, respawn_at, ore_definition_id)"
                                 + " VALUES (?, ?, ?, ?, ?, ?, 0, 'READY', NULL, ?)"
                                 + " ON CONFLICT (channel_id, node_id) DO UPDATE SET ore_definition_id = EXCLUDED.ore_definition_id"
                                 + " RETURNING generation, state")) {
                for (String w : worlds) {
                    String nodeId = nodeId(w, x, y, z);
                    st.setString(1, channelId);
                    st.setString(2, nodeId);
                    st.setString(3, w);
                    st.setInt(4, x);
                    st.setInt(5, y);
                    st.setInt(6, z);
                    st.setString(7, oreId);
                    try (ResultSet rs = st.executeQuery()) {
                        rs.next();
                        created.add(new Node(nodeId, w, x, y, z, oreId, rs.getLong(1),
                                "READY".equals(rs.getString(2)) ? State.READY : State.DEPLETED, 0L));
                    }
                }
            } catch (SQLException e) {
                Bukkit.getScheduler().runTask(plugin, () -> done.accept("database error: " + e.getMessage()));
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                for (Node n : created) {
                    Node old = byBlock.get(new BlockKey(n.world, n.x, n.y, n.z));
                    if (old != null) unregister(old);
                    if (n.state == State.DEPLETED) n.respawnAtMillis = System.currentTimeMillis();
                    register(n);
                }
                done.accept(null);
            });
        });
    }

    /** Deletes the node at that block in every given world. */
    public void removeNode(List<String> worlds, int x, int y, int z, Consumer<String> done) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement st = conn.prepareStatement("DELETE FROM mine_nodes WHERE channel_id = ? AND node_id = ?")) {
                for (String w : worlds) {
                    st.setString(1, channelId);
                    st.setString(2, nodeId(w, x, y, z));
                    st.addBatch();
                }
                st.executeBatch();
            } catch (SQLException e) {
                Bukkit.getScheduler().runTask(plugin, () -> done.accept("database error: " + e.getMessage()));
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                for (String w : worlds) {
                    Node n = byBlock.get(new BlockKey(w, x, y, z));
                    if (n != null) unregister(n);
                }
                done.accept(null);
            });
        });
    }

    /** A block position (used by the bulk methods below). */
    public record Pos(int x, int y, int z) {}

    /**
     * Bulk version of {@link #addNode}: every position in every world, one transaction.
     * Used by /dmb (ore veins, blueprints). Calls back on the main thread with an error text or null.
     */
    public void addNodes(List<String> worlds, List<Pos> positions, String oreId, Consumer<String> done) {
        if (positions.isEmpty()) { done.accept(null); return; }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<Node> created = new ArrayList<>();
            try (Connection conn = plugin.getDatabaseManager().getConnection()) {
                conn.setAutoCommit(false);
                try (PreparedStatement st = conn.prepareStatement(
                        "INSERT INTO mine_nodes (channel_id, node_id, dimension_id, block_x, block_y, block_z, generation, state, respawn_at, ore_definition_id)"
                                + " VALUES (?, ?, ?, ?, ?, ?, 0, 'READY', NULL, ?)"
                                + " ON CONFLICT (channel_id, node_id) DO UPDATE SET ore_definition_id = EXCLUDED.ore_definition_id")) {
                    for (String w : worlds) {
                        for (Pos p : positions) {
                            st.setString(1, channelId);
                            st.setString(2, nodeId(w, p.x(), p.y(), p.z()));
                            st.setString(3, w);
                            st.setInt(4, p.x());
                            st.setInt(5, p.y());
                            st.setInt(6, p.z());
                            st.setString(7, oreId);
                            st.addBatch();
                            created.add(new Node(nodeId(w, p.x(), p.y(), p.z()), w, p.x(), p.y(), p.z(), oreId, 0, State.READY, 0L));
                        }
                    }
                    st.executeBatch();
                    conn.commit();
                } catch (SQLException e) {
                    conn.rollback();
                    throw e;
                } finally {
                    conn.setAutoCommit(true);
                }
            } catch (SQLException e) {
                Bukkit.getScheduler().runTask(plugin, () -> done.accept("database error: " + e.getMessage()));
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                for (Node n : created) {
                    Node old = byBlock.get(new BlockKey(n.world, n.x, n.y, n.z));
                    if (old != null) {
                        // re-typed existing node: keep its live state/generation
                        old.oreId = n.oreId;
                        applyVisual(old);
                    } else {
                        register(n);
                    }
                }
                done.accept(null);
            });
        });
    }

    /** Bulk delete of nodes at these positions in every world (undo of /dmb). */
    public void removeNodes(List<String> worlds, List<Pos> positions, Consumer<String> done) {
        if (positions.isEmpty()) { done.accept(null); return; }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement st = conn.prepareStatement("DELETE FROM mine_nodes WHERE channel_id = ? AND node_id = ?")) {
                for (String w : worlds) {
                    for (Pos p : positions) {
                        st.setString(1, channelId);
                        st.setString(2, nodeId(w, p.x(), p.y(), p.z()));
                        st.addBatch();
                    }
                }
                st.executeBatch();
            } catch (SQLException e) {
                Bukkit.getScheduler().runTask(plugin, () -> done.accept("database error: " + e.getMessage()));
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                for (String w : worlds) {
                    for (Pos p : positions) {
                        Node n = byBlock.get(new BlockKey(w, p.x(), p.y(), p.z()));
                        if (n != null) unregister(n);
                    }
                }
                done.accept(null);
            });
        });
    }

    public boolean isNodeAt(String world, int x, int y, int z) {
        return byBlock.containsKey(new BlockKey(world, x, y, z));
    }

    /**
     * Channel 1.3: a channel opened at runtime gets the ore nodes of its channel 1 (positions and ore type,
     * fresh generation). Nodes the target world already has (rows from an earlier opening) are left alone,
     * so their generation keeps matching the DB.
     */
    public void cloneWorldNodes(String fromWorld, String toWorld) {
        Map<String, List<Pos>> byOre = new HashMap<>();
        for (Node n : byBlock.values()) {
            if (!n.world.equals(fromWorld) || isNodeAt(toWorld, n.x, n.y, n.z)) continue;
            byOre.computeIfAbsent(n.oreId, k -> new ArrayList<>()).add(new Pos(n.x, n.y, n.z));
        }
        for (Map.Entry<String, List<Pos>> e : byOre.entrySet()) {
            addNodes(List.of(toWorld), e.getValue(), e.getKey(), err -> {
                if (err != null) plugin.getLogger().warning("[Mining] Copying nodes to " + toWorld + " failed: " + err);
                else plugin.getLogger().info("[Mining] Copied " + e.getValue().size() + " " + e.getKey() + " node(s) to " + toWorld);
            });
        }
    }

    /** Test helper: depleted nodes in a world respawn on the next queue pass. */
    public int respawnNow(String world) {
        int count = 0;
        long now = System.currentTimeMillis();
        for (Node n : byBlock.values()) {
            if (n.world.equals(world) && n.state == State.DEPLETED) {
                respawnQueue.remove(n);
                n.respawnAtMillis = now;
                respawnQueue.add(n);
                count++;
            }
        }
        return count;
    }

    public boolean isNode(Block b) {
        return byBlock.containsKey(new BlockKey(b.getWorld().getName(), b.getX(), b.getY(), b.getZ()));
    }

    private static String nodeId(String world, int x, int y, int z) {
        return world + ":" + x + "_" + y + "_" + z;
    }
}
