package com.dimensionalminer.core.dimensionalMinerCore.build;

import com.dimensionalminer.core.dimensionalMinerCore.DimensionalMinerCore;
import com.dimensionalminer.core.dimensionalMinerCore.mining.MineNodeManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Applies world edits for /dmb: per-player selections, a tick-budgeted apply queue, an undo stack,
 * and saving. All on the main thread.
 *
 * <p>Every world on this server runs with autoSave=false (maps are treated as stateless), so an edit
 * is lost on restart unless saved. Each finished /dmb operation therefore saves the worlds it touched.
 *
 * <p>Edits in the hub map are mirrored to all of its channel worlds (same as mining nodes), so the
 * channels stay identical. Blocks that are mining nodes are never overwritten by an edit.
 */
public final class BuildManager {

    public static final int MAX_BLOCKS = 1_000_000;
    private static final int BLOCKS_PER_TICK = 6_000;
    private static final int UNDO_DEPTH = 5;

    /** One block change in a plan: absolute position in the "base" world, mirrored to the family. */
    public record Change(int x, int y, int z, BlockData data) {}

    /** A planned edit: block changes plus mining nodes to create afterwards. */
    public record Plan(String label, List<Change> changes, Map<String, List<MineNodeManager.Pos>> nodesByOre,
                       Map<String, int[]> markers) {
        public int nodeCount() { return nodesByOre.values().stream().mapToInt(List::size).sum(); }
    }

    private record Prior(String world, int x, int y, int z, BlockData data) {}

    private record UndoEntry(String label, List<Prior> blocks, List<String> worlds,
                             Map<String, List<MineNodeManager.Pos>> nodesByOre) {}

    public static final class Selection {
        public String world;
        public int[] pos1;
        public int[] pos2;

        public boolean complete() { return world != null && pos1 != null && pos2 != null; }

        public int[] min() { return new int[]{Math.min(pos1[0], pos2[0]), Math.min(pos1[1], pos2[1]), Math.min(pos1[2], pos2[2])}; }

        public int[] max() { return new int[]{Math.max(pos1[0], pos2[0]), Math.max(pos1[1], pos2[1]), Math.max(pos1[2], pos2[2])}; }

        public long volume() {
            int[] a = min(), b = max();
            return (long) (b[0] - a[0] + 1) * (b[1] - a[1] + 1) * (b[2] - a[2] + 1);
        }
    }

    private final DimensionalMinerCore plugin;
    private final MineNodeManager nodes;
    private final Map<UUID, Selection> selections = new HashMap<>();
    private final Map<UUID, Deque<UndoEntry>> undo = new HashMap<>();
    private final Map<UUID, BukkitTask> running = new HashMap<>();

    public BuildManager(DimensionalMinerCore plugin, MineNodeManager nodes) {
        this.plugin = plugin;
        this.nodes = nodes;
    }

    public Selection selection(Player p) {
        return selections.computeIfAbsent(p.getUniqueId(), k -> new Selection());
    }

    public boolean isBusy(Player p) {
        return running.containsKey(p.getUniqueId());
    }

    /** The hub map's channel worlds if the world is one of them, otherwise just the world itself. */
    public List<String> familyOf(String world) {
        List<String> channels = plugin.getChannelManager() != null ? plugin.getChannelManager().getChannelWorldNames() : List.of();
        return channels.contains(world) ? channels : List.of(world);
    }

