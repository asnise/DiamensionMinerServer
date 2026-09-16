package com.dimensionalminer.core.dimensionalMinerCore;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;

public class BalanceCommand implements CommandExecutor {

    private final DimensionalMinerCore plugin;

    public BalanceCommand(DimensionalMinerCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }

        UUID playerId = player.getUniqueId();
        plugin.getDatabaseManager().ensureAccounts(playerId);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Map<String, Long> balances = plugin.getDatabaseManager().getBalances(playerId);
            long coin = balances.getOrDefault("COIN", 0L);
            long energy = balances.getOrDefault("ENERGY", 0L);

            Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(
                    ChatColor.GOLD + "[DimensionalMiner] "
                            + ChatColor.YELLOW + "COIN " + ChatColor.WHITE + coin
                            + ChatColor.GRAY + "  |  "
                            + ChatColor.YELLOW + "ENERGY " + ChatColor.WHITE + energy));
        });
        return true;
    }
}