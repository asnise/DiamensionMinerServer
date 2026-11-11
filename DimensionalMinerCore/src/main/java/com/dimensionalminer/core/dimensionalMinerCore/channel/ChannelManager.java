package com.dimensionalminer.core.dimensionalMinerCore.channel;

import com.dimensionalminer.core.dimensionalMinerCore.DimensionalMinerCore;
import com.dimensionalminer.core.dimensionalMinerCore.util.WorldFiles;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ChannelManager {

    private static final String HUB_WORLD = "overworld_t0";
    /** Written into a channel folder once a clone finished, so a crash mid-copy is redone next start. */
    private static final String CLONE_MARKER = ".dm_channel_clone";

    private final DimensionalMinerCore plugin;
    private final Map<Integer, String> channelWorlds = new ConcurrentHashMap<>();
    private final int totalChannels = 3;

    public ChannelManager(DimensionalMinerCore plugin) {
        this.plugin = plugin;
        initChannels();
    }

    /**
     * Channel 1 is the hub world. Channels 2..N are copies of the hub's overworld dimension, cloned
     * on an async thread (a whole dimension copy would freeze startup) and then loaded on the main
     * thread. A channel is unavailable (/channel says so) until its world has loaded.
     */
    public void initChannels() {
        channelWorlds.put(1, HUB_WORLD);
        File dimensions = new File(new File(Bukkit.getWorldContainer(), HUB_WORLD), "dimensions/minecraft");
        File template = new File(dimensions, "overworld");

        for (int i = 2; i <= totalChannels; i++) {
            String wName = HUB_WORLD + "_ch" + i;
            channelWorlds.put(i, wName);

            if (Bukkit.getWorld(wName) != null) {
                continue; // already loaded (e.g. after /reload): never touch a loaded world's files
            }

            File target = new File(dimensions, wName);
            boolean needsClone = !new File(target, CLONE_MARKER).isFile();
            if (needsClone && !template.isDirectory()) {
                plugin.getLogger().severe("Channel template " + template + " not found; channel " + i + " is disabled.");
                continue;
            }

            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                if (needsClone) {
                    try {
                        WorldFiles.deleteTree(target.toPath());
                        WorldFiles.copyWorld(template.toPath(), target.toPath());
                        Files.writeString(new File(target, CLONE_MARKER).toPath(), "cloned from " + template.getName());
                        plugin.getLogger().info("Cloned map template for channel: " + wName);
                    } catch (IOException e) {
                        plugin.getLogger().severe("Failed to clone map template for channel " + wName + ": " + e.getMessage());
                        return;
                    }
                }
                Bukkit.getScheduler().runTask(plugin, () -> loadChannelWorld(wName));
            });
        }
    }

    private void loadChannelWorld(String wName) {
        if (Bukkit.getWorld(wName) != null) return;
        World mainWorld = Bukkit.getWorld(HUB_WORLD);
        try {
            WorldCreator wc = new WorldCreator(wName);
            if (mainWorld != null) {
                wc.copy(mainWorld);
            }
            World w = wc.createWorld();
            if (w == null) {
                plugin.getLogger().severe("WorldCreator returned null for channel world: " + wName);
                return;
            }
            w.setAutoSave(false);
            w.setGameRule(org.bukkit.GameRule.KEEP_INVENTORY, true);
            if (mainWorld != null) {
                w.setSpawnLocation(mainWorld.getSpawnLocation());
            }
        } catch (Exception e) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Could not load channel world: " + wName, e);
        }
    }

    public int getPlayerChannel(Player player) {
        if (player == null) return -1;
        String wName = player.getWorld().getName();
        for (Map.Entry<Integer, String> entry : channelWorlds.entrySet()) {
            if (entry.getValue().equalsIgnoreCase(wName)) {
                return entry.getKey();
            }
        }
        return -1;
    }

    public boolean switchChannel(Player player, int channelId) {
        if (channelId < 1 || channelId > totalChannels) {
            player.sendMessage(ChatColor.RED + "Invalid channel number (1 - " + totalChannels + ").");
            return false;
        }

        if (plugin.getDungeonInstanceManager() != null && plugin.getDungeonInstanceManager().isInDungeon(player)) {
            player.sendMessage(ChatColor.RED + "You cannot switch channels while inside a dungeon! Please use /exitdungeon first.");
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
            player.sendMessage(ChatColor.RED + "[Channel] Channel " + channelId + " is currently unavailable.");
            return false;
        }

        plugin.getInventorySync().snapshotAndMaybeSave(player);

        Location loc = player.getLocation();
        Location targetLoc;
        if (currentCh != -1) {
            targetLoc = new Location(targetWorld, loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
        } else {
            targetLoc = targetWorld.getSpawnLocation();
        }

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

    /** World names of all channels of the hub map (channel 1 = hub world), in channel order. */
    public List<String> getChannelWorldNames() {
        List<String> names = new ArrayList<>();
        for (int i = 1; i <= totalChannels; i++) {
            String n = channelWorlds.get(i);
            if (n != null) names.add(n);
        }
        return names;
    }

    public record ChannelStatus(int id, String name, int onlineCount, int maxCount) {}

}
