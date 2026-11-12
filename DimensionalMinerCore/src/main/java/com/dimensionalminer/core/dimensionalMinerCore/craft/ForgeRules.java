package com.dimensionalminer.core.dimensionalMinerCore.craft;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Balance numbers for crafting, Advancement and Refine, read from config.yml ({@code craft:} and
 * {@code forge:}). Missing keys fall back to the defaults bundled in the jar's config.yml.
 * Tables are indexed by the current level (refine_chance_bp[3] = chance to go +3 → +4).
 */
public final class ForgeRules {

    public record Cost(long energy, int material) {}

    private final int quoteSeconds;
    private final int defaultCraftEnergy;
    private final String defaultUpgradeMaterial;
    private final double defaultGrowthRatio;
    private final int[] refineBonusBp;
    private final int[] refineChanceBp;
    private final int[] refinePity;
    private final double advEnergyBase, advEnergyPer, advMatBase, advMatPer;
    private final double refEnergyBase, refEnergyPer, refMatBase, refMatPer;
    private final Map<String, Integer> qualityBp;
    private final long engraveEnergy, removeEnergy;
    private final String removerItem;
    private final long inheritEnergy;

    public ForgeRules(FileConfiguration cfg) {
        quoteSeconds = Math.max(5, cfg.getInt("forge.quote_seconds", 60));
        defaultCraftEnergy = Math.max(0, cfg.getInt("craft.default_energy", 5));
        defaultUpgradeMaterial = cfg.getString("forge.default_upgrade_material", "prim_metal").toLowerCase(Locale.ROOT);
        defaultGrowthRatio = cfg.getDouble("forge.default_growth_ratio", 0.02);
        refineBonusBp = table(cfg.getIntegerList("forge.refine_bonus_bp"), GearMath.MAX_REFINE + 1,
                new int[]{0, 300, 600, 1000, 1400, 1900, 2500, 3200, 4000, 5000, 6000});
        refineChanceBp = table(cfg.getIntegerList("forge.refine_chance_bp"), GearMath.MAX_REFINE,
                new int[]{10000, 9000, 8000, 7000, 6000, 5000, 4000, 3000, 2000, 1000});
        refinePity = table(cfg.getIntegerList("forge.refine_pity"), GearMath.MAX_REFINE,
                new int[]{0, 2, 3, 4, 5, 6, 7, 8, 10, 12});
        advEnergyBase = cfg.getDouble("forge.advancement.energy_base", 2);
        advEnergyPer = cfg.getDouble("forge.advancement.energy_per_level", 0.1);
        advMatBase = cfg.getDouble("forge.advancement.material_base", 1);
        advMatPer = cfg.getDouble("forge.advancement.material_per_level", 0.04);
        refEnergyBase = cfg.getDouble("forge.refine.energy_base", 10);
        refEnergyPer = cfg.getDouble("forge.refine.energy_per_level", 10);
        refMatBase = cfg.getDouble("forge.refine.material_base", 2);
        refMatPer = cfg.getDouble("forge.refine.material_per_level", 1);
        qualityBp = new HashMap<>(Map.of("NORMAL", 10000, "FINE", 10300, "MASTERWORK", 10600));
        ConfigurationSection q = cfg.getConfigurationSection("forge.quality_bp");
        if (q != null) for (String k : q.getKeys(false)) qualityBp.put(k.toUpperCase(Locale.ROOT), q.getInt(k));
        engraveEnergy = Math.max(0, cfg.getLong("forge.engrave.energy", 20));
        removeEnergy = Math.max(0, cfg.getLong("forge.engrave.remove_energy", 10));
        removerItem = cfg.getString("forge.engrave.remover_item", "engrave_solvent").toLowerCase(Locale.ROOT);
        inheritEnergy = Math.max(0, cfg.getLong("forge.inherit.energy", 50));
    }

    public long engraveEnergy() { return engraveEnergy; }
    public long inheritEnergy() { return inheritEnergy; }
    public long removeEnergy() { return removeEnergy; }
    /** Consumable used to take a fragment out (the fragment is returned). */
    public String removerItem() { return removerItem; }

    private static int[] table(List<Integer> values, int size, int[] fallback) {
        if (values == null || values.size() < size) return fallback;
        int[] out = new int[size];
        for (int i = 0; i < size; i++) out[i] = values.get(i);
        return out;
    }

    public int quoteSeconds() { return quoteSeconds; }
    public int defaultCraftEnergy() { return defaultCraftEnergy; }
    public String defaultUpgradeMaterial() { return defaultUpgradeMaterial; }
    public double defaultGrowthRatio() { return defaultGrowthRatio; }

    public int refineBonusBp(int refine) {
        return refineBonusBp[Math.clamp(refine, 0, GearMath.MAX_REFINE)];
    }

    /** Chance (bp) to go from {@code refine} to refine+1; 0 at max. */
    public int refineChanceBp(int refine) {
        return refine >= GearMath.MAX_REFINE ? 0 : refineChanceBp[Math.max(0, refine)];
    }

    /** Failures at this level after which the next attempt is guaranteed; 0 = no pity. */
    public int refinePityMax(int refine) {
        return refine >= GearMath.MAX_REFINE ? 0 : refinePity[Math.max(0, refine)];
    }

    /** Effective chance including pity: 100% once the pity counter is full. */
    public int effectiveRefineChanceBp(int refine, int pity) {
        int max = refinePityMax(refine);
        if (max > 0 && pity >= max) return GearMath.BP;
        return refineChanceBp(refine);
    }

    public int qualityBp(String quality) {
        return qualityBp.getOrDefault(quality == null ? "NORMAL" : quality.toUpperCase(Locale.ROOT), GearMath.BP);
    }

    /** Cost of Advancement level → level+1. */
    public Cost advancementCost(int level) {
        return new Cost((long) Math.floor(advEnergyBase + advEnergyPer * level),
                (int) Math.max(0, Math.floor(advMatBase + advMatPer * level)));
    }

    /** Cost of one Refine attempt at level → level+1. */
    public Cost refineCost(int level) {
        return new Cost((long) Math.floor(refEnergyBase + refEnergyPer * level),
                (int) Math.max(0, Math.floor(refMatBase + refMatPer * level)));
    }

    public static String percent(int bp) {
        return bp % 100 == 0 ? (bp / 100) + "%" : String.format(Locale.ROOT, "%.1f%%", bp / 100.0);
    }
}
