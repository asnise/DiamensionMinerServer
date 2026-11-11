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
import java.util.List;
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
        File rootRuntimeDir = new File(Bukkit.getWorldContainer(), runtimeWorldName);
        File dimensionsBaseDir = new File(new File(Bukkit.getWorldContainer(), "overworld_t0"), "dimensions/minecraft");
        File dimensionRuntimeDir = new File(dimensionsBaseDir, runtimeWorldName);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                if (templateDir.exists() && templateDir.isDirectory()) {
                    copyDirectory(templateDir.toPath(), dimensionRuntimeDir.toPath());
                    cleanLocksAndUids(dimensionRuntimeDir);

                    copyDirectory(templateDir.toPath(), rootRuntimeDir.toPath());
                    cleanLocksAndUids(rootRuntimeDir);
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

    private static final int UNLOAD_ATTEMPTS = 5;
    private static final long UNLOAD_RETRY_TICKS = 40L;

    /**
     * Main thread only. Ends the session, moves every player out of the instance world with a
     * synchronous teleport, unloads the world, and deletes its folders only once the unload has
     * actually succeeded (Paper refuses to unload a world that still has players in it).
     * The future completes with true when the folders were deleted.
     */
    public CompletableFuture<Boolean> destroyInstance(String sessionId) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        DungeonInstanceSession session = activeSessions.remove(sessionId);
        if (session == null) {
            future.complete(false);
            return future;
        }

        // Forget everyone mapped to this session, including /dungeon join players who are not party members.
        playerToSession.values().removeIf(sessionId::equals);
        session.cleanup(); // stops timers and pending steps, removes spawned mobs

        World world = session.getWorld();
        if (world != null) {
            for (UUID mId : session.getMembers()) {
                Player p = Bukkit.getPlayer(mId);
                if (p != null && p.isOnline() && p.getWorld().equals(world)) evacuateNow(p);
            }
            for (Player p : List.copyOf(world.getPlayers())) {
                evacuateNow(p);
            }
        }

        unloadThenDelete(world, session.getRuntimeWorldName(), 1, future);
        return future;
    }

    private void unloadThenDelete(World world, String runtimeWorldName, int attempt, CompletableFuture<Boolean> future) {
        if (world != null && Bukkit.getWorld(world.getUID()) != null) {
            for (Player p : List.copyOf(world.getPlayers())) {
                evacuateNow(p);
            }
            if (!Bukkit.unloadWorld(world, false)) {
                if (attempt < UNLOAD_ATTEMPTS) {
                    plugin.getLogger().warning("Instance world " + runtimeWorldName + " did not unload (attempt "
                            + attempt + "/" + UNLOAD_ATTEMPTS + "), retrying.");
                    Bukkit.getScheduler().runTaskLater(plugin,
                            () -> unloadThenDelete(world, runtimeWorldName, attempt + 1, future), UNLOAD_RETRY_TICKS);
                } else {
                    // Leave the files alone: deleting under a loaded world corrupts it and fails on Windows.
                    plugin.getLogger().severe("Instance world " + runtimeWorldName + " could not be unloaded after "
                            + UNLOAD_ATTEMPTS + " attempts; its folder was kept. Remove it after a restart.");
                    future.complete(false);
                }
                return;
            }
        }

        File rootRuntimeDir = new File(Bukkit.getWorldContainer(), runtimeWorldName);
        File dimensionsBaseDir = new File(new File(Bukkit.getWorldContainer(), "overworld_t0"), "dimensions/minecraft");
        File dimensionRuntimeDir = new File(dimensionsBaseDir, runtimeWorldName);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean ok = true;
            for (File dir : new File[]{rootRuntimeDir, dimensionRuntimeDir}) {
                try {
                    deleteDirectory(dir.toPath());
                } catch (IOException e) {
                    ok = false;
                    plugin.getLogger().warning("Could not fully delete " + dir + ": " + e.getMessage());
                }
            }
            future.complete(ok);
        });
    }

    /** Synchronous teleport to the hub, so the player has left before the world is unloaded. */
    private void evacuateNow(Player player) {
        World fallback = Bukkit.getWorld("overworld_t0");
        if (fallback == null) fallback = Bukkit.getWorlds().get(0);
        if (!player.teleport(fallback.getSpawnLocation())) {
            player.kick(net.kyori.adventure.text.Component.text("The dungeon instance closed. Please rejoin."));
        }
    }

    private static void cleanLocksAndUids(File dir) {
        File uidFile = new File(dir, "uid.dat");
        if (uidFile.exists()) uidFile.delete();
        File lockFile = new File(dir, "session.lock");
        if (lockFile.exists()) lockFile.delete();
        File paperMeta = new File(dir, "data/paper/metadata.dat");
        if (paperMeta.exists()) paperMeta.delete();
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
        // try-with-resources: an unclosed Files.walk keeps directory handles open, which blocks deletes on Windows.
        try (java.util.stream.Stream<Path> paths = Files.walk(src)) {
            copyEach(paths, src, dest);
        }
    }

    private static void copyEach(java.util.stream.Stream<Path> paths, Path src, Path dest) {
        paths.forEach(source -> {
            try {
                if (source.getFileName() != null) {
                    String fn = source.getFileName().toString();
                    if (fn.equalsIgnoreCase("session.lock") || fn.equalsIgnoreCase("uid.dat") || fn.equalsIgnoreCase("metadata.dat")) {
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

    /** Deletes the tree; throws if anything could not be removed. */
    private static void deleteDirectory(Path path) throws IOException {
        if (!Files.exists(path)) return;
        List<Path> all;
        try (java.util.stream.Stream<Path> paths = Files.walk(path)) {
            all = paths.sorted(Comparator.reverseOrder()).toList();
        }
        IOException first = null;
        for (Path p : all) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException e) {
                if (first == null) first = e;
            }
        }
        if (first != null) throw first;
    }
}
