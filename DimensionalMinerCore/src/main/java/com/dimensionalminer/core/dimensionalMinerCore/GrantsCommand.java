package com.dimensionalminer.core.dimensionalMinerCore;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /grants: opens the mailbox. All claim logic lives in {@link GrantsMenu}. */
public class GrantsCommand implements CommandExecutor {

    private final DimensionalMinerCore plugin;

    public GrantsCommand(DimensionalMinerCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "This command can only be executed by players.");
            return true;
        }
        GrantsMenu.open(plugin, player);
        return true;
    }
}
