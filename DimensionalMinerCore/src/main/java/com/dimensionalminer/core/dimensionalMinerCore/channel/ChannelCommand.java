package com.dimensionalminer.core.dimensionalMinerCore.channel;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class ChannelCommand implements CommandExecutor, TabCompleter {

    // Variables/Fields
    private final ChannelManager channelManager;

    // Constructors
    public ChannelCommand(ChannelManager channelManager) {
        this.channelManager = channelManager;
    }

    // Public Methods
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can switch channels.");
            return true;
        }

        if (args.length == 0) {
            int current = channelManager.getPlayerChannel(player);
            player.sendMessage(ChatColor.GOLD + "=== [ Dimensional Miner Channels ] ===");
            for (ChannelManager.ChannelStatus st : channelManager.getChannels()) {
                boolean isCurrent = st.id() == current;
                String prefix = isCurrent ? ChatColor.GREEN + "* " : ChatColor.GRAY + "  ";
                String tag = isCurrent ? ChatColor.GREEN + "[Current]" : ChatColor.YELLOW + "[/ch " + st.id() + "]";
                player.sendMessage(prefix + ChatColor.AQUA + st.name() + ChatColor.WHITE + " - "
                        + ChatColor.GRAY + "Players: " + ChatColor.WHITE + st.onlineCount() + "/" + st.maxCount() + " " + tag);
            }
            player.sendMessage(ChatColor.YELLOW + "Type " + ChatColor.AQUA + "/" + label + " <1-" + channelManager.getTotalChannels() + ">" + ChatColor.YELLOW + " to switch.");
            return true;
        }

        try {
            int targetCh = Integer.parseInt(args[0]);
            channelManager.switchChannel(player, targetCh);
        } catch (NumberFormatException e) {
            player.sendMessage(ChatColor.RED + "Invalid channel number: " + args[0]);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("1", "2", "3");
        }
        return Collections.emptyList();
    }
}
