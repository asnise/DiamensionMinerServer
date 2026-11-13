package com.exea.entity.core.model;

import com.exea.entity.api.SpawnOptions;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.entity.EntityType;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Per-model settings from the optional {@code models/<id>_meta.json}: what the driver mob is, how it
 * is called and how strong it is, which bones glow, and the hurt/death feedback. Every key is optional:
 * <pre>
 * display_name, name_color (ChatColor name), health, attack_damage, movement_speed, hitbox_scale,
 * follow_range, glow_bones [bone...], driver (entity type, e.g. "husk", "zombie", "armor_stand"),
 * behavior (key given to EXeaAPI.registerBehavior), hurt_sound / death_sound (sound key, "" = none),
 * hurt_particle / death_particle (particle key, "" = none), death_ticks (death sequence length)
 * </pre>
 *
 * @param driver   null = HUSK with AI, ARMOR_STAND without
 * @param behavior null = registry lookup by model id, then the default behavior
 */
public record EntityMeta(String displayName, ChatColor nameColor, double health, double attackDamage,
                         double movementSpeed, double hitboxScale, double followRange, Set<String> glowBones,
                         EntityType driver, String behavior, String hurtSound, String deathSound,
                         Particle hurtParticle, Particle deathParticle, int deathTicks, boolean nameVisible) {

    /**
     * Defaults when a model has no meta file or leaves a key out: name from the id, zombie-like stats,
     * normal size. Hurt/death feedback keeps the Wither sounds the first models were made with.
     */
    public static EntityMeta defaults(String id) {
        StringBuilder name = new StringBuilder();
        for (String part : id.split("_")) {
            if (part.isEmpty()) continue;
            if (!name.isEmpty()) name.append(' ');
            name.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return new EntityMeta(name.toString(), ChatColor.WHITE, 20.0, 3.0, 0.23, 1.0, 32.0, Set.of(),
                null, null, "entity.wither.hurt", "entity.wither.death",
                Particle.PORTAL, Particle.WARPED_SPORE, 60, true);
    }

    public static EntityMeta load(String id, InputStream stream) {
        EntityMeta d = defaults(id);
        if (stream == null) return d;
        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            JsonObject o = JsonParser.parseReader(reader).getAsJsonObject();
            ChatColor color = d.nameColor();
            if (o.has("name_color")) {
                try {
                    color = ChatColor.valueOf(o.get("name_color").getAsString().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ignored) {
                }
            }
            Set<String> glow = new HashSet<>();
            if (o.has("glow_bones")) {
                for (JsonElement e : o.getAsJsonArray("glow_bones")) glow.add(e.getAsString().toLowerCase(Locale.ROOT));
            }
            return new EntityMeta(
                    o.has("display_name") ? o.get("display_name").getAsString() : d.displayName(),
                    color,
                    num(o, "health", d.health()),
                    num(o, "attack_damage", d.attackDamage()),
                    num(o, "movement_speed", d.movementSpeed()),
                    num(o, "hitbox_scale", d.hitboxScale()),
                    num(o, "follow_range", d.followRange()),
                    Set.copyOf(glow),
                    o.has("driver") ? entityType(o.get("driver").getAsString()) : d.driver(),
                    o.has("behavior") ? o.get("behavior").getAsString() : d.behavior(),
                    o.has("hurt_sound") ? o.get("hurt_sound").getAsString() : d.hurtSound(),
                    o.has("death_sound") ? o.get("death_sound").getAsString() : d.deathSound(),
                    o.has("hurt_particle") ? particle(o.get("hurt_particle").getAsString()) : d.hurtParticle(),
                    o.has("death_particle") ? particle(o.get("death_particle").getAsString()) : d.deathParticle(),
                    o.has("death_ticks") ? Math.max(1, o.get("death_ticks").getAsInt()) : d.deathTicks(),
                    d.nameVisible());
        } catch (Exception e) {
            return d;
        }
    }

    /** This meta with every value set in {@code o} replacing the model's. */
    public EntityMeta with(SpawnOptions o) {
        if (o == null) return this;
        return new EntityMeta(
                o.displayName() != null ? o.displayName() : displayName,
                o.displayName() != null ? ChatColor.RESET : nameColor,
                o.health() != null ? o.health() : health,
                o.attackDamage() != null ? o.attackDamage() : attackDamage,
                o.movementSpeed() != null ? o.movementSpeed() : movementSpeed,
                o.hitboxScale() != null ? o.hitboxScale() : hitboxScale,
                o.followRange() != null ? o.followRange() : followRange,
                glowBones,
                o.driver() != null ? o.driver() : driver,
                behavior, hurtSound, deathSound, hurtParticle, deathParticle, deathTicks,
                o.nameVisible() != null ? o.nameVisible() : nameVisible);
    }

    public String healthName(double hp) {
        ChatColor bar = hp > health * 0.5 ? ChatColor.GREEN : hp > health * 0.2 ? ChatColor.YELLOW : ChatColor.RED;
        return nameColor + displayName + " " + bar + "[" + (int) Math.ceil(hp) + "/" + (int) health + "]";
    }

    private static double num(JsonObject o, String key, double fallback) {
        return o.has(key) ? o.get(key).getAsDouble() : fallback;
    }

    private static NamespacedKey key(String s) {
        String k = s.trim().toLowerCase(Locale.ROOT);
        return k.isEmpty() ? null : NamespacedKey.fromString(k.contains(":") ? k : "minecraft:" + k);
    }

    /** Living entity type by key ("husk", "minecraft:zombie"); null if unknown or not living. */
    private static EntityType entityType(String s) {
        NamespacedKey k = key(s);
        EntityType type = k == null ? null : Registry.ENTITY_TYPE.get(k);
        return type != null && type.isAlive() ? type : null;
    }

    /** Particle by key ("portal"); null for "" or unknown (no particle). */
    private static Particle particle(String s) {
        NamespacedKey k = key(s);
        return k == null ? null : Registry.PARTICLE_TYPE.get(k);
    }
}
