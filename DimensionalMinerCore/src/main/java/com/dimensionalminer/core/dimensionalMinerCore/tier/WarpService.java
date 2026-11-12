package com.dimensionalminer.core.dimensionalMinerCore.tier;

import com.dimensionalminer.core.dimensionalMinerCore.DimensionalMinerCore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Warp (1.4): a {@code warp.prepare_seconds} countdown that is cancelled when the player moves away
 * from where it started or takes damage, then a {@code warp.cooldown_seconds} cooldown after a
 * successful warp. The destination is checked again when the countdown ends (GDD: always re-check).
 */
public final class WarpService implements Listener {

    private record Pending(Location start, BukkitTask task) {}

    private final DimensionalMinerCore plugin;
    private final int prepare, cooldown;
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Map<UUID, Long> readyAt = new HashMap<>();

    public WarpService(DimensionalMinerCore plugin) {
        this.plugin = plugin;
        this.prepare = Math.max(0, plugin.getConfig().getInt("warp.prepare_seconds", 5));
        this.cooldown = Math.max(0, plugin.getConfig().getInt("warp.cooldown_seconds", 30));
    }

    /** Seconds left on the cooldown (0 = ready). */
    public long cooldownLeft(Player p) {
        long left = readyAt.getOrDefault(p.getUniqueId(), 0L) - System.currentTimeMillis();
        return left <= 0 ? 0 : (left + 999) / 1000;
    }

    /**
     * Starts a warp. {@code check} runs at the end and returns the destination, or null to abort
     * (it sends its own message). {@code label} is what the countdown shows.
     */
    public void start(Player p, String label, Supplier<Location> check) {
        long cd = cooldownLeft(p);
        if (cd > 0) {
            p.sendMessage(Component.text("[Warp] Ready again in " + cd + "s.", NamedTextColor.RED));
            return;
        }
        if (plugin.getDungeonInstanceManager() != null && plugin.getDungeonInstanceManager().isInDungeon(p)) {
            p.sendMessage(Component.text("[Warp] Leave the dungeon first (/exitdungeon).", NamedTextColor.RED));
            return;
        }
        cancel(p, null);
        UUID id = p.getUniqueId();
        int[] left = {prepare};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            Player pl = Bukkit.getPlayer(id);
            if (pl == null) { cancelQuiet(id); return; }
            if (left[0] > 0) {
                pl.sendActionBar(Component.text("Warping to " + label + " in " + left[0] + "s · don't move", NamedTextColor.AQUA));
                pl.getWorld().spawnParticle(Particle.PORTAL, pl.getLocation().add(0, 1, 0), 30, 0.4, 0.8, 0.4, 0.3);
                pl.playSound(pl.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.4f, 0.8f + 0.1f * (prepare - left[0]));
                left[0]--;
                return;
            }
            cancelQuiet(id);
            Location dest = check.get();
            if (dest == null) return;
            plugin.getInventorySync().snapshotAndMaybeSave(pl);
            pl.teleportAsync(dest).thenAccept(ok -> {
                if (!ok) { pl.sendMessage(Component.text("[Warp] Teleport failed.", NamedTextColor.RED)); return; }
                readyAt.put(id, System.currentTimeMillis() + cooldown * 1000L);
                pl.sendActionBar(Component.text("Arrived: " + label, NamedTextColor.GREEN));
                pl.playSound(pl.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.7f, 1.1f);
            });
        }, 0L, 20L);
        pending.put(id, new Pending(p.getLocation().clone(), task[0]));
    }

    private void cancelQuiet(UUID id) {
        Pending pd = pending.remove(id);
        if (pd != null) pd.task().cancel();
    }

    private void cancel(Player p, String why) {
        Pending pd = pending.remove(p.getUniqueId());
        if (pd == null) return;
        pd.task().cancel();
        if (why != null) {
            p.sendActionBar(Component.text("Warp cancelled: " + why, NamedTextColor.RED));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.6f);
        }
    }

    /** Checked every second by the countdown too, but a move event reacts at once. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(org.bukkit.event.player.PlayerMoveEvent event) {
        Pending pd = pending.get(event.getPlayer().getUniqueId());
        if (pd == null) return;
        Location to = event.getTo();
        if (to.getWorld() != pd.start().getWorld() || to.distanceSquared(pd.start()) > 0.36) cancel(event.getPlayer(), "you moved");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player p && pending.containsKey(p.getUniqueId())) cancel(p, "you took damage");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cancelQuiet(event.getPlayer().getUniqueId());
    }
}
