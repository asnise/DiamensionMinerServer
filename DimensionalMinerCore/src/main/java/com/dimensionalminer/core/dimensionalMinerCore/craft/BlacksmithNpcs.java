package com.dimensionalminer.core.dimensionalMinerCore.craft;

import com.dimensionalminer.core.dimensionalMinerCore.DimensionalMinerCore;
import com.dimensionalminer.core.dimensionalMinerCore.ItemDefinition;
import com.google.gson.JsonObject;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Blacksmith NPCs repair every damaged gear piece a player carries for COIN (forge.repair.coin_per_point).
 * Positions live in plugins/DimensionalMinerCore/repair_npcs.yml; an NPC is spawned at each position in
 * every channel world of the hub map (worlds are stateless, so NPCs are not saved with the world).
 * Admin: /forge npc add | remove | list.
 */
public final class BlacksmithNpcs implements Listener {

    private record Pos(double x, double y, double z, float yaw) {}

    private record Repair(int slot, UUID gearId, ItemDefinition def, int from, int to) {}

    private final DimensionalMinerCore plugin;
    private final CraftService service;
    private final NamespacedKey tag;
    private final File file;
    private final List<Pos> positions = new ArrayList<>();
    private final List<Entity> spawned = new ArrayList<>();

    public BlacksmithNpcs(DimensionalMinerCore plugin, CraftService service) {
        this.plugin = plugin;
        this.service = service;
        this.tag = new NamespacedKey(plugin, "blacksmith_npc");
        this.file = new File(plugin.getDataFolder(), "repair_npcs.yml");
        load();
    }

    // ------------------------------------------------------------------ positions

