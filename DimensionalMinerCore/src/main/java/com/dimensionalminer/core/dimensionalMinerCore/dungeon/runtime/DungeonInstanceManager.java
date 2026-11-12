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
    /** instance_runs / run_members and the entry-cost escrow (6.1 / 6.2). */
    private final DungeonRunStore runs;
    /** False until runs left open by the last stop have been settled (refund / close). */
    private volatile boolean recovered = false;

    // Constructors
    public DungeonInstanceManager(DimensionalMinerCore plugin) {
        this.plugin = plugin;
        this.runs = new DungeonRunStore(plugin);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            runs.recover();
            recovered = true;
        });
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

    /**
     * Opens a run for the leader's party (6.1 / 6.2):
     * <ol>
     *   <li>main thread: checks, then the key items of the entry cost are taken from the leader's inventory;</li>
     *   <li>async: one transaction records the run (RESERVED) with its escrow and charges the currency cost;</li>
     *   <li>async: the template is copied (LOADING); main thread: the world loads, the party goes in (ACTIVE,
     *       cost consumed).</li>
     * </ol>
     * If anything fails before ACTIVE the cost is refunded: items back to the inventory (mailbox if the
     * leader went offline), currency back through the ledger.
     */
    public CompletableFuture<DungeonInstanceSession> createDungeonInstance(DungeonDefinition def, UUID partyId, Player leader, Set<UUID> members) {
        CompletableFuture<DungeonInstanceSession> future = new CompletableFuture<>();
        UUID leaderId = leader.getUniqueId();

        if (!recovered) {
            future.completeExceptionally(new IllegalStateException("The dungeon system is still starting up, try again in a moment."));
            return future;
        }
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
        if (members.size() > def.getMaxPlayers()) {
            future.completeExceptionally(new IllegalStateException("Your party has " + members.size() + " players; this dungeon allows "
                    + def.getMaxPlayers() + "."));
            return future;
        }

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

        // ---- entry cost (6.2): the leader pays. Key items are taken now and held until the run starts.
        Map<String, Integer> keyItems = new java.util.LinkedHashMap<>();
        Map<String, Long> currencies = new java.util.LinkedHashMap<>();
        for (DungeonDefinition.EntryCost c : def.getEntryCost()) {
            if (c.isItem()) keyItems.merge(c.item(), (int) c.amount(), Integer::sum);
            else currencies.merge(c.currency(), c.amount(), Long::sum);
        }
        var craft = plugin.getCraftService();
        for (Map.Entry<String, Integer> k : keyItems.entrySet()) {
            int have = craft.count(leader, k.getKey());
            if (have < k.getValue()) {
                future.completeExceptionally(new IllegalStateException("You need " + k.getValue() + "x " + craft.displayName(k.getKey())
                        + " to open this dungeon (you have " + have + ")."));
                return future;
            }
        }
        final List<org.bukkit.inventory.ItemStack> taken = keyItems.isEmpty() ? List.of() : craft.take(leader, keyItems);
        if (!taken.isEmpty()) craft.persist(leader);

        provisioningParties.add(partyId);
        future.whenComplete((s, ex) -> provisioningParties.remove(partyId));
        final UUID runId = UUID.randomUUID();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            DungeonRunStore.ReserveResult res = runs.reserve(runId, def, partyId, leaderId, roster, keyItems, currencies);
            if (res.status() != DungeonRunStore.Reserve.OK) {
                // Nothing was written: hand the key items straight back.
                Bukkit.getScheduler().runTask(plugin, () -> giveBack(leaderId, taken));
                future.completeExceptionally(new IllegalStateException(res.status() == DungeonRunStore.Reserve.NO_FUNDS
                        ? "Not enough " + res.shortCurrency() + " (you have " + res.balance() + ", need " + currencies.get(res.shortCurrency()) + ")."
                        : "The run could not be recorded (database error). Nothing was charged."));
                return;
            }
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
                refundRun(runId, leaderId, taken, future, "Could not copy the template map: " + e.getMessage());
                return;
            }
            runs.markLoading(runId);

            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    WorldCreator wc = new WorldCreator(runtimeWorldName);
                    World world = wc.createWorld();
                    if (world == null) {
                        refundRun(runId, leaderId, taken, future, "Failed to load instance world: " + runtimeWorldName);
                        return;
                    }

                    world.setAutoSave(false);
                    world.setGameRule(org.bukkit.GameRule.KEEP_INVENTORY, true);

                    DungeonInstanceSession session = new DungeonInstanceSession(plugin, sessionId, runId, def, runtimeWorldName, world, partyId, leaderId, roster);
                    activeSessions.put(sessionId, session);
                    for (UUID mId : roster) {
                        playerToSession.put(mId, sessionId);
                    }
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> runs.markActive(runId));

                    broadcastParty(roster, ChatColor.AQUA + "[Dungeon] Entering " + def.getDisplayName()
                            + ChatColor.GRAY + " (time limit " + (def.getTimeLimitSeconds() / 60) + " min)");
                    if (!def.getEntryCost().isEmpty()) {
                        broadcastParty(roster, ChatColor.GRAY + "[Dungeon] Entry paid by " + leader.getName() + ": " + describeEntryCost(def));
                    }

                    for (UUID mId : roster) {
                        Player p = Bukkit.getPlayer(mId);
                        if (p != null && p.isOnline()) {
                            p.teleportAsync(world.getSpawnLocation());
                        }
                    }

                    session.start();
                    future.complete(session);
                } catch (Exception e) {
                    refundRun(runId, leaderId, taken, future, e.getMessage());
                }
            });
        });

        return future;
    }

    private String describeCost(DungeonDefinition.EntryCost c) {
        return c.isItem() ? c.amount() + "x " + plugin.getCraftService().displayName(c.item()) : c.amount() + " " + c.currency();
    }

    /** "1x Training Key, 100 COIN": the entry cost of a dungeon for chat ("free" if none). */
    public String describeEntryCost(DungeonDefinition def) {
        if (def.getEntryCost().isEmpty()) return "free";
        return String.join(", ", def.getEntryCost().stream().map(this::describeCost).toList());
    }

    /**
     * Any thread. The run never started: refund it in the DB, then give back the held key items.
     * If the DB refund fails the items are kept back on purpose: the run stays RESERVED and the next
     * start refunds it by mail ({@link DungeonRunStore#recover}), so nothing is paid back twice.
     */
    private void refundRun(UUID runId, UUID leaderId, List<org.bukkit.inventory.ItemStack> taken,
                           CompletableFuture<DungeonInstanceSession> future, String why) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean ok = runs.refund(runId, false);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (ok) giveBack(leaderId, taken);
                future.completeExceptionally(new IllegalStateException(why + (ok
                        ? " The entry cost was refunded."
                        : " The refund could not be recorded now; it is retried (by mailbox) on the next server start.")));
            });
        });
    }

    /** Main thread. Held key items back to the leader (inventory, or the mailbox if offline). */
    private void giveBack(UUID leaderId, List<org.bukkit.inventory.ItemStack> taken) {
        if (taken.isEmpty()) return;
        var craft = plugin.getCraftService();
        Player p = Bukkit.getPlayer(leaderId);
        if (p != null && p.isOnline()) {
            craft.give(p, taken);
            craft.persist(p);
        } else {
            craft.returnOffline(leaderId, taken, "DUNGEON_KEY_REFUND");
        }
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
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> runs.memberJoined(session.getRunId(), playerId));
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
                UUID pid = player.getUniqueId();
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> runs.memberLeft(session.getRunId(), pid));
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
            runs.close(session.getRunId(), session.getOutcome() != null ? session.getOutcome() : "ABORTED"); // blocking: schedulers are stopped
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
                destroyInstance(id, "FAILED");
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
                    destroyInstance(id, "FAILED");
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
        return destroyInstance(sessionId, "ABORTED");
    }

    /**
     * @param outcome recorded on the run unless the session already has one (a clear = SUCCEEDED):
     *                FAILED for time up / everyone gone, ABORTED for an admin destroy
     */
    public CompletableFuture<Boolean> destroyInstance(String sessionId, String outcome) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        DungeonInstanceSession session = activeSessions.remove(sessionId);
        emptySince.remove(sessionId);
        if (session == null) {
            future.complete(false);
            return future;
        }
        String finalOutcome = session.getOutcome() != null ? session.getOutcome() : outcome;
        UUID runId = session.getRunId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> runs.close(runId, finalOutcome));

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

    public DungeonRunStore getRunStore() { return runs; }

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
