package com.dimensionalminer.core.dimensionalMinerCore.combat;

import com.dimensionalminer.core.dimensionalMinerCore.ItemDefinition;
import com.dimensionalminer.core.dimensionalMinerCore.craft.GearItems;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The one damage pipeline (GDD 6) for player ↔ mob hits, melee and projectiles:
 * hit rule (PvP off, owner of projectile/pet) → base (gear ATK × attack charge, or mob ATK)
 * → crit (crit_chance %) → defense ({@code Raw × K/(K+Defense)}, no vanilla armor/enchant
 * reduction) → shields (vanilla blocking is left alone) → HP → contribution per attacker
 * → proc (hook for Unique Effects, 3.4).
 * Environmental damage (fall, lava, drowning) and mob-vs-mob stay vanilla.
 */
public final class CombatService implements Listener {

    public record Hit(LivingEntity attacker, LivingEntity victim, double raw, boolean crit, double defense, double damage,
                      EntityDamageEvent.DamageCause cause) {}

    /** Combat stats of a player from equipped gear (main hand + armor + off-hand). */
    public record PlayerStats(boolean gearWeapon, double attack, double critChance, double defense) {}

    private record Charge(float value, int tick) {}

    private static final org.bukkit.inventory.EquipmentSlot[] ARMOR = {org.bukkit.inventory.EquipmentSlot.HEAD,
            org.bukkit.inventory.EquipmentSlot.CHEST, org.bukkit.inventory.EquipmentSlot.LEGS, org.bukkit.inventory.EquipmentSlot.FEET};

    private final Plugin plugin;
    private final GearItems gear;
    private final CombatConfig cfg;
    private final NamespacedKey kAtk, kDef;
    private final Map<UUID, Charge> charge = new HashMap<>();
    /** victim → attacker → damage dealt (for World Boss / dungeon rewards later). */
    private final Map<UUID, Map<UUID, Double>> contribution = new HashMap<>();

    public CombatService(Plugin plugin, GearItems gear, CombatConfig cfg) {
        this.plugin = plugin;
        this.gear = gear;
        this.cfg = cfg;
        this.kAtk = new NamespacedKey(plugin, "combat_attack");
        this.kDef = new NamespacedKey(plugin, "combat_defense");
    }

    public CombatConfig config() { return cfg; }

    // ------------------------------------------------------------------ mob stats

    /** Sets HP / attack / defense on a mob (null = keep). Used by dungeon SUMMON actions. */
    public void applyMobStats(LivingEntity mob, Double hp, Double attack, Double defense) {
        if (hp != null && hp > 0) {
            AttributeInstance max = mob.getAttribute(Attribute.MAX_HEALTH);
            if (max != null) {
                max.setBaseValue(hp);
                mob.setHealth(Math.min(hp, max.getValue()));
            }
        }
        PersistentDataContainer pdc = mob.getPersistentDataContainer();
        if (attack != null) pdc.set(kAtk, PersistentDataType.DOUBLE, attack);
        if (defense != null) pdc.set(kDef, PersistentDataType.DOUBLE, defense);
    }

    public double mobAttack(LivingEntity mob) {
        Double v = mob.getPersistentDataContainer().get(kAtk, PersistentDataType.DOUBLE);
        return v != null ? v : cfg.mob(mob.getType().name()).attack();
    }

    public double mobDefense(LivingEntity mob) {
        Double v = mob.getPersistentDataContainer().get(kDef, PersistentDataType.DOUBLE);
        return v != null ? v : cfg.mob(mob.getType().name()).defense();
    }

