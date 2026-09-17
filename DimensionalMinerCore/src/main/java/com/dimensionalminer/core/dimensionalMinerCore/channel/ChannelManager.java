package com.dimensionalminer.core.dimensionalMinerCore.channel;

import com.dimensionalminer.core.dimensionalMinerCore.DimensionalMinerCore;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ChannelManager {

    // Variables/Fields
    private final DimensionalMinerCore plugin;
    private final Map<Integer, String> channelWorlds = new ConcurrentHashMap<>();
    private final int totalChannels = 3;

    // Constructors
    public ChannelManager(DimensionalMinerCore plugin) {
        this.plugin = plugin;
        initChannels();
    }

    // Public Methods
    public void initChannels() {
        // Channel 1 maps to default overworld_t0
        channelWorlds.put(1, "overworld_t0");

        // Provision secondary channels if needed (overworld_t0_ch2, overworld_t0_ch3)
        for (int i = 2; i <= totalChannels; i++) {
            String wName = "overworld_t0_ch" + i;
            channelWorlds.put(i, wName);
            World w = Bukkit.getWorld(wName);
            if (w == null) {
                try {
                    WorldCreator wc = new WorldCreator(wName);
                    w = wc.createWorld();
                    if (w != null) {
                        w.setAutoSave(false);
                        w.setGameRule(org.bukkit.GameRule.KEEP_INVENTORY, true);
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("Could not pre-load channel world: " + wName);
                }
            }
        }
    }

    public int getPlayerChannel(Player player) {
        if (player == null) return 1;
        String wName = player.getWorld().getName();
        for (Map.Entry<Integer, String> entry : channelWorlds.entrySet()) {
            if (entry.getValue().equalsIgnoreCase(wName)) {
                return entry.getKey();
            }
        }
        return 1;
    }

    public boolean switchChannel(Player player, int channelId) {
        if (channelId < 1 || channelId > totalChannels) {
            player.sendMessage(ChatColor.RED + "Invalid channel number (1 - " + totalChannels + ").");
            return false;
        }

        int currentCh = getPlayerChannel(player);
        if (currentCh == channelId) {
            player.sendMessage(ChatColor.YELLOW + "You are already in Channel " + channelId + ".");
            return false;
        }

        String targetWorldName = channelWorlds.get(channelId);
        World targetWorld = Bukkit.getWorld(targetWorldName);
        if (targetWorld == null) {
            // Fallback to default
            targetWorld = Bukkit.getWorld("overworld_t0");
        }

        if (targetWorld == null) {
            player.sendMessage(ChatColor.RED + "Target channel world is currently unavailable.");
            return false;
        }

        // Save inventory before teleporting
        plugin.getInventorySync().snapshotAndMaybeSave(player);

        Location loc = player.getLocation();
        Location targetLoc = new Location(targetWorld, loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());

        player.teleportAsync(targetLoc).thenAccept(success -> {
            if (success) {
                player.sendMessage(ChatColor.GREEN + "[Channel] Successfully switched to Channel " + channelId + "!");
                player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_ENDERMAN_TELEPORT, 0.7f, 1.2f);
            } else {
                player.sendMessage(ChatColor.RED + "[Channel] Teleportation failed.");
            }
        });
        return true;
    }

    public List<ChannelStatus> getChannels() {
        List<ChannelStatus> list = new ArrayList<>();
        for (int i = 1; i <= totalChannels; i++) {
            String wName = channelWorlds.get(i);
            World w = Bukkit.getWorld(wName);
            int count = (w != null) ? w.getPlayerCount() : 0;
            list.add(new ChannelStatus(i, "CH-" + i, count, 50));
        }
        return list;
    }

    public int getTotalChannels() { return totalChannels; }

    public record ChannelStatus(int id, String name, int onlineCount, int maxCount) {}
}
