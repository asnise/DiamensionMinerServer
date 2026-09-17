package com.dimensionalminer.core.dimensionalMinerCore.channel;

import com.dimensionalminer.core.dimensionalMinerCore.DimensionalMinerCore;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ChannelManager {

    private final DimensionalMinerCore plugin;
    private final Map<Integer, String> channelWorlds = new ConcurrentHashMap<>();
    private final int totalChannels = 3;

    public ChannelManager(DimensionalMinerCore plugin) {
        this.plugin = plugin;
        initChannels();
    }

    public void initChannels() {
        channelWorlds.put(1, "overworld_t0");

        World mainWorld = Bukkit.getWorld("overworld_t0");
        File worldContainer = Bukkit.getWorldContainer();
        File templateDimensionDir = new File(new File(worldContainer, "overworld_t0"), "dimensions/minecraft/overworld");
        if (!templateDimensionDir.exists() || !templateDimensionDir.isDirectory()) {
            templateDimensionDir = new File(new File(worldContainer.getParentFile(), "Map/OverWorld_t0/overworld_t0"), "dimensions/minecraft/overworld");
        }

        for (int i = 2; i <= totalChannels; i++) {
            String wName = "overworld_t0_ch" + i;
            channelWorlds.put(i, wName);

            File targetDimensionDir = new File(new File(worldContainer, "overworld_t0"), "dimensions/minecraft/" + wName);
            File targetRegionDir = new File(targetDimensionDir, "region");

            if (!targetRegionDir.exists() || targetRegionDir.list() == null || targetRegionDir.list().length < 5) {
                if (templateDimensionDir.exists() && templateDimensionDir.isDirectory()) {
                    try {
                        if (targetDimensionDir.exists()) {
                            deleteDirectory(targetDimensionDir.toPath());
                        }
                        copyDirectory(templateDimensionDir.toPath(), targetDimensionDir.toPath());
                        cleanLocksAndUids(targetDimensionDir);
                        plugin.getLogger().info("Cloned map template for channel: " + wName);
                    } catch (Exception e) {
                        plugin.getLogger().severe("Failed to clone map template for channel " + wName + ": " + e.getMessage());
                    }
                }
            }

            World w = Bukkit.getWorld(wName);
            if (w == null) {
                try {
                    WorldCreator wc = new WorldCreator(wName);
                    if (mainWorld != null) {
                        wc.copy(mainWorld);
                    }
                    w = wc.createWorld();
                    if (w != null) {
                        w.setAutoSave(false);
                        w.setGameRule(org.bukkit.GameRule.KEEP_INVENTORY, true);
                        if (mainWorld != null) {
                            w.setSpawnLocation(mainWorld.getSpawnLocation());
                        }
                    } else {
                        plugin.getLogger().severe("WorldCreator returned null for channel world: " + wName);
                    }
                } catch (Exception e) {
                    plugin.getLogger().log(java.util.logging.Level.SEVERE, "Could not pre-load channel world: " + wName, e);
                }
            }
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

    public record ChannelStatus(int id, String name, int onlineCount, int maxCount) {}

    private static void copyDirectory(Path src, Path dest) throws IOException {
        Files.walk(src).forEach(source -> {
            try {
                if (source.getFileName() != null) {
                    String name = source.getFileName().toString();
                    if (name.equalsIgnoreCase("session.lock") ||
                            name.equalsIgnoreCase("uid.dat") ||
                            name.equalsIgnoreCase("metadata.dat")) {
                        return;
                    }
                }
                Path destination = dest.resolve(src.relativize(source));
                if (Files.isDirectory(source)) {
                    if (!Files.exists(destination)) Files.createDirectories(destination);
                } else {
                    Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static void deleteDirectory(Path path) throws IOException {
        if (!Files.exists(path)) return;
        Files.walk(path)
                .sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {}
                });
    }

    private static void cleanLocksAndUids(File dir) {
        File uidFile = new File(dir, "uid.dat");
        if (uidFile.exists()) uidFile.delete();
        File lockFile = new File(dir, "session.lock");
        if (lockFile.exists()) lockFile.delete();
        File paperMeta = new File(dir, "data/paper/metadata.dat");
        if (paperMeta.exists()) paperMeta.delete();
    }
}
