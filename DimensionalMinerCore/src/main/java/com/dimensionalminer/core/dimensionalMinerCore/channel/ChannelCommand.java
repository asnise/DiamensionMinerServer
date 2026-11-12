package com.dimensionalminer.core.dimensionalMinerCore.channel;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;

public class ChannelCommand implements CommandExecutor, TabCompleter {

    private final ChannelManager channelManager;

    public ChannelCommand(ChannelManager channelManager) {
        this.channelManager = channelManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can switch channels.");
            return true;
        }

        if (args.length == 0) {
            int current = channelManager.getPlayerChannel(player);
            player.sendMessage(ChatColor.GOLD + "=== [ Dimensional Miner Channels ] ===");
            for (ChannelManager.ChannelStatus st : channelManager.getChannels(player)) {
                boolean isCurrent = st.id() == current;
                boolean full = st.onlineCount() >= st.maxCount();
                String prefix = isCurrent ? ChatColor.GREEN + "* " : ChatColor.GRAY + "  ";
                String tag = !st.loaded() ? ChatColor.DARK_GRAY + "[Opening...]"
                        : isCurrent ? ChatColor.GREEN + "[Current]"
                        : full ? ChatColor.RED + "[Full]"
                        : ChatColor.YELLOW + "[/ch " + st.id() + "]";
                player.sendMessage(prefix + ChatColor.AQUA + st.name() + ChatColor.WHITE + " - "
                        + ChatColor.GRAY + "Players: " + (full ? ChatColor.RED : ChatColor.WHITE) + st.onlineCount() + "/" + st.maxCount() + " " + tag);
            }
            player.sendMessage(ChatColor.YELLOW + "Type " + ChatColor.AQUA + "/" + label + " <number>" + ChatColor.YELLOW
                    + " to switch. A new channel opens by itself when all are full.");
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
        if (args.length == 1 && sender instanceof Player p) {
            return channelManager.getChannels(p).stream().map(s -> String.valueOf(s.id()))
                    .filter(s -> s.startsWith(args[0])).toList();
        }
        return Collections.emptyList();
    }
}