    private void load() {
        positions.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String s : y.getStringList("npcs")) {
            String[] p = s.split(",");
            try {
                positions.add(new Pos(Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2]),
                        p.length > 3 ? Float.parseFloat(p[3]) : 0f));
            } catch (Exception e) {
                plugin.getLogger().warning("[Blacksmith] Bad position in repair_npcs.yml: " + s);
            }
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("npcs", positions.stream().map(p -> String.format(Locale.ROOT, "%.2f,%.2f,%.2f,%.1f", p.x(), p.y(), p.z(), p.yaw())).toList());
        try {
            y.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("[Blacksmith] Could not save repair_npcs.yml: " + e.getMessage());
        }
    }

    private List<String> worlds() {
        return plugin.getChannelManager() != null ? plugin.getChannelManager().getChannelWorldNames() : List.of("overworld_t0");
    }

    /** (Re)spawns every NPC in every loaded channel world. */
    public void spawnAll() {
        despawnAll();
        for (String w : worlds()) {
            World world = Bukkit.getWorld(w);
            if (world != null) spawnIn(world);
        }
    }

    private void spawnIn(World world) {
        for (Pos p : positions) {
            Location loc = new Location(world, p.x(), p.y(), p.z(), p.yaw(), 0);
            world.addPluginChunkTicket(loc.getBlockX() >> 4, loc.getBlockZ() >> 4, plugin);
            Villager v = world.spawn(loc, Villager.class, e -> {
                e.setPersistent(false);
                e.setAI(false);
                e.setInvulnerable(true);
                e.setSilent(true);
                e.setCollidable(false);
                e.setProfession(Villager.Profession.WEAPONSMITH);
                e.customName(Component.text("Blacksmith", NamedTextColor.GOLD));
                e.setCustomNameVisible(true);
                e.getPersistentDataContainer().set(tag, PersistentDataType.BYTE, (byte) 1);
            });
            spawned.add(v);
        }
    }

    public void despawnAll() {
        for (Entity e : spawned) if (e.isValid()) e.remove();
        spawned.clear();
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        if (worlds().contains(event.getWorld().getName())) spawnIn(event.getWorld());
    }

    public String add(Player admin) {
        Location l = admin.getLocation();
        positions.add(new Pos(l.getBlockX() + 0.5, l.getY(), l.getBlockZ() + 0.5, l.getYaw() + 180));
        save();
        spawnAll();
        return "Blacksmith added at " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ() + " in every channel.";
    }

    public String remove(Player admin) {
        Location l = admin.getLocation();
        Pos best = null;
        double bestD = 9; // within 3 blocks
        for (Pos p : positions) {
            double d = Math.pow(p.x() - l.getX(), 2) + Math.pow(p.y() - l.getY(), 2) + Math.pow(p.z() - l.getZ(), 2);
            if (d < bestD) { bestD = d; best = p; }
        }
        if (best == null) return "No Blacksmith within 3 blocks.";
        positions.remove(best);
        save();
        spawnAll();
        return "Blacksmith removed.";
    }

    public List<String> list() {
        return positions.stream().map(p -> String.format(Locale.ROOT, "%.0f %.0f %.0f", p.x(), p.y(), p.z())).toList();
    }

    // ------------------------------------------------------------------ interaction

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (!event.getRightClicked().getPersistentDataContainer().has(tag)) return;
        event.setCancelled(true); // no trading screen
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        Player p = event.getPlayer();
        service.loadBalance(p, "COIN", coin -> { if (p.isOnline()) ask(p, coin); });
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity().getPersistentDataContainer().has(tag)) event.setCancelled(true);
    }

    private List<Repair> scan(Player p) {
        List<Repair> out = new ArrayList<>();
        GearItems gear = service.gear();
        for (int i = 0; i <= 40; i++) {
            ItemStack s = p.getInventory().getItem(i);
            ItemDefinition def = gear.gearDefinition(s);
            if (def == null) continue;
            GearItems.GearData d = gear.readOrAssign(s);
            int max = gear.maxDurability(def), cur = gear.durability(def, d);
            if (cur < max) out.add(new Repair(i, d.id(), def, cur, max));
        }
        return out;
    }

    private void ask(Player p, long coin) {
        List<Repair> list = scan(p);
        if (list.isEmpty()) {
            p.sendMessage(Component.text("[Blacksmith] All your gear is in perfect shape.", NamedTextColor.GRAY));
            return;
        }
        int points = list.stream().mapToInt(r -> r.to() - r.from()).sum();
        long cost = service.rules().repairCoin(points);
        List<DialogBody> body = new ArrayList<>();
        for (Repair r : list)
            body.add(DialogBody.plainMessage(Component.text(r.def().getDisplay() + ": " + r.from() + " → " + r.to()
                    + (r.from() == 0 ? " (broken)" : ""), r.from() == 0 ? NamedTextColor.RED : NamedTextColor.GRAY)));
        body.add(DialogBody.plainMessage(Component.text("Cost: " + cost + " COIN" + (coin >= 0 ? " (you have " + coin + ")" : ""),
                coin >= 0 && coin < cost ? NamedTextColor.RED : NamedTextColor.YELLOW)));
        body.add(DialogBody.plainMessage(Component.text("Self repair at the forge costs Energy instead.", NamedTextColor.DARK_GRAY)));
        ClickCallback.Options once = ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(2)).build();
        Dialog dialog = Dialog.create(b -> b.empty()
                .base(DialogBase.builder(Component.text("Blacksmith: repair " + list.size() + " piece(s)?", NamedTextColor.GOLD))
                        .canCloseWithEscape(true).body(body).build())
                .type(DialogType.confirmation(
                        ActionButton.builder(Component.text("Repair for " + cost + " COIN", NamedTextColor.GOLD))
                                .action(DialogAction.customClick((view, audience) -> Bukkit.getScheduler().runTask(plugin,
                                        () -> confirm(p, list, cost)), once)).build(),
                        ActionButton.builder(Component.text("Not now")).action(DialogAction.customClick((v, a) -> {}, once)).build())));
        p.showDialog(dialog);
    }

    private void confirm(Player p, List<Repair> quoted, long cost) {
        if (!p.isOnline()) return;
        List<Repair> now = scan(p);
        if (!sameRepairs(now, quoted)) {
            p.sendMessage(Component.text("[Blacksmith] Your gear changed. Talk to me again.", NamedTextColor.YELLOW));
            return;
        }
        UUID pid = p.getUniqueId();
        if (!service.tryBegin(pid)) return;
        List<CraftService.ItemTx> txs = new ArrayList<>();
        for (Repair r : quoted) {
            JsonObject m = new JsonObject();
            m.addProperty("gear_id", r.gearId().toString());
            m.addProperty("from", r.from());
            m.addProperty("to", r.to());
            m.addProperty("by", "BLACKSMITH");
            m.addProperty("coin_total", cost);
            txs.add(new CraftService.ItemTx(r.def().getId(), "REPAIR", m));
        }
        JsonObject response = new JsonObject();
        response.addProperty("pieces", quoted.size());
        response.addProperty("coin", cost);
        service.commit(new CraftService.Commit(pid, "repair/" + UUID.randomUUID(), "blacksmith:" + quoted.size(), "REPAIR",
                cost, txs, response, "COIN"), result -> {
            service.end(pid);
            if (!p.isOnline()) return; // nothing was taken out of the inventory; the repair simply is not applied
            if (!result.ok()) {
                p.sendMessage(Component.text(result.status() == CraftService.Status.NO_ENERGY
                        ? "[Blacksmith] Not enough COIN (" + result.energyAfter() + "/" + cost + ")."
                        : "[Blacksmith] Repair failed (" + result.status() + "). Nothing was charged.", NamedTextColor.RED));
                return;
            }
            GearItems gear = service.gear();
            for (Repair r : quoted) {
                int slot = gear.findSlot(p, r.gearId());
                if (slot < 0) continue;
                ItemStack s = p.getInventory().getItem(slot);
                ItemDefinition def = gear.gearDefinition(s);
                GearItems.GearData d = gear.read(s);
                if (def == null || d == null) continue;
                gear.write(s, def, d.withDurability(gear.maxDurability(def)));
                p.getInventory().setItem(slot, s);
            }
            service.persist(p);
            p.sendMessage(Component.text("[Blacksmith] Repaired " + quoted.size() + " piece(s) for " + cost + " COIN.", NamedTextColor.GREEN));
            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_ANVIL_USE, 0.7f, 1.1f);
        });
    }

    private static boolean sameRepairs(List<Repair> a, List<Repair> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++)
            if (!a.get(i).gearId().equals(b.get(i).gearId()) || a.get(i).from() != b.get(i).from()) return false;
        return true;
    }
}
