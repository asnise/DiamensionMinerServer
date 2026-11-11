package com.dimensionalminer.core.dimensionalMinerCore.dungeon;

import com.dimensionalminer.core.dimensionalMinerCore.DimensionalMinerCore;
import com.dimensionalminer.core.dimensionalMinerCore.dungeon.runtime.DungeonInstanceManager;
import com.dimensionalminer.core.dimensionalMinerCore.dungeon.runtime.DungeonInstanceSession;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.Optional;

public class DungeonEventListener implements Listener {

    // Variables/Fields
    private final DimensionalMinerCore plugin;
    private final DungeonInstanceManager instanceManager;
    private final NamespacedKey mobIdKey;
    private final NamespacedKey dummyIdKey;

    // Constructors
    public DungeonEventListener(DimensionalMinerCore plugin, DungeonInstanceManager instanceManager) {
        this.plugin = plugin;
        this.instanceManager = instanceManager;
        this.mobIdKey = new NamespacedKey(plugin, "dungeon_mob_id");
        this.dummyIdKey = new NamespacedKey(plugin, "dungeon_dummy_id");
    }

    // Public Methods
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerInteractDummy(org.bukkit.event.player.PlayerInteractAtEntityEvent event) {
        org.bukkit.entity.Entity entity = event.getRightClicked();
        if (!entity.getPersistentDataContainer().has(dummyIdKey, PersistentDataType.STRING)) return;

        String dummyId = entity.getPersistentDataContainer().get(dummyIdKey, PersistentDataType.STRING);
        instanceManager.getSessionByWorldName(entity.getWorld().getName()).ifPresent(session -> {
            session.onDummyInteracted(dummyId);
            event.setCancelled(true);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (!entity.getPersistentDataContainer().has(mobIdKey, PersistentDataType.STRING)) return;

        String mobId = entity.getPersistentDataContainer().get(mobIdKey, PersistentDataType.STRING);
        instanceManager.getSessionByWorldName(entity.getWorld().getName()).ifPresent(session -> {
            session.onMobKilled(mobId);
        });
    }

    // The party lock during a run lives in PartyManager (checking command text here could be
    // bypassed with /dimensionalminercore:party ...).

    /** Dying inside a run respawns you at the instance spawn instead of stranding you in the hub. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerRespawn(org.bukkit.event.player.PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        instanceManager.getSessionByPlayer(player.getUniqueId()).ifPresent(session -> {
            org.bukkit.World w = session.getWorld();
            if (!session.isCompleted() && w != null && org.bukkit.Bukkit.getWorld(w.getUID()) != null) {
                event.setRespawnLocation(w.getSpawnLocation());
            }
        });
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerSpawnLocation(org.spigotmc.event.player.PlayerSpawnLocationEvent event) {
        org.bukkit.Location loc = event.getSpawnLocation();
        org.bukkit.World world = loc.getWorld();
        if (world == null || instanceManager.isDungeonWorld(world.getName())) {
            org.bukkit.World hub = org.bukkit.Bukkit.getWorld("overworld_t0");
            if (hub != null) {
                event.setSpawnLocation(hub.getSpawnLocation());
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (instanceManager.isInDungeon(player)) {
            org.bukkit.World hub = org.bukkit.Bukkit.getWorld("overworld_t0");
            if (hub != null) {
                player.teleport(hub.getSpawnLocation());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Optional<DungeonInstanceSession> sessionOpt = instanceManager.getSessionByPlayer(player.getUniqueId());

        if (sessionOpt.isPresent() && !sessionOpt.get().isCompleted()) {
            DungeonInstanceSession session = sessionOpt.get();
            org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    player.teleportAsync(session.getWorld().getSpawnLocation());
                    player.sendMessage("§a[Dungeon] Reconnected to active dungeon session: " + session.getRuntimeWorldName());
                }
            }, 5L);
        } else {
            if (instanceManager.isInDungeon(player)) {
                instanceManager.evacuateToPublicWorld(player);
                player.sendMessage("§e[Dungeon] Your previous dungeon session has concluded. Returned to safety.");
            }
        }
    }
}
