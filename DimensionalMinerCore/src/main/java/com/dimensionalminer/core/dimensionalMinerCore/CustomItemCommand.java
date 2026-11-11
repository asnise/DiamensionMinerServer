package com.dimensionalminer.core.dimensionalMinerCore;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public class CustomItemCommand implements CommandExecutor, TabCompleter {

    /** Declared in plugin.yml with default: op. */
    public static final String ADMIN_PERMISSION = "dimensionalminer.admin";

    private final DimensionalMinerCore plugin;

    public CustomItemCommand(DimensionalMinerCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Every /dm subcommand is admin-only: give creates items, reload swaps the registry,
        // list exposes unreleased content IDs.
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use this command.");
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender, label);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("list")) {
            Collection<ItemDefinition> items = plugin.getContentRegistryManager().getAllItems();
            sender.sendMessage(ChatColor.GOLD + "=== [ Dimensional Miner Items (" + items.size() + ") ] ===");
            if (items.isEmpty()) {
                sender.sendMessage(ChatColor.GRAY + "No custom items loaded. Use /" + label + " reload to fetch.");
                return true;
            }
            for (ItemDefinition def : items) {
                sender.sendMessage(ChatColor.YELLOW + "- " + ChatColor.AQUA + def.getId()
                        + ChatColor.GRAY + " (" + def.getDisplay() + ChatColor.GRAY + ") "
                        + ChatColor.DARK_GRAY + "[" + def.getCategory() + " / " + def.getRarity() + "]");
            }
            return true;
        }

        if (sub.equals("reload")) {
            sender.sendMessage(ChatColor.YELLOW + "[ContentRegistry] Reloading item definitions...");
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                plugin.getContentRegistryManager().loadAll();
                sender.sendMessage(ChatColor.GREEN + "[ContentRegistry] Successfully reloaded "
                        + plugin.getContentRegistryManager().getAllItems().size() + " item definitions!");
            });
            return true;
        }

        if (sub.equals("give")) {
            if (args.length < 3) {
                sender.sendMessage(ChatColor.RED + "Usage: /" + label + " give <player> <item_id> [amount]");
                return true;
            }

            Player target = Bukkit.getPlayer(args[1]);
            if (target == null || !target.isOnline()) {
                sender.sendMessage(ChatColor.RED + "Player not found or offline: " + args[1]);
                return true;
            }

            String itemId = args[2].toLowerCase(Locale.ROOT);
            int amount = 1;
            if (args.length >= 4) {
                try {
                    amount = Math.max(1, Integer.parseInt(args[3]));
                } catch (NumberFormatException e) {
                    sender.sendMessage(ChatColor.RED + "Invalid amount number: " + args[3]);
                    return true;
                }
            }

            ItemStack stack = plugin.getContentRegistryManager().createItemStack(itemId, amount);
            target.getInventory().addItem(stack);
            sender.sendMessage(ChatColor.GREEN + "Gave " + amount + "x " + ChatColor.AQUA + itemId + ChatColor.GREEN + " to " + target.getName());
            target.sendMessage(ChatColor.GREEN + "You received " + amount + "x " + stack.getItemMeta().getDisplayName());
            return true;
        }

        sendHelp(sender, label);
        return true;
    }

    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage(ChatColor.GOLD + "=== [ Dimensional Miner Content Commands ] ===");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " list " + ChatColor.GRAY + "- List all custom items in server");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " give <player> <item_id> [amount] " + ChatColor.GRAY + "- Give custom item");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " reload " + ChatColor.GRAY + "- Reload items from DB and JSON");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            return filter(Arrays.asList("list", "give", "reload"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            return null; // Player names
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            List<String> ids = plugin.getContentRegistryManager().getAllItems().stream()
                    .map(ItemDefinition::getId)
                    .collect(Collectors.toList());
            return filter(ids, args[2]);
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("give")) {
            return Arrays.asList("1", "16", "32", "64");
        }
        return Collections.emptyList();
    }

    private List<String> filter(List<String> list, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        return list.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(p)).collect(Collectors.toList());
    }
}
