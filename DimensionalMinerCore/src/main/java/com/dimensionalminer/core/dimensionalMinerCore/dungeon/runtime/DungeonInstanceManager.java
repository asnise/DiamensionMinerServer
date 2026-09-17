package com.dimensionalminer.core.dimensionalMinerCore.dungeon.runtime;

import com.dimensionalminer.core.dimensionalMinerCore.DimensionalMinerCore;
import com.dimensionalminer.core.dimensionalMinerCore.dungeon.DungeonDefinition;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class DungeonInstanceManager {

    // Variables/Fields
    private final DimensionalMinerCore plugin;
    private final Map<String, DungeonInstanceSession> activeSessions = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerToSession = new ConcurrentHashMap<>();

    // Constructors
    public DungeonInstanceManager(DimensionalMinerCore plugin) {
        this.plugin = plugin;
    }

    // Public Methods
    public CompletableFuture<DungeonInstanceSession> createDungeonInstance(DungeonDefinition def, UUID partyId, UUID leaderId, Set<UUID> members) {
        CompletableFuture<DungeonInstanceSession> future = new CompletableFuture<>();

        String sessionId = UUID.randomUUID().toString().substring(0, 8);
        String templateName = def.getTemplateMap();
        String runtimeWorldName = templateName + "_" + sessionId;

        File templateDir = new File(Bukkit.getWorldContainer(), templateName);
        File runtimeDir = new File(Bukkit.getWorldContainer(), runtimeWorldName);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                if (templateDir.exists() && templateDir.isDirectory()) {
                    copyDirectory(templateDir.toPath(), runtimeDir.toPath());
                    File uidFile = new File(runtimeDir, "uid.dat");
                    if (uidFile.exists()) uidFile.delete();
                    File lockFile = new File(runtimeDir, "session.lock");
                    if (lockFile.exists()) lockFile.delete();
                }

                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        WorldCreator wc = new WorldCreator(runtimeWorldName);
                        World world = wc.createWorld();
                        if (world == null) {
                            future.completeExceptionally(new IllegalStateException("Failed to load instance world: " + runtimeWorldName));
                            return;
                        }

                        world.setAutoSave(false);
                        world.setGameRule(org.bukkit.GameRule.KEEP_INVENTORY, true);

                        DungeonInstanceSession session = new DungeonInstanceSession(plugin, sessionId, def, runtimeWorldName, world, partyId, leaderId, members);
                        activeSessions.put(sessionId, session);
                        for (UUID mId : members) {
                            playerToSession.put(mId, sessionId);
                        }

                        // Broadcast debug entry message
                        broadcastParty(members, ChatColor.AQUA + "[Debug] Entered Dungeon - [" + def.getDisplayName() + "] (ID: " + runtimeWorldName + ")");

                        // Teleport members
                        for (UUID mId : members) {
                            Player p = Bukkit.getPlayer(mId);
                            if (p != null && p.isOnline()) {
                                p.teleportAsync(world.getSpawnLocation());
                            }
                        }

                        // Start Step 1
                        session.start();
                        future.complete(session);
                    } catch (Exception e) {
                        future.completeExceptionally(e);
                    }
                });
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        return future;
    }

    public boolean joinInstance(Player player, String sessionId) {
        DungeonInstanceSession session = activeSessions.get(sessionId);
        if (session == null || session.isCompleted()) {
            player.sendMessage(ChatColor.RED + "Dungeon instance session not found or already completed.");
            return false;
        }

        playerToSession.put(player.getUniqueId(), sessionId);
        player.teleportAsync(session.getWorld().getSpawnLocation());
        player.sendMessage(ChatColor.GREEN + "[Dungeon] Joined instance: " + session.getRuntimeWorldName());
        broadcastParty(session.getMembers(), ChatColor.YELLOW + player.getName() + " has entered the dungeon!");
        return true;
    }

    public CompletableFuture<Boolean> destroyInstance(String sessionId) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        DungeonInstanceSession session = activeSessions.remove(sessionId);
        if (session == null) {
            future.complete(false);
            return future;
        }

        World world = session.getWorld();

        for (UUID mId : session.getMembers()) {
            playerToSession.remove(mId);
            Player p = Bukkit.getPlayer(mId);
            if (p != null && p.isOnline()) {
                if (world != null && p.getWorld().equals(world)) {
                    evacuateToPublicWorld(p);
                } else if (isInDungeon(p)) {
                    evacuateToPublicWorld(p);
                }
            }
        }

        session.cleanup();

        if (world != null) {
            Bukkit.unloadWorld(world, false);
        }

        File runtimeDir = new File(Bukkit.getWorldContainer(), session.getRuntimeWorldName());
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                deleteDirectory(runtimeDir.toPath());
            } catch (Exception ignored) {}
            future.complete(true);
        });

        return future;
    }

    public void evacuateToPublicWorld(Player player) {
        World fallback = Bukkit.getWorld("overworld_t0");
        if (fallback != null) {
            player.teleportAsync(fallback.getSpawnLocation());
        }
    }

    public boolean isDungeonWorld(String worldName) {
        if (worldName == null) return false;
        if (getSessionByWorldName(worldName).isPresent()) return true;
        return (worldName.startsWith("ind_") && !worldName.equals("ind_t0")) || worldName.matches("^.+_[a-f0-9]{8}$");
    }

    public boolean isInDungeon(Player player) {
        return player != null && player.isOnline() && isDungeonWorld(player.getWorld().getName());
    }

    public Optional<DungeonInstanceSession> getSession(String sessionId) {
        return Optional.ofNullable(activeSessions.get(sessionId));
    }

    public Optional<DungeonInstanceSession> getSessionByWorldName(String worldName) {
        if (worldName == null) return Optional.empty();
        for (DungeonInstanceSession session : activeSessions.values()) {
            if (worldName.equalsIgnoreCase(session.getRuntimeWorldName())) {
                return Optional.of(session);
            }
        }
        return Optional.empty();
    }

    public Map<String, DungeonInstanceSession> getActiveSessions() {
        return Collections.unmodifiableMap(activeSessions);
    }

    public Optional<DungeonInstanceSession> getSessionByPlayer(UUID playerId) {
        String sId = playerToSession.get(playerId);
        return sId != null ? getSession(sId) : Optional.empty();
    }

    private void broadcastParty(Set<UUID> members, String msg) {
        for (UUID mId : members) {
            Player p = Bukkit.getPlayer(mId);
            if (p != null && p.isOnline()) {
                p.sendMessage(msg);
            }
        }
    }

    private static void copyDirectory(Path src, Path dest) throws IOException {
        Files.walk(src).forEach(source -> {
            try {
                if (source.getFileName() != null && source.getFileName().toString().equalsIgnoreCase("session.lock")) {
                    return;
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
}