    /** Config HP for mob types that list one (combat.mobs.&lt;TYPE&gt;.hp). */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (!cfg.enabled()) return;
        CombatConfig.MobStats s = cfg.mobTypes().get(event.getEntityType().name());
        if (s != null && s.hp() != null) applyMobStats(event.getEntity(), s.hp(), null, null);
    }

    // ------------------------------------------------------------------ player stats

    public PlayerStats playerStats(Player p) {
        EntityEquipment eq = p.getEquipment();
        double attack = cfg.unarmedAttack(), crit = 0, def = cfg.playerBaseDefense();
        boolean gearWeapon = false;
        ItemStack hand = eq.getItemInMainHand();
        Map<String, Double> handStats = stats(hand);
        if (handStats != null) {
            gearWeapon = handStats.getOrDefault("attack_damage", 0.0) > 0;
            if (gearWeapon) attack = handStats.get("attack_damage");
            crit += handStats.getOrDefault("crit_chance", 0.0);
            def += handStats.getOrDefault("defense", 0.0);
        }
        for (ItemStack piece : new ItemStack[]{eq.getHelmet(), eq.getChestplate(), eq.getLeggings(), eq.getBoots(), eq.getItemInOffHand()}) {
            Map<String, Double> s = stats(piece);
            if (s == null) continue;
            crit += s.getOrDefault("crit_chance", 0.0);
            def += s.getOrDefault("defense", 0.0);
        }
        return new PlayerStats(gearWeapon, attack, crit, def);
    }

    private Map<String, Double> stats(ItemStack stack) {
        ItemDefinition def = gear.gearDefinition(stack);
        if (def == null) return null;
        GearItems.GearData d = gear.read(stack);
        if (d == null) d = new GearItems.GearData(UUID.randomUUID(), 0, 0, 0, "NORMAL");
        return gear.stats(def, d);
    }

    /** Vanilla-like charge curve: a spammed swing does 20% of a full one. */
    private double chargeScale(Player p) {
        Charge c = charge.get(p.getUniqueId());
        float v = c != null && c.tick() >= Bukkit.getCurrentTick() - 1 ? c.value() : 1f;
        return 0.2 + 0.8 * v * v;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPreAttack(PrePlayerAttackEntityEvent event) {
        // Read before vanilla resets the attack cooldown.
        charge.put(event.getPlayer().getUniqueId(), new Charge(event.getPlayer().getAttackCooldown(), Bukkit.getCurrentTick()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        charge.remove(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ pipeline

    /** Owner of the damage: projectile shooter, pet owner, or the entity itself. */
    private static LivingEntity owner(Entity damager) {
        Entity e = damager;
        if (e instanceof Projectile pr && pr.getShooter() instanceof Entity shooter) e = shooter;
        if (e instanceof Tameable t && t.getOwner() instanceof Player owner) return owner;
        return e instanceof LivingEntity le ? le : null;
    }

    @SuppressWarnings("deprecation") // DamageModifier is the only way to drop vanilla armor/enchant reduction
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!cfg.enabled()) return;
        if (!(event.getEntity() instanceof LivingEntity victim) || victim instanceof ArmorStand) return;
        LivingEntity attacker = owner(event.getDamager());
        if (attacker == null) return;
        boolean byPlayer = attacker instanceof Player, toPlayer = victim instanceof Player;

        // Hit rule: no PvP (this also covers a player's arrows and pets hitting players).
        if (byPlayer && toPlayer) {
            if (!cfg.pvp()) { event.setCancelled(true); return; }
        } else if (!byPlayer && !toPlayer) {
            return; // mob vs mob: vanilla
        }
        // Shield block: vanilla handles it.
        if (event.isApplicable(EntityDamageEvent.DamageModifier.BLOCKING) && event.getDamage(EntityDamageEvent.DamageModifier.BLOCKING) < 0) return;

        EntityDamageEvent.DamageCause cause = event.getCause();
        boolean weaponHit = cause == EntityDamageEvent.DamageCause.ENTITY_ATTACK || cause == EntityDamageEvent.DamageCause.PROJECTILE;
        double raw;
        boolean crit = false;
        double defense;
        if (byPlayer) {
            Player p = (Player) attacker;
            PlayerStats s = playerStats(p);
            boolean melee = event.getDamager() == p;
            if (!s.gearWeapon() || !melee && !(event.getDamager() instanceof Projectile)) {
                raw = event.getDamage(); // vanilla weapon / pet / other: vanilla raw damage
            } else if (cause == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) {
                raw = s.attack() * chargeScale(p) * cfg.sweepRatio();
            } else if (event.getDamager() instanceof Projectile pr) {
                raw = s.attack() * Math.clamp(pr.getVelocity().length() / 3.0, 0.1, 1.0);
            } else {
                raw = s.attack() * chargeScale(p);
            }
            if (weaponHit && s.critChance() > 0) crit = ThreadLocalRandom.current().nextDouble(100) < s.critChance();
            defense = toPlayer ? playerStats((Player) victim).defense() : mobDefense(victim);
        } else {
            raw = weaponHit ? mobAttack(attacker) : event.getDamage(); // explosions etc keep vanilla falloff
            defense = playerStats((Player) victim).defense();
        }
        double damage = raw * (crit ? cfg.critMultiplier() : 1) * cfg.defenseK() / (cfg.defenseK() + Math.max(0, defense));

        event.setDamage(EntityDamageEvent.DamageModifier.BASE, damage);
        if (event.isApplicable(EntityDamageEvent.DamageModifier.ARMOR)) event.setDamage(EntityDamageEvent.DamageModifier.ARMOR, 0);
        if (event.isApplicable(EntityDamageEvent.DamageModifier.MAGIC)) event.setDamage(EntityDamageEvent.DamageModifier.MAGIC, 0);

        // Durability (5.4): the weapon wears per landed hit, each armor piece per hit taken.
        if (byPlayer && weaponHit) gear.wear((Player) attacker, org.bukkit.inventory.EquipmentSlot.HAND, 1);
        if (toPlayer && !byPlayer && weaponHit) {
            Player v = (Player) victim;
            for (org.bukkit.inventory.EquipmentSlot slot : ARMOR) gear.wear(v, slot, 1);
        }

        Hit hit = new Hit(attacker, victim, raw, crit, defense, damage, cause);
        if (byPlayer) {
            double dealt = Math.min(damage, victim.getHealth() + victim.getAbsorptionAmount());
            contribution.computeIfAbsent(victim.getUniqueId(), k -> new HashMap<>()).merge(attacker.getUniqueId(), dealt, Double::sum);
            if (cfg.indicators()) indicator(victim, damage, crit);
        }
        proc(hit);
    }

    /** Hook for Unique Effects (Task 3.4): runs once per resolved hit, never for its own damage. */
    private void proc(Hit hit) {}

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent event) {
        contribution.remove(event.getEntity().getUniqueId());
    }

    /** Damage dealt to a living entity by each attacker so far (read before it dies). */
    public Map<UUID, Double> contribution(UUID victim) {
        return Map.copyOf(contribution.getOrDefault(victim, Map.of()));
    }

    // ------------------------------------------------------------------ damage numbers

    private void indicator(LivingEntity victim, double damage, boolean crit) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Location at = victim.getLocation().add(r.nextDouble(-0.4, 0.4), victim.getHeight() + 0.2, r.nextDouble(-0.4, 0.4));
        String num = String.format(Locale.ROOT, damage >= 10 ? "%.0f" : "%.1f", damage);
        Component text = crit
                ? Component.text("✦ " + num, NamedTextColor.GOLD).decorate(TextDecoration.BOLD)
                : Component.text(num, NamedTextColor.WHITE);
        TextDisplay d = victim.getWorld().spawn(at, TextDisplay.class, t -> {
            t.text(text);
            t.setBillboard(Display.Billboard.CENTER);
            t.setPersistent(false);
            t.setShadowed(true);
            t.setBackgroundColor(org.bukkit.Color.fromARGB(0, 0, 0, 0));
            if (crit) t.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(),
                    new org.joml.Quaternionf(), new org.joml.Vector3f(1.4f, 1.4f, 1.4f), new org.joml.Quaternionf()));
        });
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!d.isValid()) return;
            d.setTeleportDuration(15);
            d.teleport(d.getLocation().add(0, 0.8, 0));
        }, 1L);
        Bukkit.getScheduler().runTaskLater(plugin, d::remove, 18L);
    }
}
