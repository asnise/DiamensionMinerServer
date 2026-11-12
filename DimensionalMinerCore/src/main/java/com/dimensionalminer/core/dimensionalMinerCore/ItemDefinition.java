package com.dimensionalminer.core.dimensionalMinerCore;

import java.util.Collections;
import java.util.Map;

public class ItemDefinition {

    private final String id;
    private final String kind;
    private final String name;
    private final String display;
    private final String icon;
    private final String category;
    private final String rarity;
    private final String binding;
    private final int stack;
    private final Integer durability;
    private final String description;
    private final Map<String, Double> baseStats;
    /** Stat gained per Advancement level (GearMath Growth_s); empty = derive from base_stats. */
    private Map<String, Double> growthStats = Collections.emptyMap();
    /** Material consumed by Advancement/Refine; null = forge default. */
    private String upgradeMaterial;
    /** Set when this item is an Engrave fragment (payload "engrave"). */
    private Engrave engrave;
    /** Unique Effects of this gear (payload "unique_effects"). */
    private java.util.List<UniqueEffect> effects = java.util.List.of();

    /**
     * What an Engrave fragment adds to a gear piece. flat = stat units, percentBp = basis points
     * (1000 = +10%). exclusive = only one fragment with this affix per piece. categories empty = any gear.
     */
    public record Engrave(String affix, boolean exclusive, Map<String, Double> flat, Map<String, Integer> percentBp,
                          java.util.List<String> categories) {
        public boolean fits(String category) {
            return categories.isEmpty() || categories.stream().anyMatch(c -> c.equalsIgnoreCase(category));
        }
    }

    public ItemDefinition(
            String id,
            String kind,
            String name,
            String display,
            String icon,
            String category,
            String rarity,
            String binding,
            int stack,
            Integer durability,
            String description,
            Map<String, Double> baseStats
    ) {
        this.id = id;
        this.kind = kind;
        this.name = name;
        this.display = display;
        this.icon = icon;
        this.category = category != null ? category : "MATERIAL";
        this.rarity = rarity != null ? rarity : "COMMON";
        this.binding = binding != null ? binding : "UNBOUND";
        this.stack = stack > 0 ? stack : 64;
        this.durability = durability;
        this.description = description;
        this.baseStats = baseStats != null ? baseStats : Collections.emptyMap();
    }

    public String getId() {
        return id;
    }

    public String getKind() {
        return kind;
    }

    public String getName() {
        return name;
    }

    public String getDisplay() {
        return display != null && !display.isEmpty() ? display : name;
    }

    public String getIcon() {
        return icon;
    }

    public String getCategory() {
        return category;
    }

    public String getRarity() {
        return rarity;
    }

    public String getBinding() {
        return binding;
    }

    public int getStack() {
        return stack;
    }

    public Integer getDurability() {
        return durability;
    }

    public String getDescription() {
        return description;
    }

    public Map<String, Double> getBaseStats() {
        return baseStats;
    }

    public Map<String, Double> getGrowthStats() {
        return growthStats;
    }

    public String getUpgradeMaterial() {
        return upgradeMaterial;
    }
    public Engrave getEngrave() {
        return engrave;
    }
    public java.util.List<UniqueEffect> getEffects() {
        return effects;
    }
    public boolean isFragment() {
        return engrave != null;
    }

    /** Gear = a single-stack item with stats; it gets a UUID, Advancement and Refine. */
    public boolean isGear() {
        return stack == 1 && !baseStats.isEmpty();
    }

    /** Set once while parsing, before the definition is published in the registry. */
    ItemDefinition withForgeData(Map<String, Double> growthStats, String upgradeMaterial) {
        this.growthStats = growthStats != null ? Map.copyOf(growthStats) : Collections.emptyMap();
        this.upgradeMaterial = upgradeMaterial;
        return this;
    }
    ItemDefinition withEngrave(Engrave engrave) {
        this.engrave = engrave;
        return this;
    }
    ItemDefinition withEffects(java.util.List<UniqueEffect> effects) {
        this.effects = effects != null ? java.util.List.copyOf(effects) : java.util.List.of();
        return this;
    }
}