    /**
     * Applies a plan to the player's world (and its channel copies) over several ticks, then creates
     * the plan's mining nodes, records undo, and saves the touched worlds.
     */
    public void apply(Player player, String baseWorld, Plan plan) {
        UUID id = player.getUniqueId();
        List<String> worlds = familyOf(baseWorld).stream().filter(w -> Bukkit.getWorld(w) != null).toList();
        if (worlds.isEmpty()) {
            player.sendMessage(Component.text("[Build] World not loaded.", NamedTextColor.RED));
            return;
        }

        // Expand to every world of the family; never touch mining-node blocks.
        List<Object[]> work = new ArrayList<>(plan.changes().size() * worlds.size());
        for (String w : worlds) {
            for (Change c : plan.changes()) {
                if (nodes.isNodeAt(w, c.x(), c.y(), c.z())) continue;
                work.add(new Object[]{w, c});
            }
        }
        List<Prior> priors = new ArrayList<>(work.size());
        final int total = work.size();
        player.sendMessage(Component.text("[Build] " + plan.label() + ": " + total + " block(s) in " + worlds.size()
                + " world(s) " + worlds + (plan.nodeCount() > 0 ? ", " + plan.nodeCount() + " node(s)" : "") + "...", NamedTextColor.YELLOW));

        int[] index = {0};
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int end = Math.min(total, index[0] + BLOCKS_PER_TICK);
            for (int i = index[0]; i < end; i++) {
                String w = (String) work.get(i)[0];
                Change c = (Change) work.get(i)[1];
                World world = Bukkit.getWorld(w);
                if (world == null) continue;
                Block b = world.getBlockAt(c.x(), c.y(), c.z());
                BlockData before = b.getBlockData();
                if (before.equals(c.data())) continue;
                priors.add(new Prior(w, c.x(), c.y(), c.z(), before));
                b.setBlockData(c.data(), false);
            }
            index[0] = end;
            if (end < total) {
                if (end % (BLOCKS_PER_TICK * 10) == 0 && player.isOnline()) {
                    player.sendActionBar(Component.text("[Build] " + end + " / " + total, NamedTextColor.GRAY));
                }
                return;
            }
            BukkitTask self = running.remove(id);
            if (self != null) self.cancel();
            finish(player, plan, worlds, priors);
        }, 1L, 1L);
        running.put(id, task);
    }

    private void finish(Player player, Plan plan, List<String> worlds, List<Prior> priors) {
        Map<String, List<MineNodeManager.Pos>> nodesByOre = plan.nodesByOre();
        int[] pending = {nodesByOre.size()};
        StringBuilder errors = new StringBuilder();
        Runnable done = () -> {
            pushUndo(player.getUniqueId(), new UndoEntry(plan.label(), priors, worlds, nodesByOre));
            saveWorlds(worlds);
            if (!player.isOnline()) return;
            player.sendMessage(Component.text("[Build] Done: " + priors.size() + " block(s) changed"
                    + (plan.nodeCount() > 0 ? ", " + plan.nodeCount() + " mining node(s) per world" : "")
                    + ". Saved " + worlds.size() + " world(s). /dmb undo to revert.", NamedTextColor.GREEN));
            if (errors.length() > 0) player.sendMessage(Component.text("[Build] Node errors: " + errors, NamedTextColor.RED));
            for (Map.Entry<String, int[]> m : plan.markers().entrySet()) {
                int[] p = m.getValue();
                player.sendMessage(Component.text("[Build] marker " + m.getKey() + " = " + p[0] + " " + p[1] + " " + p[2], NamedTextColor.AQUA));
            }
        };
        if (nodesByOre.isEmpty()) { done.run(); return; }
        for (Map.Entry<String, List<MineNodeManager.Pos>> e : nodesByOre.entrySet()) {
            nodes.addNodes(worlds, e.getValue(), e.getKey(), err -> {
                if (err != null) errors.append(e.getKey()).append(": ").append(err).append(' ');
                if (--pending[0] == 0) done.run();
            });
        }
    }

    /** Reverts the player's last /dmb operation (blocks and the nodes it created). */
    public void undo(Player player) {
        Deque<UndoEntry> stack = undo.get(player.getUniqueId());
        if (stack == null || stack.isEmpty()) {
            player.sendMessage(Component.text("[Build] Nothing to undo.", NamedTextColor.GRAY));
            return;
        }
        UndoEntry e = stack.pop();
        int restored = 0;
        for (int i = e.blocks().size() - 1; i >= 0; i--) {
            Prior p = e.blocks().get(i);
            World w = Bukkit.getWorld(p.world());
            if (w == null) continue;
            w.getBlockAt(p.x(), p.y(), p.z()).setBlockData(p.data(), false);
            restored++;
        }
        List<MineNodeManager.Pos> all = new ArrayList<>();
        e.nodesByOre().values().forEach(all::addAll);
        final int blocks = restored;
        nodes.removeNodes(e.worlds(), all, err -> {
            saveWorlds(e.worlds());
            player.sendMessage(Component.text("[Build] Undid \"" + e.label() + "\": " + blocks + " block(s) restored"
                    + (all.isEmpty() ? "" : ", " + all.size() + " node(s) removed per world")
                    + (err == null ? "" : " (node error: " + err + ")") + ".", err == null ? NamedTextColor.GREEN : NamedTextColor.RED));
        });
    }

    private void pushUndo(UUID id, UndoEntry entry) {
        Deque<UndoEntry> stack = undo.computeIfAbsent(id, k -> new ArrayDeque<>());
        stack.push(entry);
        while (stack.size() > UNDO_DEPTH) stack.removeLast();
    }

    public void saveWorlds(List<String> worlds) {
        for (String w : worlds) {
            World world = Bukkit.getWorld(w);
            if (world != null) world.save();
        }
    }

    /** Builds an ordered change map helper: later writes to the same block win. */
    public static Map<Long, Change> newChangeMap() {
        return new LinkedHashMap<>();
    }

    public static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }
}
