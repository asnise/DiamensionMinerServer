package com.dimensionalminer.core.dimensionalMinerCore.combat;

import com.dimensionalminer.core.dimensionalMinerCore.DimensionalMinerCore;
import com.dimensionalminer.core.dimensionalMinerCore.craft.GearItems;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Death outside a dungeon (mines / open world, GDD 6): no items, coins or Energy are lost
 * (keepInventory is set by InventorySync); every equipped gear piece loses
 * {@code combat.death.durability_loss_percent} of its max durability and the player respawns
 * at the hub spawn. Dungeon deaths are left to the dungeon engine.
 * Also cancels vanilla item wear on gear: durability is managed by {@link GearItems#wear}.
 */
public final class DeathListener implements Listener {

    private static final EquipmentSlot[] WORN = {EquipmentSlot.HAND, EquipmentSlot.OFF_HAND,
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private final DimensionalMinerCore plugin;
    private final GearItems gear;
    private final double lossPercent;
    private final String hubWorld;
    private final Set<UUID> diedInMine = new HashSet<>();

    public DeathListener(DimensionalMinerCore plugin, GearItems gear) {
        this.plugin = plugin;
        this.gear = gear;
        this.lossPercent = Math.max(0, plugin.getConfig().getDouble("combat.death.durability_loss_percent", 10));
        this.hubWorld = plugin.getConfig().getString("combat.death.respawn_world", "overworld_t0");
    }

    /** Old gear (vanilla wear, no gear_dur) is fixed once the inventory has been loaded from the DB. */
    @EventHandler
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent event) {
        Player p = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            int n = gear.normalize(p);
            if (n > 0) plugin.getInventorySync().snapshotAndMaybeSave(p);
        }, 100L);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVanillaWear(PlayerItemDamageEvent event) {
        if (gear.gearDefinition(event.getItem()) != null) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        Player p = event.getPlayer();
        var dungeons = plugin.getDungeonInstanceManager();
        if (dungeons != null && dungeons.isInDungeon(p)) return;
        diedInMine.add(p.getUniqueId());
        if (lossPercent > 0) for (EquipmentSlot slot : WORN) gear.wearPercent(p, slot, lossPercent);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        Player p = event.getPlayer();
        if (!diedInMine.remove(p.getUniqueId())) return;
        World hub = Bukkit.getWorld(hubWorld);
        if (hub != null) event.setRespawnLocation(hub.getSpawnLocation());
        Bukkit.getScheduler().runTask(plugin, () -> p.sendMessage(Component.text(
                "[Death] You were sent back to the hub. Nothing was lost; equipped gear lost "
                        + pct(lossPercent) + "% durability.", NamedTextColor.GRAY)));
    }

    private static String pct(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
