package com.dimensionalminer.core.dimensionalMinerCore.dungeon.runtime;

import com.dimensionalminer.core.dimensionalMinerCore.DimensionalMinerCore;
import com.dimensionalminer.core.dimensionalMinerCore.dungeon.DungeonDefinition;
import com.dimensionalminer.core.dimensionalMinerCore.dungeon.DungeonStep;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class DungeonInstanceSession {

    // Variables/Fields
    private final DimensionalMinerCore plugin;
    private final String sessionId;
    private final DungeonDefinition definition;
    private final String runtimeWorldName;
    private final World world;
    private final UUID partyId;
    private final UUID leaderId;
    private final Set<UUID> members;

    private int currentStepIndex = 0;
    private BukkitTask currentIntervalTask;
    private final Map<String, Integer> killCounts = new ConcurrentHashMap<>();
    private final List<LivingEntity> spawnedEntities = new ArrayList<>();
    private BossBar activeBossBar;
    private boolean completed = false;
    /** True from the moment a step's goal is met until the next step starts; blocks double advances. */
    private boolean advancing = false;
    private BukkitTask currentGoalTask;
    private int stepHoldProgress = 0;

    // Constructors
    public DungeonInstanceSession(DimensionalMinerCore plugin, String sessionId,
                                  DungeonDefinition definition, String runtimeWorldName,
                                  World world, UUID partyId, UUID leaderId, Set<UUID> members) {
        this.plugin = plugin;
        this.sessionId = sessionId;
        this.definition = definition;
        this.runtimeWorldName = runtimeWorldName;
        this.world = world;
        this.partyId = partyId;
        this.leaderId = leaderId;
        this.members = members;
    }

    // Public Methods
    public void start() {
        if (definition.getSteps().isEmpty()) {
            finishDungeon();
            return;
        }
        runStep(0);
    }

    public void runStep(int index) {
        if (completed) return; // destroyed or finished while the step delay was pending
        if (index >= definition.getSteps().size()) {
            finishDungeon();
            return;
        }

        this.currentStepIndex = index;
        this.advancing = false;
        killCounts.clear();
        stepHoldProgress = 0;
        if (currentGoalTask != null) currentGoalTask.cancel();

        DungeonStep step = definition.getSteps().get(index);

        // 1. Execute on_start actions
        for (JsonObject act : step.getOnStartActions()) {
            executeAction(act);
        }

        // 2. Start on_current interval task if configured
        if (currentIntervalTask != null) currentIntervalTask.cancel();
        if (step.getOnCurrentConfig() != null && step.getOnCurrentConfig().has("actions")) {
            int intervalSec = step.getOnCurrentConfig().has("interval_seconds")
                    ? step.getOnCurrentConfig().get("interval_seconds").getAsInt() : 15;
            JsonArray actArr = step.getOnCurrentConfig().getAsJsonArray("actions");

            currentIntervalTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                if (completed) return;
                for (JsonElement el : actArr) {
                    if (el.isJsonObject()) executeAction(el.getAsJsonObject());
                }
            }, 20L * intervalSec, 20L * intervalSec);
        }

        // 3. Goal Timer/Tick Listener for HOLD_ZONE and SURVIVE_TIME
        JsonObject goal = step.getGoalConfig();
        if (goal.has("type")) {
            String gType = goal.get("type").getAsString();
            if (gType.equalsIgnoreCase("SURVIVE_TIME")) {
                int totalSec = goal.has("duration_seconds") ? goal.get("duration_seconds").getAsInt() : 60;
                currentGoalTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                    if (completed) return;
                    stepHoldProgress++;
                    int remaining = totalSec - stepHoldProgress;
                    if (remaining > 0) {
                        broadcastActionBar(ChatColor.YELLOW + "Survive: " + remaining + "s remaining");
                    } else {
                        broadcastActionBar(ChatColor.GREEN + "Wave survived!");
                        advanceStep();
                    }
                }, 20L, 20L);
            } else if (gType.equalsIgnoreCase("HOLD_ZONE")) {
                JsonObject center = goal.has("center") ? goal.getAsJsonObject("center") : new JsonObject();
                double cx = center.has("x") ? center.get("x").getAsDouble() : 0;
                double cy = center.has("y") ? center.get("y").getAsDouble() : 64;
                double cz = center.has("z") ? center.get("z").getAsDouble() : 0;
                double radius = goal.has("radius") ? goal.get("radius").getAsDouble() : 5.0;
                double rSq = radius * radius;
                int reqHold = goal.has("required_hold_seconds") ? goal.get("required_hold_seconds").getAsInt() : 20;

                currentGoalTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                    if (completed) return;
                    boolean anyoneInZone = false;
                    for (UUID mId : members) {
                        Player p = Bukkit.getPlayer(mId);
                        if (p != null && p.getWorld().equals(world)) {
                            double dx = p.getLocation().getX() - cx;
                            double dy = p.getLocation().getY() - cy;
                            double dz = p.getLocation().getZ() - cz;
                            if ((dx * dx + dy * dy + dz * dz) <= rSq) {
                                anyoneInZone = true;
                                break;
                            }
                        }
                    }

                    if (anyoneInZone) {
                        stepHoldProgress++;
                        broadcastActionBar(ChatColor.GREEN + "Capturing Zone: [" + stepHoldProgress + "/" + reqHold + "s]");
                        if (stepHoldProgress >= reqHold) {
                            broadcastActionBar(ChatColor.GOLD + "Zone Secured!");
                            advanceStep();
                        }
                    } else {
                        if (stepHoldProgress > 0) {
                            stepHoldProgress = Math.max(0, stepHoldProgress - 1);
                            broadcastActionBar(ChatColor.RED + "Contested / Out of Zone! [" + stepHoldProgress + "/" + reqHold + "s]");
                        }
                    }
                }, 20L, 20L);
            }
        }
    }

    public void onMobKilled(String mobId) {
        if (completed || advancing || currentStepIndex >= definition.getSteps().size()) return;
        DungeonStep step = definition.getSteps().get(currentStepIndex);
        JsonObject goal = step.getGoalConfig();

        if (goal.has("type")) {
            String type = goal.get("type").getAsString();
            if (type.equalsIgnoreCase("KILL_MOBS")) {
                String targetId = goal.has("target_mob_id") ? goal.get("target_mob_id").getAsString() : "";
                if (targetId.equalsIgnoreCase(mobId)) {
                    int count = killCounts.merge(mobId, 1, Integer::sum);
                    int targetCount = goal.has("target_count") ? goal.get("target_count").getAsInt() : 1;
                    String label = goal.has("display_text") ? goal.get("display_text").getAsString() : "Defeat " + targetId;
                    broadcastActionBar(ChatColor.YELLOW + label + " [" + count + "/" + targetCount + "]");
                    if (count >= targetCount) {
                        advanceStep();
                    }
                }
            } else if (type.equalsIgnoreCase("KILL_BOSS")) {
                String targetBoss = goal.has("boss_id") ? goal.get("boss_id").getAsString() : "";
                if (targetBoss.equalsIgnoreCase(mobId)) {
                    if (activeBossBar != null) {
                        for (UUID mId : members) {
                            Player p = Bukkit.getPlayer(mId);
                            if (p != null) p.hideBossBar(activeBossBar);
                        }
                    }
                    advanceStep();
                }
            }
        }
    }

    /**
     * Ends the current step exactly once: several mobs dying in the same tick, or a goal timer
     * firing again, must not run on_end twice or skip the following step.
     */
    public void advanceStep() {
        if (completed || advancing) return;
        advancing = true;

        if (currentIntervalTask != null) currentIntervalTask.cancel();
        if (currentGoalTask != null) currentGoalTask.cancel();
        DungeonStep step = definition.getSteps().get(currentStepIndex);

        // Execute on_end
        for (JsonObject act : step.getOnEndActions()) {
            executeAction(act);
        }

        // Advance to the step after the one that just ended (captured now, not when the delay fires)
        final int nextIndex = currentStepIndex + 1;
        Bukkit.getScheduler().runTaskLater(plugin, () -> runStep(nextIndex), 40L); // 2 second delay
    }

    public void onDummyInteracted(String dummyId) {
        if (completed || advancing || currentStepIndex >= definition.getSteps().size()) return;
        DungeonStep step = definition.getSteps().get(currentStepIndex);
        JsonObject goal = step.getGoalConfig();

        if (goal.has("type") && goal.get("type").getAsString().equalsIgnoreCase("INTERACT_DUMMY")) {
            String targetDummy = goal.has("dummy_id") ? goal.get("dummy_id").getAsString() : "";
            if (targetDummy.equalsIgnoreCase(dummyId)) {
                broadcastActionBar(ChatColor.GREEN + "Activated switch: " + targetDummy + "!");
                advanceStep();
            }
        }
    }

    public void finishDungeon() {
        if (completed) return; // rewards and the auto-destroy must only be scheduled once
        this.completed = true;
        if (currentIntervalTask != null) currentIntervalTask.cancel();
        if (currentGoalTask != null) currentGoalTask.cancel();

        broadcastTitle("§a§lVICTORY!", "§6Dungeon Cleared Successfully!");
        broadcastParty(ChatColor.GOLD + "========================================");
        broadcastParty(ChatColor.GREEN + "[Dungeon] Cleared: " + definition.getDisplayName());
        broadcastParty(ChatColor.YELLOW + "Final rewards have been delivered to your /grants mailbox!");
        broadcastParty(ChatColor.GOLD + "========================================");

        // Distribute final rewards to PostgreSQL reward_grants
        distributeFinalRewards();

        // 30s auto evacuation
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            plugin.getDungeonInstanceManager().destroyInstance(sessionId);
        }, 20L * 30);
    }

    public void cleanup() {
        // Pending delayed work (next step, delayed actions) checks this flag and stops.
        this.completed = true;
        if (currentIntervalTask != null) currentIntervalTask.cancel();
        if (currentGoalTask != null) currentGoalTask.cancel();
        if (activeBossBar != null) {
            for (UUID mId : members) {
                Player p = Bukkit.getPlayer(mId);
                if (p != null) p.hideBossBar(activeBossBar);
            }
        }
        for (LivingEntity e : spawnedEntities) {
            if (e != null && e.isValid()) e.remove();
        }
    }

    // Private Methods
    private void executeAction(JsonObject act) {
        if (!act.has("action")) return;
        long delayTicks = act.has("delay_ticks") ? act.get("delay_ticks").getAsLong() : 0L;
        if (delayTicks > 0) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!completed) processActionLogic(act);
            }, delayTicks);
        } else {
            processActionLogic(act);
        }
    }

    private void processActionLogic(JsonObject act) {
        String actionType = act.get("action").getAsString();

        switch (actionType.toUpperCase(Locale.ROOT)) {
            case "BROADCAST_TITLE": {
                String title = act.has("title") ? ChatColor.translateAlternateColorCodes('&', act.get("title").getAsString()) : "";
                String sub = act.has("subtitle") ? ChatColor.translateAlternateColorCodes('&', act.get("subtitle").getAsString()) : "";
                broadcastTitle(title, sub);
                break;
            }
            case "BROADCAST_MESSAGE": {
                String msg = act.has("message") ? ChatColor.translateAlternateColorCodes('&', act.get("message").getAsString()) : "";
                broadcastParty(msg);
                break;
            }
            case "BROADCAST_ACTIONBAR": {
                String msg = act.has("message") ? ChatColor.translateAlternateColorCodes('&', act.get("message").getAsString()) : "";
                broadcastActionBar(msg);
                break;
            }
            case "SUMMON_MOB": {
                String mobId = act.has("mob_id") ? act.get("mob_id").getAsString() : "crawler";
                String vType = act.has("vanilla_type") ? act.get("vanilla_type").getAsString() : "ZOMBIE";
                double x = act.has("x") ? act.get("x").getAsDouble() : 0;
                double y = act.has("y") ? act.get("y").getAsDouble() : 64;
                double z = act.has("z") ? act.get("z").getAsDouble() : 0;
                int amount = act.has("amount") ? act.get("amount").getAsInt() : 1;
                Location loc = new Location(world, x, y, z);
                spawnMob(mobId, vType, loc, amount);
                break;
            }
            case "SUMMON_GROUP": {
                String mobId = act.has("mob_id") ? act.get("mob_id").getAsString() : "crawler";
                String vType = act.has("vanilla_type") ? act.get("vanilla_type").getAsString() : "ZOMBIE";
                JsonObject center = act.has("center") ? act.getAsJsonObject("center") : new JsonObject();
                double cx = center.has("x") ? center.get("x").getAsDouble() : 0;
                double cy = center.has("y") ? center.get("y").getAsDouble() : 64;
                double cz = center.has("z") ? center.get("z").getAsDouble() : 0;
                double radius = act.has("radius") ? act.get("radius").getAsDouble() : 5.0;
                int amount = act.has("amount") ? act.get("amount").getAsInt() : 4;
                for (int i = 0; i < amount; i++) {
                    double ox = (Math.random() - 0.5) * 2 * radius;
                    double oz = (Math.random() - 0.5) * 2 * radius;
                    Location loc = new Location(world, cx + ox, cy, cz + oz);
                    spawnMob(mobId, vType, loc, 1);
                }
                break;
            }
            case "SUMMON_BOSS": {
                String bossId = act.has("boss_id") ? act.get("boss_id").getAsString() : "boss";
                String vType = act.has("vanilla_type") ? act.get("vanilla_type").getAsString() : "WITHER_SKELETON";
                String name = act.has("name") ? ChatColor.translateAlternateColorCodes('&', act.get("name").getAsString()) : "Boss";
                double hp = act.has("max_health") ? act.get("max_health").getAsDouble() : 200;
                double x = act.has("x") ? act.get("x").getAsDouble() : 0;
                double y = act.has("y") ? act.get("y").getAsDouble() : 64;
                double z = act.has("z") ? act.get("z").getAsDouble() : 0;
                Location loc = new Location(world, x, y, z);
                spawnBoss(bossId, vType, name, hp, loc);
                break;
            }
            case "GIVE_BONUS": {
                if (act.has("items") && act.get("items").isJsonArray()) {
                    for (JsonElement el : act.getAsJsonArray("items")) {
                        if (!el.isJsonObject()) continue;
                        JsonObject itemObj = el.getAsJsonObject();
                        String itId = itemObj.has("item") ? itemObj.get("item").getAsString() : "iron_nugget";
                        int qty = itemObj.has("qty") ? itemObj.get("qty").getAsInt() : 1;
                        for (UUID mId : members) {
                            Player p = Bukkit.getPlayer(mId);
                            if (p != null && p.isOnline()) {
                                ItemStack st = plugin.getContentRegistryManager() != null && plugin.getContentRegistryManager().hasItem(itId)
                                        ? plugin.getContentRegistryManager().createItemStack(itId, qty)
                                        : new ItemStack(Material.IRON_NUGGET, qty);
                                p.getInventory().addItem(st);
                                p.sendMessage(ChatColor.GREEN + "[Bonus] Received: " + qty + "x " + itId);
                            }
                        }
                    }
                }
                break;
            }
            case "SPAWN_DUMMY": {
                String dummyId = act.has("dummy_id") ? act.get("dummy_id").getAsString() : "dummy_1";
                String name = act.has("name") ? ChatColor.translateAlternateColorCodes('&', act.get("name").getAsString()) : "§e[ Ancient Switch ]";
                double x = act.has("x") ? act.get("x").getAsDouble() : 0;
                double y = act.has("y") ? act.get("y").getAsDouble() : 64;
                double z = act.has("z") ? act.get("z").getAsDouble() : 0;
                Location loc = new Location(world, x, y, z);
                spawnDummy(dummyId, name, loc);
                break;
            }
            case "DESPAWN_DUMMY": {
                String dummyId = act.has("dummy_id") ? act.get("dummy_id").getAsString() : "";
                removeDummy(dummyId);
                break;
            }
        }
    }

    private void spawnDummy(String dummyId, String displayName, Location loc) {
        if (loc.getWorld() == null) return;
        org.bukkit.entity.ArmorStand stand = (org.bukkit.entity.ArmorStand) loc.getWorld().spawnEntity(loc, EntityType.ARMOR_STAND);
        stand.setVisible(false);
        stand.setGravity(false);
        stand.setCustomName(displayName);
        stand.setCustomNameVisible(true);
        stand.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(plugin, "dungeon_dummy_id"),
                org.bukkit.persistence.PersistentDataType.STRING, dummyId);
        spawnedEntities.add(stand);
    }

    private void removeDummy(String dummyId) {
        if (dummyId.isEmpty()) return;
        org.bukkit.NamespacedKey key = new org.bukkit.NamespacedKey(plugin, "dungeon_dummy_id");
        spawnedEntities.removeIf(e -> {
            if (e != null && e.isValid()) {
                String val = e.getPersistentDataContainer().get(key, org.bukkit.persistence.PersistentDataType.STRING);
                if (dummyId.equalsIgnoreCase(val)) {
                    e.remove();
                    return true;
                }
            }
            return false;
        });
    }

    private void spawnMob(String mobId, String vanillaType, Location loc, int count) {
        EntityType et = EntityType.ZOMBIE;
        try { et = EntityType.valueOf(vanillaType.toUpperCase(Locale.ROOT)); } catch (Exception ignored) {}
        for (int i = 0; i < count; i++) {
            if (loc.getWorld() == null) continue;
            org.bukkit.entity.Entity e = loc.getWorld().spawnEntity(loc, et);
            if (e instanceof LivingEntity le) {
                le.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(plugin, "dungeon_mob_id"),
                        org.bukkit.persistence.PersistentDataType.STRING, mobId);
                spawnedEntities.add(le);
            }
        }
    }

    private void spawnBoss(String bossId, String vanillaType, String displayName, double maxHp, Location loc) {
        EntityType et = EntityType.WITHER_SKELETON;
        try { et = EntityType.valueOf(vanillaType.toUpperCase(Locale.ROOT)); } catch (Exception ignored) {}
        if (loc.getWorld() == null) return;
        org.bukkit.entity.Entity e = loc.getWorld().spawnEntity(loc, et);
        if (e instanceof LivingEntity le) {
            le.setCustomName(displayName);
            le.setCustomNameVisible(true);
            if (le.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH) != null) {
                le.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).setBaseValue(maxHp);
                le.setHealth(maxHp);
            }
            le.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(plugin, "dungeon_mob_id"),
                    org.bukkit.persistence.PersistentDataType.STRING, bossId);
            spawnedEntities.add(le);

            // Bind BossBar
            activeBossBar = BossBar.bossBar(Component.text(displayName), 1.0f, BossBar.Color.PURPLE, BossBar.Overlay.PROGRESS);
            for (UUID mId : members) {
                Player p = Bukkit.getPlayer(mId);
                if (p != null) p.showBossBar(activeBossBar);
            }
        }
    }

    private void distributeFinalRewards() {
        JsonObject rewardsObj = definition.getFinalRewards();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            for (UUID mId : members) {
                try {
                    UUID accountId = plugin.getDatabaseManager().getAccountId(mId);
                    String grantSql = "INSERT INTO reward_grants (grant_id, player_id, source_key, payload, state) VALUES (?, ?, ?, ?::jsonb, 'AVAILABLE');";
                    try (Connection conn = plugin.getDatabaseManager().getConnection();
                         PreparedStatement stmt = conn.prepareStatement(grantSql)) {
                        stmt.setObject(1, UUID.randomUUID());
                        stmt.setObject(2, accountId);
                        stmt.setString(3, "dungeon_reward:" + sessionId + ":" + mId);

                        // Build payload
                        JsonObject payload = new JsonObject();
                        JsonArray itemsArr = new JsonArray();
                        if (rewardsObj.has("guaranteed") && rewardsObj.get("guaranteed").isJsonArray()) {
                            for (JsonElement el : rewardsObj.getAsJsonArray("guaranteed")) {
                                if (el.isJsonObject() && el.getAsJsonObject().has("item")) {
                                    itemsArr.add(el.getAsJsonObject());
                                }
                            }
                        }
                        payload.add("items", itemsArr);
                        stmt.setString(4, payload.toString());
                        stmt.executeUpdate();
                    }
                } catch (SQLException e) {
                    plugin.getLogger().warning("Failed to grant dungeon rewards: " + e.getMessage());
                }
            }
        });
    }

    private void broadcastParty(String msg) {
        for (UUID mId : members) {
            Player p = Bukkit.getPlayer(mId);
            if (p != null && p.isOnline()) p.sendMessage(msg);
        }
    }

    private void broadcastActionBar(String msg) {
        for (UUID mId : members) {
            Player p = Bukkit.getPlayer(mId);
            if (p != null && p.isOnline()) p.sendActionBar(Component.text(msg));
        }
    }

    private void broadcastTitle(String title, String subtitle) {
        Title t = Title.title(Component.text(title), Component.text(subtitle),
                Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(3), Duration.ofMillis(500)));
        for (UUID mId : members) {
            Player p = Bukkit.getPlayer(mId);
            if (p != null && p.isOnline()) p.showTitle(t);
        }
    }

    public String getSessionId() { return sessionId; }
    public String getRuntimeWorldName() { return runtimeWorldName; }
    public World getWorld() { return world; }
    public Set<UUID> getMembers() { return members; }
    public UUID getLeaderId() { return leaderId; }
    public DungeonDefinition getDefinition() { return definition; }
    public int getCurrentStepIndex() { return currentStepIndex; }
    public boolean isCompleted() { return completed; }
}
