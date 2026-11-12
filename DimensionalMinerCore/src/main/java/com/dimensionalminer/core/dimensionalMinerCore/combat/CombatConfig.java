package com.dimensionalminer.core.dimensionalMinerCore.combat;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Numbers from config "combat:" (defaults below). */
public record CombatConfig(boolean enabled, double defenseK, double unarmedAttack, double playerBaseDefense,
                           double critMultiplier, double sweepRatio, boolean pvp, boolean indicators, boolean scaleByDifficulty,
                           MobStats mobDefault, Map<String, MobStats> mobTypes) {

    /** hp null = keep the vanilla max health. */
    public record MobStats(Double hp, double attack, double defense) {}

    public static CombatConfig from(FileConfiguration cfg) {
        ConfigurationSection c = cfg.getConfigurationSection("combat");
        if (c == null) c = cfg.createSection("combat");
        MobStats def = new MobStats(null, 3, 0);
        Map<String, MobStats> types = new HashMap<>();
        ConfigurationSection mobs = c.getConfigurationSection("mobs");
        if (mobs != null) {
            for (String key : mobs.getKeys(false)) {
                ConfigurationSection m = mobs.getConfigurationSection(key);
                if (m == null) continue;
                MobStats s = new MobStats(m.contains("hp") ? m.getDouble("hp") : null, m.getDouble("attack", 3), m.getDouble("defense", 0));
                if (key.equalsIgnoreCase("default")) def = s;
                else types.put(key.toUpperCase(Locale.ROOT), s);
            }
        }
        return new CombatConfig(
                c.getBoolean("enabled", true),
                Math.max(1, c.getDouble("defense_k", 50)),
                c.getDouble("unarmed_attack", 1),
                c.getDouble("player_base_defense", 0),
                Math.max(1, c.getDouble("crit_multiplier", 1.5)),
                Math.clamp(c.getDouble("sweep_ratio", 0.25), 0, 1),
                c.getBoolean("pvp", false),
                c.getBoolean("damage_indicators", true),
                c.getBoolean("scale_by_difficulty", true),
                def, Map.copyOf(types));
    }

    public MobStats mob(String entityType) {
        return mobTypes.getOrDefault(entityType.toUpperCase(Locale.ROOT), mobDefault);
    }
}
