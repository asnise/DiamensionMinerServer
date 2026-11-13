package example;

import com.exea.entity.api.EXeaAPI;
import com.exea.entity.api.EXeaEntity;
import com.exea.entity.api.SpawnOptions;
import com.exea.entity.api.behavior.DefaultBehavior;
import com.exea.entity.api.behavior.EXeaBehavior;
import com.exea.entity.api.event.EXeaAnimationEventTriggerEvent;
import com.exea.entity.api.event.EXeaEntityDamageEvent;
import com.exea.entity.api.event.EXeaEntityDeathEvent;
import com.exea.entity.api.event.EXeaEntityRemoveEvent;
import com.exea.entity.api.event.EXeaEntitySpawnEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.SmallFireball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Every snippet of EXeaEntityCore/docs/API_GUIDE_TH.md, compiled against the API jar. */
public final class GuideExamples extends JavaPlugin implements Listener {

    // --- 0. check and register -------------------------------------------------------------------
    @Override
    public void onEnable() {
        if (!Bukkit.getPluginManager().isPluginEnabled("EXeaEntityCore")) {
            getLogger().warning("EXeaEntityCore is not installed: 3D bosses are off");
            return;
        }
        EXeaAPI exea = EXeaAPI.getInstance();
        exea.registerBehavior("void_overseer", e -> new PhaseBossBehavior(this));   // every void_overseer
        exea.registerBehavior("fireball_caster", e -> new FireballBehavior());      // meta "behavior": "fireball_caster"
        exea.registerDamageProvider((attacker, victim, base, skillId) ->
                skillId.equals("attack_slam") ? base * 1.5 : base);
        getServer().getPluginManager().registerEvents(this, this);
    }

    // --- 1. spawn ------------------------------------------------------------------------------------
    public EXeaEntity spawnBoss(Location where) {
        EXeaEntity boss = EXeaAPI.getInstance().spawn("void_overseer", where,
                SpawnOptions.create()
                        .driver(EntityType.ZOMBIE)
                        .health(2000)
                        .attackDamage(15)
                        .movementSpeed(0.3)
                        .displayName("§5Floor 3 Overseer"));
        if (boss == null) {
            getLogger().warning("No model void_overseer (or the driver type cannot spawn)");
        }
        return boss;
    }

    public EXeaEntity spawnStatue(Location where) {
        return EXeaAPI.getInstance().spawn("vaelith", where, SpawnOptions.create().ai(false).nameVisible(false));
    }

    // --- 2/3. find + animate -------------------------------------------------------------------------
    public void slamIfModel(LivingEntity mob) {
        EXeaEntity e = EXeaAPI.getInstance().get(mob);
        if (e == null || e.isDying()) return;
        if (e.getAnimations().contains("attack_slam")) {
            e.playAnimation("attack_slam", 1.2f, EXeaEntity.Loop.ONCE);
        }
        String now = e.getCurrentAnimation();
        getLogger().info(e.getModelId() + " plays " + now);
    }

    // --- 4. effects from a bone ------------------------------------------------------------------------
    public void fireFromHand(EXeaEntity e, Player target) {
        Location hand = e.getBoneLocation("hand_r");
        if (hand == null) return;
        Vector dir = target.getEyeLocation().toVector().subtract(hand.toVector()).normalize();
        SmallFireball ball = hand.getWorld().spawn(hand, SmallFireball.class);
        ball.setShooter(e.getDriver());
        ball.setDirection(dir);
        hand.getWorld().spawnParticle(Particle.FLAME, hand, 20, 0.2, 0.2, 0.2, 0.02);
    }

    // --- 5. events -------------------------------------------------------------------------------------
    @EventHandler
    public void onSpawn(EXeaEntitySpawnEvent event) {
        getLogger().info("Spawned " + event.getTemplateId() + " at " + event.getEntity().getLocation());
    }

    @EventHandler
    public void onDeath(EXeaEntityDeathEvent event) {
        Map<UUID, Double> damage = event.getDamageByAttacker();
        List<Map.Entry<UUID, Double>> top = damage.entrySet().stream()
                .sorted(Map.Entry.<UUID, Double>comparingByValue(Comparator.reverseOrder()))
                .limit(3).toList();
        int rank = 1;
        for (Map.Entry<UUID, Double> entry : top) {
            Player p = Bukkit.getPlayer(entry.getKey());
            if (p != null) p.sendMessage("Rank " + rank + ": " + Math.round(entry.getValue()) + " damage");
            rank++;
        }
        LivingEntity killer = event.getKiller();
        if (killer instanceof Player p) p.sendMessage("Last hit on " + event.getEntityTemplateId() + "!");
    }

