package com.dimensionalminer.core.dimensionalMinerCore.combat;

import com.dimensionalminer.core.dimensionalMinerCore.ContentRegistryManager;
import com.dimensionalminer.core.dimensionalMinerCore.GearSet;
import com.dimensionalminer.core.dimensionalMinerCore.ItemDefinition;
import com.dimensionalminer.core.dimensionalMinerCore.UniqueEffect;
import com.dimensionalminer.core.dimensionalMinerCore.craft.GearItems;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Unique Effects (3.4) and Set bonuses (3.5) of what a player wears. Rules (GDD 4):
 * every effect has a proc chance and an internal cooldown per wearer; only one effect of a
 * stacking group procs per hit; damage dealt by a proc never triggers procs (no chains);
 * lifesteal heals from real damage dealt and is capped per hit; broken gear (durability 0)
 * gives no effects and doesn't count for sets.
 */
public final class EffectEngine {

    private static final EquipmentSlot[] WORN = {EquipmentSlot.HAND, EquipmentSlot.OFF_HAND,
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private record Weak(long until, double percent) {}

    private final Plugin plugin;
    private final GearItems gear;
    private final ContentRegistryManager registry;
    private final double lifestealCap;
    private final NamespacedKey shieldKey;
    private CombatService combat;
    /** wearer → effect id → ready again at (ms). */
    private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();
    /** victim → group → running burn. */
    private final Map<UUID, Map<String, BukkitTask>> burns = new HashMap<>();
    private final Map<UUID, Weak> weakened = new HashMap<>();
    private final Map<UUID, BukkitTask> shields = new HashMap<>();

    public EffectEngine(Plugin plugin, GearItems gear, ContentRegistryManager registry) {
        this.plugin = plugin;
        this.gear = gear;
        this.registry = registry;
        this.lifestealCap = Math.max(0, plugin.getConfig().getDouble("combat.effects.lifesteal_cap", 4));
        this.shieldKey = new NamespacedKey(plugin, "unique_shield");
    }

    void attach(CombatService combat) {
        this.combat = combat;
    }

    // ------------------------------------------------------------------ what the player wears

    private record Worn(EquipmentSlot slot, ItemDefinition def) {}

    /** Worn, unbroken gear. weaponOverride (a projectile's launcher) replaces the main hand. */
    private List<Worn> worn(Player p, String weaponOverride) {
        List<Worn> out = new ArrayList<>();
        for (EquipmentSlot slot : WORN) {
            ItemStack s;
            if (slot == EquipmentSlot.HAND && weaponOverride != null) {
                ItemDefinition d = registry.getItem(weaponOverride);
                if (d != null && d.isGear()) out.add(new Worn(slot, d));
                continue;
            }
            s = p.getInventory().getItem(slot);
            ItemDefinition def = gear.gearDefinition(s);
            if (def == null) continue;
            GearItems.GearData d = gear.read(s);
            if (d != null && gear.broken(def, d)) continue;
            out.add(new Worn(slot, def));
        }
        return out;
    }

    /** Active set bonuses: (set, pieces worn) for every set with at least one bonus reached. */
    private List<GearSet.Bonus> activeBonuses(List<Worn> worn) {
        Map<GearSet, Set<String>> count = new HashMap<>();
        for (Worn w : worn) {
            GearSet set = registry.getSetOf(w.def().getId());
            if (set != null) count.computeIfAbsent(set, k -> new HashSet<>()).add(w.def().getId());
        }
        List<GearSet.Bonus> out = new ArrayList<>();
        for (Map.Entry<GearSet, Set<String>> e : count.entrySet())
            for (GearSet.Bonus b : e.getKey().bonuses()) if (e.getValue().size() >= b.count()) out.add(b);
        return out;
    }

    /** Flat stats from active set bonuses (added to the player's totals). */
    public Map<String, Double> setStats(Player p) {
        Map<String, Double> out = new HashMap<>();
        for (GearSet.Bonus b : activeBonuses(worn(p, null))) b.stats().forEach((k, v) -> out.merge(k, v, Double::sum));
        return out;
    }

    /** Pieces worn per set, for /gear-like displays and the action bar on equip. */
    public Map<String, Integer> setCounts(Player p) {
        Map<String, Set<String>> c = new HashMap<>();
        for (Worn w : worn(p, null)) {
            GearSet set = registry.getSetOf(w.def().getId());
            if (set != null) c.computeIfAbsent(set.name(), k -> new HashSet<>()).add(w.def().getId());
        }
        Map<String, Integer> out = new HashMap<>();
        c.forEach((k, v) -> out.put(k, v.size()));
        return out;
    }

    private List<UniqueEffect> effects(Player p, String weaponOverride) {
        List<Worn> worn = worn(p, weaponOverride);
        Set<UniqueEffect> out = new LinkedHashSet<>();
        for (Worn w : worn) out.addAll(w.def().getEffects());
        for (GearSet.Bonus b : activeBonuses(worn)) out.addAll(b.effects());
        return new ArrayList<>(out);
    }

    // ------------------------------------------------------------------ outgoing damage modifier

    /** 1 − WEAKEN % for an entity that is weakened right now. */
    public double outgoingMultiplier(LivingEntity e) {
        Weak w = weakened.get(e.getUniqueId());
        if (w == null) return 1;
        if (System.currentTimeMillis() > w.until()) { weakened.remove(e.getUniqueId()); return 1; }
        return Math.max(0, 1 - w.percent() / 100.0);
    }

    // ------------------------------------------------------------------ triggers

    /** Called once per resolved pipeline hit (never for proc damage). */
    void onHit(CombatService.Hit hit, boolean killed) {
        if (hit.attacker() instanceof Player p) {
            List<UniqueEffect> list = effects(p, hit.weaponId());
            Set<String> groups = new HashSet<>();
            fire(p, list, UniqueEffect.Trigger.ON_HIT, hit, groups);
            if (hit.crit()) fire(p, list, UniqueEffect.Trigger.ON_CRIT, hit, groups);
            if (killed) fire(p, list, UniqueEffect.Trigger.ON_KILL, hit, groups);
        }
        if (hit.victim() instanceof Player v && !killed) {
            fire(v, effects(v, null), UniqueEffect.Trigger.ON_HURT, hit, new HashSet<>());
        }
    }

    private void fire(Player wearer, List<UniqueEffect> list, UniqueEffect.Trigger trigger, CombatService.Hit hit, Set<String> groups) {
        long now = System.currentTimeMillis();
        Map<String, Long> cd = cooldowns.computeIfAbsent(wearer.getUniqueId(), k -> new HashMap<>());
        for (UniqueEffect e : list) {
            if (e.trigger() != trigger || groups.contains(e.group())) continue;
            if (cd.getOrDefault(e.id(), 0L) > now) continue;
            if (e.chance() < 100 && ThreadLocalRandom.current().nextDouble(100) >= e.chance()) continue;
            groups.add(e.group());
            if (e.cooldown() > 0) cd.put(e.id(), now + (long) (e.cooldown() * 1000));
            apply(wearer, e, hit);
        }
    }

    private void apply(Player wearer, UniqueEffect e, CombatService.Hit hit) {
        // Outgoing effects target the victim; ON_HURT effects target the one who hit the wearer.
        boolean defensive = e.trigger() == UniqueEffect.Trigger.ON_HURT;
        LivingEntity target = defensive ? hit.attacker() : hit.victim();
        switch (e.action()) {
            case EXTRA_DAMAGE -> {
                LivingEntity t = target;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!t.isValid() || t.isDead()) return;
                    combat.dealProc(wearer, t, e.value(), true);
                    t.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, t.getLocation().add(0, t.getHeight() / 2, 0), 12, 0.3, 0.4, 0.3, 0.1);
                    t.getWorld().playSound(t.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 0.4f, 1.6f);
                });
            }
            case BURN -> burn(wearer, target, e);
            case LIFESTEAL -> {
                double heal = Math.min(lifestealCap, hit.dealt() * e.value() / 100.0);
                AttributeInstance max = wearer.getAttribute(Attribute.MAX_HEALTH);
                if (heal > 0 && max != null) {
                    wearer.setHealth(Math.min(max.getValue(), wearer.getHealth() + heal));
                    wearer.getWorld().spawnParticle(Particle.HEART, wearer.getLocation().add(0, 2, 0), 2, 0.3, 0.2, 0.3);
                }
            }
            case SLOW -> {
                target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, (int) (e.duration() * 20), Math.max(0, (int) e.value() - 1)));
                target.getWorld().spawnParticle(Particle.SNOWFLAKE, target.getLocation().add(0, 1, 0), 10, 0.3, 0.5, 0.3, 0.02);
            }
            case WEAKEN -> {
                weakened.put(target.getUniqueId(), new Weak(System.currentTimeMillis() + (long) (e.duration() * 1000), e.value()));
                target.getWorld().spawnParticle(Particle.SMOKE, target.getLocation().add(0, 1, 0), 15, 0.3, 0.5, 0.3, 0.02);
            }
            case SHIELD -> shield(wearer, e);
            case REFLECT -> {
                double back = hit.dealt() * e.value() / 100.0;
                LivingEntity t = target;
                if (back > 0) Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!t.isValid() || t.isDead()) return;
                    combat.dealProc(wearer, t, back, true);
                    t.getWorld().spawnParticle(Particle.CRIT, t.getLocation().add(0, t.getHeight() / 2, 0), 10, 0.3, 0.4, 0.3, 0.1);
                });
            }
        }
        if (e.action() != UniqueEffect.Action.LIFESTEAL) // lifesteal procs too often to announce
            wearer.sendActionBar(Component.text("✦ " + e.name(), NamedTextColor.GOLD));
    }

    private void burn(Player wearer, LivingEntity target, UniqueEffect e) {
        Map<String, BukkitTask> mine = burns.computeIfAbsent(target.getUniqueId(), k -> new HashMap<>());
        BukkitTask old = mine.remove(e.group());
        if (old != null) old.cancel(); // re-applying refreshes, it doesn't stack
        int ticks = Math.max(1, (int) Math.round(e.duration()));
        UUID wid = wearer.getUniqueId();
        BukkitTask[] task = new BukkitTask[1];
        int[] left = {ticks};
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            Player w = Bukkit.getPlayer(wid);
            if (left[0]-- <= 0 || !target.isValid() || target.isDead() || w == null) {
                task[0].cancel();
                Map<String, BukkitTask> m = burns.get(target.getUniqueId());
                if (m != null) { m.remove(e.group(), task[0]); if (m.isEmpty()) burns.remove(target.getUniqueId()); }
                return;
            }
            combat.dealProc(w, target, e.value(), false);
            target.getWorld().spawnParticle(Particle.FLAME, target.getLocation().add(0, target.getHeight() / 2, 0), 8, 0.25, 0.4, 0.25, 0.01);
        }, 20L, 20L);
        mine.put(e.group(), task[0]);
    }

    private void shield(Player wearer, UniqueEffect e) {
        AttributeInstance max = wearer.getAttribute(Attribute.MAX_ABSORPTION);
        if (max == null || e.value() <= 0) return;
        BukkitTask old = shields.remove(wearer.getUniqueId());
        if (old != null) old.cancel();
        max.removeModifier(shieldKey);
        max.addTransientModifier(new AttributeModifier(shieldKey, e.value(), AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.ANY));
        wearer.setAbsorptionAmount(Math.max(wearer.getAbsorptionAmount(), e.value()));
        wearer.getWorld().spawnParticle(Particle.ENCHANT, wearer.getLocation().add(0, 1, 0), 30, 0.4, 0.6, 0.4, 0.5);
        wearer.playSound(wearer.getLocation(), Sound.ITEM_ARMOR_EQUIP_GOLD, 0.8f, 1.3f);
        UUID id = wearer.getUniqueId();
        shields.put(id, Bukkit.getScheduler().runTaskLater(plugin, () -> {
            shields.remove(id);
            Player p = Bukkit.getPlayer(id);
            if (p == null) return;
            AttributeInstance m = p.getAttribute(Attribute.MAX_ABSORPTION);
            if (m != null) m.removeModifier(shieldKey);
            p.setAbsorptionAmount(Math.min(p.getAbsorptionAmount(), m != null ? m.getValue() : 0));
        }, Math.max(1, (long) (e.duration() * 20))));
    }

    /** Clears per-player state (cooldowns, shield) on quit. */
    public void forget(Player p) {
        cooldowns.remove(p.getUniqueId());
        weakened.remove(p.getUniqueId());
        BukkitTask s = shields.remove(p.getUniqueId());
        if (s != null) s.cancel();
        AttributeInstance m = p.getAttribute(Attribute.MAX_ABSORPTION);
        if (m != null) m.removeModifier(shieldKey);
    }

    public void forgetEntity(UUID id) {
        weakened.remove(id);
        Map<String, BukkitTask> b = burns.remove(id);
        if (b != null) b.values().forEach(BukkitTask::cancel);
    }
}
