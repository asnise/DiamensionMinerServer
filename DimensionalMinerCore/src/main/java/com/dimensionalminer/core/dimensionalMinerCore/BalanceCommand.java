package com.dimensionalminer.core.dimensionalMinerCore;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

/** /balance: shows COIN and ENERGY. All DB work runs off the main thread. */
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

        UUID minecraftId = player.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Map<String, Long> balances;
            try {
                UUID accountId = plugin.getDatabaseManager().getAccountId(minecraftId);
                balances = plugin.getDatabaseManager().getBalances(accountId);
            } catch (SQLException e) {
                plugin.getLogger().warning("/balance failed for " + player.getName() + ": " + e.getMessage());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) player.sendMessage(ChatColor.RED + "[DimensionalMiner] Could not load your balance. Try again later.");
                });
                return;
            }
            long coin = balances.getOrDefault("COIN", 0L);
            long energy = balances.getOrDefault("ENERGY", 0L);

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                player.sendMessage(ChatColor.GOLD + "[DimensionalMiner] "
                        + ChatColor.YELLOW + "COIN " + ChatColor.WHITE + coin
                        + ChatColor.GRAY + "  |  "
                        + ChatColor.YELLOW + "ENERGY " + ChatColor.WHITE + energy);
            });
        });
        return true;
    }
}