    @EventHandler
    public void onRemove(EXeaEntityRemoveEvent event) {
        if (event.getReason() == EXeaEntityRemoveEvent.Reason.DRIVER_LOST) {
            getLogger().warning(event.getTemplateId() + " vanished without dying");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EXeaEntityDamageEvent event) {
        boolean victimIsModel = EXeaAPI.getInstance().isEXeaEntity(event.getVictim());
        if (victimIsModel && event.getAttacker() instanceof Player p && p.isSneaking()) {
            event.setDamage(event.getDamage() * 0.5);
        }
        if (event.getSkillId().equals("attack_singularity") && event.getVictim() instanceof Player p) {
            p.setFreezeTicks(100);
        }
    }

    @EventHandler
    public void onAnimationEvent(EXeaAnimationEventTriggerEvent event) {
        if (!event.getEventTag().startsWith("summon ")) return;
        String type = event.getEventTag().substring("summon ".length()).trim();
        EntityType t = EntityType.fromName(type);
        if (t != null) event.getTriggerLocation().getWorld().spawnEntity(event.getTriggerLocation(), t);
    }

    // --- 6. behaviors -------------------------------------------------------------------------------------
    /** Default idle/move/attack, plus a second phase below half health and a timed skill. */
    static final class PhaseBossBehavior extends DefaultBehavior {
        private final JavaPlugin plugin;
        private boolean phaseTwo;
        private int skillCooldown = 200;

        PhaseBossBehavior(JavaPlugin plugin) {
            this.plugin = plugin;
        }

        @Override
        public void onTick(EXeaEntity entity) {
            super.onTick(entity);                       // keep idle/move switching
            if (--skillCooldown > 0 || isAttacking()) return;
            skillCooldown = phaseTwo ? 100 : 200;
            entity.playAnimation("attack_singularity", 1f, EXeaEntity.Loop.ONCE);
        }

        @Override
        public void onDamaged(EXeaEntity entity, LivingEntity attacker, double damage) {
            LivingEntity d = entity.getDriver();
            if (!phaseTwo && d.getHealth() < d.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue() / 2) {
                phaseTwo = true;
                d.getWorld().playSound(d.getLocation(), Sound.ENTITY_WITHER_SPAWN, 2f, 0.7f);
                plugin.getLogger().info(entity.getModelId() + " enters phase 2");
            }
        }

        @Override
        protected String pickAttack(EXeaEntity entity, LivingEntity target) {
            return phaseTwo && entity.getAnimations().contains("attack_slam") ? "attack_slam" : super.pickAttack(entity, target);
        }
    }

    /** A behavior from scratch: stands still and throws a fireball at the nearest player every 3 s. */
    static final class FireballBehavior implements EXeaBehavior {
        private int cooldown = 60;

        @Override
        public void onSpawn(EXeaEntity entity) {
            entity.playAnimation("idle");
        }

        @Override
        public void onTick(EXeaEntity entity) {
            if (--cooldown > 0) return;
            cooldown = 60;
            Player target = nearestPlayer(entity.getDriver().getLocation(), 16);
            if (target == null) return;
            entity.playAnimation("attack_cast", 1f, EXeaEntity.Loop.ONCE);
            Location hand = entity.getBoneLocation("hand_r");
            if (hand == null) hand = entity.getDriver().getEyeLocation();
            SmallFireball ball = hand.getWorld().spawn(hand, SmallFireball.class);
            ball.setShooter(entity.getDriver());
            ball.setDirection(target.getEyeLocation().toVector().subtract(hand.toVector()));
        }

        @Override
        public void onAnimationEnd(EXeaEntity entity, String animation) {
            entity.playAnimation("idle");
        }

        @Override
        public void onDeath(EXeaEntity entity, LivingEntity killer) {
            entity.getDriver().getWorld().dropItemNaturally(entity.getDriver().getLocation(),
                    new org.bukkit.inventory.ItemStack(org.bukkit.Material.BLAZE_ROD, 3));
        }

        private static Player nearestPlayer(Location at, double radius) {
            Player best = null;
            double bestSq = radius * radius;
            for (Player p : at.getWorld().getPlayers()) {
                double d = p.getLocation().distanceSquared(at);
                if (d < bestSq) {
                    bestSq = d;
                    best = p;
                }
            }
            return best;
        }
    }

    public EXeaEntity spawnWithOwnBehavior(Location where) {
        return EXeaAPI.getInstance().spawn("vaelith", where, SpawnOptions.create().behavior(new FireballBehavior()));
    }

    // --- 9. kill / remove ----------------------------------------------------------------------------
    public void clearArena() {
        for (EXeaEntity e : EXeaAPI.getInstance().getAll()) {
            if (e.getModelId().equals("void_overseer")) e.remove();   // no death, no rewards
        }
    }

    public void killWithAnimation(EXeaEntity e) {
        if (e.isValid() && !e.isDying()) e.kill();                     // death event + death animation
    }
}
