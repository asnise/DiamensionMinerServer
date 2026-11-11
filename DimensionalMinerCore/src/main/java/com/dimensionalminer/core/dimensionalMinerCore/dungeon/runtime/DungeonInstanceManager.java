package com.dimensionalminer.core.dimensionalMinerCore.dungeon.runtime;

import com.dimensionalminer.core.dimensionalMinerCore.DimensionalMinerCore;
import com.dimensionalminer.core.dimensionalMinerCore.dungeon.DungeonDefinition;
import com.dimensionalminer.core.dimensionalMinerCore.util.WorldFiles;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class DungeonInstanceManager {

    // Variables/Fields
    private static final String HUB_WORLD = "overworld_t0";
    private static final long WATCHDOG_PERIOD_TICKS = 20L * 5;
    private static final long EMPTY_GRACE_MILLIS = 60_000L;
    private static final java.util.regex.Pattern SESSION_SUFFIX = java.util.regex.Pattern.compile("_[0-9a-f]{8}$");

    private final DimensionalMinerCore plugin;
    private final Map<String, DungeonInstanceSession> activeSessions = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerToSession = new ConcurrentHashMap<>();
    /** Parties whose instance is being provisioned right now (blocks a second /dungeon enter). */
    private final Set<UUID> provisioningParties = ConcurrentHashMap.newKeySet();
    /** sessionId -> first time the watchdog saw nobody from the roster inside the instance. */
    private final Map<String, Long> emptySince = new ConcurrentHashMap<>();

    // Constructors
    public DungeonInstanceManager(DimensionalMinerCore plugin) {
        this.plugin = plugin;
        // Watchdog: enforces time_limit_seconds and closes instances nobody is in any more.
        Bukkit.getScheduler().runTaskTimer(plugin, this::watchdogTick, WATCHDOG_PERIOD_TICKS, WATCHDOG_PERIOD_TICKS);
        // One tick after enable (worlds are loaded by then): remove instance folders left by a crash.
        Bukkit.getScheduler().runTask(plugin, this::sweepOrphanInstanceFolders);
    }

    // Public Methods

    /** True while the party has a running instance or one being created. */
    public boolean isPartyBusy(UUID partyId) {
        if (provisioningParties.contains(partyId)) return true;
        for (DungeonInstanceSession s : activeSessions.values()) {
            if (partyId.equals(s.getPartyId())) return true;
        }
        return false;
    }

    public CompletableFuture<DungeonInstanceSession> createDungeonInstance(DungeonDefinition def, UUID partyId, UUID leaderId, Set<UUID> members) {
        CompletableFuture<DungeonInstanceSession> future = new CompletableFuture<>();

        // One run per party, and nobody may be in two runs at once.
        if (isPartyBusy(partyId)) {
            future.completeExceptionally(new IllegalStateException("Your party already has a dungeon running or starting."));
            return future;
        }
        for (UUID m : members) {
            if (playerToSession.containsKey(m)) {
                Player p = Bukkit.getPlayer(m);
                future.completeExceptionally(new IllegalStateException((p != null ? p.getName() : m) + " is already in a dungeon."));
                return future;
            }
        }
        provisioningParties.add(partyId);
        future.whenComplete((s, ex) -> provisioningParties.remove(partyId));

        final Set<UUID> roster = Set.copyOf(members);
        String sessionId = UUID.randomUUID().toString().substring(0, 8);
        String templateName = def.getTemplateMap();
        String runtimeWorldName = templateName + "_" + sessionId;

        File templateDir = new File(Bukkit.getWorldContainer(), templateName);
        File dimensionsBaseDir = new File(new File(Bukkit.getWorldContainer(), HUB_WORLD), "dimensions/minecraft");
        File dimensionRuntimeDir = new File(dimensionsBaseDir, runtimeWorldName);

        // Without the template, WorldCreator would silently generate a random new world.
        if (!templateDir.isDirectory()) {
            future.completeExceptionally(new IllegalStateException("Template map '" + templateName + "' was not found in the server folder."));
            return future;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                // Paper 26.x loads extra worlds as dimensions of the main level
                // (server log: "level 'minecraft:<name>'"), i.e. from
                // <hub>/dimensions/minecraft/<name>. A copy in the server root is never read.
                WorldFiles.copyWorld(templateDir.toPath(), dimensionRuntimeDir.toPath());
            } catch (Exception e) {
                // Do not leave a half-copied folder behind.
                try {
                    WorldFiles.deleteTree(dimensionRuntimeDir.toPath());
                } catch (IOException cleanup) {
                    plugin.getLogger().warning("Could not remove half-copied " + dimensionRuntimeDir + "; it is removed on next start.");
                }
                future.completeExceptionally(new IllegalStateException("Could not copy the template map: " + e.getMessage(), e));
                return;
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

                    DungeonInstanceSession session = new DungeonInstanceSession(plugin, sessionId, def, runtimeWorldName, world, partyId, leaderId, roster);
                    activeSessions.put(sessionId, session);
                    for (UUID mId : roster) {
                        playerToSession.put(mId, sessionId);
                    }

                    broadcastParty(roster, ChatColor.AQUA + "[Dungeon] Entering " + def.getDisplayName()
                            + ChatColor.GRAY + " (time limit " + (def.getTimeLimitSeconds() / 60) + " min)");

                    for (UUID mId : roster) {
                        Player p = Bukkit.getPlayer(mId);
                        if (p != null && p.isOnline()) {
                            p.teleportAsync(world.getSpawnLocation());
                        }
                    }

                    session.start();
                    future.complete(session);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });
        });

        return future;
    }

    /**
     * /dungeon join: only members of the party that owns the run may join (e.g. someone who was
     * offline when it started), the run must not be full, and nobody can be in two runs.
     */
    public boolean joinInstance(Player player, String sessionId) {
        DungeonInstanceSession session = activeSessions.get(sessionId);
        if (session == null || session.isCompleted()) {
            player.sendMessage(ChatColor.RED + "Dungeon instance session not found or already completed.");
            return false;
        }
        UUID playerId = player.getUniqueId();
        String current = playerToSession.get(playerId);
        if (current != null && !current.equals(sessionId)) {
            player.sendMessage(ChatColor.RED + "You are already in another dungeon. Use /exitdungeon first.");
            return false;
        }
        UUID myParty = plugin.getPartyManager().getPartyByPlayer(playerId).map(p -> p.getPartyId()).orElse(null);
        if (!session.getPartyId().equals(myParty)) {
            player.sendMessage(ChatColor.RED + "You can only join a dungeon started by your own party.");
            return false;
        }
        if (!session.getMembers().contains(playerId)
                && session.getMembers().size() >= session.getDefinition().getMaxPlayers()) {
            player.sendMessage(ChatColor.RED + "This dungeon is full (" + session.getDefinition().getMaxPlayers() + " players).");
            return false;
        }

        session.addMember(playerId);
        playerToSession.put(playerId, sessionId);
        player.teleportAsync(session.getWorld().getSpawnLocation());
        player.sendMessage(ChatColor.GREEN + "[Dungeon] Joined instance: " + session.getDefinition().getDisplayName());
        broadcastParty(session.getMembers(), ChatColor.YELLOW + player.getName() + " has entered the dungeon!");
        return true;
    }

    /** /exitdungeon: leaves the run for good (no final rewards, no auto-return on reconnect). */
    public void leaveInstance(Player player) {
        String sessionId = playerToSession.remove(player.getUniqueId());
        if (sessionId != null) {
            DungeonInstanceSession session = activeSessions.get(sessionId);
            if (session != null) {
                session.removeMember(player.getUniqueId());
                broadcastParty(session.getMembers(), ChatColor.YELLOW + player.getName() + " left the dungeon.");
            }
        }
        evacuateToPublicWorld(player);
    }

    /** Called from onDisable: closes every running instance synchronously (schedulers no longer run). */
    public void shutdownAll() {
        for (String sessionId : List.copyOf(activeSessions.keySet())) {
            DungeonInstanceSession session = activeSessions.remove(sessionId);
            if (session == null) continue;
            session.cleanup();
            World world = session.getWorld();
            if (world != null && Bukkit.getWorld(world.getUID()) != null) {
                for (Player p : List.copyOf(world.getPlayers())) evacuateNow(p);
                if (!Bukkit.unloadWorld(world, false)) {
                    plugin.getLogger().warning("Instance " + session.getRuntimeWorldName() + " did not unload at shutdown; it will be removed on next start.");
                    continue;
                }
            }
            deleteInstanceFolders(session.getRuntimeWorldName());
        }
        playerToSession.clear();
    }

    private void watchdogTick() {
        long now = System.currentTimeMillis();
        for (DungeonInstanceSession session : List.copyOf(activeSessions.values())) {
            if (session.isCompleted()) continue; // finishDungeon already scheduled the teardown
            String id = session.getSessionId();

            long limitMillis = session.getDefinition().getTimeLimitSeconds() * 1000L;
            if (limitMillis > 0 && now - session.getStartedAtMillis() > limitMillis) {
                broadcastParty(session.getMembers(), ChatColor.RED + "[Dungeon] Time is up! The instance is closing.");
                emptySince.remove(id);
                destroyInstance(id);
                continue;
            }

            boolean anyoneInside = false;
            for (UUID m : session.getMembers()) {
                Player p = Bukkit.getPlayer(m);
                if (p != null && p.isOnline() && p.getWorld().equals(session.getWorld())) {
                    anyoneInside = true;
                    break;
                }
            }
            if (anyoneInside) {
                emptySince.remove(id);
            } else {
                long since = emptySince.computeIfAbsent(id, k -> now);
                if (now - since > EMPTY_GRACE_MILLIS) {
                    plugin.getLogger().info("[Dungeon] Closing empty instance " + session.getRuntimeWorldName());
                    emptySince.remove(id);
                    destroyInstance(id);
                }
            }
        }
    }

    /** Deletes <template>_<8 hex> folders that no running session owns (left over from a crash or restart). */
    private void sweepOrphanInstanceFolders() {
        Set<String> templates = new java.util.HashSet<>();
        for (DungeonDefinition d : plugin.getContentRegistryManager().getAllDungeons()) templates.add(d.getTemplateMap());
        if (templates.isEmpty()) return;

        File container = Bukkit.getWorldContainer();
        File dims = new File(new File(container, HUB_WORLD), "dimensions/minecraft");
        Set<String> candidates = new java.util.TreeSet<>();
        for (File base : new File[]{container, dims}) {
            File[] children = base.listFiles(File::isDirectory);
            if (children == null) continue;
            for (File f : children) {
                String name = f.getName();
                if (!SESSION_SUFFIX.matcher(name).find()) continue;
                String template = name.substring(0, name.length() - 9);
                if (templates.contains(template)) candidates.add(name);
            }
        }

        for (String name : candidates) {
            if (getSessionByWorldName(name).isPresent()) continue;
            World loaded = Bukkit.getWorld(name);
            if (loaded != null) {
                for (Player p : List.copyOf(loaded.getPlayers())) evacuateNow(p);
                if (!Bukkit.unloadWorld(loaded, false)) {
                    plugin.getLogger().warning("[Dungeon] Leftover instance world " + name + " is loaded and could not be unloaded; skipped.");
                    continue;
                }
            }
            plugin.getLogger().info("[Dungeon] Removing leftover instance folder " + name);
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> deleteInstanceFolders(name));
        }
    }

    /**
     * Deletes an instance's world folder. The server-root path is only checked for leftovers from
     * older builds, which copied the template there as well (unused by Paper 26.x).
     * @return true if everything was removed
     */
    private boolean deleteInstanceFolders(String runtimeWorldName) {
        File dim = new File(new File(new File(Bukkit.getWorldContainer(), HUB_WORLD), "dimensions/minecraft"), runtimeWorldName);
        File legacyRoot = new File(Bukkit.getWorldContainer(), runtimeWorldName);
        boolean ok = true;
        for (File dir : new File[]{dim, legacyRoot}) {
            try {
                WorldFiles.deleteTree(dir.toPath());
            } catch (IOException e) {
                ok = false;
                plugin.getLogger().warning("Could not fully delete " + dir + ": " + e.getMessage());
            }
        }
        return ok;
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
        emptySince.remove(sessionId);
        if (session == null) {
            future.complete(false);
            return future;
        }

        // Forget everyone mapped to this session, including players who joined late.
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

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> future.complete(deleteInstanceFolders(runtimeWorldName)));
    }

    /** Synchronous teleport to the hub, so the player has left before the world is unloaded. */
    private void evacuateNow(Player player) {
        World fallback = Bukkit.getWorld("overworld_t0");
        if (fallback == null) fallback = Bukkit.getWorlds().get(0);
        if (!player.teleport(fallback.getSpawnLocation())) {
            player.kick(net.kyori.adventure.text.Component.text("The dungeon instance closed. Please rejoin."));
        }
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
}
