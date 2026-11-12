package com.dimensionalminer.core.dimensionalMinerCore.mining;

import com.dimensionalminer.core.dimensionalMinerCore.CustomItemCommand;
import com.dimensionalminer.core.dimensionalMinerCore.DimensionalMinerCore;
import org.bukkit.ChatColor;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * /mine (admin): place and inspect mining nodes by looking at a block.
 * <pre>
 * /mine types                   ore types loaded from content_registry.json
 * /mine node add &lt;ore_type&gt;     make the looked-at block a node (every channel of the hub map)
 * /mine node remove             delete the node you look at (every channel)
 * /mine info                    state / generation of the node you look at
 * /mine list [radius]           nodes around you (default 32)
 * /mine respawn                 respawn depleted nodes in this world now (testing)
 * </pre>
 */
public class MineCommand implements CommandExecutor, TabCompleter {

    private static final int TARGET_RANGE = 8;

    private final DimensionalMinerCore plugin;
    private final MineNodeManager nodes;

    public MineCommand(DimensionalMinerCore plugin, MineNodeManager nodes) {
        this.plugin = plugin;
        this.nodes = nodes;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(CustomItemCommand.ADMIN_PERMISSION)) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use this command.");
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only (you need to look at a block).");
            return true;
        }
        if (!nodes.isLoaded()) {
            player.sendMessage(ChatColor.RED + "[Mine] Nodes are still loading (or the DB is down). Try again in a moment.");
            return true;
        }

        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "help";
        switch (sub) {
            case "types" -> {
                var types = plugin.getContentRegistryManager().getAllOreTypes();
                player.sendMessage(ChatColor.GOLD + "=== Ore types (" + types.size() + ") ===");
                for (OreType t : types) {
                    player.sendMessage(ChatColor.AQUA + t.id() + ChatColor.GRAY + " · " + t.displayName()
                            + " · " + t.block() + " -> " + t.depletedBlock() + " · respawn " + t.respawnSeconds()
                            + "s · pickaxe " + OreType.ToolTier.name(t.requiredToolTier()) + "+ · loot " + t.lootTableId());
                }
                if (types.isEmpty()) player.sendMessage(ChatColor.GRAY + "None. Add OreType + LootTable content, then /dm reload.");
            }
            case "node" -> handleNode(player, args);
            case "info" -> {
                Block b = target(player);
                if (b == null) return true;
                String d = nodes.describe(b);
                player.sendMessage(d == null ? ChatColor.GRAY + "[Mine] That block is not a node." : ChatColor.GREEN + "[Mine] " + d);
            }
            case "list" -> {
                int radius = 32;
                if (args.length > 1) {
                    try { radius = Math.max(1, Math.min(256, Integer.parseInt(args[1]))); } catch (NumberFormatException ignored) {
                        player.sendMessage(ChatColor.RED + "Radius must be a number.");
                        return true;
                    }
                }
                Map<String, Integer> counts = nodes.countNear(player.getLocation(), radius);
                player.sendMessage(ChatColor.GOLD + "=== Nodes within " + radius + " blocks ===");
                if (counts.isEmpty()) player.sendMessage(ChatColor.GRAY + "None.");
                counts.forEach((k, v) -> player.sendMessage(ChatColor.YELLOW + "- " + k + ": " + v));
            }
            case "respawn" -> {
                int n = nodes.respawnNow(player.getWorld().getName());
                player.sendMessage(ChatColor.GREEN + "[Mine] " + n + " depleted node(s) in this world will respawn within a second.");
            }
            default -> help(player, label);
        }
        return true;
    }

    private void handleNode(Player player, String[] args) {
        String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        Block b = target(player);
        if (b == null) return;
        List<String> worlds = familyOf(player.getWorld().getName());

        if (action.equals("add")) {
            if (args.length < 3) {
                player.sendMessage(ChatColor.RED + "Usage: /mine node add <ore_type>");
                return;
            }
            OreType ore = plugin.getContentRegistryManager().getOreType(args[2]);
            if (ore == null) {
                player.sendMessage(ChatColor.RED + "Unknown ore type '" + args[2] + "'. See /mine types.");
                return;
            }
            nodes.addNode(worlds, b.getX(), b.getY(), b.getZ(), ore.id(), err -> player.sendMessage(err == null
                    ? ChatColor.GREEN + "[Mine] Node " + ore.id() + " at " + b.getX() + " " + b.getY() + " " + b.getZ()
                      + " added in " + worlds.size() + " world(s): " + String.join(", ", worlds)
                    : ChatColor.RED + "[Mine] Failed: " + err));
        } else if (action.equals("remove")) {
            if (!nodes.isNode(b)) {
                player.sendMessage(ChatColor.GRAY + "[Mine] That block is not a node.");
                return;
            }
            nodes.removeNode(worlds, b.getX(), b.getY(), b.getZ(), err -> player.sendMessage(err == null
                    ? ChatColor.GREEN + "[Mine] Node removed from " + worlds.size() + " world(s). The block stays as it is."
                    : ChatColor.RED + "[Mine] Failed: " + err));
        } else {
            player.sendMessage(ChatColor.RED + "Usage: /mine node <add <ore_type>|remove>");
        }
    }

    /** A node placed in a tier map (hub included) is placed in every channel copy of it; other worlds stand alone. */
    private List<String> familyOf(String world) {
        return plugin.getChannelManager() != null ? plugin.getChannelManager().familyOf(world) : List.of(world);
    }

    private Block target(Player player) {
        Block b = player.getTargetBlockExact(TARGET_RANGE);
        if (b == null || b.getType().isAir()) {
            player.sendMessage(ChatColor.RED + "[Mine] Look at a block (within " + TARGET_RANGE + " blocks).");
            return null;
        }
        return b;
    }

    private void help(Player p, String label) {
        p.sendMessage(ChatColor.GOLD + "=== /" + label + " (mining nodes) ===");
        p.sendMessage(ChatColor.YELLOW + "/" + label + " types" + ChatColor.GRAY + " - ore types");
        p.sendMessage(ChatColor.YELLOW + "/" + label + " node add <ore_type>" + ChatColor.GRAY + " - make the block you look at a node");
        p.sendMessage(ChatColor.YELLOW + "/" + label + " node remove" + ChatColor.GRAY + " - delete the node you look at");
        p.sendMessage(ChatColor.YELLOW + "/" + label + " info" + ChatColor.GRAY + " - node state / generation");
        p.sendMessage(ChatColor.YELLOW + "/" + label + " list [radius]" + ChatColor.GRAY + " - nodes around you");
        p.sendMessage(ChatColor.YELLOW + "/" + label + " respawn" + ChatColor.GRAY + " - respawn depleted nodes here now");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(CustomItemCommand.ADMIN_PERMISSION)) return Collections.emptyList();
        if (args.length == 1) return filter(List.of("types", "node", "info", "list", "respawn"), args[0]);
        if (args.length == 2 && args[0].equalsIgnoreCase("node")) return filter(List.of("add", "remove"), args[1]);
        if (args.length == 3 && args[0].equalsIgnoreCase("node") && args[1].equalsIgnoreCase("add")) {
            List<String> ids = new ArrayList<>();
            for (OreType t : plugin.getContentRegistryManager().getAllOreTypes()) ids.add(t.id());
            return filter(ids, args[2]);
        }
        return Collections.emptyList();
    }

    private static List<String> filter(List<String> list, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        return list.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(p)).collect(Collectors.toList());
    }
}
