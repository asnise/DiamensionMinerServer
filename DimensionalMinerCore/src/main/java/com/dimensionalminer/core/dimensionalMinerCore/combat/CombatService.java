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

    /** dealt = damage that actually landed (capped by the victim's HP); weaponId = launcher of a projectile, else null. */
    public record Hit(LivingEntity attacker, LivingEntity victim, double raw, boolean crit, double defense, double damage,
                      EntityDamageEvent.DamageCause cause, double dealt, String weaponId) {}

    /** Combat stats of a player from equipped gear (main hand + armor + off-hand). */
    public record PlayerStats(boolean gearWeapon, double attack, double critChance, double defense) {}

    private record Charge(float value, int tick) {}

    private static final org.bukkit.inventory.EquipmentSlot[] ARMOR = {org.bukkit.inventory.EquipmentSlot.HEAD,
            org.bukkit.inventory.EquipmentSlot.CHEST, org.bukkit.inventory.EquipmentSlot.LEGS, org.bukkit.inventory.EquipmentSlot.FEET};

    private final Plugin plugin;
    private final GearItems gear;
    private final CombatConfig cfg;
    private final NamespacedKey kAtk, kDef, kProjAtk, kProjCrit, kProjSafe, kProjPvp;
    private com.dimensionalminer.core.dimensionalMinerCore.zone.ZoneManager zones;
    private EffectEngine effects;
    private NamespacedKey kProjWeapon;
    /** Set while a proc deals its damage: that hit skips the pipeline and never procs again. */
    private record ProcHit(LivingEntity target, double amount, boolean burst) {}
    private ProcHit pendingProc;
    private final Map<UUID, Charge> charge = new HashMap<>();
    /** victim → attacker → damage dealt (for World Boss / dungeon rewards later). */
    private final Map<UUID, Map<UUID, Double>> contribution = new HashMap<>();

    public CombatService(Plugin plugin, GearItems gear, CombatConfig cfg) {
        this.plugin = plugin;
        this.gear = gear;
        this.cfg = cfg;
        this.kAtk = new NamespacedKey(plugin, "combat_attack");
        this.kDef = new NamespacedKey(plugin, "combat_defense");
        this.kProjAtk = new NamespacedKey(plugin, "proj_attack");
        this.kProjCrit = new NamespacedKey(plugin, "proj_crit");
        this.kProjSafe = new NamespacedKey(plugin, "proj_from_safe");
        this.kProjPvp = new NamespacedKey(plugin, "proj_from_pvp");
    }

    public void setZones(com.dimensionalminer.core.dimensionalMinerCore.zone.ZoneManager zones) {
        this.zones = zones;
    }

    public void setEffects(EffectEngine effects) {
        this.effects = effects;
        this.kProjWeapon = new NamespacedKey(plugin, "proj_weapon");
        effects.attach(this);
    }

    /**
     * Damage from a Unique Effect: goes through defense, counts as the wearer's damage (contribution),
     * shows a purple number, and never triggers procs, wear or the normal pipeline.
     */
    void dealProc(Player source, LivingEntity target, double amount, boolean burst) {
        if (amount <= 0 || !target.isValid() || target.isDead()) return;
        if (safe(target.getLocation()) || safe(source.getLocation())) return;
        if (target instanceof Player && !cfg.pvp() && !(pvp(source.getLocation()) && pvp(target.getLocation()))) return;
        double def = target instanceof Player tp ? playerStats(tp).defense() : mobDefense(target);
        double dmg = amount * cfg.defenseK() / (cfg.defenseK() + Math.max(0, def));
        int iframes = target.getNoDamageTicks();
        target.setNoDamageTicks(0);
        pendingProc = new ProcHit(target, dmg, burst);
        try {
            target.damage(dmg, source);
        } finally {
            pendingProc = null;
            target.setNoDamageTicks(Math.max(target.getNoDamageTicks(), iframes));
        }
    }

    private boolean safe(Location l) { return zones != null && zones.isSafe(l); }

    private boolean pvp(Location l) { return zones != null && zones.isPvp(l); }

    /**
     * Anti-abuse (5.2): a player's projectile carries the weapon stats and the zone of the moment it
     * was fired, so swapping weapons mid-flight changes nothing and a shot from a Safe Zone stays
     * harmless. The bow (gear) wears when it fires, not when the arrow lands.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLaunch(org.bukkit.event.entity.ProjectileLaunchEvent event) {
        if (!cfg.enabled() || !(event.getEntity().getShooter() instanceof Player p)) return;
        PlayerStats s = playerStats(p);
        PersistentDataContainer pdc = event.getEntity().getPersistentDataContainer();
        pdc.set(kProjAtk, PersistentDataType.DOUBLE, s.gearWeapon() ? s.attack() : -1.0);
        pdc.set(kProjCrit, PersistentDataType.DOUBLE, s.critChance());
        pdc.set(kProjSafe, PersistentDataType.BYTE, (byte) (safe(p.getLocation()) ? 1 : 0));
        pdc.set(kProjPvp, PersistentDataType.BYTE, (byte) (pvp(p.getLocation()) ? 1 : 0));
        String weapon = gear.definitionId(p.getInventory().getItemInMainHand());
        if (weapon != null && kProjWeapon != null) pdc.set(kProjWeapon, PersistentDataType.STRING, weapon);
        if (s.gearWeapon()) gear.wear(p, org.bukkit.inventory.EquipmentSlot.HAND, 1);
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

    /** Vanilla's mob-damage curve: Easy = min(a/2 + 1, a), Normal = a, Hard = a × 1.5, Peaceful = 0. */
    private double difficultyScaled(double attack, org.bukkit.Difficulty d) {
        if (!cfg.scaleByDifficulty()) return attack;
        return switch (d) {
            case PEACEFUL -> 0;
            case EASY -> Math.min(attack / 2 + 1, attack);
            case NORMAL -> attack;
            case HARD -> attack * 1.5;
        };
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
        if (effects != null) {
            Map<String, Double> set = effects.setStats(p);
            if (gearWeapon) attack += set.getOrDefault("attack_damage", 0.0);
            crit += set.getOrDefault("crit_chance", 0.0);
            def += set.getOrDefault("defense", 0.0);
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
        if (effects != null) effects.forget(event.getPlayer());
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
        ProcHit procHit = pendingProc;
        if (procHit != null && procHit.target() == victim) {
            event.setDamage(EntityDamageEvent.DamageModifier.BASE, procHit.amount());
            if (event.isApplicable(EntityDamageEvent.DamageModifier.ARMOR)) event.setDamage(EntityDamageEvent.DamageModifier.ARMOR, 0);
            if (event.isApplicable(EntityDamageEvent.DamageModifier.MAGIC)) event.setDamage(EntityDamageEvent.DamageModifier.MAGIC, 0);
            if (event.getDamager() instanceof Player src) {
                double dealt = Math.min(procHit.amount(), victim.getHealth() + victim.getAbsorptionAmount());
                contribution.computeIfAbsent(victim.getUniqueId(), k -> new HashMap<>()).merge(src.getUniqueId(), dealt, Double::sum);
            }
            if (cfg.indicators()) indicator(victim, procHit.amount(), false, procHit.burst() ? NamedTextColor.LIGHT_PURPLE : NamedTextColor.GOLD);
            return;
        }
        LivingEntity attacker = owner(event.getDamager());
        if (attacker == null) return;
        boolean byPlayer = attacker instanceof Player, toPlayer = victim instanceof Player;

        if (!byPlayer && !toPlayer) return; // mob vs mob: vanilla

        // Hit rule. Where did the hit come from? A projectile remembers its launch zone.
        Projectile proj = event.getDamager() instanceof Projectile pr ? pr : null;
        PersistentDataContainer ppdc = proj != null ? proj.getPersistentDataContainer() : null;
        boolean snap = ppdc != null && ppdc.has(kProjSafe, PersistentDataType.BYTE);
        boolean fromSafe = snap ? ppdc.get(kProjSafe, PersistentDataType.BYTE) == 1 : safe(event.getDamager().getLocation());
        boolean fromPvp = snap ? ppdc.get(kProjPvp, PersistentDataType.BYTE) == 1 : pvp(event.getDamager().getLocation());
        // Safe Zone: nothing hurts across its border in either direction (no farming from inside).
        if (fromSafe || safe(victim.getLocation())) { event.setCancelled(true); return; }
        // PvP only when attacker and victim are both in a PvP zone (or combat.pvp is on).
        if (byPlayer && toPlayer && !cfg.pvp() && !(fromPvp && pvp(victim.getLocation()))) { event.setCancelled(true); return; }
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
            double critChance = s.critChance();
            Double snapAtk = ppdc != null ? ppdc.get(kProjAtk, PersistentDataType.DOUBLE) : null;
            if (snapAtk != null) {
                // Stats of the weapon that fired it, not of whatever is in hand now.
                critChance = ppdc.getOrDefault(kProjCrit, PersistentDataType.DOUBLE, 0.0);
                raw = snapAtk >= 0 ? snapAtk * Math.clamp(proj.getVelocity().length() / 3.0, 0.1, 1.0) : event.getDamage();
            } else if (!s.gearWeapon() || !melee && proj == null) {
                raw = event.getDamage(); // vanilla weapon / pet / other: vanilla raw damage
            } else if (cause == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) {
                raw = s.attack() * chargeScale(p) * cfg.sweepRatio();
            } else if (event.getDamager() instanceof Projectile pr) {
                raw = s.attack() * Math.clamp(pr.getVelocity().length() / 3.0, 0.1, 1.0);
            } else {
                raw = s.attack() * chargeScale(p);
            }
            if (weaponHit && critChance > 0) crit = ThreadLocalRandom.current().nextDouble(100) < critChance;
            defense = toPlayer ? playerStats((Player) victim).defense() : mobDefense(victim);
        } else {
            // Explosions etc keep vanilla damage (already scaled by difficulty); mob ATK is scaled here.
            raw = weaponHit ? difficultyScaled(mobAttack(attacker), victim.getWorld().getDifficulty()) : event.getDamage();
            defense = playerStats((Player) victim).defense();
        }
        if (effects != null) raw *= effects.outgoingMultiplier(attacker); // WEAKEN
        double damage = raw * (crit ? cfg.critMultiplier() : 1) * cfg.defenseK() / (cfg.defenseK() + Math.max(0, defense));

        event.setDamage(EntityDamageEvent.DamageModifier.BASE, damage);
        if (event.isApplicable(EntityDamageEvent.DamageModifier.ARMOR)) event.setDamage(EntityDamageEvent.DamageModifier.ARMOR, 0);
        if (event.isApplicable(EntityDamageEvent.DamageModifier.MAGIC)) event.setDamage(EntityDamageEvent.DamageModifier.MAGIC, 0);

        // Durability (5.4): the weapon wears per landed hit, each armor piece per hit taken.
        // (Projectiles wore the bow at launch.)
        if (byPlayer && cause == EntityDamageEvent.DamageCause.ENTITY_ATTACK && event.getDamager() == attacker)
            gear.wear((Player) attacker, org.bukkit.inventory.EquipmentSlot.HAND, 1);
        if (toPlayer && !byPlayer && weaponHit) {
            Player v = (Player) victim;
            for (org.bukkit.inventory.EquipmentSlot slot : ARMOR) gear.wear(v, slot, 1);
        }

        double dealt = Math.min(damage, victim.getHealth() + victim.getAbsorptionAmount());
        boolean killed = damage >= victim.getHealth() + victim.getAbsorptionAmount();
        String weaponId = ppdc != null && kProjWeapon != null ? ppdc.get(kProjWeapon, PersistentDataType.STRING) : null;
        Hit hit = new Hit(attacker, victim, raw, crit, defense, damage, cause, dealt, weaponId);
        if (byPlayer) {
            contribution.computeIfAbsent(victim.getUniqueId(), k -> new HashMap<>()).merge(attacker.getUniqueId(), dealt, Double::sum);
            if (cfg.indicators()) indicator(victim, damage, crit, null);
        }
        // Proc (3.4): Unique Effects and set effects, once per resolved hit (proc damage never gets here).
        if (effects != null && weaponHit) effects.onHit(hit, killed);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent event) {
        contribution.remove(event.getEntity().getUniqueId());
        if (effects != null && !(event.getEntity() instanceof Player)) effects.forgetEntity(event.getEntity().getUniqueId());
    }

    /** Damage dealt to a living entity by each attacker so far (read before it dies). */
    public Map<UUID, Double> contribution(UUID victim) {
        return Map.copyOf(contribution.getOrDefault(victim, Map.of()));
    }

    // ------------------------------------------------------------------ damage numbers

    private void indicator(LivingEntity victim, double damage, boolean crit, NamedTextColor procColor) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Location at = victim.getLocation().add(r.nextDouble(-0.4, 0.4), victim.getHeight() + 0.2, r.nextDouble(-0.4, 0.4));
        String num = String.format(Locale.ROOT, damage >= 10 ? "%.0f" : "%.1f", damage);
        Component text = procColor != null ? Component.text("✧ " + num, procColor)
                : crit ? Component.text("✦ " + num, NamedTextColor.GOLD).decorate(TextDecoration.BOLD)
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
