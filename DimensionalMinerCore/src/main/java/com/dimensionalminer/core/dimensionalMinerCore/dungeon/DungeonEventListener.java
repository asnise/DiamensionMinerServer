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
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
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

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPartyCommandPreprocess(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        String msg = event.getMessage().trim().toLowerCase();

        if (msg.startsWith("/party leave") || msg.startsWith("/p leave")
                || msg.startsWith("/party disband") || msg.startsWith("/p disband")
                || msg.startsWith("/party kick") || msg.startsWith("/p kick")) {

            if (instanceManager.isInDungeon(player)) {
                if (player.isOp()) return; // OP bypass

                event.setCancelled(true);
                player.sendMessage("§c[Party Lock] You cannot leave or modify your party while inside a dungeon!");
                player.sendMessage("§eUse §b/exitdungeon§e to return to the public world first.");
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Optional<DungeonInstanceSession> sessionOpt = instanceManager.getSessionByPlayer(player.getUniqueId());

        if (sessionOpt.isPresent() && !sessionOpt.get().isCompleted()) {
            DungeonInstanceSession session = sessionOpt.get();
            player.teleportAsync(session.getWorld().getSpawnLocation());
            player.sendMessage("§a[Dungeon] Reconnected to active dungeon session: " + session.getRuntimeWorldName());
        } else {
            if (instanceManager.isInDungeon(player)) {
                instanceManager.evacuateToPublicWorld(player);
                player.sendMessage("§e[Dungeon] Your previous dungeon session has concluded. Returned to safety.");
            }
        }
    }
}
